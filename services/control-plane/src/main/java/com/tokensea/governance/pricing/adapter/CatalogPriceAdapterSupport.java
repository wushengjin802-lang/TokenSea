package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tokensea.governance.PriceSourceParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CatalogPriceAdapterSupport {
    static final long MILLION_TOKENS = 1_000_000L;
    private static final Pattern DEFAULT_MODEL_PATTERN = Pattern.compile(
            "(?i)\\b(?:gpt|o[134]|claude|gemini|llama|mistral|command|cohere|nova|titan|jamba|deepseek|qwen|kimi|glm|mimo|minimax)[a-z0-9._:/-]*\\b");
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*([kmb]?)");

    private CatalogPriceAdapterSupport() {}

    record ExternalRecord(String recordId,
                          String service,
                          String product,
                          String sku,
                          String meter,
                          String model,
                          String region,
                          String currency,
                          String unit,
                          BigDecimal price,
                          Map<String,Object> rawPayload) {}

    record MappingDecision(String ruleId,
                           String providerType,
                           String model,
                           String componentType,
                           String requestMode,
                           String serviceTier,
                           String contextTier,
                           String region,
                           String billingBasis,
                           long billingQuantity) {}

    enum ChargeType {
        INPUT_TOKEN("INPUT_TOKEN"),
        OUTPUT_TOKEN("OUTPUT_TOKEN"),
        CACHE_READ_TOKEN("CACHE_READ_TOKEN"),
        CACHE_WRITE_TOKEN("CACHE_WRITE_TOKEN"),
        REQUEST("REQUEST"),
        UNKNOWN("");

        private final String componentType;

        ChargeType(String componentType) {
            this.componentType = componentType;
        }

        String componentType() {
            return componentType;
        }
    }

    static String text(JsonNode node, String... fields) {
        if (node == null) return "";
        for (String field : fields) {
            JsonNode value = at(node, field);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                String result = value.isTextual() ? value.asText() : value.toString();
                if (!result.isBlank()) return result;
            }
        }
        return "";
    }

    static JsonNode at(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) return null;
        JsonNode current = node;
        for (String part : field.split("\\.")) {
            current = current == null ? null : current.get(part);
            if (current == null) return null;
        }
        return current;
    }

    static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            if (node.isObject()) {
                BigDecimal units = decimal(node.get("units"));
                BigDecimal nanos = decimal(node.get("nanos"));
                if (units == null && nanos == null) return null;
                return value(units).add(value(nanos).movePointLeft(9));
            }
            return new BigDecimal(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    static String model(String evidence, Map<String,Object> config) {
        String normalized = Objects.toString(evidence, "");
        Map<String,Object> mappings = object(config.get("modelMappings"));
        for (Map.Entry<String,Object> entry : mappings.entrySet()) {
            if (normalized.toLowerCase(Locale.ROOT).contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                String mapped = Objects.toString(entry.getValue(), "").trim();
                if (!mapped.isBlank()) return mapped;
            }
        }
        String patternValue = Objects.toString(config.get("modelPattern"), "").trim();
        Pattern pattern = patternValue.isBlank() ? DEFAULT_MODEL_PATTERN : Pattern.compile(patternValue, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(normalized);
        if (!matcher.find()) return "";
        try {
            String named = matcher.group("model");
            if (named != null && !named.isBlank()) return cleanModel(named);
        } catch (IllegalArgumentException ignored) {
            // Pattern has no named group.
        }
        if (matcher.groupCount() >= 1 && matcher.group(1) != null && !matcher.group(1).isBlank()) {
            return cleanModel(matcher.group(1));
        }
        return cleanModel(matcher.group());
    }

    static ChargeType chargeType(String evidence, Map<String,Object> config) {
        String value = Objects.toString(evidence, "");
        if (matches(value, config, "cacheReadPattern", "(?i)(cache|cached).*(read|hit)|缓存.*(读取|命中)")) {
            return ChargeType.CACHE_READ_TOKEN;
        }
        if (matches(value, config, "cacheWritePattern", "(?i)(cache|cached).*(write|creation|store)|缓存.*(写入|创建|存储)")) {
            return ChargeType.CACHE_WRITE_TOKEN;
        }
        if (matches(value, config, "outputPattern", "(?i)(output|completion|generated|response).*(token|character)|输出.*(token|字符)")) {
            return ChargeType.OUTPUT_TOKEN;
        }
        if (matches(value, config, "inputPattern", "(?i)(input|prompt).*(token|character)|输入.*(token|字符)")) {
            return ChargeType.INPUT_TOKEN;
        }
        if (matches(value, config, "requestPattern", "(?i)(request|query|call|invocation)|请求|调用")) {
            return ChargeType.REQUEST;
        }
        return ChargeType.UNKNOWN;
    }

    static boolean matches(String value, Map<String,Object> config, String key, String fallback) {
        String expression = Objects.toString(config.get(key), fallback);
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(value).find();
    }

    static boolean include(String evidence, Map<String,Object> config) {
        String include = Objects.toString(config.get("includePattern"), "").trim();
        String exclude = Objects.toString(config.get("excludePattern"), "").trim();
        if (!include.isBlank() && !Pattern.compile(include, Pattern.CASE_INSENSITIVE).matcher(evidence).find()) return false;
        return exclude.isBlank() || !Pattern.compile(exclude, Pattern.CASE_INSENSITIVE).matcher(evidence).find();
    }

    static MappingDecision mapping(ExternalRecord record, Map<String,Object> config) {
        Object rawRules = config == null ? null : config.get("mappingRules");
        if (!(rawRules instanceof Collection<?> rules)) return null;
        for (Object item : rules) {
            Map<String,Object> rule = object(item);
            if (!ruleMatches(rule, record)) continue;
            String model = Objects.toString(rule.get("targetModelName"), "").trim();
            String component = Objects.toString(rule.get("targetComponentType"), "").trim().toUpperCase(Locale.ROOT);
            if (model.isBlank() || component.isBlank()) continue;
            long quantity = longValue(rule.get("billingQuantity"),
                    "REQUEST".equals(component) ? 1L : MILLION_TOKENS);
            return new MappingDecision(
                    Objects.toString(rule.get("id"), ""),
                    nullable(rule.get("targetProviderType")),
                    model,
                    component,
                    value(nullable(rule.get("targetRequestMode")), "STANDARD"),
                    value(nullable(rule.get("targetServiceTier")), "DEFAULT"),
                    value(nullable(rule.get("targetContextTier")), "DEFAULT"),
                    value(nullable(rule.get("targetRegion")), record.region()),
                    value(nullable(rule.get("billingBasis")), "TOKEN"),
                    Math.max(1L, quantity));
        }
        return null;
    }

    static Map<String,Object> unmapped(ExternalRecord record, String reasonCode, String reasonMessage) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("externalRecordId", record.recordId());
        value.put("externalService", record.service());
        value.put("externalProduct", record.product());
        value.put("externalSku", record.sku());
        value.put("externalMeter", record.meter());
        value.put("externalModel", record.model());
        value.put("externalRegion", record.region());
        value.put("externalCurrency", record.currency());
        value.put("externalUnit", record.unit());
        value.put("externalPrice", record.price());
        value.put("reasonCode", reasonCode);
        value.put("reasonMessage", reasonMessage);
        value.put("rawPayload", record.rawPayload() == null ? Map.of() : record.rawPayload());
        return value;
    }

    static BigDecimal normalizedAmount(BigDecimal price, String basis, long billingQuantity,
                                       String unitText, Map<String,Object> config) {
        if (price == null) return null;
        if (!"TOKEN".equalsIgnoreCase(value(basis, "TOKEN"))) return price;
        if (billingQuantity > 0) {
            return price.multiply(BigDecimal.valueOf(MILLION_TOKENS))
                    .divide(BigDecimal.valueOf(billingQuantity), 12, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }
        return perMillion(price, unitText, config);
    }

    private static boolean ruleMatches(Map<String,Object> rule, ExternalRecord record) {
        return patternMatches(rule.get("externalServicePattern"), record.service())
                && patternMatches(rule.get("externalProductPattern"), record.product())
                && patternMatches(rule.get("externalSkuPattern"), record.sku())
                && patternMatches(rule.get("externalMeterPattern"), record.meter())
                && patternMatches(rule.get("externalModelPattern"), record.model());
    }

    private static boolean patternMatches(Object expression, String value) {
        String pattern = Objects.toString(expression, "").trim();
        if (pattern.isBlank()) return true;
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(Objects.toString(value, "")).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String nullable(Object value) {
        String result = Objects.toString(value, "").trim();
        return result.isBlank() ? null : result;
    }

    private static long longValue(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static BigDecimal perMillion(BigDecimal price, String unitText, Map<String,Object> config) {
        if (price == null) return null;
        long quantity = billingQuantity(unitText, config);
        if (quantity <= 0) quantity = MILLION_TOKENS;
        return price.multiply(BigDecimal.valueOf(MILLION_TOKENS))
                .divide(BigDecimal.valueOf(quantity), 12, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    static long billingQuantity(String unitText, Map<String,Object> config) {
        Object configured = config.get("sourceBillingQuantity");
        if (configured != null) {
            try {
                long value = Long.parseLong(String.valueOf(configured));
                if (value > 0) return value;
            } catch (Exception ignored) {
                // Fall through to unit parsing.
            }
        }
        String normalized = Objects.toString(unitText, "").replace(",", "").toLowerCase(Locale.ROOT);
        Matcher matcher = QUANTITY_PATTERN.matcher(normalized);
        while (matcher.find()) {
            BigDecimal base = new BigDecimal(matcher.group(1));
            String suffix = matcher.group(2).toLowerCase(Locale.ROOT);
            BigDecimal multiplier = switch (suffix) {
                case "k" -> BigDecimal.valueOf(1_000);
                case "m" -> BigDecimal.valueOf(1_000_000);
                case "b" -> BigDecimal.valueOf(1_000_000_000L);
                default -> BigDecimal.ONE;
            };
            long result = base.multiply(multiplier).longValue();
            if (result > 1 || normalized.contains("token") || normalized.contains("character")) return Math.max(result, 1);
        }
        if (normalized.contains("million") || normalized.contains("百万")) return MILLION_TOKENS;
        if (normalized.contains("thousand") || normalized.contains("千")) return 1_000L;
        return MILLION_TOKENS;
    }

    static Map<String,Object> component(BigDecimal price, String basis, long quantity) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("unitPrice", price);
        value.put("unitBasis", basis);
        value.put("unitQuantity", quantity);
        value.put("mode", price == null ? "UNKNOWN" : price.signum() == 0 ? "EXPLICIT_ZERO" : "EXPLICIT");
        value.put("variant", "DEFAULT");
        value.put("priority", 100);
        return value;
    }

    static PriceSourceParser.NormalizedPrice normalized(PriceSourceAdapterContext context,
                                                         String model,
                                                         String displayName,
                                                         String currency,
                                                         String region,
                                                         String requestMode,
                                                         String serviceTier,
                                                         String contextTier,
                                                         Map<String,BigDecimal> prices,
                                                         String sourceRef,
                                                         Map<String,Object> raw) {
        Map<String,Object> components = new LinkedHashMap<>();
        for (Map.Entry<String,BigDecimal> entry : prices.entrySet()) {
            components.put(entry.getKey(), component(entry.getValue(),
                    "REQUEST".equals(entry.getKey()) ? "REQUEST" : "TOKEN",
                    "REQUEST".equals(entry.getKey()) ? 1L : MILLION_TOKENS));
        }
        return new PriceSourceParser.NormalizedPrice(
                value(context.providerType(), "cloud_catalog"),
                model,
                value(displayName, model),
                value(currency, value(context.defaultCurrency(), "USD")).toUpperCase(Locale.ROOT),
                prices.containsKey("REQUEST") && prices.size() == 1 ? "REQUEST" : "TOKEN",
                prices.containsKey("REQUEST") && prices.size() == 1 ? 1L : MILLION_TOKENS,
                prices.get("INPUT_TOKEN"),
                prices.get("OUTPUT_TOKEN"),
                value(region, value(context.region(), "global")),
                value(requestMode, value(context.requestMode(), "STANDARD")),
                value(serviceTier, "DEFAULT"),
                value(contextTier, "DEFAULT"),
                components,
                sourceRef,
                OffsetDateTime.now(),
                null,
                raw == null ? Map.of() : raw);
    }

    static List<PriceSourceParser.NormalizedPrice> buildGrouped(PriceSourceAdapterContext context,
                                                                 Collection<Group> groups,
                                                                 String sourceRef) {
        List<PriceSourceParser.NormalizedPrice> result = new ArrayList<>();
        for (Group group : groups) {
            if (group.model().isBlank() || group.prices().isEmpty()) continue;
            result.add(normalized(context, group.model(), group.displayName(), group.currency(), group.region(),
                    group.requestMode(), group.serviceTier(), group.contextTier(), group.prices(), sourceRef, group.raw()));
        }
        return result;
    }

    static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> map)) return Map.of();
        Map<String,Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static String cleanModel(String value) {
        return Objects.toString(value, "").trim().replaceAll("^[`'\"]+|[`'\",;:]+$", "");
    }

    record Group(String model,
                 String displayName,
                 String currency,
                 String region,
                 String requestMode,
                 String serviceTier,
                 String contextTier,
                 Map<String,BigDecimal> prices,
                 Map<String,Object> raw) {
        Group(String model,
              String displayName,
              String currency,
              String region,
              String requestMode,
              Map<String,BigDecimal> prices,
              Map<String,Object> raw) {
            this(model, displayName, currency, region, requestMode, "DEFAULT", "DEFAULT", prices, raw);
        }

        Group {
            prices = prices == null ? new LinkedHashMap<>() : prices;
            raw = raw == null ? new LinkedHashMap<>() : raw;
        }
    }
}
