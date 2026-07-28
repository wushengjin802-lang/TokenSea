package com.tokensea.governance.pricing.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record PriceSourceAdapterContext(
        String priceSourceId,
        String adapterCode,
        String providerType,
        String endpoint,
        String region,
        String defaultCurrency,
        String requestMode,
        int sourcePriority,
        String priceNature,
        String parserVersion,
        Map<String,Object> config
) {
    public PriceSourceAdapterContext {
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }
}
