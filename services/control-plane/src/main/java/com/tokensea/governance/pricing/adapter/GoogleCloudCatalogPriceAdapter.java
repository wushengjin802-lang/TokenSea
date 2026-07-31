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
public class GoogleCloudCatalogPriceAdapter implements PriceSourceAdapter {
    public static final String ADAPTER_CODE = "GOOGLE_CLOUD_CATALOG";
    private final ObjectMapper json;

    public GoogleCloudCatalogPriceAdapter(ObjectMapper json) {
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
            JsonNode skus = root.path("skus");
            if (!skus.isArray()) throw new IllegalArgumentException("Google Cloud Billing Catalog 响应缺少 skus 数组");

            Map<String,CatalogPriceAdapterSupport.Group> groups = new LinkedHashMap<>();
            List<Map<String,Object>> unmapped = new ArrayList<>();
            int skipped = 0;
            for (JsonNode sku : skus) {
                String evidence = String.join(" ",
                        CatalogPriceAdapterSupport.text(sku, "description"),
                        CatalogPriceAdapterSupport.text(sku, "category.serviceDisplayName"),
                        CatalogPriceAdapterSupport.text(sku, "category.resourceFamily"),
                        CatalogPriceAdapterSupport.text(sku, "category.resourceGroup"),
                        CatalogPriceAdapterSupport.text(sku, "category.usageType"));
                if (!CatalogPriceAdapterSupport.include(evidence, context.config())) {
                    skipped++;
                    continue;
                }
                JsonNode pricingInfo = latestPricingInfo(sku.path("pricingInfo"));
                JsonNode expression = pricingInfo == null ? null : pricingInfo.path("pricingExpression");
                JsonNode tier = firstTier(expression == null ? null : expression.path("tieredRates"));
                BigDecimal unitPrice = tier == null ? null : CatalogPriceAdapterSupport.decimal(tier.get("unitPrice"));
                String unit = expression == null ? "" : String.join(" ",
                        CatalogPriceAdapterSupport.text(expression, "usageUnit"),
                        CatalogPriceAdapterSupport.text(expression, "usageUnitDescription"),
                        CatalogPriceAdapterSupport.text(expression, "baseUnit"));
                String currency = CatalogPriceAdapterSupport.value(
                        CatalogPriceAdapterSupport.text(pricingInfo, "currencyConversionRate.currencyCode"),
                        context.defaultCurrency());
                String region = firstRegion(sku.path("serviceRegions"), context.region());
                Map<String,Object> raw = json.convertValue(sku, new TypeReference<>() {});
                String service = CatalogPriceAdapterSupport.text(sku, "category.serviceDisplayName");
                String product = CatalogPriceAdapterSupport.text(sku, "description");
                String externalSku = CatalogPriceAdapterSupport.text(sku, "skuId", "name");
                String meter = CatalogPriceAdapterSupport.text(sku, "category.resourceGroup", "category.usageType");
                CatalogPriceAdapterSupport.ExternalRecord external = new CatalogPriceAdapterSupport.ExternalRecord(
                        externalSku, service, product, externalSku, meter, product, region, currency, unit, unitPrice, raw);
                CatalogPriceAdapterSupport.MappingDecision mapping = CatalogPriceAdapterSupport.mapping(external, context.config());
                String model = mapping == null
                        ? CatalogPriceAdapterSupport.model(evidence, context.config()) : mapping.model();
                CatalogPriceAdapterSupport.ChargeType charge = CatalogPriceAdapterSupport.chargeType(evidence, context.config());
                String component = mapping == null ? charge.componentType() : mapping.componentType();
                if (model.isBlank() || component.isBlank() || "UNKNOWN".equals(component) || unitPrice == null) {
                    skipped++;
                    if (unmapped.size() < 200) {
                        String reason = unitPrice == null ? "PRICE_MISSING"
                                : model.isBlank() ? "MODEL_NOT_MAPPED" : "COMPONENT_NOT_MAPPED";
                        unmapped.add(CatalogPriceAdapterSupport.unmapped(external, reason,
                                "Google Cloud SKU 未匹配到完整的模型与计费组件"));
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
                        unitPrice, basis, quantity, unit, context.config());
                String key = String.join("|", model.toLowerCase(Locale.ROOT), targetRegion.toLowerCase(Locale.ROOT),
                        currency.toUpperCase(Locale.ROOT), requestMode, serviceTier, contextTier);
                CatalogPriceAdapterSupport.Group group = groups.computeIfAbsent(key, ignored ->
                        new CatalogPriceAdapterSupport.Group(model, model, currency, targetRegion, requestMode,
                                serviceTier, contextTier, new LinkedHashMap<>(), new LinkedHashMap<>()));
                group.prices().put(component, normalized);
                if (mapping != null) raw.put("mappingRuleId", mapping.ruleId());
                group.raw().put(component, raw);
            }

            List<PriceSourceParser.NormalizedPrice> prices = CatalogPriceAdapterSupport.buildGrouped(
                    context, groups.values(), document.endpoint());
            Map<String,Object> sourceEvidence = new LinkedHashMap<>();
            sourceEvidence.put("parseStatus", prices.isEmpty() ? "NO_PRICE_RECORD" : "PRICE_PARSED");
            sourceEvidence.put("recordCount", skus.size());
            sourceEvidence.put("skippedRecordCount", skipped);
            sourceEvidence.put("generatedPriceCount", prices.size());
            sourceEvidence.put("unmappedRecordCount", unmapped.size());
            sourceEvidence.put("mappingCoverageRatio", skus.isEmpty() ? 0D
                    : (double) Math.max(0, skus.size() - unmapped.size()) / skus.size());
            sourceEvidence.put("unmappedRecords", unmapped);
            sourceEvidence.put("paginationPages", root.path("_tokenseaPageCount").asInt(1));
            sourceEvidence.put("adapter", ADAPTER_CODE);
            List<String> warnings = prices.isEmpty()
                    ? List.of("Google Cloud Catalog 返回了 SKU，但未匹配出模型价格；请检查服务过滤、modelPattern 及计费项匹配规则")
                    : List.of();
            return new PriceSourceParseResult(prices, List.of(), List.of(), warnings,
                    PriceStructureFingerprint.calculate(json, document.content(), document.contentType()),
                    sourceEvidence, false);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Google Cloud Billing Catalog 解析失败", exception);
        }
    }

    private JsonNode latestPricingInfo(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) return null;
        return values.get(values.size() - 1);
    }

    private JsonNode firstTier(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) return null;
        return values.get(0);
    }

    private String firstRegion(JsonNode values, String fallback) {
        if (values != null && values.isArray() && !values.isEmpty()) return values.get(0).asText();
        return CatalogPriceAdapterSupport.value(fallback, "global");
    }

    private String mode(String evidence, PriceSourceAdapterContext context) {
        if (CatalogPriceAdapterSupport.matches(evidence, context.config(), "batchPattern", "(?i)batch|flex|批处理")) {
            return "BATCH";
        }
        if (CatalogPriceAdapterSupport.matches(evidence, context.config(), "priorityPattern", "(?i)priority|paygo|优先")) {
            return "PRIORITY";
        }
        return CatalogPriceAdapterSupport.value(context.requestMode(), "STANDARD");
    }
}
