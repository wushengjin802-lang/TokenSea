package com.tokensea.usage;

import com.tokensea.usage.service.UsageAnalyticsService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsageAnalyticsLiveTests {
    @Test
    void readsDashboardDetailsAndFilterOptionsFromPostgres() {
        String url = System.getProperty("tokensea.it.db.url", "");
        Assumptions.assumeTrue(!url.isBlank(), "set -Dtokensea.it.db.url to run PostgreSQL integration test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                System.getProperty("tokensea.it.db.user", "postgres"),
                System.getProperty("tokensea.it.db.password", ""));
        UsageAnalyticsService service = new UsageAnalyticsService(new NamedParameterJdbcTemplate(dataSource));
        UsageAnalyticsService.UsageQuery query = UsageAnalyticsService.UsageQuery.of(
                null, null, null, null, null, null, null, null, null, null,
                1, 20, "createdAt", "desc");

        Map<String,Object> dashboard = service.dashboard(query);
        Map<String,Object> details = service.details(query);
        Map<String,Object> options = service.options();

        assertThat(dashboard).containsKeys("summary", "trend", "providerCost", "modelUsage", "tenantCost",
                "projectUsage", "appTrend", "keyRanking");
        assertThat((List<?>) dashboard.get("trend")).isNotEmpty();
        assertThat(details).containsKeys("items", "total", "page", "size");
        assertThat((Number) details.get("total")).isNotNull();
        assertThat(options).containsKeys("tenants", "projects", "apps", "apiKeys", "providers", "models");
    }
}
