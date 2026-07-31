package com.tokensea.governance.pricing.reference;

import com.tokensea.common.PageResult;
import com.tokensea.governance.ProviderPriceSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferencePriceQueryPerformanceTests {

    @Test
    void readEndpointsDoNotRunStaleStatusUpdate() {
        ReferencePriceHealthService health = mock(ReferencePriceHealthService.class);
        ReferencePriceOverviewController controller = new ReferencePriceOverviewController(
                health, mock(ProviderPriceSyncService.class));
        when(health.overview()).thenReturn(Map.of("modelCount", 1L));
        when(health.sources()).thenReturn(List.of());
        when(health.models(1, 20, null, null, null, "updatedAt", "desc"))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        controller.overview();
        controller.sources();
        controller.models(1, 20, null, null, null, "updatedAt", "desc");

        verify(health, never()).refreshStaleStatus();
    }

    @Test
    void modelPageUsesWindowCountInsteadOfASecondFullCountQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReferencePriceHealthService service = new ReferencePriceHealthService(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", "price-1",
                "providerType", "qwen",
                "providerModelName", "qwen-plus",
                "priceStatus", "CURRENT",
                "__total", 8045L)));

        PageResult<Map<String,Object>> result = service.models(
                1, 20, null, null, null, "updatedAt", "desc");

        assertEquals(8045L, result.total());
        assertEquals("price-1", result.items().getFirst().get("id"));
        assertFalse(result.items().getFirst().containsKey("__total"));
        verify(jdbc).queryForList(anyString(), any(Object[].class));
        verify(jdbc, never()).queryForObject(anyString(), any(Class.class), any(Object[].class));
    }

    @Test
    void modelKeywordSearchIncludesProviderType() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReferencePriceHealthService service = new ReferencePriceHealthService(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.models(1, 20, "volcengine_ark", null, null, "updatedAt", "desc");

        verify(jdbc).queryForList(org.mockito.ArgumentMatchers.argThat(sql -> {
            String normalized = sql.replaceAll("\\s+", " ").toLowerCase();
            return normalized.contains("lower(provider_type) like ?")
                    && normalized.contains("lower(provider_model_name) like ?");
        }), any(Object[].class));
    }

    @Test
    void staleRefreshOnlyWritesRowsWhoseEffectiveStatusChanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReferencePriceHealthService service = new ReferencePriceHealthService(jdbc);

        service.refreshStaleStatus();

        verify(jdbc).update(org.mockito.ArgumentMatchers.<String>argThat(sql -> {
            String normalized = sql.replaceAll("\\s+", " ").toLowerCase();
            return normalized.contains("price_status is distinct from")
                    && normalized.contains("stale_at<=now()")
                    && normalized.contains("updated_at=now()");
        }));
    }

    @Test
    void sourceListComputesStalenessFromTimestampWithoutWaitingForScheduler() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReferencePriceHealthService service = new ReferencePriceHealthService(jdbc);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        service.sources();

        verify(jdbc).queryForList(org.mockito.ArgumentMatchers.<String>argThat(sql -> {
            String normalized = sql.replaceAll("\\s+", " ").toLowerCase();
            return normalized.contains("r.stale_at is not null")
                    && normalized.contains("r.stale_at<=now()")
                    && !normalized.contains("with source_stats");
        }));
    }
}
