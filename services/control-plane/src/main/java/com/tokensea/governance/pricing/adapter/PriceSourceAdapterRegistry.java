package com.tokensea.governance.pricing.adapter;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PriceSourceAdapterRegistry {
    private final List<PriceSourceAdapter> adapters;

    public PriceSourceAdapterRegistry(List<PriceSourceAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public Optional<PriceSourceAdapter> find(String adapterCode) {
        List<PriceSourceAdapter> matches = adapters.stream()
                .filter(adapter -> adapter.supports(adapterCode))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("价格适配器重复注册: " + adapterCode);
        }
        return matches.stream().findFirst();
    }
}
