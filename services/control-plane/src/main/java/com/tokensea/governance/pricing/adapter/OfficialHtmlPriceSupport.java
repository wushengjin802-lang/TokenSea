package com.tokensea.governance.pricing.adapter;

import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OfficialHtmlPriceSupport {
    static final long ONE_MILLION = 1_000_000L;
    private static final Pattern MONEY = Pattern.compile("(?:CNY|RMB|USD|[$¥￥])?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:元|美元|CNY|RMB|USD)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_MONEY = Pattern.compile(
            "(?:CNY|RMB|USD|[$¥￥])\\s*([0-9]+(?:\\.[0-9]+)?)|([0-9]+(?:\\.[0-9]+)?)\\s*(?:元|美元|CNY|RMB|USD)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*[kK]\\s*(?:<|≤|~|～|-|至|到).*?([0-9]+(?:\\.[0-9]+)?)\\s*[kK]");
    private static final Pattern MAX_RANGE = Pattern.compile("(?:≤|<=|不超过|最多)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*[kK]", Pattern.CASE_INSENSITIVE);

    private OfficialHtmlPriceSupport() {}

    static String normalize(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replace('（', '(').replace('）', ')')
                .replaceAll("\\s+", " ").trim();
    }

    static String compact(String value) {
        return normalize(value).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static boolean containsAny(String value, String... terms) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        for (String term : terms) if (normalized.contains(term.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    static BigDecimal money(String value) {
        Matcher matcher = MONEY.matcher(normalize(value).replace(",", ""));
        while (matcher.find()) {
            BigDecimal candidate = new BigDecimal(matcher.group(1));
            String token = matcher.group();
            if (token.matches(".*[kK].*") || token.matches(".*[mM].*")) continue;
            return candidate;
        }
        return null;
    }

    static BigDecimal explicitMoney(String value) {
        Matcher matcher = EXPLICIT_MONEY.matcher(normalize(value).replace(",", ""));
        if (!matcher.find()) return null;
        String amount = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        return amount == null ? null : new BigDecimal(amount);
    }

    static List<BigDecimal> moneyValues(String value) {
        List<BigDecimal> values = new ArrayList<>();
        Matcher matcher = MONEY.matcher(normalize(value).replace(",", ""));
        while (matcher.find()) values.add(new BigDecimal(matcher.group(1)));
        return values;
    }

    static String currency(String value, String configuredCurrency) {
        String text = normalize(value);
        boolean cny = Pattern.compile("人民币|CNY|RMB|[¥￥]|[0-9]\\s*元", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean usd = Pattern.compile("美元|USD|\\$", Pattern.CASE_INSENSITIVE).matcher(text).find();
        if (cny && usd) throw new IllegalArgumentException("官方价格证据同时包含 CNY 与 USD，无法安全判定币种");
        String detected = cny ? "CNY" : usd ? "USD" : null;
        String configured = normalize(configuredCurrency).toUpperCase(Locale.ROOT);
        if (detected != null && !configured.isBlank() && !detected.equals(configured)) {
            throw new IllegalArgumentException("官方价格币种为 " + detected + "，与价格源默认币种 " + configured + " 不一致");
        }
        return detected == null ? configured : detected;
    }

    static boolean perMillion(String value) {
        return Pattern.compile("(?:每|/)?\\s*(?:1M|100万|一百万|百万)\\s*(?:个)?\\s*TOKEN", Pattern.CASE_INSENSITIVE)
                .matcher(compact(value)).find();
    }

    static Map<String,Object> tokenComponent(BigDecimal price, String variant,
                                             Map<String,Object> scope, String sourceRef) {
        if (price == null) return Map.of();
        Map<String,Object> component = new LinkedHashMap<>();
        component.put("unitPrice", price);
        component.put("unitBasis", "TOKEN");
        component.put("unitQuantity", ONE_MILLION);
        component.put("variant", variant == null || variant.isBlank() ? "DEFAULT" : variant);
        component.put("mode", "EXPLICIT");
        component.put("priority", 100);
        component.put("scope", scope == null ? Map.of() : scope);
        component.put("metadata", Map.of("officialPage", sourceRef));
        return component;
    }

    static Map<String,Object> inheritedInputTokenComponent(String variant,
                                                           Map<String,Object> scope,
                                                           String sourceRef) {
        Map<String,Object> component = new LinkedHashMap<>();
        component.put("unitBasis", "TOKEN");
        component.put("unitQuantity", ONE_MILLION);
        component.put("variant", variant == null || variant.isBlank() ? "DEFAULT" : variant);
        component.put("mode", "INHERIT_INPUT");
        component.put("priority", 100);
        component.put("scope", scope == null ? Map.of() : scope);
        component.put("metadata", Map.of("officialPage", sourceRef,
                "reason", "缓存未命中输入价格适用于缓存写入 Token"));
        return component;
    }

    static RangeTier rangeTier(String value) {
        String text = normalize(value);
        Matcher range = RANGE.matcher(text);
        if (range.find()) {
            long min = toTokens(range.group(1));
            long max = toTokens(range.group(2));
            return new RangeTier(min, max, min + "_" + max,
                    Map.of("minInputTokensInclusive", min, "maxInputTokensInclusive", max,
                            "pricingApplication", "WHOLE_REQUEST"));
        }
        Matcher maximum = MAX_RANGE.matcher(text);
        if (maximum.find()) {
            long max = toTokens(maximum.group(1));
            return new RangeTier(0L, max, "0_" + max,
                    Map.of("minInputTokensInclusive", 0, "maxInputTokensInclusive", max,
                            "pricingApplication", "WHOLE_REQUEST"));
        }
        return new RangeTier(null, null, "DEFAULT", Map.of());
    }

    static String evidencePath(Element table, int rowIndex) {
        String id = table.id().isBlank() ? "" : "#" + table.id();
        return "table" + id + "/row[" + rowIndex + "]";
    }

    static String nearestHeading(Element element) {
        Element current = element;
        while (current != null) {
            Element sibling = current.previousElementSibling();
            while (sibling != null) {
                Element heading = sibling.is("h1,h2,h3,h4,h5") ? sibling : sibling.selectFirst("h1,h2,h3,h4,h5");
                if (heading != null) return normalize(heading.text());
                sibling = sibling.previousElementSibling();
            }
            current = current.parent();
        }
        return "";
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Map<String,Object> rawMetadata(String priceNature, Map<String,Object> conditions,
                                          int sourcePriority, String evidencePath,
                                          String model, String sourceRef) {
        Map<String,Object> raw = new LinkedHashMap<>();
        raw.put("priceNature", priceNature);
        raw.put("pricingConditions", conditions == null ? Map.of() : conditions);
        raw.put("sourcePriority", sourcePriority);
        raw.put("sourceEvidencePath", evidencePath);
        raw.put("providerModelName", model);
        raw.put("officialPage", sourceRef);
        return raw;
    }

    static String priceNature(String value, String fallback) {
        if (containsAny(value, "限时", "优惠", "促销", "折扣", "活动价", "特惠")) return "PROMOTIONAL";
        if (containsAny(value, "免费额度", "免费")) return "FREE_QUOTA";
        return fallback == null || fallback.isBlank() ? "ORIGINAL" : fallback;
    }

    static String serviceTier(String value) {
        if (containsAny(value, "highspeed", "高速版")) return "HIGH_SPEED";
        if (containsAny(value, "非思考", "non-thinking", "non thinking")) return "NON_THINKING";
        if (containsAny(value, "思考模式", "thinking")) return "THINKING";
        return "DEFAULT";
    }

    static String requestMode(String value, String fallback) {
        if (containsAny(value, "batch", "批量推理", "批处理")) return "BATCH";
        return fallback == null || fallback.isBlank() ? "STANDARD" : fallback;
    }

    private static long toTokens(String value) {
        return new BigDecimal(value).multiply(new BigDecimal("1000")).longValue();
    }

    record RangeTier(Long minTokens, Long maxTokens, String code, Map<String,Object> scope) {}
}
