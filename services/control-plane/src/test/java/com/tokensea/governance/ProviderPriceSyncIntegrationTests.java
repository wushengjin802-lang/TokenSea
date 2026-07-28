package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import com.tokensea.governance.pricing.adapter.PriceSourceParseResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderPriceSyncIntegrationTests {
    @Test
    void decodesGzipResponseBeforeSnapshotPersistence() throws Exception {
        byte[] expected = "{\"currency\":\"CNY\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(expected);
        }

        assertThat(ProviderPriceSyncService.decodeBody(compressed.toByteArray(), "gzip"))
                .isEqualTo(expected);
    }

    @Test
    void removesNulCharactersBeforeWritingTextSnapshot() {
        assertThat(ProviderPriceSyncService.snapshotContent(
                "{\"currency\":\"CNY\"}\u0000".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("{\"currency\":\"CNY\"}");
    }

    @Test
    void componentComparisonIgnoresEquivalentBigDecimalScale() {
        Map<String,Object> stored = Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", new BigDecimal("0.140000000000"),
                        "unitBasis", "TOKEN", "unitQuantity", 1_000_000),
                "OUTPUT_TOKEN", Map.of("unitPrice", new BigDecimal("0.280000000000"),
                        "unitBasis", "TOKEN", "unitQuantity", 1_000_000));
        Map<String,Object> parsed = Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", new BigDecimal("0.14"),
                        "unitBasis", "TOKEN", "unitQuantity", 1_000_000),
                "OUTPUT_TOKEN", Map.of("unitPrice", new BigDecimal("0.28"),
                        "unitBasis", "TOKEN", "unitQuantity", 1_000_000));

        assertThat(ProviderPriceSyncService.jsonValueEquals(stored, parsed)).isTrue();
    }

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
        PricingComponentService pricingComponents = new PricingComponentService(json);
        ProviderPriceCatalogService matcher = new ProviderPriceCatalogService(jdbc, json, audits, pricingComponents);
        ProviderConnectionService providerConnections = mock(ProviderConnectionService.class);
        ProviderPriceSyncService service = new ProviderPriceSyncService(
                jdbc, json, new PriceSourceParser(json), matcher, audits, providerConnections, pricingComponents,
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
        jdbc.update("""
            insert into provider_instance(id,instance_name,provider_type,region,status)
            values('provider-instance-it','Provider A','provider-a','global','启用')
            """);
        jdbc.update("""
            insert into provider_model_snapshot(id,provider_instance_id,source_endpoint,http_status,checksum,raw_payload)
            values('model-snapshot-it','provider-instance-it','https://official.example/models',200,repeat('c',64),'{}')
            """);
        jdbc.update("""
            insert into channel_model_deployment(id,provider_instance_id,provider_model_name,display_name,raw_model,
              source_snapshot_id,review_status,routing_status)
            values('deployment-it','provider-instance-it','model-a','Model A','{}','model-snapshot-it','APPROVED','ELIGIBLE')
            """);
        Map<String,Object> normalized = new LinkedHashMap<>();
        normalized.put("providerType", "provider-a");
        normalized.put("providerModelName", "model-a");
        normalized.put("displayName", "Model A");
        normalized.put("currency", "USD");
        normalized.put("billingBasis", "TOKEN");
        normalized.put("billingQuantity", 1_000_000);
        normalized.put("inputUnitPrice", "1");
        normalized.put("outputUnitPrice", "2");
        normalized.put("region", "global");
        normalized.put("requestMode", "STANDARD");
        normalized.put("serviceTier", "DEFAULT");
        normalized.put("contextTier", "DEFAULT");
        normalized.put("components", Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", "1", "unitBasis", "TOKEN", "unitQuantity", 1_000_000),
                "CACHE_READ_TOKEN", Map.of("unitBasis", "TOKEN", "unitQuantity", 1_000_000,
                        "mode", "NOT_APPLICABLE"),
                "CACHE_WRITE_TOKEN", Map.of("unitBasis", "TOKEN", "unitQuantity", 1_000_000,
                        "mode", "NOT_APPLICABLE"),
                "OUTPUT_TOKEN", Map.of("unitPrice", "2", "unitBasis", "TOKEN", "unitQuantity", 1_000_000)));
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
        assertThat(jdbc.queryForObject("select count(*) from provider_price_component", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select publish_mode from provider_model_price_catalog limit 1", String.class))
                .isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("select count(*) from price_version where deployment_id='deployment-it' and status='ACTIVE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select catalog_price_id from price_version where deployment_id='deployment-it'",
                String.class)).isNotBlank();

        Map<String,Object> batchNormalized = new LinkedHashMap<>(normalized);
        batchNormalized.put("requestMode", "BATCH");
        batchNormalized.put("inputUnitPrice", "0.5");
        batchNormalized.put("outputUnitPrice", "1");
        batchNormalized.put("components", Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", "0.5", "unitBasis", "TOKEN", "unitQuantity", 1_000_000),
                "CACHE_READ_TOKEN", Map.of("unitBasis", "TOKEN", "unitQuantity", 1_000_000,
                        "mode", "NOT_APPLICABLE"),
                "CACHE_WRITE_TOKEN", Map.of("unitBasis", "TOKEN", "unitQuantity", 1_000_000,
                        "mode", "NOT_APPLICABLE"),
                "OUTPUT_TOKEN", Map.of("unitPrice", "1", "unitBasis", "TOKEN", "unitQuantity", 1_000_000)));
        jdbc.update("""
            insert into provider_price_diff(id,price_source_id,sync_run_id,raw_snapshot_id,provider_type,
              provider_model_name,region,request_mode,service_tier,context_tier,diff_type,new_value,risk_level,status)
            values('diff-batch-it','source-it','run-it','snapshot-it','provider-a','model-a','global','BATCH',
              'DEFAULT','DEFAULT','MODEL_ADDED',cast(? as jsonb),'HIGH','PENDING')
            """, json.writeValueAsString(batchNormalized));

        Map<String,Object> batchApproved = service.approveDiff("diff-batch-it", "admin", "approve batch price");
        String batchCatalogId = String.valueOf(batchApproved.get("published_catalog_id"));
        assertThat(jdbc.queryForList("""
            select revision from provider_model_price_catalog
            where provider_type='provider-a' and provider_model_name='model-a' order by revision
            """, Integer.class)).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("""
            select count(*) from provider_model_price_catalog
            where provider_type='provider-a' and provider_model_name='model-a' and status='ACTIVE'
            """, Integer.class)).isEqualTo(2);

        Map<String,Object> revoked = service.revokeDiff("diff-batch-it", "admin", "wrong dimension");
        assertThat(revoked.get("status")).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select status from provider_model_price_catalog where id=?",
                String.class, batchCatalogId)).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("""
            select count(*) from provider_model_price_catalog
            where provider_type='provider-a' and provider_model_name='model-a'
              and request_mode='STANDARD' and status='ACTIVE'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from price_version where deployment_id='deployment-it' and status='ACTIVE'
            """, Integer.class)).isEqualTo(1);

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
                "provider-a", "model-a", "Model A", "USD", "TOKEN", 1_000_000L,
                new BigDecimal("1"), new BigDecimal("2"), "global", "STANDARD", "DEFAULT", "DEFAULT",
                Map.of("INPUT_TOKEN", Map.of("unitPrice", "1", "unitBasis", "TOKEN", "unitQuantity", 1_000_000)),
                "https://reference.example/model-a", OffsetDateTime.now(), null, Map.of());
        var method = ProviderPriceSyncService.class.getDeclaredMethod("processReferences",
                Map.class, String.class, String.class, String.class, List.class);
        method.setAccessible(true);
        method.invoke(service, publicSource, "run-public", "snapshot-public", "b".repeat(64), List.of(reference));

        assertThat(jdbc.queryForObject("select count(*) from public_model_price_reference", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from public_model_reference where canonical_name='provider-a/model-a'", Integer.class)).isEqualTo(1);

        var fingerprintMethod = ProviderPriceSyncService.class.getDeclaredMethod("updateStructureFingerprint",
                Map.class, String.class, String.class, String.class);
        fingerprintMethod.setAccessible(true);
        Map<String,Object> officialSource = jdbc.queryForMap("select * from provider_price_source where id='source-it'");
        fingerprintMethod.invoke(service, officialSource, "run-it", "snapshot-it", "fingerprint-a");
        officialSource = jdbc.queryForMap("select * from provider_price_source where id='source-it'");
        fingerprintMethod.invoke(service, officialSource, "run-it", "snapshot-it", "fingerprint-b");
        assertThat(jdbc.queryForObject("""
            select count(*) from provider_price_diff where price_source_id='source-it'
              and diff_type='SOURCE_STRUCTURE_CHANGED' and risk_level='HIGH' and status='PENDING'
            """, Integer.class)).isEqualTo(1);

        jdbc.update("""
            insert into provider_price_source(
              id,name,source_class,adapter_code,provider_type,endpoint,official_hosts,region,
              default_currency,auto_publish,status,config)
            values('kimi-parent','Kimi Parent','OFFICIAL','KIMI_OFFICIAL_PAGE','moonshot',
              'https://platform.kimi.com/docs/pricing/chat-k26','["platform.kimi.com"]','cn',
              'CNY',true,'ACTIVE','{"seedPricingPages":[]}')
            """);
        var childSourceMethod = ProviderPriceSyncService.class.getDeclaredMethod("persistDiscoveredPriceSources",
                Map.class, List.class);
        childSourceMethod.setAccessible(true);
        Map<String,Object> kimiParent = jdbc.queryForMap("select * from provider_price_source where id='kimi-parent'");
        int childSources = (Integer) childSourceMethod.invoke(service, kimiParent, List.of(
                new PriceSourceParseResult.OfficialSubPage(
                        "https://platform.kimi.com/docs/pricing/chat-k3", "Kimi K3", "page-evidence"),
                new PriceSourceParseResult.OfficialSubPage(
                        "https://platform.kimi.com/docs/pricing/promotion", "Kimi K3 充值活动", "promo-evidence"),
                new PriceSourceParseResult.OfficialSubPage(
                        "https://platform.kimi.com/docs/pricing/limits", "充值与限速", "limits-evidence")));
        assertThat(childSources).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from provider_price_source where adapter_code='KIMI_OFFICIAL_PAGE'
              and endpoint='https://platform.kimi.com/docs/pricing/chat-k3' and status='ACTIVE'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from provider_price_source where adapter_code='KIMI_OFFICIAL_PAGE'
              and endpoint in ('https://platform.kimi.com/docs/pricing/promotion',
                               'https://platform.kimi.com/docs/pricing/limits')
            """, Integer.class)).isZero();
    }

    @Test
    void persistsConfirmationStateResetsOnChangeAndPublishesAfterTwoMatchingObservations() throws Exception {
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
        PricingComponentService pricingComponents = new PricingComponentService(json);
        ProviderPriceCatalogService matcher = new ProviderPriceCatalogService(jdbc, json, audits, pricingComponents);
        ProviderConnectionService providerConnections = mock(ProviderConnectionService.class);
        ProviderPriceSyncService service = new ProviderPriceSyncService(
                jdbc, json, new PriceSourceParser(json), matcher, audits, providerConnections, pricingComponents,
                new DataSourceTransactionManager(dataSource), "", 18080, "official.example");

        jdbc.update("""
            insert into provider_price_source(id,name,source_class,adapter_code,provider_type,endpoint,official_hosts,
              default_currency,auto_publish,max_auto_change_ratio,confirmation_runs,status)
            values('source-confirm','Official Confirm','OFFICIAL','OFFICIAL_JSON','provider-a',
              'https://official.example/prices','["official.example"]','USD',true,0.3000,2,'ACTIVE')
            """);

        var processOfficial = ProviderPriceSyncService.class.getDeclaredMethod("processOfficial",
                Map.class, String.class, String.class, String.class, List.class, boolean.class, String.class);
        processOfficial.setAccessible(true);
        Map<String,Object> source = jdbc.queryForMap("select * from provider_price_source where id='source-confirm'");

        PriceSourceParser.NormalizedPrice first = price("0.001", "0.002");
        observePrice(jdbc, service, processOfficial, source, "run-confirm-1", "snapshot-confirm-1", "1".repeat(64), first);
        Map<String,Object> initial = jdbc.queryForMap("select * from provider_price_diff where price_source_id='source-confirm'");
        assertThat(initial.get("diff_type")).isEqualTo("MODEL_ADDED");
        assertThat(initial.get("risk_level")).isEqualTo("HIGH");
        assertThat(initial.get("status")).isEqualTo("PENDING");
        service.approveDiff(String.valueOf(initial.get("id")), "admin", "approve initial official price");

        PriceSourceParser.NormalizedPrice changedOnce = price("0.0011", "0.0022");
        observePrice(jdbc, service, processOfficial, source, "run-confirm-2", "snapshot-confirm-2", "2".repeat(64), changedOnce);
        Map<String,Object> pending = jdbc.queryForMap("""
            select * from provider_price_diff where price_source_id='source-confirm' and status='PENDING'
            """);
        assertThat(pending.get("diff_type")).isEqualTo("PRICE_CHANGED");
        assertThat(pending.get("confirmation_count")).isEqualTo(1);
        String firstHash = String.valueOf(pending.get("last_confirmed_hash"));

        PriceSourceParser.NormalizedPrice changedAgain = price("0.0012", "0.0024");
        observePrice(jdbc, service, processOfficial, source, "run-confirm-3", "snapshot-confirm-3", "3".repeat(64), changedAgain);
        pending = jdbc.queryForMap("select * from provider_price_diff where price_source_id='source-confirm' and status='PENDING'");
        assertThat(pending.get("confirmation_count")).isEqualTo(1);
        assertThat(String.valueOf(pending.get("last_confirmed_hash"))).isNotEqualTo(firstHash);

        observePrice(jdbc, service, processOfficial, source, "run-confirm-4", "snapshot-confirm-4", "4".repeat(64), changedAgain);
        Map<String,Object> published = jdbc.queryForMap("""
            select * from provider_price_diff where price_source_id='source-confirm' and diff_type='PRICE_CHANGED'
            """);
        assertThat(published.get("status")).isEqualTo("AUTO_PUBLISHED");
        assertThat(published.get("confirmation_count")).isEqualTo(2);
        assertThat(published.get("last_confirmed_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from provider_model_price_catalog where status='ACTIVE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select publish_mode from provider_model_price_catalog where status='ACTIVE'",
                String.class)).isEqualTo("AUTO");
    }

    private static PriceSourceParser.NormalizedPrice price(String input, String output) {
        return new PriceSourceParser.NormalizedPrice(
                "provider-a", "model-confirm", "Model Confirm", "USD", "TOKEN", 1_000_000L,
                new BigDecimal(input), new BigDecimal(output), "global", "STANDARD", "DEFAULT", "DEFAULT",
                Map.of(
                        "INPUT_TOKEN", Map.of("unitPrice", input, "unitBasis", "TOKEN", "unitQuantity", 1_000_000),
                        "OUTPUT_TOKEN", Map.of("unitPrice", output, "unitBasis", "TOKEN", "unitQuantity", 1_000_000)),
                "https://official.example/prices", OffsetDateTime.now(), null, Map.of());
    }

    private static void observePrice(JdbcTemplate jdbc, ProviderPriceSyncService service,
                                     java.lang.reflect.Method processOfficial, Map<String,Object> source,
                                     String runId, String snapshotId, String checksum,
                                     PriceSourceParser.NormalizedPrice price) throws Exception {
        jdbc.update("insert into provider_price_sync_run(id,price_source_id,status) values(?,'source-confirm','SUCCEEDED')", runId);
        jdbc.update("""
            insert into provider_price_raw_snapshot(id,price_source_id,sync_run_id,source_endpoint,final_endpoint,
              http_status,content_type,checksum,response_bytes,raw_content,parser_version)
            values(?,'source-confirm',?,'https://official.example/prices','https://official.example/prices',
              200,'application/json',?,2,'{}','1.0.0')
            """, snapshotId, runId, checksum);
        processOfficial.invoke(service, source, runId, snapshotId, checksum, List.of(price), false, "fixture-fingerprint");
    }
}
