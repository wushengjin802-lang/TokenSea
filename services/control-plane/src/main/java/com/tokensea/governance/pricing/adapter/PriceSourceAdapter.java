package com.tokensea.governance.pricing.adapter;

public interface PriceSourceAdapter {
    boolean supports(String adapterCode);

    PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument document);
}
