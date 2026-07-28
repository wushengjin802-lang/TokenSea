package com.tokensea.fx;

import com.tokensea.audit.service.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FxRateServiceIntegrationTests {
    @Test
    void manualRateOverridesAutomaticAndCanRestoreHistoricalAutomaticVersion() {
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
        AuditService audits = mock(AuditService.class);
        FxRateService service = new FxRateService(jdbc, audits, new DataSourceTransactionManager(dataSource),
                "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml", "USD,EUR", "", 18080);

        LocalDate month = LocalDate.of(2026, 7, 1);
        jdbc.update("""
            insert into fx_rate(id,rate_month,from_currency,to_currency,rate,source_type,source_ref,
              source_date,note,status,version,created_by)
            values('auto-v1',?,'USD','CNY',7.20,'AUTOMATIC_ECB','https://www.ecb.europa.eu/',
              '2026-06-30','ECB automatic','ACTIVE',1,'SYSTEM')
            """, month);

        Map<String,Object> manual = service.saveManual(new FxRateService.ManualRateRequest(
                month, "usd", "cny", new BigDecimal("7.35"), "财务月度管理汇率"), "admin-1");
        assertThat(manual.get("source_type")).isEqualTo("MANUAL");
        assertThat(manual.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) manual.get("version")).intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from fx_rate where id='auto-v1'", String.class))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select tokensea_fx_amount(2,'USD','2026-07-15 12:00:00+00','CNY')",
                BigDecimal.class)).isEqualByComparingTo("14.70");

        FxRateService.SyncSummary restored = service.restoreAutomatic(String.valueOf(manual.get("id")), "admin-1");
        assertThat(restored.status()).isEqualTo("SUCCEEDED");
        Map<String,Object> active = jdbc.queryForMap("""
            select * from fx_rate where rate_month=? and from_currency='USD' and to_currency='CNY' and status='ACTIVE'
            """, month);
        assertThat(active.get("source_type")).isEqualTo("AUTOMATIC_ECB");
        assertThat((BigDecimal) active.get("rate")).isEqualByComparingTo("7.20");
        assertThat(((Number) active.get("version")).intValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select tokensea_fx_amount(2,'USD','2026-07-15 12:00:00+00','CNY')",
                BigDecimal.class)).isEqualByComparingTo("14.40");
    }
}
