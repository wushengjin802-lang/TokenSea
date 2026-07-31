package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderBillingControllerTests {
    @Test
    void activeBillingSourceRequiresDedicatedCredential() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("channel-1")))
                .thenReturn(List.of(Map.of("id", "channel-1", "status", "启用")));
        ProviderBillingController controller = new ProviderBillingController(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ProviderBillingSyncService.class), mock(AuditService.class));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () ->
                controller.create(request("ACTIVE", null), null));

        assertEquals(400, failure.getStatusCode().value());
    }

    @Test
    void inferenceCredentialCannotBeUsedAsBillingCredential() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("channel-1")))
                .thenReturn(List.of(Map.of("id", "channel-1", "status", "启用")));
        when(jdbc.queryForList(anyString(), eq("secret-inference"), eq("channel-1")))
                .thenReturn(List.of());
        ProviderBillingController controller = new ProviderBillingController(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ProviderBillingSyncService.class), mock(AuditService.class));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () ->
                controller.create(request("ACTIVE", "secret-inference"), null));

        assertEquals(400, failure.getStatusCode().value());
    }

    private ProviderBillingController.BillingSourceRequest request(String status, String credentialRef) {
        return new ProviderBillingController.BillingSourceRequest(
                "OpenAI 实际成本",
                "channel-1",
                "OPENAI_COSTS_API",
                "https://api.openai.com/v1/organization/costs",
                List.of("api.openai.com"),
                "USD",
                "P1D",
                Map.of("lookbackDays", 7),
                status,
                credentialRef,
                "BILLING_READ",
                "provider-cost-record-v1",
                new BigDecimal("0.05"),
                true);
    }
}
