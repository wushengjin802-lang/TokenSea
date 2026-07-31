package com.tokensea.governance.pricing.reference;

import java.util.Locale;

/**
 * Builds stable reference-model identities without collapsing models that share a vendor name
 * but are served through different providers (for example OpenRouter versus direct OpenAI).
 */
public final class ReferenceModelMatcher {
    private ReferenceModelMatcher() {}

    public static String canonical(String providerType, String providerModelName) {
        String provider = normalizeSegment(providerType, "providerType");
        String model = normalizeSegment(providerModelName, "providerModelName");
        String providerPrefix = provider + "/";
        return model.startsWith(providerPrefix) ? model : providerPrefix + model;
    }

    private static String normalizeSegment(String value, String field) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank()) throw new IllegalArgumentException("参考模型字段不能为空: " + field);
        return normalized;
    }
}
