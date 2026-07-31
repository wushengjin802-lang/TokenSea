package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgressPolicyControllerTests {
    @Test
    void draftPriceSourceHostsAreAvailableForTestFetch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("from provider_price_source")))
                .thenReturn(List.of(Map.of("official_hosts", "[\"www.volcengine.com\"]")));
        when(jdbc.queryForList(contains("from platform_setting"), eq(String.class)))
                .thenReturn(List.of());

        EgressPolicyController controller = new EgressPolicyController(
                jdbc, new ObjectMapper(), "policy-token");

        Map<String, Object> data = controller.allowedHosts("policy-token").data();
        @SuppressWarnings("unchecked")
        Collection<String> hosts = (Collection<String>) data.get("allowedHosts");
        assertTrue(hosts.contains("www.volcengine.com"));
        verify(jdbc).queryForList(contains("'DRAFT'"));
    }
}
