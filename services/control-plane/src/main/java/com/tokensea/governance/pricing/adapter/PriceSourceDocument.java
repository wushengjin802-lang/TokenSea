package com.tokensea.governance.pricing.adapter;

public record PriceSourceDocument(
        String content,
        String endpoint,
        String contentType,
        String checksum
) {}
