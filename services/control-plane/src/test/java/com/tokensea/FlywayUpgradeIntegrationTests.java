package com.tokensea;

import com.tokensea.config.LegacyV7PreflightCallback;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void cleanInstallMigratesV1ThroughV12WithoutQuarantineSentinels() throws Exception {
        String database = createDatabase();
        Flyway flyway = flyway(database, null);
        flyway.migrate();
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals("12", scalar(database, "select version from flyway_schema_history where success order by installed_rank desc limit 1"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='channel_model_deployment'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='usage_cost_snapshot'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='budget_rule_event'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='runtime_quickstart_config'"));
        assertEquals("4", scalar(database, "select count(*) from information_schema.columns where table_name='capability_validation' and column_name in ('probe_endpoint','http_status','stream_verified','probe_request_id')"));
        assertEquals("2", scalar(database, "select count(*) from information_schema.columns where table_name='price_version' and column_name in ('activated_by','activated_at')"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='error_code_registry'"));
        assertEquals("1", scalar(database, "select count(*) from information_schema.tables where table_name='cost_statement'"));
        assertEquals("0", scalar(database, "select count(*) from public_model_reference"));
        assertEquals("0", scalar(database, "select count(*) from provider where id='migration_quarantine_provider'"));
        assertEquals("0", scalar(database, "select count(*) from model where id='migration_quarantine_model'"));
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

        assertEquals("12", scalar(database, "select version from flyway_schema_history where success order by installed_rank desc limit 1"));
        assertTrue(Integer.parseInt(scalar(database, "select count(*) from migration_quarantine")) >= 4);
        assertTrue(Integer.parseInt(scalar(database, "select count(*) from audit_log where action='MIGRATION_QUARANTINE'")) >= 4);
        assertEquals("0", scalar(database, "select count(*) from provider_secret where num_nonnulls(provider_id,provider_instance_id)<>1"));
        assertEquals("0", scalar(database, "select count(*) from model_price where least(input_cost_per_1k,output_cost_per_1k,input_price_per_1k,output_price_per_1k)<0"));
        assertEquals("1", scalar(database, "select count(*) from model_price where id in ('price_overlap_a','price_overlap_b') and status='ACTIVE'"));
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
