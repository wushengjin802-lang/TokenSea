package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourcePricingAdapterTests {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void parsesAzureRetailPricesIntoOneModelPrice() {
        String payload = """
                {"Items":[
                  {"serviceName":"Azure OpenAI","productName":"gpt-4o input tokens","skuName":"gpt-4o","meterName":"Input Tokens","retailPrice":2.5,"currencyCode":"USD","unitOfMeasure":"1M Tokens","armRegionName":"eastus"},
                  {"serviceName":"Azure OpenAI","productName":"gpt-4o output tokens","skuName":"gpt-4o","meterName":"Output Tokens","retailPrice":10,"currencyCode":"USD","unitOfMeasure":"1M Tokens","armRegionName":"eastus"}
                ],"_tokenseaPageCount":1}
                """;
        var result = new AzureRetailPriceAdapter(json).parse(
                context(AzureRetailPriceAdapter.ADAPTER_CODE, "azure", Map.of(
                        "includePattern", "(?i)azure openai",
                        "modelPattern", "(?i)(?<model>gpt-4o)")),
                document(payload, "application/json"));

        assertEquals(1, result.prices().size());
        assertEquals(0, new BigDecimal("2.5").compareTo(result.prices().getFirst().inputUnitPrice()));
        assertEquals(0, new BigDecimal("10").compareTo(result.prices().getFirst().outputUnitPrice()));
        assertEquals("eastus", result.prices().getFirst().region());
    }

    @Test
    void azureMappingRuleOverridesRegexFallbackAndPreservesScope() {
        String payload = """
                {"Items":[
                  {"serviceName":"Azure OpenAI","productName":"Enterprise Model Family","skuName":"custom-sku","meterName":"Prompt Input","retailPrice":4,"currencyCode":"USD","unitOfMeasure":"1M Tokens","armRegionName":"eastus2"}
                ]}
                """;
        Map<String,Object> rule = new java.util.LinkedHashMap<>();
        rule.put("id", "mapping-1");
        rule.put("externalProductPattern", "(?i)enterprise model family");
        rule.put("externalMeterPattern", "(?i)prompt input");
        rule.put("targetProviderType", "azure");
        rule.put("targetModelName", "enterprise-model-v1");
        rule.put("targetComponentType", "INPUT_TOKEN");
        rule.put("targetRequestMode", "PRIORITY");
        rule.put("targetServiceTier", "ENTERPRISE");
        rule.put("targetContextTier", "LONG");
        rule.put("targetRegion", "eastus2");
        rule.put("billingBasis", "TOKEN");
        rule.put("billingQuantity", 1_000_000L);
        var result = new AzureRetailPriceAdapter(json).parse(
                context(AzureRetailPriceAdapter.ADAPTER_CODE, "azure", Map.of(
                        "includePattern", "(?i)azure openai",
                        "mappingRules", List.of(rule))),
                document(payload, "application/json"));

        assertEquals(1, result.prices().size());
        PriceSourceParser.NormalizedPrice price = result.prices().getFirst();
        assertEquals("enterprise-model-v1", price.providerModelName());
        assertEquals("PRIORITY", price.requestMode());
        assertEquals("ENTERPRISE", price.serviceTier());
        assertEquals("LONG", price.contextTier());
        assertEquals(new BigDecimal("4"), price.inputUnitPrice());
        assertEquals(0, ((Number) result.sourceEvidence().get("unmappedRecordCount")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void azureUnmappedSkuIsReturnedAsGovernanceEvidence() {
        String payload = """
                {"Items":[
                  {"serviceName":"Azure OpenAI","productName":"Unknown Family","skuName":"sku-404","meterName":"Unclassified Meter","retailPrice":7,"currencyCode":"USD","unitOfMeasure":"1M Tokens","armRegionName":"eastus"}
                ]}
                """;
        var result = new AzureRetailPriceAdapter(json).parse(
                context(AzureRetailPriceAdapter.ADAPTER_CODE, "azure", Map.of("includePattern", "(?i)azure openai")),
                document(payload, "application/json"));

        assertTrue(result.prices().isEmpty());
        List<Map<String,Object>> unmapped = (List<Map<String,Object>>) result.sourceEvidence().get("unmappedRecords");
        assertEquals(1, unmapped.size());
        assertEquals("sku-404", unmapped.getFirst().get("externalSku"));
        assertTrue(List.of("MODEL_NOT_MAPPED", "COMPONENT_NOT_MAPPED").contains(unmapped.getFirst().get("reasonCode")));
    }

    @Test
    void parsesGoogleCatalogSkuPrices() {
        String payload = """
                {"skus":[
                  {"description":"Vertex AI Gemini 2.5 Pro input tokens","category":{"serviceDisplayName":"Vertex AI"},"serviceRegions":["us-central1"],"pricingInfo":[{"pricingExpression":{"usageUnit":"1M tokens","tieredRates":[{"unitPrice":{"units":"1","nanos":250000000}}]}}]},
                  {"description":"Vertex AI Gemini 2.5 Pro output tokens","category":{"serviceDisplayName":"Vertex AI"},"serviceRegions":["us-central1"],"pricingInfo":[{"pricingExpression":{"usageUnit":"1M tokens","tieredRates":[{"unitPrice":{"units":"10","nanos":0}}]}}]}
                ],"_tokenseaPageCount":1}
                """;
        var result = new GoogleCloudCatalogPriceAdapter(json).parse(
                context(GoogleCloudCatalogPriceAdapter.ADAPTER_CODE, "google_vertex", Map.of(
                        "includePattern", "(?i)vertex ai",
                        "modelMappings", Map.of("Gemini 2.5 Pro", "gemini-2.5-pro"))),
                document(payload, "application/json"));

        assertEquals(1, result.prices().size());
        assertEquals(0, new BigDecimal("1.25").compareTo(result.prices().getFirst().inputUnitPrice()));
        assertEquals(0, new BigDecimal("10").compareTo(result.prices().getFirst().outputUnitPrice()));
    }

    @Test
    void parsesAwsBulkPriceDimensions() {
        String payload = """
                {
                  "products":{
                    "sku-input":{"productFamily":"Amazon Bedrock","attributes":{"model":"claude-3-5-sonnet","usagetype":"input tokens","regionCode":"us-east-1"}},
                    "sku-output":{"productFamily":"Amazon Bedrock","attributes":{"model":"claude-3-5-sonnet","usagetype":"output tokens","regionCode":"us-east-1"}}
                  },
                  "terms":{"OnDemand":{
                    "sku-input":{"term1":{"priceDimensions":{"dimension1":{"description":"claude-3-5-sonnet input tokens","unit":"1M tokens","pricePerUnit":{"USD":"3"}}}}},
                    "sku-output":{"term2":{"priceDimensions":{"dimension2":{"description":"claude-3-5-sonnet output tokens","unit":"1M tokens","pricePerUnit":{"USD":"15"}}}}}
                  }}
                }
                """;
        var result = new AwsPriceListBulkAdapter(json).parse(
                context(AwsPriceListBulkAdapter.ADAPTER_CODE, "aws_bedrock", Map.of(
                        "includePattern", "(?i)bedrock|claude",
                        "modelPattern", "(?i)(?<model>claude-3-5-sonnet)")),
                document(payload, "application/json"));

        assertEquals(1, result.prices().size());
        assertEquals(new BigDecimal("3"), result.prices().getFirst().inputUnitPrice());
        assertEquals(new BigDecimal("15"), result.prices().getFirst().outputUnitPrice());
    }

    @Test
    void genericDocumentMapsJsonCsvAndHtmlWithoutSupplierParser() {
        GenericDocumentPriceAdapter adapter = new GenericDocumentPriceAdapter(json);
        Map<String,Object> config = Map.of(
                "recordsPath", "data",
                "modelField", "model",
                "inputField", "input",
                "outputField", "output",
                "currencyField", "currency",
                "sourceBillingQuantity", 1_000_000);
        PriceSourceAdapterContext context = context(GenericDocumentPriceAdapter.ADAPTER_CODE, "demo", config);

        var jsonResult = adapter.parse(context, document(
                "{\"data\":[{\"model\":\"demo-v1\",\"input\":\"2\",\"output\":\"8\",\"currency\":\"USD\"}]}",
                "application/json"));
        var csvResult = adapter.parse(context, document(
                "model,input,output,currency\ndemo-v1,2,8,USD\n", "text/csv"));
        var htmlResult = adapter.parse(context, document(
                "<table><tr><th>model</th><th>input</th><th>output</th><th>currency</th></tr><tr><td>demo-v1</td><td>2</td><td>8</td><td>USD</td></tr></table>",
                "text/html"));

        for (PriceSourceParseResult result : List.of(jsonResult, csvResult, htmlResult)) {
            assertEquals(1, result.prices().size());
            assertEquals("demo-v1", result.prices().getFirst().providerModelName());
            assertEquals(new BigDecimal("2"), result.prices().getFirst().inputUnitPrice());
            assertEquals(new BigDecimal("8"), result.prices().getFirst().outputUnitPrice());
        }
    }

    @Test
    void genericJsonSupportsSafeRootPathAndRejectsScriptExpressions() {
        GenericDocumentPriceAdapter adapter = new GenericDocumentPriceAdapter(json);
        Map<String,Object> config = Map.of(
                "recordsPath", "$.data.models[*]",
                "modelField", "id",
                "inputField", "cost.input",
                "outputField", "cost.output",
                "sourceBillingQuantity", 1_000_000);
        var result = adapter.parse(context(GenericDocumentPriceAdapter.ADAPTER_CODE, "demo", config),
                document("{\"data\":{\"models\":[{\"id\":\"demo-json\",\"cost\":{\"input\":2,\"output\":8}}]}}",
                        "application/json"));

        assertEquals(1, result.prices().size());
        assertEquals("demo-json", result.prices().getFirst().providerModelName());
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(
                context(GenericDocumentPriceAdapter.ADAPTER_CODE, "demo", Map.of(
                        "recordsPath", "$..models[?(@.active)]", "modelField", "id", "inputField", "input")),
                document("{\"models\":[]}", "application/json")));
    }

    @Test
    void genericCsvSupportsUtf8BomSemicolonAndRowEvidence() {
        GenericDocumentPriceAdapter adapter = new GenericDocumentPriceAdapter(json);
        var result = adapter.parse(context(GenericDocumentPriceAdapter.ADAPTER_CODE, "demo", Map.of(
                        "delimiter", ";", "modelField", "model", "inputField", "input",
                        "outputField", "output", "currencyField", "currency",
                        "sourceBillingQuantity", 1_000_000)),
                document("\uFEFFmodel;input;output;currency\n\"demo;csv\";2;8;USD\n", "text/csv"));

        assertEquals(1, result.prices().size());
        assertEquals("demo;csv", result.prices().getFirst().providerModelName());
        Map<String,Object> raw = result.prices().getFirst().raw();
        Map<String,Object> evidence = (Map<String,Object>) raw.get("evidence");
        assertTrue(String.valueOf(evidence.get("sourceText")).contains("demo;csv"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void genericPdfLinePatternProducesPageEvidence() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("demo-pdf input 3 output 9 USD");
                stream.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        String pattern = "(?<model>demo-pdf)\\s+input\\s+(?<input>[0-9.]+)\\s+output\\s+(?<output>[0-9.]+)\\s+(?<currency>[A-Z]{3})";
        GenericDocumentPriceAdapter adapter = new GenericDocumentPriceAdapter(json);
        var result = adapter.parse(context(GenericDocumentPriceAdapter.ADAPTER_CODE, "demo", Map.of(
                        "linePattern", pattern, "sourceBillingQuantity", 1_000_000, "maxPages", 10)),
                new PriceSourceDocument(Base64.getEncoder().encodeToString(pdf),
                        "https://example.com/pricing.pdf", "application/pdf", "checksum"));

        assertEquals(1, result.prices().size());
        assertEquals(new BigDecimal("3"), result.prices().getFirst().inputUnitPrice());
        Map<String,Object> evidence = (Map<String,Object>) result.prices().getFirst().raw().get("evidence");
        assertEquals(1, ((Number) evidence.get("pageNumber")).intValue());
        assertTrue(String.valueOf(evidence.get("sourceText")).contains("demo-pdf"));
        Map<String,Object> coordinates = (Map<String,Object>) evidence.get("coordinates");
        assertTrue(((Number) coordinates.get("width")).doubleValue() > 0);
    }

    @Test
    void genericPdfIsExtractedButRequiresConfiguredLlmForSchemaMapping() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("Model demo-v1 input 2 USD output 8 USD per 1M tokens");
                stream.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        GenericDocumentPriceAdapter adapter = new GenericDocumentPriceAdapter(json);
        var result = adapter.parse(
                context(GenericDocumentPriceAdapter.ADAPTER_CODE, "demo", Map.of("llmEnabled", true)),
                new PriceSourceDocument(Base64.getEncoder().encodeToString(pdf),
                        "https://example.com/pricing.pdf", "application/pdf", "checksum"));

        assertTrue(result.prices().isEmpty());
        assertEquals("PDF", result.sourceEvidence().get("documentType"));
        assertFalse((Boolean) result.sourceEvidence().get("llmAvailable"));
        assertTrue(result.warnings().stream().anyMatch(message -> message.contains("尚未配置")));
    }

    private PriceSourceAdapterContext context(String adapter, String provider, Map<String,Object> config) {
        return new PriceSourceAdapterContext("source-1", adapter, provider,
                "https://example.com/pricing", "global", "USD", "STANDARD",
                100, "ORIGINAL", "1.0.0", config);
    }

    private PriceSourceDocument document(String content, String contentType) {
        return new PriceSourceDocument(content, "https://example.com/pricing", contentType, "checksum");
    }
}
