package com.tokensea.usage;

import com.tokensea.usage.service.UsageAnalyticsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsageAnalyticsFxIntegrationTests {
    @Test
    void dashboardReturnsCacheHitRateAndNetSavingsInCny() {
        String url = System.getProperty("tokensea.it.db.url", "");
        Assumptions.assumeTrue(!url.isBlank(), "set -Dtokensea.it.db.url to run PostgreSQL integration test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                System.getProperty("tokensea.it.db.user", "postgres"),
                System.getProperty("tokensea.it.db.password", ""));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            insert into usage_record(id,request_id,prompt_tokens,completion_tokens,total_tokens,cost_amount,sales_amount,
              currency,status,created_at,budget_currency)
            values('usage-cache','request-cache',100,20,120,5,5,'CNY','SUCCESS','2026-07-12 08:00:00+00','CNY')
            """);
        jdbc.update("""
            insert into usage_cost_snapshot(
              id,request_id,usage_record_id,price_layer,currency,billing_basis,billing_quantity,input_unit_price,output_unit_price,
              prompt_tokens,completion_tokens,actual_cost_amount,input_uncached_tokens,input_tokens_total,output_tokens,
              cache_read_tokens,cache_write_tokens,reasoning_tokens,cache_storage_token_seconds,usage_schema_version,
              usage_source,usage_evidence,cache_gross_savings,cache_write_premium,cache_storage_cost,cache_net_savings,
              cache_hit_rate,cost_status)
            values(
              'cost-cache','request-cache','usage-cache','CHANNEL_ACTUAL','CNY','TOKEN',1000000,2,8,100,20,5,60,100,20,
              40,0,0,0,2,'UPSTREAM','{}',3,0.5,0.2,2.3,0.4,'COMPLETE')
            """);
        UsageAnalyticsService service = new UsageAnalyticsService(new NamedParameterJdbcTemplate(dataSource));
        UsageAnalyticsService.UsageQuery query = UsageAnalyticsService.UsageQuery.of(
                "2026-07-01T00:00:00Z", "2026-07-31T23:59:59Z", null, null, null, null,
                null, null, null, null, 1, 20, "createdAt", "desc");

        Map<String,Object> summary = (Map<String,Object>) service.dashboard(query).get("summary");
        assertThat((BigDecimal) summary.get("cache_hit_rate")).isEqualByComparingTo("0.4");
        assertThat((BigDecimal) summary.get("cache_net_savings")).isEqualByComparingTo("2.3");
    }

    @Test
    void dashboardConvertsMonthlyCostsToCnyWhileDetailsKeepOriginalCurrencyAndAmount() {
        String url = System.getProperty("tokensea.it.db.url", "");
        Assumptions.assumeTrue(!url.isBlank(), "set -Dtokensea.it.db.url to run PostgreSQL integration test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                System.getProperty("tokensea.it.db.user", "postgres"),
                System.getProperty("tokensea.it.db.password", ""));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            insert into fx_rate(id,rate_month,from_currency,to_currency,rate,source_type,source_ref,source_date,status,version)
            values('usd-cny-202607','2026-07-01','USD','CNY',7.2,'MANUAL','test://fx','2026-07-01','ACTIVE',1)
            """);
        jdbc.update("""
            insert into usage_record(id,request_id,prompt_tokens,completion_tokens,total_tokens,cost_amount,sales_amount,
              currency,status,created_at,budget_currency)
            values
              ('usage-cny','request-cny',10,5,15,10,10,'CNY','SUCCESS','2026-07-10 08:00:00+00','CNY'),
              ('usage-usd','request-usd',20,10,30,2,2,'USD','SUCCESS','2026-07-11 08:00:00+00','CNY')
            """);
        UsageAnalyticsService service = new UsageAnalyticsService(new NamedParameterJdbcTemplate(dataSource));
        UsageAnalyticsService.UsageQuery query = UsageAnalyticsService.UsageQuery.of(
                "2026-07-01T00:00:00Z", "2026-07-31T23:59:59Z", null, null, null, null,
                null, null, null, null, 1, 20, "createdAt", "desc");

        Map<String,Object> dashboard = service.dashboard(query);
        Map<String,Object> summary = (Map<String,Object>) dashboard.get("summary");
        assertThat((BigDecimal) summary.get("cost_amount")).isEqualByComparingTo("24.4");
        assertThat(summary.get("currency")).isEqualTo("CNY");
        assertThat(((Number) summary.get("fx_missing_count")).longValue()).isZero();

        Map<String,Object> details = service.details(query);
        List<Map<String,Object>> items = (List<Map<String,Object>>) details.get("items");
        Map<String,Object> usd = items.stream().filter(item -> "request-usd".equals(item.get("request_id"))).findFirst().orElseThrow();
        assertThat(usd.get("currency")).isEqualTo("USD");
        assertThat((BigDecimal) usd.get("cost_amount")).isEqualByComparingTo("2");
    }
}
