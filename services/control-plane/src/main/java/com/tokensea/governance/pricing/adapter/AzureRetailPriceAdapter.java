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
            int skipped = 0;
            for (JsonNode item : items) {
                String evidence = String.join(" ",
                        CatalogPriceAdapterSupport.text(item, "serviceName"),
                        CatalogPriceAdapterSupport.text(item, "productName"),
                        CatalogPriceAdapterSupport.text(item, "skuName"),
                        CatalogPriceAdapterSupport.text(item, "meterName"));
                if (!CatalogPriceAdapterSupport.include(evidence, context.config())) {
                    skipped++;
                    continue;
                }
                String model = CatalogPriceAdapterSupport.model(evidence, context.config());
                CatalogPriceAdapterSupport.ChargeType charge = CatalogPriceAdapterSupport.chargeType(evidence, context.config());
                BigDecimal retail = CatalogPriceAdapterSupport.decimal(item.get("retailPrice"));
                String unit = CatalogPriceAdapterSupport.text(item, "unitOfMeasure");
                if (model.isBlank() || charge == CatalogPriceAdapterSupport.ChargeType.UNKNOWN || retail == null) {
                    skipped++;
                    continue;
                }
                String currency = CatalogPriceAdapterSupport.value(
                        CatalogPriceAdapterSupport.text(item, "currencyCode"), context.defaultCurrency());
                String region = CatalogPriceAdapterSupport.value(
                        CatalogPriceAdapterSupport.text(item, "armRegionName", "location"), context.region());
                String requestMode = mode(evidence, context);
                BigDecimal normalized = charge == CatalogPriceAdapterSupport.ChargeType.REQUEST
                        ? retail
                        : CatalogPriceAdapterSupport.perMillion(retail, unit, context.config());
                String key = String.join("|", model.toLowerCase(Locale.ROOT), region.toLowerCase(Locale.ROOT),
                        currency.toUpperCase(Locale.ROOT), requestMode);
                CatalogPriceAdapterSupport.Group group = groups.computeIfAbsent(key, ignored ->
                        new CatalogPriceAdapterSupport.Group(model, model, currency, region, requestMode,
                                new LinkedHashMap<>(), new LinkedHashMap<>()));
                group.prices().put(charge.componentType(), normalized);
                group.raw().put(charge.componentType(), json.convertValue(item, new TypeReference<>() {}));
            }

            List<PriceSourceParser.NormalizedPrice> prices = CatalogPriceAdapterSupport.buildGrouped(
                    context, groups.values(), document.endpoint());
            Map<String,Object> evidence = new LinkedHashMap<>();
            evidence.put("parseStatus", prices.isEmpty() ? "NO_PRICE_RECORD" : "PRICE_PARSED");
            evidence.put("recordCount", items.size());
            evidence.put("skippedRecordCount", skipped);
            evidence.put("generatedPriceCount", prices.size());
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
