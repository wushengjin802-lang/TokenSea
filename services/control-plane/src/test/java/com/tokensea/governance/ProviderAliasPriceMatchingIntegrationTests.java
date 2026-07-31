package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.audit.service.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderAliasPriceMatchingIntegrationTests {
    @Test
    void approvedAliasMapsChannelDeploymentToOfficialCatalogPrice() {
        JdbcTemplate jdbc = database();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        PricingComponentService components = new PricingComponentService(json);
        ProviderPriceCatalogService service = new ProviderPriceCatalogService(
                jdbc, json, mock(AuditService.class), components);

        jdbc.update("""
            insert into provider_instance(id,instance_name,provider_type,region,status)
            values('alias-channel','Qwen CN','qwen','cn','启用')
            """);
        jdbc.update("""
            insert into provider_model_snapshot(id,provider_instance_id,source_endpoint,http_status,checksum,raw_payload)
            values('alias-snapshot','alias-channel','https://dashscope.aliyuncs.com/models',200,repeat('a',64),'{}')
            """);
        jdbc.update("""
            insert into channel_model_deployment(id,provider_instance_id,provider_model_name,raw_model,source_snapshot_id)
            values('alias-deployment','alias-channel','qwen-plus-latest','{}','alias-snapshot')
            """);
        jdbc.update("""
            insert into provider_model_price_catalog(
              id,provider_type,provider_model_name,display_name,currency,billing_basis,billing_quantity,
              input_unit_price,output_unit_price,cache_read_mode,cache_write_mode,
              price_components,component_schema_version,price_completeness_status,cache_pricing_status,
              source_type,source_ref,effective_from,status,
              region,request_mode,service_tier,context_tier)
            values('alias-catalog','qwen','qwen-plus-2026-07-01','Qwen Plus','CNY','TOKEN',1000000,
              1,2,'NOT_APPLICABLE','NOT_APPLICABLE',
              '[{"componentType":"INPUT_TOKEN","variant":"DEFAULT","unitPrice":1,"unitBasis":"TOKEN","unitQuantity":1000000,"mode":"EXPLICIT","priority":100,"scope":{},"sourceRef":"test","metadata":{}},{"componentType":"OUTPUT_TOKEN","variant":"DEFAULT","unitPrice":2,"unitBasis":"TOKEN","unitQuantity":1000000,"mode":"EXPLICIT","priority":100,"scope":{},"sourceRef":"test","metadata":{}}]',
              2,'UNSUPPORTED_CACHE','UNSUPPORTED_CACHE','OFFICIAL_REFERENCE','https://help.aliyun.com',now(),'ACTIVE',
              'cn','STANDARD','DEFAULT','0_256000')
            """);
        jdbc.update("""
            insert into provider_model_alias(
              id,provider_type,provider_model_name,target_provider_model_name,relation_type,region,
              source_type,source_ref,evidence_hash,review_status,effective_from)
            values('alias-approved','qwen','qwen-plus-latest','qwen-plus-2026-07-01','STABLE_ALIAS','cn',
              'OFFICIAL_REFERENCE','https://help.aliyun.com',repeat('b',64),'APPROVED',now()-interval '1 day')
            """);

        ProviderInstance instance = new ProviderInstance();
        instance.setId("alias-channel");
        instance.setProviderType("qwen");
        instance.setRegion("cn");
        var result = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .execute(status -> service.autoFill(instance, "alias-deployment", "qwen-plus-latest"));

        assertThat(result.matched()).isTrue();
        assertThat(result.matchType()).isEqualTo("ALIAS");
        assertThat(result.catalogPriceId()).isEqualTo("alias-catalog");
        assertThat(jdbc.queryForObject("""
            select count(*) from price_version where deployment_id='alias-deployment'
              and price_layer='PROVIDER_OFFICIAL' and status='ACTIVE'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select price_status from channel_model_deployment where id='alias-deployment'
            """, String.class)).isEqualTo("MATCHED_OFFICIAL");
        assertThat(jdbc.queryForObject("""
            select context_tier from price_version where deployment_id='alias-deployment'
              and price_layer='PROVIDER_OFFICIAL' and status='ACTIVE'
            """, String.class)).isEqualTo("DEFAULT");

        jdbc.update("""
            insert into channel_model_deployment(id,provider_instance_id,provider_model_name,raw_model,source_snapshot_id)
            values('missing-price-deployment','alias-channel','unpriced-model','{}','alias-snapshot')
            """);
        jdbc.update("""
            update channel_model_deployment
            set health_status='HEALTHY',review_status='APPROVED',production_status='APPROVED',routing_status='ELIGIBLE'
            where id='missing-price-deployment'
            """);
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> service.autoFill(instance, "missing-price-deployment", "unpriced-model"));

        assertThat(jdbc.queryForObject("select price_status from channel_model_deployment where id='missing-price-deployment'",
                String.class)).isEqualTo("MISSING");
        assertThat(jdbc.queryForObject("select production_status from channel_model_deployment where id='missing-price-deployment'",
                String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select routing_status from channel_model_deployment where id='missing-price-deployment'",
                String.class)).isEqualTo("ELIGIBLE");

        assertThat(jdbc.queryForObject("""
            select count(*) from alert_event where alert_type='PROVIDER_PRICE_COVERAGE_GAP'
              and resource_type='PROVIDER_INSTANCE' and resource_id='alias-channel' and status='OPEN'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from alert_event where alert_type='MODEL_PRICE_MISSING'
              and resource_type='MODEL_DEPLOYMENT' and resource_id='missing-price-deployment'
              and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class)).isZero();
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
