package com.tokensea.governance.pricing.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import com.tokensea.governance.pricing.adapter.PriceSourceParseResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PriceDocumentExtractionServiceTests {
    @Test
    void deterministicValidRecordCanContinueButLlmRecordRequiresReview() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        PriceDocumentExtractionService service = new PriceDocumentExtractionService(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                new PriceExtractionValidator(), new ExtractionConfidenceCalculator());
        Map<String,Object> source = Map.of(
                "id", "source-1",
                "schema_version", "price-record-v1",
                "minimum_confidence", new BigDecimal("0.85"),
                "require_manual_review", false,
                "config", Map.of("extractionMode", "DETERMINISTIC_LLM"));

        var deterministic = service.persist(source, "sync-1", "snapshot-1",
                parsed(price("DETERMINISTIC_MAPPING", new BigDecimal("0.98"))));
        var llm = service.persist(source, "sync-2", "snapshot-2",
                parsed(price("LLM_SCHEMA_MAPPING", new BigDecimal("0.96"))));

        assertEquals(1, deterministic.acceptedPrices().size());
        assertEquals(0, deterministic.pendingReview());
        assertEquals(0, llm.acceptedPrices().size());
        assertEquals(1, llm.pendingReview());
        assertEquals("REVIEW_REQUIRED", llm.status());
    }

    private PriceSourceParseResult parsed(PriceSourceParser.NormalizedPrice price) {
        return new PriceSourceParseResult(List.of(price), List.of(), List.of(), List.of(),
                "fingerprint", Map.of("documentType", "HTML", "extractionMethod", "DETERMINISTIC_WITH_LLM_SUPPLEMENT"), false);
    }

    private PriceSourceParser.NormalizedPrice price(String method, BigDecimal confidence) {
        Map<String,Object> raw = Map.of(
                "extractionMethod", method,
                "confidence", confidence,
                "evidence", Map.of("sourceText", "demo-v1 input 2 USD output 8 USD", "tableIndex", 0, "rowIndex", 1));
        return new PriceSourceParser.NormalizedPrice(
                "demo", "demo-v1", "Demo V1", "USD", "TOKEN", 1_000_000,
                new BigDecimal("2"), new BigDecimal("8"), "global", "STANDARD", "DEFAULT", "DEFAULT",
                Map.of(
                        "INPUT_TOKEN", Map.of("unitPrice", new BigDecimal("2"), "unitBasis", "TOKEN", "unitQuantity", 1_000_000, "mode", "EXPLICIT"),
                        "OUTPUT_TOKEN", Map.of("unitPrice", new BigDecimal("8"), "unitBasis", "TOKEN", "unitQuantity", 1_000_000, "mode", "EXPLICIT")),
                "https://example.com/pricing", OffsetDateTime.now(), null, raw);
    }
}
