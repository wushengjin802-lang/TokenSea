package com.tokensea.governance.pricing.adapter;

import com.tokensea.governance.PriceSourceParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PriceSourceParseResult(
        List<PriceSourceParser.NormalizedPrice> prices,
        List<ModelAliasCandidate> aliases,
        List<OfficialSubPage> discoveredPricePages,
        List<String> warnings,
        String structureFingerprint,
        Map<String,Object> sourceEvidence,
        boolean headlessRecommended
) {
    public PriceSourceParseResult {
        prices = prices == null ? List.of() : List.copyOf(prices);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        discoveredPricePages = discoveredPricePages == null ? List.of() : List.copyOf(discoveredPricePages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        sourceEvidence = sourceEvidence == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sourceEvidence));
    }

    public record ModelAliasCandidate(
            String providerType,
            String providerModelName,
            String targetProviderModelName,
            String relationType,
            String region,
            String sourceRef,
            String evidenceHash,
            Map<String,Object> raw
    ) {
        public ModelAliasCandidate {
            raw = raw == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
        }
    }

    public String parseStatus() {
        return String.valueOf(sourceEvidence.getOrDefault("parseStatus", prices.isEmpty() ? "NO_PRICE_RECORD" : "PRICE_PARSED"));
    }

    public int tableCount() {
        return integerEvidence("tableCount");
    }

    public int matchedTableCount() {
        return integerEvidence("matchedTableCount");
    }

    public int skippedTableCount() {
        return integerEvidence("skippedTableCount");
    }

    public int generatedPriceCount() {
        Object value = sourceEvidence.get("generatedPriceCount");
        return value == null ? prices.size() : integerValue(value);
    }

    private int integerEvidence(String key) {
        return integerValue(sourceEvidence.get(key));
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record OfficialSubPage(String url, String label, String evidenceHash) {}
}
