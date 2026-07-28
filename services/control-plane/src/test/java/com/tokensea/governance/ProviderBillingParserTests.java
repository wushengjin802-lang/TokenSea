package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderBillingParserTests {
    private final ProviderBillingParser parser = new ProviderBillingParser(new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesOpenAiCostsBuckets() {
        String payload = """
                {
                  "object":"page",
                  "data":[{
                    "object":"bucket",
                    "start_time":1730419200,
                    "end_time":1730505600,
                    "results":[{
                      "object":"organization.costs.result",
                      "amount":{"value":0.06,"currency":"usd"},
                      "line_item":"Image models",
                      "project_id":"proj_abc"
                    }]
                  }],
                  "has_more":false,
                  "next_page":null
                }
                """;

        var result = parser.parse("OPENAI_COSTS_API", payload,
                "https://api.openai.com/v1/organization/costs", "USD", Map.of());

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("0.06"), result.getFirst().amount());
        assertEquals("USD", result.getFirst().currency());
        assertEquals("Image models", result.getFirst().lineItem());
        assertEquals("proj_abc", result.getFirst().providerProjectId());
    }

    @Test
    void parsesConfiguredGenericBillingJson() {
        String payload = """
                {"records":[{
                  "from":"2026-07-01T00:00:00Z",
                  "to":"2026-07-02T00:00:00Z",
                  "total":"12.50",
                  "ccy":"CNY",
                  "input":"1000",
                  "output":"200",
                  "requests":"4",
                  "category":"模型调用",
                  "model":"demo-v1"
                }]}
                """;
        Map<String,Object> config = Map.of(
                "recordsPath", "records",
                "periodStartField", "from",
                "periodEndField", "to",
                "amountField", "total",
                "currencyField", "ccy",
                "inputTokensField", "input",
                "outputTokensField", "output",
                "requestCountField", "requests",
                "lineItemField", "category",
                "modelField", "model");

        var result = parser.parse("GENERIC_BILLING_JSON", payload,
                "https://billing.example.com/costs", "USD", config);

        assertEquals(1, result.size());
        assertEquals(OffsetDateTime.parse("2026-07-01T00:00:00Z"), result.getFirst().periodStart());
        assertEquals(new BigDecimal("12.50"), result.getFirst().amount());
        assertEquals(1000L, result.getFirst().inputTokens());
        assertEquals(4L, result.getFirst().requestCount());
        assertEquals("demo-v1", result.getFirst().providerModelName());
    }
}
