package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelDiscoveryLifecycleTests {
    @Test
    void debouncesMissingModelsRequiresProductionApprovalAndReapprovesRecovery() {
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

        jdbc.update("""
            insert into provider_instance(id,instance_name,provider_type,region,status)
            values('channel-life','Kimi Production','moonshot','cn','启用')
            """);
        jdbc.update("""
            insert into provider_model_snapshot(id,provider_instance_id,source_endpoint,http_status,checksum,raw_payload)
            values('model-snapshot-life','channel-life','https://api.moonshot.cn/v1/models',200,repeat('a',64),'{}')
            """);
        jdbc.update("""
            insert into channel_model_deployment(
              id,provider_instance_id,provider_model_name,display_name,raw_model,source_snapshot_id)
            values('deployment-life','channel-life','kimi-test','Kimi Test','{}','model-snapshot-life')
            """);
        lifecycle.markNewDeployment("deployment-life");
        assertState(jdbc, "PROBE_PENDING", "CANDIDATE", "INELIGIBLE", "DISCOVERED", 0);

        assertThat(jdbc.queryForObject(
                "select price_status from channel_model_deployment where id='deployment-life'", String.class))
                .isEqualTo("MISSING");
        insertProbe(jdbc, "probe-life-1", "PASSED");
        lifecycle.markProbeResult("deployment-life", true);
        assertState(jdbc, "HEALTHY", "READY_FOR_REVIEW", "INELIGIBLE", "DISCOVERED", 0);

        lifecycle.approveProduction("deployment-life", "admin", "initial approval");
        assertState(jdbc, "HEALTHY", "APPROVED", "ELIGIBLE", "DISCOVERED", 0);

        for (int streak = 1; streak <= 3; streak++) {
            ModelLifecycleService.MissingDecision decision = lifecycle.recordMissingObservation("deployment-life", 4);
            assertThat(decision.missingStreak()).isEqualTo(streak);
            assertThat(decision.probeRequired()).isFalse();
            assertState(jdbc, "HEALTHY", "APPROVED", "ELIGIBLE", "SUSPECTED_MISSING", streak);
        }
        ModelLifecycleService.MissingDecision fourth = lifecycle.recordMissingObservation("deployment-life", 4);
        assertThat(fourth.probeRequired()).isTrue();
        assertState(jdbc, "PROBE_PENDING", "APPROVED", "ELIGIBLE", "SUSPECTED_MISSING", 4);

        insertProbe(jdbc, "probe-life-2", "PASSED");
        lifecycle.markProbeResult("deployment-life", true);
        assertState(jdbc, "HEALTHY", "APPROVED", "ELIGIBLE", "SUSPECTED_MISSING", 4);

        for (int streak = 5; streak <= 7; streak++) {
            ModelLifecycleService.MissingDecision decision = lifecycle.recordMissingObservation("deployment-life", 4);
            assertThat(decision.probeRequired()).isFalse();
        }
        ModelLifecycleService.MissingDecision eighth = lifecycle.recordMissingObservation("deployment-life", 4);
        assertThat(eighth.probeRequired()).isTrue();
        insertProbe(jdbc, "probe-life-3", "FAILED");
        lifecycle.markProbeResult("deployment-life", false);
        assertState(jdbc, "UNAVAILABLE", "SUSPENDED", "SUSPENDED", "MISSING_CONFIRMED", 8);

        ModelLifecycleService.SeenDecision recovered = lifecycle.markSeen("deployment-life", "model-snapshot-life");
        assertThat(recovered.probeRequired()).isTrue();
        assertState(jdbc, "PROBE_PENDING", "SUSPENDED", "INELIGIBLE", "RECOVERED", 0);

        insertProbe(jdbc, "probe-life-4", "PASSED");
        lifecycle.markProbeResult("deployment-life", true);
        assertState(jdbc, "HEALTHY", "READY_FOR_REVIEW", "INELIGIBLE", "RECOVERED", 0);
        lifecycle.approveProduction("deployment-life", "admin", "recovery approval");
        assertState(jdbc, "HEALTHY", "APPROVED", "ELIGIBLE", "RECOVERED", 0);
    }

    private static void insertProbe(JdbcTemplate jdbc, String id, String status) {
        jdbc.update("""
            insert into capability_validation(id,deployment_id,capability_code,test_type,status)
            values(?,'deployment-life','CHAT','LIVE_PROBE',?)
            """, id, status);
    }

    private static void assertState(JdbcTemplate jdbc, String health, String production,
                                    String routing, String discovery, int streak) {
        Map<String,Object> value = jdbc.queryForMap(
                "select * from channel_model_deployment where id='deployment-life'");
        assertThat(value.get("health_status")).isEqualTo(health);
        assertThat(value.get("production_status")).isEqualTo(production);
        assertThat(value.get("routing_status")).isEqualTo(routing);
        assertThat(value.get("discovery_status")).isEqualTo(discovery);
        assertThat(((Number) value.get("missing_streak")).intValue()).isEqualTo(streak);
    }
}
