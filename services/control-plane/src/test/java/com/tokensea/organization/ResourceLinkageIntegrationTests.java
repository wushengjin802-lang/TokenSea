package com.tokensea.organization;

import com.tokensea.organization.service.ResourceLinkageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceLinkageIntegrationTests {
    @Test
    void projectAndAppExposeKeyUsageCostAndDatabaseRejectsCrossTenantKey() {
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

        jdbc.update("""
            insert into tenant(id,name,type,owner_name,contact_email,status,model_scope)
            values('tenant-a','租户 A','INTERNAL','负责人','a@example.test','ACTIVE','["*"]'),
                  ('tenant-b','租户 B','INTERNAL','负责人','b@example.test','ACTIVE','["*"]')
            """);
        jdbc.update("""
            insert into project(id,tenant_id,name,owner_name,monthly_budget,status)
            values('project-a','tenant-a','项目 A','项目负责人',100,'ACTIVE'),
                  ('project-b','tenant-b','项目 B','项目负责人',100,'ACTIVE')
            """);
        jdbc.update("""
            insert into app(id,tenant_id,project_id,name,owner_name,environment,status)
            values('app-a','tenant-a','project-a','应用 A','应用负责人','PROD','ACTIVE')
            """);
        jdbc.update("""
            insert into api_key(id,tenant_id,project_id,app_id,name,key_hash,key_prefix,status,approval_status,model_scope)
            values('key-a','tenant-a','project-a','app-a','Key A','hash-a','ts_test','ACTIVE','APPROVED','["chat-standard"]')
            """);
        jdbc.update("""
            insert into usage_record(id,request_id,tenant_id,project_id,app_id,api_key_id,model_alias,
              prompt_tokens,completion_tokens,total_tokens,cost_amount,sales_amount,currency,status,latency_ms)
            values('usage-a','request-a','tenant-a','project-a','app-a','key-a','chat-standard',
              10,20,30,2,2,'CNY','SUCCESS',120)
            """);

        ResourceLinkageService service = new ResourceLinkageService(jdbc);
        Map<String,Object> project = service.projectOverview("project-a");
        Map<String,Object> app = service.appOverview("app-a");

        assertThat(project.get("app_count")).isEqualTo(1L);
        assertThat(project.get("key_count")).isEqualTo(1L);
        assertThat(project.get("monthly_requests")).isEqualTo(1L);
        assertThat(new BigDecimal(project.get("monthly_cost_cny").toString())).isEqualByComparingTo("2");
        assertThat(new BigDecimal(project.get("budget_usage_percent").toString())).isEqualByComparingTo("2");
        assertThat(app.get("key_count")).isEqualTo(1L);
        assertThat(app.get("monthly_tokens")).isEqualTo(30L);
        assertThat(new BigDecimal(app.get("success_rate").toString())).isEqualByComparingTo("100");
        assertThat(service.projectKeys("project-a")).singleElement()
                .extracting(row -> row.get("app_name")).isEqualTo("应用 A");

        assertThatThrownBy(() -> jdbc.update("""
            insert into api_key(id,tenant_id,project_id,name,key_hash,key_prefix,status,approval_status,model_scope)
            values('key-invalid','tenant-a','project-b','错误 Key','hash-invalid','ts_bad','ACTIVE','APPROVED','["chat-standard"]')
            """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Virtual Key 租户与项目租户不一致");
    }
}
