package com.tokensea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.config.LegacyV7PreflightCallback;
import com.tokensea.governance.ProviderPriceSyncController;
import com.tokensea.governance.ProviderPriceSyncService;
import com.tokensea.governance.pricing.connector.AzureRetailPriceConnector;
import com.tokensea.governance.pricing.connector.AwsPriceListConnector;
import com.tokensea.governance.pricing.connector.GoogleCloudCatalogConnector;
import com.tokensea.governance.pricing.connector.HttpDocumentConnector;
import com.tokensea.governance.pricing.connector.LitellmReferenceConnector;
import com.tokensea.governance.pricing.connector.ModelsDevReferenceConnector;
import com.tokensea.governance.pricing.connector.PriceSourceConnectorRegistry;
import com.tokensea.governance.pricing.extractor.ExtractionConfidenceCalculator;
import com.tokensea.governance.pricing.extractor.PriceDocumentExtractionService;
import com.tokensea.governance.pricing.extractor.PriceExtractionValidator;
import com.tokensea.governance.pricing.reference.ReferencePriceBundleLoader;
import com.tokensea.governance.pricing.reference.ReferencePriceHealthService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
class FlywayUpgradeIntegrationTests {
    private final List<String> databases = new ArrayList<>();
    private String host;
    private String port;
    private String user;
    private String password;

    @BeforeEach
    void configure() {
        host = System.getenv().getOrDefault("TOKENSEA_TEST_DB_HOST", "localhost");
        port = System.getenv().getOrDefault("TOKENSEA_TEST_DB_PORT", "39213");
        user = System.getenv("SPRING_DATASOURCE_USERNAME");
        password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        Assumptions.assumeTrue(user != null && password != null, "PostgreSQL test credentials are required");
    }

    @AfterEach
    void dropTemporaryDatabases() throws Exception {
        if (user == null || password == null || databases.isEmpty()) return;
        try (Connection connection = DriverManager.getConnection(adminUrl(), user, password);
             Statement statement = connection.createStatement()) {
            for (String database : databases) statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    void cleanInstallMigratesThroughGenericPricingSchema() throws Exception {
        String database = createDatabase();
        Flyway flyway = flyway(database, null);
        flyway.migrate();
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals("44", scalar(database, "select version from flyway_schema_history where success order by installed_rank desc limit 1"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='channel_model_deployment'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='usage_cost_snapshot'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='budget_rule_event'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='runtime_quickstart_config'"));
        assertEquals("4", scalar(database, "select count(*) from information_schema.columns where table_name='capability_validation' and column_name in ('probe_endpoint','http_status','stream_verified','probe_request_id')"));
        assertEquals("6", scalar(database, "select count(*) from information_schema.columns where table_name='price_version' and column_name in ('activated_by','activated_at','billing_basis','billing_quantity','input_unit_price','output_unit_price')"));
        assertEquals("6", scalar(database, "select count(*) from information_schema.columns where table_name='price_version' and column_name in ('cache_read_unit_price','cache_write_unit_price','cache_read_mode','cache_write_mode','component_schema_version','price_completeness_status')"));
        assertEquals("8", scalar(database, "select count(*) from information_schema.columns where table_name='provider_model_price_catalog' and column_name in ('cache_read_unit_price','cache_write_unit_price','cache_read_mode','cache_write_mode','price_components','component_schema_version','price_completeness_status','cache_pricing_status')"));
        assertEquals("5", scalar(database, "select count(*) from information_schema.columns where table_name='provider_price_component' and column_name in ('variant','component_mode','priority','source_ref','metadata')"));
        assertEquals("12", scalar(database, "select count(*) from information_schema.columns where table_name='usage_cost_snapshot' and column_name in ('input_uncached_tokens','input_tokens_total','output_tokens','cache_storage_token_seconds','usage_schema_version','usage_source','usage_evidence','cache_gross_savings','cache_write_premium','cache_storage_cost','cache_net_savings','cost_status')"));
        assertEquals("5", scalar(database, "select count(*) from information_schema.columns where table_name='usage_cost_snapshot' and column_name in ('cache_read_unit_price','cache_read_mode','cache_write_unit_price','cache_write_mode','cache_hit_rate')"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='error_code_registry'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='cost_statement'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='fx_rate'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='fx_rate_sync_run'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.columns where table_name='usage_record' and column_name='budget_currency'"));
        assertEquals("3", scalar(database, "select count(*) from information_schema.columns where table_name='role' and column_name in ('description','status','system_builtin')"));
        assertEquals("2", scalar(database, "select count(*) from information_schema.columns where table_name='user_account' and column_name in ('password_changed_at','last_login_at')"));
        assertEquals("2", scalar(database, "select count(*) from role where system_builtin=true and code in ('ADMIN','TENANT_USER')"));
        assertEquals("14", scalar(database, "select count(*) from permission where code in ('USER_READ','USER_WRITE','ROLE_READ','ROLE_WRITE','TENANT_READ','TENANT_WRITE','MODEL_READ','MODEL_WRITE','API_KEY_READ','API_KEY_WRITE','USAGE_READ','COST_READ','AUDIT_READ','SYSTEM_WRITE')"));
        assertEquals("CNY", scalar(database, "select setting_value from platform_setting where setting_key='BASE_CURRENCY'"));
        assertEquals("https://api-docs.deepseek.com/zh-cn/quick_start/pricing/", scalar(database, "select endpoint from provider_price_source where id='builtin_deepseek_official_price'"));
        assertEquals("CNY", scalar(database, "select default_currency from provider_price_source where id='builtin_deepseek_official_price'"));
        assertEquals("global", scalar(database, "select region from provider_price_source where id='builtin_deepseek_official_price'"));
        assertEquals("false", scalar(database, "select auto_publish::text from provider_price_source where id='builtin_deepseek_official_price'"));
        assertEquals("2.0.0", scalar(database, "select parser_version from provider_price_source where id='builtin_deepseek_official_price'"));
        assertEquals("4", scalar(database, "select count(*) from provider_price_source where id in ('builtin_qwen_cn_official_price','builtin_kimi_cn_official_price','builtin_xiaomi_mimo_cn_official_price','builtin_zhipu_cn_official_price') and status='PAUSED'"));
        assertEquals("XIAOMI_MIMO_OFFICIAL_PAGE", scalar(database, "select adapter_code from provider_price_source where id='builtin_xiaomi_mimo_cn_official_price'"));
        assertEquals("https://api.xiaomimimo.com/v1", scalar(database, "select default_api_base from provider_template where id='tpl_xiaomi_mimo'"));
        assertEquals("2", scalar(database, "select count(*) from model_template where provider_template_id='tpl_xiaomi_mimo' and provider_model_name in ('mimo-v2.5','mimo-v2.5-pro')"));
        assertEquals("ZHIPU_OFFICIAL_PAGE", scalar(database, "select adapter_code from provider_price_source where id='builtin_zhipu_cn_official_price'"));
        assertEquals("HEADLESS", scalar(database, "select fetch_mode from provider_price_source where id='builtin_zhipu_cn_official_price'"));
        assertEquals("true", scalar(database, "select (official_hosts @> '[\"static.bigmodel.cn\"]'::jsonb)::text from provider_price_source where id='builtin_zhipu_cn_official_price'"));
        assertEquals("7", scalar(database, "select count(*) from model_template where provider_template_id='tpl_zhipu' and provider_model_name in ('glm-5.1','glm-4.5-air','glm-4.7-flashx','glm-5v-turbo','glm-4.6v-flashx','glm-4.6v-flash','glm-4.5v')"));
        assertEquals("6", scalar(database, "select count(*) from information_schema.columns where table_name='provider_price_source' and column_name in ('fetch_mode','source_priority','price_nature','structure_fingerprint','last_structure_fingerprint','structure_changed_at')"));
        assertEquals("3", scalar(database, "select count(*) from information_schema.tables where table_name in ('provider_model_alias','model_discovery_candidate','governance_event_outbox')"));
        assertEquals("5", scalar(database, "select count(*) from information_schema.columns where table_name='provider_price_sync_run' and column_name in ('parse_status','parsed_table_count','matched_table_count','generated_price_count','diagnostic_snapshot')"));
        assertEquals("12", scalar(database, "select count(*) from information_schema.columns where table_name='channel_model_deployment' and column_name in ('discovery_status','health_status','price_status','production_status','missing_streak','last_missing_at','last_probe_at','last_probe_status','production_approved_by','production_approved_at','production_decision_reason','recovery_requires_review')"));
        assertEquals("1", scalar(database, "select count(*) from pg_constraint where conname='ck_price_layer' and pg_get_constraintdef(oid) like '%CONTRACT_PRICE%'"));
        assertEquals("1", scalar(database, "select count(*) from pg_constraint where conname='ck_provider_price_diff_status' and pg_get_constraintdef(oid) like '%REVOKED%'"));
        assertEquals("1", scalar(database, "select count(*) from pg_trigger where tgname='trg_validate_app_scope' and not tgisinternal"));
        assertEquals("1", scalar(database, "select count(*) from pg_trigger where tgname='trg_validate_api_key_scope' and not tgisinternal"));
        assertEquals("1", scalar(database, "select count(*) from pg_indexes where indexname='idx_usage_project'"));
        assertEquals("1", scalar(database, "select count(*) from pg_indexes where indexname='idx_usage_app'"));
        assertEquals("3", scalar(database, "select count(*) from information_schema.tables where table_name in ('provider_billing_source','provider_billing_sync_run','provider_billing_record')"));
        assertEquals("4", scalar(database, "select count(*) from pg_constraint where conname in ('ck_provider_price_adapter','ck_provider_billing_adapter','ck_provider_billing_status','ck_provider_billing_run_status')"));
        assertEquals("2", scalar(database, "select count(*) from information_schema.columns where table_name='provider_reconciliation' and column_name in ('billing_source_id','billing_sync_run_id')"));
        assertEquals("7", scalar(database, "select count(*) from information_schema.columns where table_name='provider_price_source' and column_name in ('connector_code','data_scope','trust_level','publish_policy','credential_ref','credential_purpose','mapping_profile')"));
        assertEquals("7", scalar(database, "select count(*) from information_schema.columns where table_name='provider_price_source' and column_name in ('document_type','extraction_mode','minimum_confidence','require_manual_review','max_document_pages','max_document_bytes','llm_model')"));
        assertEquals("3", scalar(database, "select count(*) from information_schema.tables where table_name in ('price_source_mapping_rule','price_source_unmapped_record','provider_billing_raw_snapshot')"));
        assertEquals("3", scalar(database, "select count(*) from information_schema.tables where table_name in ('price_document_extraction_run','price_document_evidence','price_document_extracted_record')"));
        assertEquals("2", scalar(database, "select count(*) from information_schema.columns where table_name='provider_price_diff' and column_name in ('extraction_run_id','evidence_id')"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.columns where table_name='provider_secret' and column_name='secret_purpose'"));
        assertEquals("7", scalar(database, "select count(*) from information_schema.columns where table_name='provider_price_source' and column_name in ('managed_by','source_purpose','publish_target','bootstrap_version','stale_after_hours','last_checked_at','last_good_sync_at')"));
        assertEquals("6", scalar(database, "select count(*) from information_schema.columns where table_name='public_model_price_reference' and column_name in ('bundle_version','source_rank','is_current','last_seen_at','stale_at','price_status')"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.views where table_name='v_current_public_model_price_reference'"));
        assertEquals("2", scalar(database, "select count(*) from provider_price_source where id in ('builtin_litellm_cost_map','builtin_models_dev') and status='ACTIVE' and managed_by='SYSTEM' and source_purpose='REFERENCE' and publish_target='PUBLIC_REFERENCE_ONLY' and next_run_at is not null"));
        assertEquals("1", scalar(database, "select count(*) from provider_price_source where id='builtin_reference_price_bundle' and status='ACTIVE' and managed_by='SYSTEM' and next_run_at is null"));
        execute(database, "insert into fx_rate(id,rate_month,from_currency,to_currency,rate,source_type,source_ref,source_date,status,version) values ('fx_test','2026-07-01','USD','CNY',7.2,'MANUAL','test://fx','2026-07-01','ACTIVE',1)");
        assertEquals("14.400000000000", scalar(database, "select tokensea_fx_amount(2,'USD','2026-07-15 12:00:00+00','CNY')"));
        assertEquals("2", scalar(database, "select tokensea_fx_amount(2,'CNY','2026-07-15 12:00:00+00','CNY')"));
        assertEquals("", scalar(database, "select coalesce(tokensea_fx_amount(2,'USD','2026-08-15 12:00:00+00','CNY')::text,'')"));
        assertEquals("0", scalar(database, "select count(*) from public_model_reference"));
        assertEquals("0", scalar(database, "select count(*) from provider where id='migration_quarantine_provider'"));
        assertEquals("0", scalar(database, "select count(*) from model where id='migration_quarantine_model'"));
    }

    @Test
    void priceSourceControllerPersistsPhaseTwoConfiguration() throws Exception {
        String database = createDatabase();
        flyway(database, null).migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url(database), user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PriceSourceConnectorRegistry connectors = new PriceSourceConnectorRegistry(List.of(
                new HttpDocumentConnector(), new AzureRetailPriceConnector(), new AwsPriceListConnector(),
                new GoogleCloudCatalogConnector(), new LitellmReferenceConnector(), new ModelsDevReferenceConnector()));
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                jdbc, new ObjectMapper().findAndRegisterModules(), mock(ProviderPriceSyncService.class),
                mock(AuditService.class), connectors);
        ProviderPriceSyncController.PriceSourceRequest request = new ProviderPriceSyncController.PriceSourceRequest(
                "Phase 2 Generic Document", "OFFICIAL", "GENERIC_DOCUMENT", "demo", null, null, "NONE",
                "https://example.com/pricing.csv", List.of("example.com"), "global", "USD", "P1D", false,
                new BigDecimal("0.2"), 1,
                Map.of("recordsPath", "$.data[*]", "modelField", "model", "inputField", "input",
                        "outputField", "output", "sourceBillingQuantity", 1_000_000),
                "DRAFT", "2.0.0", "STRUCTURED_HTTP", 100, "ORIGINAL",
                "HTTP_DOCUMENT", "DOCUMENT", "OFFICIAL_PUBLIC", "MANUAL_ONLY",
                "price-record-v1", "NONE", "DEFAULT", "CSV", "DETERMINISTIC",
                new BigDecimal("0.90000"), true, 80, 5_000_000, null);

        Map<String,Object> created = controller.createSource(request, null).data();

        String id = String.valueOf(created.get("id"));
        assertEquals("CSV", scalar(database, "select document_type from provider_price_source where id='" + id + "'"));
        assertEquals("DETERMINISTIC", scalar(database, "select extraction_mode from provider_price_source where id='" + id + "'"));
        assertEquals("0.90000", scalar(database, "select minimum_confidence::text from provider_price_source where id='" + id + "'"));
        assertEquals("true", scalar(database, "select require_manual_review::text from provider_price_source where id='" + id + "'"));
        assertEquals("80", scalar(database, "select max_document_pages::text from provider_price_source where id='" + id + "'"));
        assertEquals("5000000", scalar(database, "select max_document_bytes::text from provider_price_source where id='" + id + "'"));
    }

    @Test
    void extractionReviewReadsPostgresJsonbAndReturnsAcceptedPrice() throws Exception {
        String database = createDatabase();
        flyway(database, null).migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url(database), user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        jdbc.update("""
            insert into provider_price_source(
              id,name,source_class,adapter_code,provider_type,auth_mode,endpoint,official_hosts,
              default_currency,status,connector_code,data_scope,trust_level,publish_policy,
              schema_version,credential_purpose,document_type,extraction_mode,require_manual_review)
            values('source-review','Review Source','OFFICIAL','GENERIC_DOCUMENT','demo','NONE',
              'https://example.com/pricing','[\"example.com\"]','USD','ACTIVE','HTTP_DOCUMENT',
              'DOCUMENT','OFFICIAL_PUBLIC','MANUAL_ONLY','price-record-v1','NONE','JSON',
              'DETERMINISTIC_LLM',true)
            """);
        jdbc.update("insert into provider_price_sync_run(id,price_source_id,status) values('sync-review','source-review','REVIEW_REQUIRED')");
        jdbc.update("""
            insert into provider_price_raw_snapshot(
              id,price_source_id,sync_run_id,source_endpoint,final_endpoint,http_status,content_type,
              checksum,response_bytes,raw_content,parser_version)
            values('snapshot-review','source-review','sync-review','https://example.com/pricing',
              'https://example.com/pricing',200,'application/json',repeat('a',64),100,'{}','2.0.0')
            """);
        jdbc.update("""
            insert into price_document_extraction_run(
              id,price_source_id,sync_run_id,raw_snapshot_id,document_type,extractor_code,
              extraction_mode,status)
            values('extract-review','source-review','sync-review','snapshot-review','JSON',
              'GENERIC_DOCUMENT','DETERMINISTIC_LLM','REVIEW_REQUIRED')
            """);
        jdbc.update("""
            insert into price_document_evidence(
              id,extraction_run_id,record_key,page_number,row_index,source_text,source_hash)
            values('evidence-review','extract-review','demo|demo-v1|global|standard|default|default',
              1,1,'demo-v1 input 2 USD output 8 USD',repeat('b',64))
            """);
        Map<String,Object> normalized = new LinkedHashMap<>();
        normalized.put("providerType", "demo");
        normalized.put("providerModelName", "demo-v1");
        normalized.put("displayName", "Demo V1");
        normalized.put("currency", "USD");
        normalized.put("billingBasis", "TOKEN");
        normalized.put("billingQuantity", 1_000_000);
        normalized.put("inputUnitPrice", new BigDecimal("2"));
        normalized.put("outputUnitPrice", new BigDecimal("8"));
        normalized.put("region", "global");
        normalized.put("requestMode", "STANDARD");
        normalized.put("serviceTier", "DEFAULT");
        normalized.put("contextTier", "DEFAULT");
        normalized.put("components", Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", new BigDecimal("2"), "unitBasis", "TOKEN", "unitQuantity", 1_000_000, "mode", "EXPLICIT"),
                "OUTPUT_TOKEN", Map.of("unitPrice", new BigDecimal("8"), "unitBasis", "TOKEN", "unitQuantity", 1_000_000, "mode", "EXPLICIT")));
        normalized.put("sourceRef", "https://example.com/pricing");
        normalized.put("raw", Map.of());
        jdbc.update("""
            insert into price_document_extracted_record(
              id,extraction_run_id,evidence_id,record_key,provider_type,provider_model_name,
              normalized_record,extraction_method,confidence,validation_status,validation_result,review_status)
            values('record-review','extract-review','evidence-review',
              'demo|demo-v1|global|standard|default|default','demo','demo-v1',cast(? as jsonb),
              'LLM_SCHEMA_MAPPING',0.95,'VALID','{}','PENDING')
            """, json.writeValueAsString(normalized));
        PriceDocumentExtractionService service = new PriceDocumentExtractionService(
                jdbc, json, new PriceExtractionValidator(), new ExtractionConfidenceCalculator());

        Map<String,Object> reviewed = service.reviewRecord(
                "record-review", "ACCEPTED", Map.of(), "admin", "证据核对通过");
        List<com.tokensea.governance.PriceSourceParser.NormalizedPrice> prices = service.acceptedPrices("extract-review");

        assertEquals("ACCEPTED", reviewed.get("review_status"));
        assertEquals(1, prices.size());
        assertEquals("demo-v1", prices.getFirst().providerModelName());
        assertEquals(new BigDecimal("2"), prices.getFirst().inputUnitPrice());
    }

    @Test
    void bundledReferencePricesImportIdempotently() throws Exception {
        String database = createDatabase();
        flyway(database, null).migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url(database), user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ReferencePriceBundleLoader loader = new ReferencePriceBundleLoader(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader(),
                "classpath:reference-prices/reference-price-bootstrap.json",
                720);

        ReferencePriceBundleLoader.BundleLoadResult first = loader.load();
        ReferencePriceBundleLoader.BundleLoadResult second = loader.load();

        assertEquals("LOADED", first.status());
        assertEquals(2, first.records());
        assertEquals(2, first.changed());
        assertEquals(0, second.changed());
        assertEquals("2", scalar(database, "select count(*) from public_model_price_reference where price_source_id='builtin_reference_price_bundle'"));
        assertEquals("2", scalar(database, "select count(*) from v_current_public_model_price_reference"));
        assertEquals("2", scalar(database, "select count(*) from public_model_reference where source_type='BUNDLE_IMPORT'"));
        assertEquals("0", scalar(database, "select count(*) from provider_price_diff where price_source_id='builtin_reference_price_bundle'"));
        assertEquals("2026.07.29.1", scalar(database, "select bootstrap_version from provider_price_source where id='builtin_reference_price_bundle'"));
        ReferencePriceHealthService health = new ReferencePriceHealthService(jdbc);
        assertEquals(2L, health.overview().get("pricedModelCount"));
        assertEquals(3, health.sources().size());
        assertTrue(health.sources().get(0).containsKey("modelCount"));
        var modelPage = health.models(1, 20, null, null, null, "updatedAt", "desc");
        assertEquals(2L, modelPage.total());
        assertTrue(modelPage.items().get(0).containsKey("providerType"));
        assertTrue(modelPage.items().get(0).containsKey("inputUnitPrice"));

        execute(database, """
            insert into provider_price_sync_run(id,price_source_id,trigger_type,status,scheduled_for,started_at,completed_at)
            values('online-reference-run','builtin_litellm_cost_map','SCHEDULED','SUCCEEDED',now(),now(),now());
            insert into provider_price_raw_snapshot(
              id,price_source_id,sync_run_id,source_endpoint,final_endpoint,http_status,content_type,
              checksum,response_bytes,raw_content,parser_version)
            values('online-reference-snapshot','builtin_litellm_cost_map','online-reference-run',
              'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json',
              'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json',
              200,'application/json',repeat('c',64),2,'{}','1.0.0');
            insert into public_model_price_reference(
              id,price_source_id,raw_snapshot_id,sync_run_id,provider_type,provider_model_name,canonical_name,
              display_name,currency,region,request_mode,service_tier,context_tier,input_unit_price,
              output_unit_price,source_ref,evidence_hash,source_rank,is_current,last_seen_at,stale_at,price_status)
            values('online-qwen-reference','builtin_litellm_cost_map','online-reference-snapshot',
              'online-reference-run','qwen','qwen-plus','qwen/qwen-plus','Qwen Plus Online','CNY','cn',
              'STANDARD','DEFAULT','DEFAULT',0.7,1.8,'https://reference.example/qwen-plus',repeat('d',64),
              200,true,now(),now()+interval '7 days','CURRENT');
            """);
        assertEquals("builtin_litellm_cost_map", scalar(database, """
            select price_source_id from v_current_public_model_price_reference
            where provider_type='qwen' and provider_model_name='qwen-plus'
            """));
        execute(database, """
            update public_model_price_reference set stale_at=now()-interval '1 minute'
            where id='online-qwen-reference'
            """);
        health.refreshStaleStatus();
        assertEquals("builtin_reference_price_bundle", scalar(database, """
            select price_source_id from v_current_public_model_price_reference
            where provider_type='qwen' and provider_model_name='qwen-plus'
            """));
    }

    @Test
    void dirtyV6SnapshotIsQuarantinedBeforeImmutableV7() throws Exception {
        String database = createDatabase();
        flyway(database, "6").migrate();
        injectDirtyGenericCrudData(database);

        Flyway flyway = Flyway.configure().dataSource(url(database), user, password)
                .locations("classpath:db/migration")
                .callbacks(new LegacyV7PreflightCallback()).load();
        flyway.migrate();
        assertTrue(flyway.validateWithResult().validationSuccessful);

        assertEquals("44", scalar(database, "select version from flyway_schema_history where success order by installed_rank desc limit 1"));
        assertTrue(Integer.parseInt(scalar(database, "select count(*) from migration_quarantine")) >= 4);
        assertTrue(Integer.parseInt(scalar(database, "select count(*) from audit_log where action='MIGRATION_QUARANTINE'")) >= 4);
        assertEquals("0", scalar(database, "select count(*) from provider_secret where num_nonnulls(provider_id,provider_instance_id)<>1"));
        assertEquals("0", scalar(database, "select count(*) from model_price"));
        assertEquals("6", scalar(database, "select count(*) from information_schema.columns where table_name='model_price' and column_name in ('billing_basis','billing_quantity','input_cost_unit_price','output_cost_unit_price','input_price_unit_price','output_price_unit_price')"));
        assertEquals("草稿", scalar(database, "select status from platform_model where id='pm_dirty'"));
        assertEquals("", scalar(database, "select coalesce(route_policy_id,'') from platform_model where id='pm_dirty'"));
    }

    private void injectDirtyGenericCrudData(String database) throws Exception {
        execute(database, """
            INSERT INTO provider(id,name,provider_type,api_style,status) VALUES ('provider_dirty','Dirty','custom','openai_compatible','ACTIVE');
            INSERT INTO provider_instance(id,instance_name,provider_type,api_style,key_status,environment,health_status,status)
              VALUES ('pi_dirty','Dirty channel','custom','openai_compatible','未配置','测试','观察','暂停');
            INSERT INTO provider_secret(id,provider_id,provider_instance_id,secret_cipher,status)
              VALUES ('secret_no_owner',NULL,NULL,'not-a-real-secret','ACTIVE'),
                     ('secret_two_owners','provider_dirty','pi_dirty','not-a-real-secret','ACTIVE');
            INSERT INTO platform_model(id,platform_model_name,display_name,route_policy_id,price_policy_id,status)
              VALUES ('pm_dirty','dirty-service','Dirty service','missing_route','missing_price','已发布'),
                     ('pm_overlap','overlap-service','Overlap service',NULL,NULL,'草稿');
            INSERT INTO model_price(id,model_id,platform_model_id,provider_instance_id,currency,
              input_cost_per_1k,output_cost_per_1k,input_price_per_1k,output_price_per_1k,effective_from,effective_to,status)
              VALUES ('price_bad',NULL,'pm_dirty','pi_dirty','usd',-1,0,0,0,now(),now()-interval '1 day','ACTIVE'),
                     ('price_overlap_a',NULL,'pm_overlap',NULL,'CNY',0,0,0,0,now()-interval '2 days',NULL,'ACTIVE'),
                     ('price_overlap_b',NULL,'pm_overlap',NULL,'CNY',0,0,0,0,now()-interval '1 day',NULL,'ACTIVE');
            """);
    }

    private Flyway flyway(String database, String target) {
        var configuration = Flyway.configure().dataSource(url(database), user, password).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private String createDatabase() throws Exception {
        String name = "tokensea_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl(), user, password);
             Statement statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + name); }
        databases.add(name);
        return name;
    }

    private void execute(String database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url(database), user, password);
             Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String scalar(String database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url(database), user, password);
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next(); return result.getString(1);
        }
    }

    private String adminUrl() { return "jdbc:postgresql://" + host + ":" + port + "/postgres"; }
    private String url(String database) { return "jdbc:postgresql://" + host + ":" + port + "/" + database; }
}
