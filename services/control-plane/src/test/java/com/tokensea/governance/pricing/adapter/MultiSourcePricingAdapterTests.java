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
