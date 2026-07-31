package com.tokensea.governance.pricing.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceSourceMappingServiceTests {
    @Test
    @SuppressWarnings("unchecked")
    void enrichConfigAddsActiveDatabaseRulesWithoutDroppingSourceConfig() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("source-1"))).thenReturn(List.of(Map.of(
                "id", "rule-1",
                "targetModelName", "gpt-4o",
                "targetComponentType", "INPUT_TOKEN")));
        PriceSourceMappingService service = new PriceSourceMappingService(
                jdbc, new ObjectMapper().findAndRegisterModules());

        Map<String,Object> result = service.enrichConfig("source-1", Map.of("includePattern", "openai"));

        assertEquals("openai", result.get("includePattern"));
        List<Map<String,Object>> rules = (List<Map<String,Object>>) result.get("mappingRules");
        assertEquals("rule-1", rules.getFirst().get("id"));
    }

    @Test
    void persistUnmappedUpsertsEvidenceInsteadOfSilentlyDroppingRecord() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        PriceSourceMappingService service = new PriceSourceMappingService(
                jdbc, new ObjectMapper().findAndRegisterModules());
        Map<String,Object> record = Map.ofEntries(
                Map.entry("externalRecordId", "sku-1"),
                Map.entry("externalService", "Azure OpenAI"),
                Map.entry("externalProduct", "Unknown Model"),
                Map.entry("externalSku", "sku-1"),
                Map.entry("externalMeter", "Input Tokens"),
                Map.entry("externalModel", "unknown"),
                Map.entry("externalRegion", "eastus"),
                Map.entry("externalCurrency", "USD"),
                Map.entry("externalUnit", "1M Tokens"),
                Map.entry("externalPrice", new BigDecimal("1.25")),
                Map.entry("reasonCode", "MODEL_NOT_MAPPED"),
                Map.entry("rawPayload", Map.of("skuName", "sku-1")));

        int changed = service.persistUnmapped("source-1", "run-1", "snapshot-1",
                Map.of("unmappedRecords", List.of(record)));

        assertEquals(1, changed);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void coveragePreviewUsesTheSameRegexSemanticsAsRuntimeMappings() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("source-1"))).thenReturn(List.of(Map.of(
                "externalSkuPattern", "(?i)gpt-4o.*",
                "targetModelName", "gpt-4o",
                "targetComponentType", "INPUT_TOKEN")));
        PriceSourceMappingService service = new PriceSourceMappingService(
                jdbc, new ObjectMapper().findAndRegisterModules());

        Map<String,Object> result = service.previewCoverage("source-1", List.of(
                Map.of("externalSku", "GPT-4O-INPUT"),
                Map.of("externalSku", "UNKNOWN")));

        assertEquals(2, result.get("total"));
        assertEquals(1, result.get("mapped"));
        assertEquals(1, result.get("unmapped"));
        assertTrue(((Number) result.get("coverageRatio")).doubleValue() > 0.49D);
    }
}
