package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AzureRetailPriceAdapter implements PriceSourceAdapter {
    public static final String ADAPTER_CODE = "AZURE_RETAIL_PRICES";
    private final ObjectMapper json;

    public AzureRetailPriceAdapter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode);
    }

    @Override
    public PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument document) {
        try {
            JsonNode root = json.readTree(document.content());
            JsonNode items = root.path("Items");
            if (!items.isArray()) items = root.path("items");
            if (!items.isArray()) throw new IllegalArgumentException("Azure Retail Prices 响应缺少 Items 数组");

            Map<String,CatalogPriceAdapterSupport.Group> groups = new LinkedHashMap<>();
            List<Map<String,Object>> unmapped = new ArrayList<>();
            int skipped = 0;
            for (JsonNode item : items) {
                String service = CatalogPriceAdapterSupport.text(item, "serviceName");
                String product = CatalogPriceAdapterSupport.text(item, "productName");
                String sku = CatalogPriceAdapterSupport.text(item, "skuName");
                String meter = CatalogPriceAdapterSupport.text(item, "meterName");
                String evidence = String.join(" ", service, product, sku, meter);
                if (!CatalogPriceAdapterSupport.include(evidence, context.config())) {
                    skipped++;
                    continue;
                }
                BigDecimal retail = CatalogPriceAdapterSupport.decimal(item.get("retailPrice"));
                String unit = CatalogPriceAdapterSupport.text(item, "unitOfMeasure");
                String currency = CatalogPriceAdapterSupport.value(
                        CatalogPriceAdapterSupport.text(item, "currencyCode"), context.defaultCurrency());
                String region = CatalogPriceAdapterSupport.value(
                        CatalogPriceAdapterSupport.text(item, "armRegionName", "location"), context.region());
                Map<String,Object> raw = json.convertValue(item, new TypeReference<>() {});
                CatalogPriceAdapterSupport.ExternalRecord external = new CatalogPriceAdapterSupport.ExternalRecord(
                        CatalogPriceAdapterSupport.text(item, "meterId"), service, product, sku, meter, sku,
                        region, currency, unit, retail, raw);
                CatalogPriceAdapterSupport.MappingDecision mapping = CatalogPriceAdapterSupport.mapping(external, context.config());
                String model = mapping == null
                        ? CatalogPriceAdapterSupport.model(evidence, context.config()) : mapping.model();
                CatalogPriceAdapterSupport.ChargeType charge = CatalogPriceAdapterSupport.chargeType(evidence, context.config());
                String component = mapping == null ? charge.componentType() : mapping.componentType();
                if (retail == null || model.isBlank() || component.isBlank() || "UNKNOWN".equals(component)) {
                    skipped++;
                    if (unmapped.size() < 200) {
                        String reason = retail == null ? "PRICE_MISSING"
                                : model.isBlank() ? "MODEL_NOT_MAPPED" : "COMPONENT_NOT_MAPPED";
                        unmapped.add(CatalogPriceAdapterSupport.unmapped(external, reason,
                                "Azure SKU 未匹配到完整的模型与计费组件"));
                    }
                    continue;
                }
                String requestMode = mapping == null ? mode(evidence, context) : mapping.requestMode();
                String serviceTier = mapping == null ? "DEFAULT" : mapping.serviceTier();
                String contextTier = mapping == null ? "DEFAULT" : mapping.contextTier();
                String targetRegion = mapping == null ? region : CatalogPriceAdapterSupport.value(mapping.region(), region);
                String basis = mapping == null
                        ? "REQUEST".equals(component) ? "REQUEST" : "TOKEN" : mapping.billingBasis();
                long quantity = mapping == null
                        ? "REQUEST".equals(component) ? 1L : CatalogPriceAdapterSupport.billingQuantity(unit, context.config())
                        : mapping.billingQuantity();
                BigDecimal normalized = CatalogPriceAdapterSupport.normalizedAmount(
                        retail, basis, quantity, unit, context.config());
                String key = String.join("|", model.toLowerCase(Locale.ROOT), targetRegion.toLowerCase(Locale.ROOT),
                        currency.toUpperCase(Locale.ROOT), requestMode, serviceTier, contextTier);
                CatalogPriceAdapterSupport.Group group = groups.computeIfAbsent(key, ignored ->
                        new CatalogPriceAdapterSupport.Group(model, model, currency, targetRegion, requestMode,
                                serviceTier, contextTier, new LinkedHashMap<>(), new LinkedHashMap<>()));
                group.prices().put(component, normalized);
                Map<String,Object> evidenceRecord = new LinkedHashMap<>(raw);
                if (mapping != null) evidenceRecord.put("mappingRuleId", mapping.ruleId());
                group.raw().put(component, evidenceRecord);
            }

            List<PriceSourceParser.NormalizedPrice> prices = CatalogPriceAdapterSupport.buildGrouped(
                    context, groups.values(), document.endpoint());
            Map<String,Object> evidence = new LinkedHashMap<>();
            evidence.put("parseStatus", prices.isEmpty() ? "NO_PRICE_RECORD" : "PRICE_PARSED");
            evidence.put("recordCount", items.size());
            evidence.put("skippedRecordCount", skipped);
            evidence.put("generatedPriceCount", prices.size());
            evidence.put("unmappedRecordCount", unmapped.size());
            evidence.put("mappingCoverageRatio", items.isEmpty() ? 0D
                    : (double) (items.size() - skipped) / items.size());
            evidence.put("unmappedRecords", unmapped);
            evidence.put("paginationPages", root.path("_tokenseaPageCount").asInt(1));
            evidence.put("adapter", ADAPTER_CODE);
            List<String> warnings = prices.isEmpty()
                    ? List.of("Azure Retail Prices 返回了数据，但未根据当前模型与计费项匹配规则生成价格；请检查 includePattern、modelPattern 及输入/输出匹配规则")
                    : List.of();
            return new PriceSourceParseResult(prices, List.of(), List.of(), warnings,
                    PriceStructureFingerprint.calculate(json, document.content(), document.contentType()),
                    evidence, false);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Azure Retail Prices 解析失败", exception);
        }
    }

    private String mode(String evidence, PriceSourceAdapterContext context) {
        if (CatalogPriceAdapterSupport.matches(evidence, context.config(), "batchPattern", "(?i)batch|批处理")) {
            return "BATCH";
        }
        if (CatalogPriceAdapterSupport.matches(evidence, context.config(), "priorityPattern", "(?i)priority|优先")) {
            return "PRIORITY";
        }
        return CatalogPriceAdapterSupport.value(context.requestMode(), "STANDARD");
    }
}
