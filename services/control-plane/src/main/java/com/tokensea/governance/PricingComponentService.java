package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class PricingComponentService {
    public static final int SCHEMA_VERSION = 2;
    public static final long DEFAULT_TOKEN_QUANTITY = 1_000_000L;

    private static final Set<String> MODES = Set.of(
            "EXPLICIT", "EXPLICIT_ZERO", "INHERIT_INPUT", "NOT_APPLICABLE", "UNKNOWN");
    private static final Set<String> BASE_COMPONENTS = Set.of(
            "INPUT_TOKEN", "CACHE_READ_TOKEN", "CACHE_WRITE_TOKEN", "OUTPUT_TOKEN");
    private static final Set<String> COMPONENT_TYPES = Set.of(
            "INPUT_TOKEN", "OUTPUT_TOKEN", "CACHE_READ_TOKEN", "CACHE_WRITE_TOKEN", "CACHE_STORAGE_TOKEN_SECOND",
            "REASONING_TOKEN", "IMAGE_INPUT", "IMAGE_OUTPUT", "AUDIO_INPUT_TOKEN", "AUDIO_OUTPUT_TOKEN",
            "AUDIO_SECOND", "VIDEO_SECOND", "CHARACTER", "REQUEST", "RERANK_UNIT", "EMBEDDING_TOKEN",
            "INPUT_TOKEN_ABOVE_200K", "OUTPUT_TOKEN_ABOVE_200K");
    private static final Set<String> BASES = Set.of(
            "TOKEN", "REQUEST", "IMAGE", "SECOND", "MINUTE", "CHARACTER", "AUDIO_MINUTE", "TOKEN_SECOND");

    private final ObjectMapper json;

    public PricingComponentService(ObjectMapper json) {
        this.json = json;
    }

    public record ComponentInput(String componentType, String variant, BigDecimal unitPrice,
                                 String unitBasis, Long unitQuantity, String mode,
                                 Map<String,Object> scope, Integer priority,
                                 String sourceRef, Map<String,Object> metadata) {}

    public record Summary(BigDecimal inputUncachedUnitPrice,
                          BigDecimal cacheReadUnitPrice,
                          BigDecimal cacheWriteUnitPrice,
                          BigDecimal outputUnitPrice,
                          String cacheReadMode,
                          String cacheWriteMode,
                          int cacheWriteVariantCount,
                          String priceCompletenessStatus,
                          String cachePricingStatus) {}

    public List<Map<String,Object>> normalize(BigDecimal inputUnitPrice,
                                               BigDecimal cacheReadUnitPrice,
                                               BigDecimal cacheWriteUnitPrice,
                                               BigDecimal outputUnitPrice,
                                               String cacheReadMode,
                                               String cacheWriteMode,
                                               String billingBasis,
                                               Long billingQuantity,
                                               List<ComponentInput> advanced,
                                               String sourceRef) {
        String basis = upper(value(billingBasis, "TOKEN"));
        long quantity = billingQuantity == null ? DEFAULT_TOKEN_QUANTITY : billingQuantity;
        requireBasis(basis);
        if (quantity <= 0) bad("计费基数必须大于零");
        requireNonnegative(inputUnitPrice, "输入价格（缓存未命中）");
        requireNonnegative(outputUnitPrice, "输出价格");

        List<Map<String,Object>> result = new ArrayList<>();
        result.add(component("INPUT_TOKEN", "DEFAULT", inputUnitPrice, basis, quantity,
                "EXPLICIT", Map.of(), 100, sourceRef, Map.of()));
        result.add(cacheComponent("CACHE_READ_TOKEN", cacheReadUnitPrice, cacheReadMode,
                inputUnitPrice, basis, quantity, sourceRef));
        result.add(cacheComponent("CACHE_WRITE_TOKEN", cacheWriteUnitPrice, cacheWriteMode,
                inputUnitPrice, basis, quantity, sourceRef));
        result.add(component("OUTPUT_TOKEN", "DEFAULT", outputUnitPrice, basis, quantity,
                "EXPLICIT", Map.of(), 100, sourceRef, Map.of()));

        if (advanced != null) {
            for (ComponentInput input : advanced) {
                if (input == null) continue;
                String type = upper(input.componentType());
                String variant = upper(value(input.variant(), "DEFAULT"));
                String mode = upper(value(input.mode(), "EXPLICIT"));
                String itemBasis = upper(value(input.unitBasis(), basis));
                long itemQuantity = input.unitQuantity() == null ? quantity : input.unitQuantity();
                Map<String,Object> scope = normalizedObject(input.scope());
                Map<String,Object> metadata = normalizedObject(input.metadata());
                int priority = input.priority() == null ? 100 : input.priority();
                validateComponent(type, variant, input.unitPrice(), itemBasis, itemQuantity, mode, priority);
                if (BASE_COMPONENTS.contains(type) && "DEFAULT".equals(variant) && scope.isEmpty()) {
                    bad("基础四项价格必须使用基础字段维护，高级组件不能重复定义 " + type + "/DEFAULT");
                }
                BigDecimal effectivePrice = effectivePrice(input.unitPrice(), mode, inputUnitPrice);
                result.add(component(type, variant, effectivePrice, itemBasis, itemQuantity, mode, scope,
                        priority, value(input.sourceRef(), sourceRef), metadata));
            }
        }

        ensureUnique(result);
        result.sort(Comparator
                .comparingInt((Map<String,Object> item) -> ((Number) item.get("priority")).intValue())
                .thenComparing(item -> String.valueOf(item.get("componentType")))
                .thenComparing(item -> String.valueOf(item.get("variant")))
                .thenComparing(item -> scopeJson(item.get("scope"))));
        return List.copyOf(result);
    }

    public List<Map<String,Object>> normalizeParsed(BigDecimal inputUnitPrice,
                                                     BigDecimal outputUnitPrice,
                                                     String providerType,
                                                     String billingBasis,
                                                     long billingQuantity,
                                                     Map<String,Object> parserComponents,
                                                     String sourceRef) {
        List<Map<String,Object>> parsed = fromParserComponents(parserComponents, sourceRef);
        Map<String,Object> cacheRead = cacheComponentForSummary(parsed, "CACHE_READ_TOKEN");
        Map<String,Object> cacheWrite = cacheComponentForSummary(parsed, "CACHE_WRITE_TOKEN");
        String readMode = cacheRead == null ? "UNKNOWN" : mode(cacheRead);
        String writeMode = cacheWrite == null
                ? ("deepseek".equalsIgnoreCase(value(providerType, "")) ? "NOT_APPLICABLE" : "UNKNOWN")
                : mode(cacheWrite);
        BigDecimal readPrice = cacheRead == null ? null : decimalNullable(cacheRead.get("unitPrice"));
        BigDecimal writePrice = cacheWrite == null ? null : decimalNullable(cacheWrite.get("unitPrice"));
        List<ComponentInput> advanced = new ArrayList<>();
        for (Map<String,Object> item : parsed) {
            String type = upper(text(item.get("componentType")));
            String variant = upper(value(text(item.get("variant")), "DEFAULT"));
            Map<String,Object> scope = object(item.get("scope"));
            if (BASE_COMPONENTS.contains(type) && "DEFAULT".equals(variant) && scope.isEmpty()) continue;
            advanced.add(new ComponentInput(type, variant, decimalNullable(item.get("unitPrice")),
                    text(item.get("unitBasis")), longValue(item.get("unitQuantity"), billingQuantity),
                    mode(item), scope, intValue(item.get("priority"), 100),
                    text(item.get("sourceRef")), object(item.get("metadata"))));
        }
        return normalize(inputUnitPrice, readPrice, writePrice, outputUnitPrice,
                readMode, writeMode, billingBasis, billingQuantity, advanced, sourceRef);
    }

    public List<Map<String,Object>> fromParserComponents(Map<String,Object> components, String sourceRef) {
        if (components == null || components.isEmpty()) return List.of();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map.Entry<String,Object> entry : components.entrySet()) {
            if (entry.getValue() instanceof List<?> variants) {
                for (Object item : variants) addParserComponent(result, entry.getKey(), item, sourceRef);
            } else addParserComponent(result, entry.getKey(), entry.getValue(), sourceRef);
        }
        ensureUnique(result);
        return List.copyOf(result);
    }

    public List<Map<String,Object>> readComponents(Object value) {
        if (value == null) return List.of();
        try {
            Object parsed = value;
            if (!(value instanceof List<?>) && !(value instanceof Map<?,?>)) {
                // PostgreSQL jsonb is returned by JdbcTemplate as PGobject rather than String.
                // Parse its JSON representation before deciding whether the component list is empty.
                parsed = json.readValue(String.valueOf(value), Object.class);
            }
            if (parsed instanceof List<?> list) {
                List<Map<String,Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?,?> map) result.add(stringMap(map));
                }
                return List.copyOf(result);
            }
            if (parsed instanceof Map<?,?> map) return fromParserComponents(stringMap(map), null);
        } catch (Exception ignored) {
            // 无法解析的组件由调用方按价格不完整处理。
        }
        return List.of();
    }

    public Summary summarize(List<Map<String,Object>> components,
                             BigDecimal inputFallback,
                             BigDecimal outputFallback) {
        List<Map<String,Object>> values = components == null ? List.of() : components;
        Map<String,Object> input = defaultComponent(values, "INPUT_TOKEN");
        Map<String,Object> read = cacheComponentForSummary(values, "CACHE_READ_TOKEN");
        Map<String,Object> write = cacheComponentForSummary(values, "CACHE_WRITE_TOKEN");
        Map<String,Object> output = defaultComponent(values, "OUTPUT_TOKEN");

        BigDecimal inputPrice = price(input, inputFallback);
        BigDecimal outputPrice = price(output, outputFallback);
        String readMode = mode(read);
        String writeMode = mode(write);
        BigDecimal readPrice = resolvedPrice(read, readMode, inputPrice);
        BigDecimal writePrice = resolvedPrice(write, writeMode, inputPrice);
        int writeVariants = (int) values.stream()
                .filter(item -> "CACHE_WRITE_TOKEN".equals(upper(text(item.get("componentType")))))
                .count();

        String cacheStatus;
        if ("NOT_APPLICABLE".equals(readMode) && "NOT_APPLICABLE".equals(writeMode)) {
            cacheStatus = "UNSUPPORTED_CACHE";
        } else if ("UNKNOWN".equals(readMode) || "UNKNOWN".equals(writeMode)) {
            cacheStatus = "UNKNOWN_CACHE_PRICE";
        } else if (read == null || write == null) {
            cacheStatus = "PARTIAL";
        } else cacheStatus = "COMPLETE";

        boolean primaryComplete = input != null && output != null && inputPrice != null && outputPrice != null;
        String completeness = !primaryComplete ? "PARTIAL" : cacheStatus;
        if (primaryComplete && "COMPLETE".equals(cacheStatus)) completeness = "COMPLETE";
        if (primaryComplete && "UNSUPPORTED_CACHE".equals(cacheStatus)) completeness = "UNSUPPORTED_CACHE";

        return new Summary(inputPrice, readPrice, writePrice, outputPrice, readMode, writeMode,
                writeVariants, completeness, cacheStatus);
    }

    public String writeComponents(List<Map<String,Object>> components) {
        try {
            return json.writeValueAsString(components == null ? List.of() : components);
        } catch (Exception e) {
            throw new IllegalStateException("价格组件序列化失败", e);
        }
    }

    public String scopeHash(Object scope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(scopeJson(scope).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("价格组件作用域摘要生成失败", e);
        }
    }

    private void addParserComponent(List<Map<String,Object>> target, String componentType,
                                    Object raw, String sourceRef) {
        if (!(raw instanceof Map<?,?> map)) return;
        Map<String,Object> spec = stringMap(map);
        String type = upper(componentType);
        String variant = upper(value(text(spec.get("variant")), "DEFAULT"));
        String basis = upper(value(text(spec.get("unitBasis")), "TOKEN"));
        long quantity = longValue(spec.get("unitQuantity"), DEFAULT_TOKEN_QUANTITY);
        String mode = upper(value(text(first(spec, "mode", "componentMode")), "EXPLICIT"));
        BigDecimal unitPrice = decimalNullable(spec.get("unitPrice"));
        int priority = intValue(spec.get("priority"), 100);
        Map<String,Object> scope = object(spec.get("scope"));
        Map<String,Object> metadata = object(spec.get("metadata"));
        validateComponent(type, variant, unitPrice, basis, quantity, mode, priority);
        BigDecimal normalizedPrice = "INHERIT_INPUT".equals(mode)
                ? null
                : effectivePrice(unitPrice, mode, null);
        target.add(component(type, variant, normalizedPrice, basis, quantity,
                mode, scope, priority, value(text(spec.get("sourceRef")), sourceRef), metadata));
    }

    private Map<String,Object> cacheComponent(String type, BigDecimal suppliedPrice, String suppliedMode,
                                              BigDecimal inputPrice, String basis, long quantity,
                                              String sourceRef) {
        String mode = upper(value(suppliedMode, suppliedPrice == null ? "UNKNOWN" : "EXPLICIT"));
        if (!MODES.contains(mode)) bad("缓存价格模式无效：" + mode);
        BigDecimal price = effectivePrice(suppliedPrice, mode, inputPrice);
        return component(type, "DEFAULT", price, basis, quantity, mode, Map.of(), 100, sourceRef, Map.of());
    }

    private BigDecimal effectivePrice(BigDecimal supplied, String mode, BigDecimal inputPrice) {
        return switch (mode) {
            case "EXPLICIT" -> {
                requireNonnegative(supplied, "明确价格");
                yield supplied;
            }
            case "EXPLICIT_ZERO" -> {
                if (supplied != null && supplied.signum() != 0) bad("明确免费价格必须为 0");
                yield BigDecimal.ZERO;
            }
            case "INHERIT_INPUT" -> {
                requireNonnegative(inputPrice, "沿用普通输入价");
                if (supplied != null && supplied.compareTo(inputPrice) != 0) bad("沿用普通输入价时不能填写不同单价");
                yield inputPrice;
            }
            case "NOT_APPLICABLE", "UNKNOWN" -> {
                if (supplied != null) bad(mode + " 模式不能填写单价");
                yield null;
            }
            default -> throw new IllegalStateException("未识别价格模式：" + mode);
        };
    }

    private void validateComponent(String type, String variant, BigDecimal unitPrice,
                                   String basis, long quantity, String mode, int priority) {
        if (!COMPONENT_TYPES.contains(type)) bad("不支持的价格组件类型：" + type);
        if (variant == null || variant.isBlank() || variant.length() > 80) bad("价格组件变体无效");
        requireBasis(basis);
        if (quantity <= 0) bad("价格组件计费基数必须大于零");
        if (!MODES.contains(mode)) bad("价格组件模式无效：" + mode);
        if (priority < 0) bad("价格组件优先级不能小于零");
        if ("EXPLICIT".equals(mode)) requireNonnegative(unitPrice, type + " 单价");
        if ("EXPLICIT_ZERO".equals(mode) && unitPrice != null && unitPrice.signum() != 0) {
            bad(type + " 明确免费价格必须为 0");
        }
        if (Set.of("NOT_APPLICABLE", "UNKNOWN").contains(mode) && unitPrice != null) {
            bad(type + " 的 " + mode + " 模式不能填写单价");
        }
    }

    private void ensureUnique(List<Map<String,Object>> components) {
        Set<String> seen = new HashSet<>();
        for (Map<String,Object> item : components) {
            String key = upper(text(item.get("componentType"))) + "|"
                    + upper(text(item.get("variant"))) + "|" + scopeHash(item.get("scope"));
            if (!seen.add(key)) bad("同一价格组件、变体和作用域只能配置一次：" + key);
        }
    }

    private Map<String,Object> component(String type, String variant, BigDecimal price,
                                         String basis, long quantity, String mode,
                                         Map<String,Object> scope, int priority,
                                         String sourceRef, Map<String,Object> metadata) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("componentType", type);
        result.put("variant", variant);
        result.put("unitPrice", price);
        result.put("unitBasis", basis);
        result.put("unitQuantity", quantity);
        result.put("mode", mode);
        result.put("scope", normalizedObject(scope));
        result.put("priority", priority);
        result.put("sourceRef", sourceRef);
        result.put("metadata", normalizedObject(metadata));
        return result;
    }

    private Map<String,Object> defaultComponent(List<Map<String,Object>> components, String type) {
        return components.stream()
                .filter(item -> type.equals(upper(text(item.get("componentType")))))
                .filter(item -> "DEFAULT".equals(upper(value(text(item.get("variant")), "DEFAULT"))))
                .filter(item -> object(item.get("scope")).isEmpty())
                .min(Comparator.comparingInt(item -> intValue(item.get("priority"), 100)))
                .orElse(null);
    }

    private Map<String,Object> cacheComponentForSummary(List<Map<String,Object>> components, String type) {
        Map<String,Object> defaultValue = defaultComponent(components, type);
        if (defaultValue != null && !"UNKNOWN".equals(mode(defaultValue))) return defaultValue;
        return components.stream()
                .filter(item -> type.equals(upper(text(item.get("componentType")))))
                .filter(item -> !"UNKNOWN".equals(mode(item)))
                .min(Comparator.comparingInt((Map<String,Object> item) -> intValue(item.get("priority"), 100))
                        .thenComparing(item -> upper(value(text(item.get("variant")), "DEFAULT"))))
                .orElse(defaultValue);
    }

    private BigDecimal resolvedPrice(Map<String,Object> component, String mode, BigDecimal inputPrice) {
        if (component == null) return null;
        if ("INHERIT_INPUT".equals(mode)) return inputPrice;
        return decimalNullable(component.get("unitPrice"));
    }

    private BigDecimal price(Map<String,Object> component, BigDecimal fallback) {
        BigDecimal value = component == null ? null : decimalNullable(component.get("unitPrice"));
        return value == null ? fallback : value;
    }

    private String mode(Map<String,Object> component) {
        return component == null ? "UNKNOWN" : upper(value(text(first(component, "mode", "componentMode")), "EXPLICIT"));
    }

    private void requireBasis(String basis) {
        if (!BASES.contains(basis)) bad("不支持的计费对象：" + basis);
    }

    private void requireNonnegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) bad(label + "不能为空且不能小于零");
    }

    private Map<String,Object> normalizedObject(Map<String,Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        return new TreeMap<>(value);
    }

    private Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return normalizedObject(stringMap(map));
        if (value instanceof String text && !text.isBlank()) {
            try {
                return normalizedObject(json.readValue(text, new TypeReference<>() {}));
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private String scopeJson(Object scope) {
        try {
            return json.writeValueAsString(object(scope));
        } catch (Exception e) {
            throw new IllegalStateException("价格组件作用域序列化失败", e);
        }
    }

    private static Map<String,Object> stringMap(Map<?,?> source) {
        Map<String,Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Object first(Map<String,Object> source, String... keys) {
        for (String key : keys) if (source.containsKey(key)) return source.get(key);
        return null;
    }

    private static BigDecimal decimalNullable(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            bad("价格必须是有效数字");
            return null;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value == null) return fallback;
        return Long.parseLong(String.valueOf(value));
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        return Integer.parseInt(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String value(String supplied, String fallback) {
        return supplied == null || supplied.isBlank() ? fallback : supplied;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
