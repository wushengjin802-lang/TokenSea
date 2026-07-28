package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AwsPriceListBulkAdapter implements PriceSourceAdapter {
    public static final String ADAPTER_CODE = "AWS_PRICE_LIST_BULK";
    private final ObjectMapper json;

    public AwsPriceListBulkAdapter(ObjectMapper json) {
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
            JsonNode products = root.path("products");
            JsonNode onDemand = root.path("terms").path("OnDemand");
            if (!products.isObject() || !onDemand.isObject()) {
                throw new IllegalArgumentException("AWS Price List Bulk 文件缺少 products 或 terms.OnDemand");
            }

            Map<String,CatalogPriceAdapterSupport.Group> groups = new LinkedHashMap<>();
            int dimensions = 0;
            int skipped = 0;
            Iterator<Map.Entry<String,JsonNode>> productFields = products.fields();
            while (productFields.hasNext()) {
                Map.Entry<String,JsonNode> productEntry = productFields.next();
                String sku = productEntry.getKey();
                JsonNode product = productEntry.getValue();
                JsonNode attributes = product.path("attributes");
                String productEvidence = String.join(" ",
                        CatalogPriceAdapterSupport.text(product, "productFamily"),
                        CatalogPriceAdapterSupport.text(attributes, "model"),
                        CatalogPriceAdapterSupport.text(attributes, "modelId"),
                        CatalogPriceAdapterSupport.text(attributes, "usagetype"),
                        CatalogPriceAdapterSupport.text(attributes, "operation"),
                        CatalogPriceAdapterSupport.text(attributes, "groupDescription"),
                        CatalogPriceAdapterSupport.text(attributes, "instanceType"));
                if (!CatalogPriceAdapterSupport.include(productEvidence, context.config())) {
                    skipped++;
                    continue;
                }
                JsonNode terms = onDemand.path(sku);
                if (!terms.isObject()) {
                    skipped++;
                    continue;
                }
                Iterator<JsonNode> termValues = terms.elements();
                while (termValues.hasNext()) {
                    JsonNode term = termValues.next();
                    JsonNode priceDimensions = term.path("priceDimensions");
                    if (!priceDimensions.isObject()) continue;
                    Iterator<JsonNode> dimensionValues = priceDimensions.elements();
                    while (dimensionValues.hasNext()) {
                        JsonNode dimension = dimensionValues.next();
                        dimensions++;
                        String description = CatalogPriceAdapterSupport.text(dimension, "description");
                        String evidence = productEvidence + " " + description;
                        String model = CatalogPriceAdapterSupport.model(evidence, context.config());
                        CatalogPriceAdapterSupport.ChargeType charge = CatalogPriceAdapterSupport.chargeType(evidence, context.config());
                        String currency = CatalogPriceAdapterSupport.value(context.defaultCurrency(), "USD").toUpperCase(Locale.ROOT);
                        BigDecimal amount = CatalogPriceAdapterSupport.decimal(dimension.path("pricePerUnit").get(currency));
                        if (amount == null && dimension.path("pricePerUnit").isObject()) {
                            Iterator<JsonNode> prices = dimension.path("pricePerUnit").elements();
                            amount = prices.hasNext() ? CatalogPriceAdapterSupport.decimal(prices.next()) : null;
                        }
                        if (model.isBlank() || charge == CatalogPriceAdapterSupport.ChargeType.UNKNOWN || amount == null) {
                            skipped++;
                            continue;
                        }
                        String unit = CatalogPriceAdapterSupport.text(dimension, "unit");
                        BigDecimal normalized = charge == CatalogPriceAdapterSupport.ChargeType.REQUEST
                                ? amount
                                : CatalogPriceAdapterSupport.perMillion(amount, unit, context.config());
                        String region = CatalogPriceAdapterSupport.value(
                                CatalogPriceAdapterSupport.text(attributes, "regionCode", "location"), context.region());
                        String requestMode = mode(evidence, context);
                        String key = String.join("|", model.toLowerCase(Locale.ROOT), region.toLowerCase(Locale.ROOT),
                                currency, requestMode);
                        CatalogPriceAdapterSupport.Group group = groups.computeIfAbsent(key, ignored ->
                                new CatalogPriceAdapterSupport.Group(model, model, currency, region, requestMode,
                                        new LinkedHashMap<>(), new LinkedHashMap<>()));
                        group.prices().put(charge.componentType(), normalized);
                        Map<String,Object> raw = new LinkedHashMap<>();
                        raw.put("product", json.convertValue(product, new TypeReference<Map<String,Object>>() {}));
                        raw.put("dimension", json.convertValue(dimension, new TypeReference<Map<String,Object>>() {}));
                        group.raw().put(charge.componentType(), raw);
                    }
                }
            }

            List<PriceSourceParser.NormalizedPrice> prices = CatalogPriceAdapterSupport.buildGrouped(
                    context, groups.values(), document.endpoint());
            Map<String,Object> evidence = new LinkedHashMap<>();
            evidence.put("parseStatus", prices.isEmpty() ? "NO_PRICE_RECORD" : "PRICE_PARSED");
            evidence.put("productCount", products.size());
            evidence.put("priceDimensionCount", dimensions);
            evidence.put("skippedRecordCount", skipped);
            evidence.put("generatedPriceCount", prices.size());
            evidence.put("adapter", ADAPTER_CODE);
            List<String> warnings = prices.isEmpty()
                    ? List.of("AWS Price List 文件已读取，但未匹配出模型价格；请配置 includePattern、modelPattern 及输入/输出计费项规则")
                    : List.of();
            return new PriceSourceParseResult(prices, List.of(), List.of(), warnings,
                    PriceStructureFingerprint.calculate(json, document.content(), document.contentType()),
                    evidence, false);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("AWS Price List Bulk 解析失败", exception);
        }
    }

    private String mode(String evidence, PriceSourceAdapterContext context) {
        if (CatalogPriceAdapterSupport.matches(evidence, context.config(), "batchPattern", "(?i)batch|flex|批处理")) {
            return "BATCH";
        }
        if (CatalogPriceAdapterSupport.matches(evidence, context.config(), "priorityPattern", "(?i)priority|optimized|优先")) {
            return "PRIORITY";
        }
        return CatalogPriceAdapterSupport.value(context.requestMode(), "STANDARD");
    }
}
