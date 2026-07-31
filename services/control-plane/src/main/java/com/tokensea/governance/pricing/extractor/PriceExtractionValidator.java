package com.tokensea.governance.pricing.extractor;

import com.tokensea.governance.PriceSourceParser;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PriceExtractionValidator {
    private static final Set<String> BASES = Set.of(
            "TOKEN", "REQUEST", "IMAGE", "SECOND", "MINUTE", "CHARACTER", "AUDIO_MINUTE", "TOKEN_SECOND");
    private static final Set<String> COMPONENTS = Set.of(
            "INPUT_TOKEN", "OUTPUT_TOKEN", "CACHE_READ_TOKEN", "CACHE_WRITE_TOKEN", "REASONING_TOKEN",
            "IMAGE_INPUT", "IMAGE_OUTPUT", "AUDIO_SECOND", "VIDEO_SECOND", "REQUEST");

    public Validation validate(PriceSourceParser.NormalizedPrice price, Map<String,Object> evidence) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (blank(price.providerType())) errors.add("缺少供应商类型");
        if (blank(price.providerModelName())) errors.add("缺少供应商模型名");
        if (blank(price.currency()) || !price.currency().toUpperCase(Locale.ROOT).matches("^[A-Z]{3}$")) {
            errors.add("币种必须为三位大写代码");
        }
        if (!BASES.contains(value(price.billingBasis()).toUpperCase(Locale.ROOT))) errors.add("计费基准无效");
        if (price.billingQuantity() <= 0) errors.add("计费数量必须大于 0");
        nonNegative(price.inputUnitPrice(), "输入单价", errors);
        nonNegative(price.outputUnitPrice(), "输出单价", errors);
        if (price.effectiveFrom() != null && price.effectiveTo() != null
                && !price.effectiveTo().isAfter(price.effectiveFrom())) errors.add("价格生效结束时间必须晚于开始时间");
        if (price.components() == null || price.components().isEmpty()) {
            errors.add("至少需要一个价格组件");
        } else {
            for (Map.Entry<String,Object> entry : price.components().entrySet()) {
                if (!COMPONENTS.contains(entry.getKey())) errors.add("不支持的价格组件：" + entry.getKey());
                validateComponent(entry.getKey(), entry.getValue(), errors, warnings);
            }
            consistent(price.inputUnitPrice(), componentPrice(price.components().get("INPUT_TOKEN")),
                    "输入单价与 INPUT_TOKEN 组件不一致", errors);
            consistent(price.outputUnitPrice(), componentPrice(price.components().get("OUTPUT_TOKEN")),
                    "输出单价与 OUTPUT_TOKEN 组件不一致", errors);
        }
        String sourceText = evidence == null ? "" : value(evidence.get("sourceText"));
        if (sourceText.isBlank()) {
            errors.add("缺少原文证据");
        } else {
            String lower = sourceText.toLowerCase(Locale.ROOT);
            if (!lower.contains(price.providerModelName().toLowerCase(Locale.ROOT))) {
                warnings.add("原文证据未直接包含模型名");
            }
        }
        String status = errors.isEmpty() ? warnings.isEmpty() ? "VALID" : "WARNING" : "INVALID";
        return new Validation(status, List.copyOf(errors), List.copyOf(warnings));
    }

    private void validateComponent(String componentType, Object value,
                                   List<String> errors, List<String> warnings) {
        if (value instanceof Map<?,?> map) {
            validateComponentMap(componentType, map, errors, warnings);
            return;
        }
        if (value instanceof List<?> values) {
            if (values.isEmpty()) errors.add(componentType + " 价格组件为空");
            for (Object item : values) {
                if (item instanceof Map<?,?> map) validateComponentMap(componentType, map, errors, warnings);
                else errors.add(componentType + " 价格组件结构无效");
            }
            return;
        }
        errors.add(componentType + " 价格组件结构无效");
    }

    private void validateComponentMap(String componentType, Map<?,?> map,
                                      List<String> errors, List<String> warnings) {
        BigDecimal price = decimal(map.get("unitPrice"));
        if (price == null) errors.add(componentType + " 缺少单价");
        else if (price.signum() < 0) errors.add(componentType + " 单价不能为负数");
        String basis = value(map.get("unitBasis")).toUpperCase(Locale.ROOT);
        if (!BASES.contains(basis)) errors.add(componentType + " 计费基准无效");
        long quantity = longValue(map.get("unitQuantity"));
        if (quantity <= 0) errors.add(componentType + " 计费数量必须大于 0");
        if ((componentType.startsWith("CACHE_") && map.get("mode") == null)) {
            warnings.add(componentType + " 未声明缓存价格模式");
        }
    }

    private BigDecimal componentPrice(Object value) {
        if (value instanceof Map<?,?> map) return decimal(map.get("unitPrice"));
        if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?,?> map) {
            return decimal(map.get("unitPrice"));
        }
        return null;
    }

    private void consistent(BigDecimal summary, BigDecimal component, String message, List<String> errors) {
        if (summary != null && component != null && summary.compareTo(component) != 0) errors.add(message);
    }

    private void nonNegative(BigDecimal value, String label, List<String> errors) {
        if (value != null && value.signum() < 0) errors.add(label + "不能为负数");
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private long longValue(Object value) {
        if (value == null) return 0;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record Validation(String status, List<String> errors, List<String> warnings) {
        public boolean valid() { return !"INVALID".equals(status); }
    }
}
