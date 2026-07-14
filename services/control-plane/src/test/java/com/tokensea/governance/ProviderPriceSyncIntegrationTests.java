package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderPriceSyncIntegrationTests {
    @Test
    void approvesDiffPublishesCatalogAndComponents() throws Exception {
        String url = System.getProperty("tokensea.it.db.url", "");
        Assumptions.assumeTrue(!url.isBlank(), "set -Dtokensea.it.db.url to run PostgreSQL integration test");
        String user = System.getProperty("tokensea.it.db.user", "postgres");
        String password = System.getProperty("tokensea.it.db.password", "testpass");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AuditService audits = mock(AuditService.class);
        ProviderPriceCatalogService matcher = mock(ProviderPriceCatalogService.class);
        ProviderConnectionService providerConnections = mock(ProviderConnectionService.class);
        when(matcher.rematchCatalog(anyString())).thenReturn(
                new ProviderPriceCatalogService.RematchSummary(0, 0, 0, 0));
        ProviderPriceSyncService service = new ProviderPriceSyncService(
                jdbc, json, new PriceSourceParser(json), matcher, audits, providerConnections,
                new DataSourceTransactionManager(dataSource), "", 18080, "official.example");

        jdbc.update("""
            insert into provider_price_source(id,name,source_class,adapter_code,provider_type,endpoint,official_hosts,
              default_currency,status)
            values('source-it','Official Test','OFFICIAL','OFFICIAL_JSON','provider-a',
              'https://official.example/prices','["official.example"]','USD','ACTIVE')
            """);
        jdbc.update("""
            insert into provider_price_sync_run(id,price_source_id,status)
            values('run-it','source-it','REVIEW_REQUIRED')
            """);
        jdbc.update("""
            insert into provider_price_raw_snapshot(id,price_source_id,sync_run_id,source_endpoint,final_endpoint,
              http_status,content_type,checksum,response_bytes,raw_content,parser_version)
            values('snapshot-it','source-it','run-it','https://official.example/prices',
              'https://official.example/prices',200,'application/json',repeat('a',64),2,'{}','1.0.0')
            """);
        Map<String,Object> normalized = new LinkedHashMap<>();
        normalized.put("providerType", "provider-a");
        normalized.put("providerModelName", "model-a");
        normalized.put("displayName", "Model A");
        normalized.put("currency", "USD");
        normalized.put("billingUnit", "PER_1K_TOKENS");
        normalized.put("inputAmountPer1k", "0.001");
        normalized.put("outputAmountPer1k", "0.002");
        normalized.put("region", "global");
        normalized.put("requestMode", "STANDARD");
        normalized.put("serviceTier", "DEFAULT");
        normalized.put("contextTier", "DEFAULT");
        normalized.put("components", Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", "0.001", "unitBasis", "PER_1K_TOKENS"),
                "OUTPUT_TOKEN", Map.of("unitPrice", "0.002", "unitBasis", "PER_1K_TOKENS")));
        normalized.put("sourceRef", "https://official.example/prices");
        jdbc.update("""
            insert into provider_price_diff(id,price_source_id,sync_run_id,raw_snapshot_id,provider_type,
              provider_model_name,diff_type,new_value,risk_level,status)
            values('diff-it','source-it','run-it','snapshot-it','provider-a','model-a','MODEL_ADDED',
              cast(? as jsonb),'LOW','PENDING')
            """, json.writeValueAsString(normalized));

        Map<String,Object> approved = service.approveDiff("diff-it", "admin", "integration test");

        assertThat(approved.get("status")).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from provider_model_price_catalog where status='ACTIVE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from provider_price_component", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select publish_mode from provider_model_price_catalog limit 1", String.class))
                .isEqualTo("MANUAL");

        jdbc.update("""
            insert into provider_price_sync_run(id,price_source_id,status)
            values('run-public','builtin_litellm_cost_map','RUNNING')
            """);
        jdbc.update("""
            insert into provider_price_raw_snapshot(id,price_source_id,sync_run_id,source_endpoint,final_endpoint,
              http_status,content_type,checksum,response_bytes,raw_content,parser_version)
            values('snapshot-public','builtin_litellm_cost_map','run-public',
              'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json',
              'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json',
              200,'application/json',repeat('b',64),2,'{}','1.0.0')
            """);
        Map<String,Object> publicSource = jdbc.queryForMap(
                "select * from provider_price_source where id='builtin_litellm_cost_map'");
        PriceSourceParser.NormalizedPrice reference = new PriceSourceParser.NormalizedPrice(
                "provider-a", "model-a", "Model A", "USD", new BigDecimal("0.001"),
                new BigDecimal("0.002"), "global", "STANDARD", "DEFAULT", "DEFAULT",
                Map.of("INPUT_TOKEN", Map.of("unitPrice", "0.001", "unitBasis", "PER_1K_TOKENS")),
                "https://reference.example/model-a", OffsetDateTime.now(), null, Map.of());
        var method = ProviderPriceSyncService.class.getDeclaredMethod("processReferences",
                Map.class, String.class, String.class, String.class, List.class);
        method.setAccessible(true);
        method.invoke(service, publicSource, "run-public", "snapshot-public", "b".repeat(64), List.of(reference));

        assertThat(jdbc.queryForObject("select count(*) from public_model_price_reference", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from public_model_reference where canonical_name='provider-a/model-a'", Integer.class)).isEqualTo(1);
    }
}
