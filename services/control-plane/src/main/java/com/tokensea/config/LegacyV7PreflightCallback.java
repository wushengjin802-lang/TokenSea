package com.tokensea.config;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

/** Isolates malformed generic-CRUD data immediately before immutable V7 runs. */
@Component
public class LegacyV7PreflightCallback implements Callback {
    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE;
    }

    @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }

    @Override
    public void handle(Event event, Context context) {
        try {
            if (!requiresPreflight(context.getConnection())) return;
            isolate(context.getConnection());
        }
        catch (SQLException e) { throw new IllegalStateException("V7 升级前数据隔离失败", e); }
    }

    private boolean requiresPreflight(Connection connection) throws SQLException {
        try (Statement statement=connection.createStatement(); ResultSet result=statement.executeQuery("""
            SELECT EXISTS (
              SELECT 1 FROM information_schema.columns
              WHERE table_schema='public' AND table_name='model_price' AND column_name='platform_model_id'
            ) AND NOT EXISTS (
              SELECT 1 FROM flyway_schema_history WHERE version='7' AND success
            )
            """)) {
            result.next(); return result.getBoolean(1);
        } catch (SQLException missingHistory) {
            return false;
        }
    }

    private void isolate(Connection connection) throws SQLException {
        execute(connection, """
            CREATE TABLE IF NOT EXISTS migration_quarantine (
              id varchar(64) PRIMARY KEY, source_table varchar(80) NOT NULL,
              source_id varchar(64) NOT NULL, reason varchar(200) NOT NULL,
              quarantined_at timestamptz NOT NULL DEFAULT now(),
              UNIQUE(source_table, source_id, reason))
            """);
        execute(connection, """
            INSERT INTO provider(id,name,provider_type,api_style,status)
            SELECT 'migration_quarantine_provider','迁移隔离渠道','quarantine','disabled','DISABLED'
            WHERE EXISTS (
              SELECT 1 FROM provider_secret s LEFT JOIN provider p ON p.id=s.provider_id
              LEFT JOIN provider_instance pi ON pi.id=s.provider_instance_id
              WHERE num_nonnulls(s.provider_id,s.provider_instance_id)<>1
                 OR (s.provider_id IS NOT NULL AND p.id IS NULL)
                 OR (s.provider_instance_id IS NOT NULL AND pi.id IS NULL))
            ON CONFLICT(id) DO NOTHING
            """);
        execute(connection, """
            INSERT INTO model(id,alias,display_name,status)
            SELECT 'migration_quarantine_model','migration-quarantine','迁移隔离模型','DISABLED'
            WHERE EXISTS (
              SELECT 1 FROM model_price mp LEFT JOIN model m ON m.id=mp.model_id
              LEFT JOIN platform_model pm ON pm.id=mp.platform_model_id
              WHERE (mp.model_id IS NOT NULL AND m.id IS NULL)
                 OR (mp.platform_model_id IS NOT NULL AND pm.id IS NULL)
                 OR num_nonnulls(mp.model_id,mp.platform_model_id)<>1)
            ON CONFLICT(id) DO NOTHING
            """);

        execute(connection, """
            INSERT INTO migration_quarantine(id,source_table,source_id,reason)
            SELECT replace(gen_random_uuid()::text,'-',''),'provider_secret',s.id,'invalid_owner_or_reference'
            FROM provider_secret s
            LEFT JOIN provider p ON p.id=s.provider_id
            LEFT JOIN provider_instance pi ON pi.id=s.provider_instance_id
            WHERE num_nonnulls(s.provider_id,s.provider_instance_id)<>1
               OR (s.provider_id IS NOT NULL AND p.id IS NULL)
               OR (s.provider_instance_id IS NOT NULL AND pi.id IS NULL)
            ON CONFLICT DO NOTHING
            """);
        execute(connection, """
            UPDATE provider_secret s SET
              status='QUARANTINED',
              provider_id=CASE WHEN pi.id IS NOT NULL THEN NULL WHEN p.id IS NOT NULL THEN p.id ELSE 'migration_quarantine_provider' END,
              provider_instance_id=CASE WHEN pi.id IS NOT NULL THEN pi.id ELSE NULL END
            FROM (SELECT s2.id,p2.id provider_ok,pi2.id instance_ok
                  FROM provider_secret s2 LEFT JOIN provider p2 ON p2.id=s2.provider_id
                  LEFT JOIN provider_instance pi2 ON pi2.id=s2.provider_instance_id) x
            LEFT JOIN provider p ON p.id=x.provider_ok
            LEFT JOIN provider_instance pi ON pi.id=x.instance_ok
            WHERE s.id=x.id AND (num_nonnulls(s.provider_id,s.provider_instance_id)<>1
               OR (s.provider_id IS NOT NULL AND p.id IS NULL)
               OR (s.provider_instance_id IS NOT NULL AND pi.id IS NULL))
            """);

        execute(connection, """
            INSERT INTO migration_quarantine(id,source_table,source_id,reason)
            SELECT replace(gen_random_uuid()::text,'-',''),'model_price',mp.id,'invalid_price_contract'
            FROM model_price mp LEFT JOIN model m ON m.id=mp.model_id
            LEFT JOIN platform_model pm ON pm.id=mp.platform_model_id
            LEFT JOIN provider_instance pi ON pi.id=mp.provider_instance_id
            WHERE (mp.model_id IS NOT NULL AND m.id IS NULL)
               OR (mp.platform_model_id IS NOT NULL AND pm.id IS NULL)
               OR (mp.provider_instance_id IS NOT NULL AND pi.id IS NULL)
               OR num_nonnulls(mp.model_id,mp.platform_model_id)<>1
               OR (mp.model_id IS NOT NULL AND mp.provider_instance_id IS NOT NULL)
               OR least(mp.input_cost_per_1k,mp.output_cost_per_1k,mp.input_price_per_1k,mp.output_price_per_1k)<0
               OR mp.currency !~ '^[A-Z]{3}$' OR (mp.effective_to IS NOT NULL AND mp.effective_to<=mp.effective_from)
            ON CONFLICT DO NOTHING
            """);
        execute(connection, """
            UPDATE model_price mp SET
              status='QUARANTINED',
              model_id=CASE WHEN pm.id IS NOT NULL THEN NULL ELSE coalesce(m.id,'migration_quarantine_model') END,
              platform_model_id=CASE WHEN pm.id IS NOT NULL THEN pm.id ELSE NULL END,
              provider_instance_id=CASE WHEN pm.id IS NOT NULL THEN pi.id ELSE NULL END,
              currency=CASE WHEN upper(mp.currency) ~ '^[A-Z]{3}$' THEN upper(mp.currency) ELSE 'CNY' END,
              input_cost_per_1k=greatest(mp.input_cost_per_1k,0), output_cost_per_1k=greatest(mp.output_cost_per_1k,0),
              input_price_per_1k=greatest(mp.input_price_per_1k,0), output_price_per_1k=greatest(mp.output_price_per_1k,0),
              effective_to=CASE WHEN mp.effective_to IS NOT NULL AND mp.effective_to<=mp.effective_from THEN NULL ELSE mp.effective_to END
            FROM (SELECT x.id,m2.id model_ok,pm2.id platform_ok,pi2.id instance_ok FROM model_price x
                  LEFT JOIN model m2 ON m2.id=x.model_id LEFT JOIN platform_model pm2 ON pm2.id=x.platform_model_id
                  LEFT JOIN provider_instance pi2 ON pi2.id=x.provider_instance_id) valid
            LEFT JOIN model m ON m.id=valid.model_ok LEFT JOIN platform_model pm ON pm.id=valid.platform_ok
            LEFT JOIN provider_instance pi ON pi.id=valid.instance_ok
            WHERE mp.id=valid.id AND ((mp.model_id IS NOT NULL AND m.id IS NULL)
               OR (mp.platform_model_id IS NOT NULL AND pm.id IS NULL) OR (mp.provider_instance_id IS NOT NULL AND pi.id IS NULL)
               OR num_nonnulls(mp.model_id,mp.platform_model_id)<>1 OR (mp.model_id IS NOT NULL AND mp.provider_instance_id IS NOT NULL)
               OR least(mp.input_cost_per_1k,mp.output_cost_per_1k,mp.input_price_per_1k,mp.output_price_per_1k)<0
               OR mp.currency !~ '^[A-Z]{3}$' OR (mp.effective_to IS NOT NULL AND mp.effective_to<=mp.effective_from))
            """);
        execute(connection, """
            INSERT INTO migration_quarantine(id,source_table,source_id,reason)
            SELECT replace(gen_random_uuid()::text,'-',''),'model_price',p.id,'overlapping_active_price'
            FROM model_price p WHERE p.status='ACTIVE' AND EXISTS (
              SELECT 1 FROM model_price q WHERE q.status='ACTIVE' AND q.id<p.id
                AND q.model_id IS NOT DISTINCT FROM p.model_id
                AND q.platform_model_id IS NOT DISTINCT FROM p.platform_model_id
                AND q.provider_instance_id IS NOT DISTINCT FROM p.provider_instance_id
                AND tstzrange(q.effective_from,coalesce(q.effective_to,'infinity'),'[)')
                    && tstzrange(p.effective_from,coalesce(p.effective_to,'infinity'),'[)'))
            ON CONFLICT DO NOTHING
            """);
        execute(connection, """
            UPDATE model_price p SET status='QUARANTINED'
            WHERE p.status='ACTIVE' AND EXISTS (
              SELECT 1 FROM model_price q WHERE q.status='ACTIVE' AND q.id<p.id
                AND q.model_id IS NOT DISTINCT FROM p.model_id
                AND q.platform_model_id IS NOT DISTINCT FROM p.platform_model_id
                AND q.provider_instance_id IS NOT DISTINCT FROM p.provider_instance_id
                AND tstzrange(q.effective_from,coalesce(q.effective_to,'infinity'),'[)')
                    && tstzrange(p.effective_from,coalesce(p.effective_to,'infinity'),'[)'))
            """);
        execute(connection, """
            INSERT INTO migration_quarantine(id,source_table,source_id,reason)
            SELECT replace(gen_random_uuid()::text,'-',''),'platform_model',pm.id,'dangling_policy_reference'
            FROM platform_model pm LEFT JOIN route_policy rp ON rp.id=pm.route_policy_id
            LEFT JOIN model_price mp ON mp.id=pm.price_policy_id
            WHERE (pm.route_policy_id IS NOT NULL AND rp.id IS NULL) OR (pm.price_policy_id IS NOT NULL AND mp.id IS NULL)
            ON CONFLICT DO NOTHING
            """);
        execute(connection, """
            UPDATE platform_model pm SET status='草稿',
              route_policy_id=CASE WHEN rp.id IS NULL THEN NULL ELSE pm.route_policy_id END,
              price_policy_id=CASE WHEN mp.id IS NULL THEN NULL ELSE pm.price_policy_id END,
              updated_at=now()
            FROM platform_model source LEFT JOIN route_policy rp ON rp.id=source.route_policy_id
            LEFT JOIN model_price mp ON mp.id=source.price_policy_id
            WHERE pm.id=source.id AND ((source.route_policy_id IS NOT NULL AND rp.id IS NULL)
              OR (source.price_policy_id IS NOT NULL AND mp.id IS NULL))
            """);
        execute(connection, """
            INSERT INTO audit_log(id,action,object_type,object_id,after_value,created_at,updated_at)
            SELECT replace(gen_random_uuid()::text,'-',''),'MIGRATION_QUARANTINE',source_table,source_id,
                   '{"reason":"pre_v7_contract_violation"}',now(),now()
            FROM migration_quarantine ON CONFLICT(id) DO NOTHING
            """);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    @Override public String getCallbackName() { return "legacy-v7-preflight"; }
}
