package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.governance.pricing.reference.ReferencePriceBindingService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelDeploymentReferencePriceTests {
    @Test
    void deploymentUsesTieredExactReferencePriceBindingsWithoutFormalPriceVersion() {
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
        ModelLifecycleService lifecycle = new ModelLifecycleService(
                jdbc, mock(AuditService.class), new ObjectMapper().findAndRegisterModules());
        ModelDeploymentGovernanceController controller = new ModelDeploymentGovernanceController(
                jdbc, lifecycle, mock(EffectiveCostPriceResolver.class));
        ReferencePriceBindingService bindings = new ReferencePriceBindingService(jdbc, true);

        jdbc.update("""
            insert into provider_instance(id,instance_name,provider_type,region,status)
            values('doubao-channel','豆包','volcengine_ark','cn','启用')
            """);
        jdbc.update("""
            insert into provider_model_snapshot(id,provider_instance_id,source_endpoint,http_status,checksum,raw_payload)
            values('doubao-snapshot','doubao-channel','https://ark.cn-beijing.volces.com/api/v3/models',
              200,repeat('a',64),'{}')
            """);
        jdbc.update("""
            insert into channel_model_deployment(
              id,provider_instance_id,provider_model_name,display_name,raw_model,source_snapshot_id,
              health_status,production_status,price_status)
            values
              ('doubao-bundled','doubao-channel','doubao-seed-2-0-lite-260215','Doubao Lite','{}',
               'doubao-snapshot','HEALTHY','READY_FOR_REVIEW','MISSING'),
              ('doubao-aggregator','doubao-channel','doubao-seed-2-0-mini-260428','Doubao Mini','{}',
               'doubao-snapshot','HEALTHY','APPROVED','MISSING'),
              ('doubao-version-mismatch','doubao-channel','doubao-seed-2-0-mini-260429','Doubao Mini New','{}',
               'doubao-snapshot','HEALTHY','READY_FOR_REVIEW','MISSING')
            """);

        insertReferenceEvidence(jdbc, "bundle", "builtin_reference_price_bundle", "b");
        jdbc.update("""
            insert into public_model_price_reference(
              id,price_source_id,raw_snapshot_id,sync_run_id,provider_type,provider_model_name,canonical_name,
              display_name,currency,region,request_mode,service_tier,context_tier,input_unit_price,
              output_unit_price,source_ref,evidence_hash,source_rank,is_current,last_seen_at,stale_at,price_status)
            values('doubao-bundle-reference','builtin_reference_price_bundle','bundle-snapshot',
              'bundle-run','volcengine_ark','doubao-seed-2-0-lite-260215',
              'volcengine_ark/doubao-seed-2-0-lite-260215','Doubao Seed 2.0 Lite','CNY','cn',
              'STANDARD','DEFAULT','DEFAULT',0.6,3.6,'bundle:test',repeat('c',64),10,true,now(),
              now()+interval '7 days','CURRENT')
            """);

        insertReferenceEvidence(jdbc, "models", "builtin_models_dev", "d");
        jdbc.update("""
            insert into public_model_price_reference(
              id,price_source_id,raw_snapshot_id,sync_run_id,provider_type,provider_model_name,canonical_name,
              display_name,currency,region,request_mode,service_tier,context_tier,input_unit_price,
              output_unit_price,source_ref,evidence_hash,source_rank,is_current,last_seen_at,stale_at,price_status)
            values('doubao-aihubmix-reference','builtin_models_dev','models-snapshot',
              'models-run','aihubmix','doubao-seed-2-0-mini-260428',
              'aihubmix/doubao-seed-2-0-mini-260428','Doubao Seed 2.0 Mini via AIHubMix','USD','global',
              'STANDARD','DEFAULT','DEFAULT',0.03,0.28,'https://models.dev/api.json',repeat('e',64),150,true,now(),
              now()+interval '7 days','CURRENT')
            """);

        assertThat(bindings.reconcileAll()).isEqualTo(2);

        List<Map<String,Object>> rows = controller.list(null, null).data();
        Map<String,Object> bundled = row(rows, "doubao-bundled");
        Map<String,Object> aggregator = row(rows, "doubao-aggregator");
        Map<String,Object> mismatch = row(rows, "doubao-version-mismatch");

        assertThat(bundled.get("reference_price_status")).isEqualTo("BUNDLED_REFERENCE");
        assertThat(bundled.get("reference_match_type")).isEqualTo("BUNDLED_EXACT");
        assertThat(bundled.get("reference_currency")).isEqualTo("CNY");

        assertThat(aggregator.get("reference_price_status")).isEqualTo("AGGREGATOR_REFERENCE");
        assertThat(aggregator.get("reference_match_type")).isEqualTo("AGGREGATOR_EXACT");
        assertThat(aggregator.get("reference_source_provider_type")).isEqualTo("aihubmix");
        assertThat(aggregator.get("reference_origin_provider_type")).isEqualTo("volcengine_ark");
        assertThat(aggregator.get("reference_currency")).isEqualTo("USD");
        assertThat((BigDecimal) aggregator.get("reference_input_unit_price")).isEqualByComparingTo("0.03");
        assertThat(String.valueOf(aggregator.get("reference_match_reason"))).contains("聚合渠道");
        assertThat(controller.referencePrice("doubao-aggregator").data().get("referencePriceStatus"))
                .isEqualTo("AGGREGATOR_REFERENCE");

        assertThat(mismatch.get("reference_price_status")).isEqualTo("MISSING_REFERENCE");
        assertThat(controller.referencePrice("doubao-version-mismatch").data().get("message"))
                .isEqualTo("公共参考库暂无精确匹配价格；该状态不影响模型生产准入和调用");

        insertReferenceEvidence(jdbc, "litellm", "builtin_litellm_cost_map", "f");
        jdbc.update("""
            insert into public_model_price_reference(
              id,price_source_id,raw_snapshot_id,sync_run_id,provider_type,provider_model_name,canonical_name,
              display_name,currency,region,request_mode,service_tier,context_tier,input_unit_price,
              output_unit_price,source_ref,evidence_hash,source_rank,is_current,last_seen_at,stale_at,price_status)
            values('doubao-vendor-reference','builtin_litellm_cost_map','litellm-snapshot',
              'litellm-run','volcengine_ark','doubao-seed-2-0-mini-260428',
              'volcengine_ark/doubao-seed-2-0-mini-260428','Doubao Seed 2.0 Mini','USD','global',
              'STANDARD','DEFAULT','DEFAULT',0.04,0.30,'https://github.com/BerriAI/litellm',repeat('1',64),200,true,now(),
              now()+interval '7 days','CURRENT')
            """);
        assertThat(bindings.reconcileAll()).isEqualTo(3);
        Map<String,Object> preferred = row(controller.list(null, null).data(), "doubao-aggregator");
        assertThat(preferred.get("reference_price_status")).isEqualTo("VENDOR_REFERENCE");
        assertThat(preferred.get("reference_match_type")).isEqualTo("VENDOR_EXACT");
        assertThat(preferred.get("reference_source_provider_type")).isEqualTo("volcengine_ark");
        assertThat((BigDecimal) preferred.get("reference_input_unit_price")).isEqualByComparingTo("0.04");

        jdbc.update("update channel_model_deployment set production_status='REJECTED',review_status='REJECTED' where id='doubao-version-mismatch'");
        lifecycle.updatePriceStatus("doubao-version-mismatch", "MATCHED_OFFICIAL");
        assertThat(jdbc.queryForObject(
                "select production_status from channel_model_deployment where id='doubao-version-mismatch'",
                String.class)).isEqualTo("REJECTED");
    }

    private static void insertReferenceEvidence(JdbcTemplate jdbc, String prefix, String sourceId, String checksumChar) {
        jdbc.update("""
            insert into provider_price_sync_run(
              id,price_source_id,trigger_type,status,scheduled_for,started_at,completed_at)
            values(?,?, 'SCHEDULED','SUCCEEDED',now(),now(),now())
            """, prefix + "-run", sourceId);
        jdbc.update("""
            insert into provider_price_raw_snapshot(
              id,price_source_id,sync_run_id,source_endpoint,final_endpoint,http_status,content_type,
              checksum,response_bytes,raw_content,parser_version)
            values(?,?,?, ?,?,200,'application/json',?,2,'{}','binding-test')
            """, prefix + "-snapshot", sourceId, prefix + "-run",
                "https://reference.example/" + prefix, "https://reference.example/" + prefix,
                checksumChar.repeat(64));
    }

    private static Map<String,Object> row(List<Map<String,Object>> rows, String id) {
        return rows.stream().filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
    }
}
