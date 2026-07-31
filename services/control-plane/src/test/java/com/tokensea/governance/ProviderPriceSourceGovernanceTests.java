package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.governance.pricing.connector.AzureRetailPriceConnector;
import com.tokensea.governance.pricing.connector.AwsPriceListConnector;
import com.tokensea.governance.pricing.connector.GoogleCloudCatalogConnector;
import com.tokensea.governance.pricing.connector.HttpDocumentConnector;
import com.tokensea.governance.pricing.connector.LitellmReferenceConnector;
import com.tokensea.governance.pricing.connector.ModelsDevReferenceConnector;
import com.tokensea.governance.pricing.connector.PriceSourceConnectorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderPriceSourceGovernanceTests {
    private final PriceSourceConnectorRegistry connectors = new PriceSourceConnectorRegistry(List.of(
            new HttpDocumentConnector(), new AzureRetailPriceConnector(), new AwsPriceListConnector(),
            new GoogleCloudCatalogConnector(), new LitellmReferenceConnector(), new ModelsDevReferenceConnector()));

    @Test
    void publicReferenceSourceCannotEnableAutoPublishing() {
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                mock(JdbcTemplate.class), new ObjectMapper().findAndRegisterModules(),
                mock(ProviderPriceSyncService.class), mock(AuditService.class), connectors);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () ->
                controller.createSource(request(true, "MANUAL_ONLY"), null));

        assertEquals(400, failure.getStatusCode().value());
    }

    @Test
    void connectorAndAdapterMustMatch() {
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                mock(JdbcTemplate.class), new ObjectMapper().findAndRegisterModules(),
                mock(ProviderPriceSyncService.class), mock(AuditService.class), connectors);
        ProviderPriceSyncController.PriceSourceRequest invalid = new ProviderPriceSyncController.PriceSourceRequest(
                "错误组合", "OFFICIAL", "AZURE_RETAIL_PRICES", "azure", null, null, "NONE",
                "https://prices.azure.com/api/retail/prices", List.of("prices.azure.com"), "global", "USD",
                "P1D", false, new BigDecimal("0.2"), 1,
                Map.of("modelPattern", "(?<model>gpt-4o)"), "DRAFT", "1.0.0", "STRUCTURED_HTTP", 100,
                "ORIGINAL", "MODELS_DEV", "PUBLIC_CATALOG", "OFFICIAL_PUBLIC", "MANUAL_ONLY",
                "price-record-v1", "NONE", "DEFAULT", "AUTO", "SPECIALIZED",
                new BigDecimal("0.85"), false, 200, 20_000_000, null);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () ->
                controller.createSource(invalid, null));

        assertEquals(400, failure.getStatusCode().value());
    }

    @Test
    void activeAuthenticatedSourceRequiresDedicatedPricingCredential() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("provider_type", "google_vertex")));
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ProviderPriceSyncService.class), mock(AuditService.class), connectors);
        ProviderPriceSyncController.PriceSourceRequest request = new ProviderPriceSyncController.PriceSourceRequest(
                "Google 价格目录", "OFFICIAL", "GOOGLE_CLOUD_CATALOG", "google_vertex",
                "provider-1", null, "PROVIDER_INSTANCE",
                "https://cloudbilling.googleapis.com/v1/services/demo/skus",
                List.of("cloudbilling.googleapis.com"), "global", "USD", "P1D", false,
                new BigDecimal("0.2"), 1,
                Map.of("modelPattern", "(?<model>gemini-[a-z0-9.-]+)", "authHeader", "x-goog-api-key"),
                "ACTIVE", "1.0.0", "STRUCTURED_HTTP", 100, "ORIGINAL",
                "GOOGLE_CLOUD_CATALOG", "PUBLIC_CATALOG", "OFFICIAL_PUBLIC", "MANUAL_ONLY",
                "price-record-v1", "PRICING_READ", "DEFAULT", "AUTO", "SPECIALIZED",
                new BigDecimal("0.85"), false, 200, 20_000_000, null);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.createSource(request, null));

        assertEquals(400, failure.getStatusCode().value());
    }

    @Test
    void llmDocumentExtractionMustBeManualReviewOnly() {
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                mock(JdbcTemplate.class), new ObjectMapper().findAndRegisterModules(),
                mock(ProviderPriceSyncService.class), mock(AuditService.class), connectors);
        ProviderPriceSyncController.PriceSourceRequest request = new ProviderPriceSyncController.PriceSourceRequest(
                "通用价格文档", "OFFICIAL", "GENERIC_DOCUMENT", "demo", null, null, "NONE",
                "https://example.com/pricing.pdf", List.of("example.com"), "global", "USD", "P1D", false,
                new BigDecimal("0.2"), 1, Map.of("llmEnabled", true), "DRAFT", "1.0.0", "AUTO", 100,
                "ORIGINAL", "HTTP_DOCUMENT", "DOCUMENT", "OFFICIAL_PUBLIC", "MANUAL_ONLY",
                "price-record-v1", "NONE", "DEFAULT", "PDF", "DETERMINISTIC_LLM",
                new BigDecimal("0.85"), false, 200, 20_000_000, null);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.createSource(request, null));

        assertEquals(400, failure.getStatusCode().value());
    }

    @Test
    void systemReferenceSourceCannotBePausedFromLegacyCrud() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", "builtin_models_dev",
                "managed_by", "SYSTEM",
                "source_purpose", "REFERENCE")));
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ProviderPriceSyncService.class), mock(AuditService.class), connectors);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.pauseSource("builtin_models_dev", null));

        assertEquals(409, failure.getStatusCode().value());
    }

    @Test
    void enablingAuthenticatedSourceWithoutPricingCredentialIsRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", "source-1",
                "auth_mode", "PROVIDER_INSTANCE",
                "provider_instance_id", "provider-1")));
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ProviderPriceSyncService.class), mock(AuditService.class), connectors);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.enableSource("source-1", null));

        assertEquals(409, failure.getStatusCode().value());
    }

    private ProviderPriceSyncController.PriceSourceRequest request(boolean autoPublish, String publishPolicy) {
        return new ProviderPriceSyncController.PriceSourceRequest(
                "LiteLLM 参考价格", "PUBLIC_REFERENCE", "LITELLM_COST_MAP", null,
                null, null, "NONE",
                "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json",
                List.of("raw.githubusercontent.com"), "global", "USD", "P1D", autoPublish,
                new BigDecimal("0.2"), 1, Map.of(), "DRAFT", "1.0.0", "STRUCTURED_HTTP", 100,
                "ORIGINAL", "LITELLM_COST_MAP", "REFERENCE_DATASET", "COMMUNITY_REFERENCE",
                publishPolicy, "price-record-v1", "NONE", "DEFAULT", "AUTO", "SPECIALIZED",
                new BigDecimal("0.85"), false, 200, 20_000_000, null);
    }
}
