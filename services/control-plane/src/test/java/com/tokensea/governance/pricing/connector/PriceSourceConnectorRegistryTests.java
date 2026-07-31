package com.tokensea.governance.pricing.connector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceSourceConnectorRegistryTests {
    private final PriceSourceConnectorRegistry registry = new PriceSourceConnectorRegistry(List.of(
            new HttpDocumentConnector(),
            new AzureRetailPriceConnector(),
            new AwsPriceListConnector(),
            new GoogleCloudCatalogConnector(),
            new LitellmReferenceConnector(),
            new ModelsDevReferenceConnector()));

    @Test
    void connectorDefinitionsAreUniqueAndCoverPhaseOneSources() {
        assertEquals(6, registry.definitions().size());
        assertEquals("AZURE_RETAIL_PRICES", registry.connectorForAdapter("AZURE_RETAIL_PRICES"));
        assertEquals("HTTP_DOCUMENT", registry.connectorForAdapter("QWEN_OFFICIAL_PAGE"));
        assertEquals("通用价格文档（HTML/CSV/JSON/PDF）", registry.require("HTTP_DOCUMENT").name());
        assertTrue(registry.supportsAdapter("AWS_PRICE_LIST_BULK", "AWS_PRICE_LIST_BULK"));
        assertFalse(registry.supportsAdapter("MODELS_DEV", "AZURE_RETAIL_PRICES"));
    }

    @Test
    void publicReferenceConnectorsAreManualOnlyAndCredentialFree() {
        for (String code : List.of("LITELLM_COST_MAP", "MODELS_DEV")) {
            PriceSourceConnectorDefinition definition = registry.require(code);
            assertEquals("REFERENCE_DATASET", definition.dataScope());
            assertEquals("COMMUNITY_REFERENCE", definition.trustLevel());
            assertEquals(List.of("MANUAL_ONLY"), definition.supportedPublishPolicies());
            assertFalse(definition.credentialRequired());
        }
    }

    @Test
    void duplicateConnectorRegistrationFailsFast() {
        assertThrows(IllegalStateException.class, () -> new PriceSourceConnectorRegistry(List.of(
                new AzureRetailPriceConnector(), new AzureRetailPriceConnector())));
    }
}
