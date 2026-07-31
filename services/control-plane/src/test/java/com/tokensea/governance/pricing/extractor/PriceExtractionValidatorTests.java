package com.tokensea.governance.pricing.extractor;

import com.tokensea.governance.PriceSourceParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceExtractionValidatorTests {
    private final PriceExtractionValidator validator = new PriceExtractionValidator();
    private final ExtractionConfidenceCalculator confidence = new ExtractionConfidenceCalculator();

    @Test
    void validPriceWithLiteralEvidencePasses() {
        PriceSourceParser.NormalizedPrice price = price("demo-v1", new BigDecimal("2"));
        var result = validator.validate(price, Map.of("sourceText", "demo-v1 input 2 USD per 1M tokens"));

        assertTrue(result.valid());
        assertEquals("VALID", result.status());
        assertEquals(new BigDecimal("0.98000"),
                confidence.calculate("DETERMINISTIC_MAPPING", Map.of("sourceText", "demo-v1 input 2"), result, null));
    }

    @Test
    void missingEvidenceAndNegativePriceAreRejected() {
        PriceSourceParser.NormalizedPrice invalid = price("demo-v1", new BigDecimal("-1"));
        var result = validator.validate(invalid, Map.of());

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(message -> message.contains("负数")));
        assertTrue(result.errors().stream().anyMatch(message -> message.contains("证据")));
        assertEquals(new BigDecimal("0.00000"),
                confidence.calculate("LLM_SCHEMA_MAPPING", Map.of(), result, new BigDecimal("0.9")));
    }

    private PriceSourceParser.NormalizedPrice price(String model, BigDecimal input) {
        return new PriceSourceParser.NormalizedPrice(
                "demo", model, model, "USD", "TOKEN", 1_000_000,
                input, new BigDecimal("8"), "global", "STANDARD", "DEFAULT", "DEFAULT",
                Map.of(
                        "INPUT_TOKEN", Map.of("unitPrice", input, "unitBasis", "TOKEN", "unitQuantity", 1_000_000, "mode", "EXPLICIT"),
                        "OUTPUT_TOKEN", Map.of("unitPrice", new BigDecimal("8"), "unitBasis", "TOKEN", "unitQuantity", 1_000_000, "mode", "EXPLICIT")),
                "https://example.com/pricing", OffsetDateTime.now(), null, Map.of());
    }
}
