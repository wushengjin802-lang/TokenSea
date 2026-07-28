package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GovernanceOutboxProcessorIntegrationTests {
    @Test
    void processedEventIsIdempotentAndFailedEventRemainsRetryable() {
        JdbcTemplate jdbc = database();
        ProviderPriceCatalogService prices = mock(ProviderPriceCatalogService.class);
        GovernanceOutboxProcessor processor = new GovernanceOutboxProcessor(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(ProviderInstanceMapper.class), prices);

        jdbc.update("""
            insert into governance_event_outbox(
              id,event_type,aggregate_type,aggregate_id,payload,status,next_retry_at)
            values('outbox-price','PRICE_CATALOG_PUBLISHED','ProviderModelPriceCatalog','catalog-1',
              '{"catalogId":"catalog-1"}','PENDING',now())
            """);

        processor.poll();
        processor.poll();

        verify(prices, times(1)).rematchCatalog("catalog-1");
        assertThat(jdbc.queryForObject("""
            select status from governance_event_outbox where id='outbox-price'
            """, String.class)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("""
            select retry_count from governance_event_outbox where id='outbox-price'
            """, Integer.class)).isZero();

        jdbc.update("""
            insert into governance_event_outbox(
              id,event_type,aggregate_type,aggregate_id,payload,status,next_retry_at)
            values('outbox-invalid','UNSUPPORTED_EVENT','Unknown','unknown-1','{}','PENDING',now())
            """);

        processor.poll();

        assertThat(jdbc.queryForObject("""
            select status from governance_event_outbox where id='outbox-invalid'
            """, String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
            select retry_count from governance_event_outbox where id='outbox-invalid'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select last_error from governance_event_outbox where id='outbox-invalid'
            """, String.class)).contains("不支持的治理事件类型");
        assertThat(jdbc.queryForObject("""
            select next_retry_at>now() from governance_event_outbox where id='outbox-invalid'
            """, Boolean.class)).isTrue();
    }

    private static JdbcTemplate database() {
        String url = System.getProperty("tokensea.it.db.url", "");
        Assumptions.assumeTrue(!url.isBlank(), "set -Dtokensea.it.db.url to run PostgreSQL integration test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getProperty("tokensea.it.db.user", "postgres"),
                System.getProperty("tokensea.it.db.password", ""));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        return new JdbcTemplate(dataSource);
    }
}
