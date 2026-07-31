package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceDocumentLlmExtractorTests {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void onlyHttpsOrTrustedInternalGatewayCanBeEnabled() {
        PriceDocumentLlmExtractor publicHttp = new PriceDocumentLlmExtractor(
                json, true, "http://example.com/v1/chat/completions", "service-model", "vk-test", 60000, "", 18080);
        PriceDocumentLlmExtractor internal = new PriceDocumentLlmExtractor(
                json, true, "http://tokensea-gateway-runtime:39212/v1/chat/completions",
                "service-model", "vk-test", 60000, "", 18080);
        PriceDocumentLlmExtractor https = new PriceDocumentLlmExtractor(
                json, true, "https://gateway.example.com/v1/chat/completions",
                "service-model", "vk-test", 60000, "", 18080);

        assertFalse(publicHttp.available());
        assertTrue(internal.available());
        assertTrue(https.available());
    }

    @Test
    void missingVirtualKeyKeepsExtractorDisabled() {
        PriceDocumentLlmExtractor extractor = new PriceDocumentLlmExtractor(
                json, true, "https://gateway.example.com/v1/chat/completions",
                "service-model", "", 60000, "", 18080);
        assertFalse(extractor.available());
    }
}
