package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProviderModelDiscoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ProviderModelDiscoveryScheduler.class);
    private static final String DISCOVERY_SCHEDULE = "PT6H";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProviderModelDiscoveryScheduler(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${tokensea.model-discovery.reconcile-ms:60000}")
    public void reconcileManagedSources() {
        try {
            List<Map<String,Object>> active = jdbc.queryForList("""
                select id,instance_name,api_base,provider_type,region from provider_instance
                where status in ('启用','ACTIVE')
                """);
            for (Map<String,Object> provider : active) ensureManagedSource(provider);
            jdbc.update("""
                update data_source s set status='SUSPENDED',updated_at=now()
                where s.source_type='PROVIDER_API' and s.config->>'managedBy'='PROVIDER_MODEL_DISCOVERY_SCHEDULER'
                  and not exists(select 1 from provider_instance p where p.id=s.provider_instance_id
                    and p.status in ('启用','ACTIVE')) and s.status<>'SUSPENDED'
                """);
        } catch (RuntimeException exception) {
            log.warn("自动模型发现数据源协调失败", exception);
        }
    }

    private void ensureManagedSource(Map<String,Object> provider) {
        String providerId = text(provider.get("id"));
        if (providerId.isBlank()) return;
        String sourceId = managedSourceId(providerId);
        String endpoint = modelEndpoint(text(provider.get("api_base")));
        Map<String,Object> config = Map.of(
                "managedBy", "PROVIDER_MODEL_DISCOVERY_SCHEDULER",
                "missingConfirmations", ModelLifecycleService.DEFAULT_MISSING_CONFIRMATIONS,
                "probeOnNewModel", true,
                "probeOnRecovery", true);
        jdbc.update("""
            insert into data_source(
              id,name,source_type,endpoint,provider_instance_id,auth_ref,sync_mode,schedule_expression,
              status,next_run_at,config)
            values(?,?,'PROVIDER_API',?,?,?,'SCHEDULED',?,'ACTIVE',now(),cast(? as jsonb))
            on conflict(id) do update set
              name=excluded.name,endpoint=excluded.endpoint,provider_instance_id=excluded.provider_instance_id,
              auth_ref=excluded.auth_ref,sync_mode='SCHEDULED',schedule_expression=excluded.schedule_expression,
              status='ACTIVE',next_run_at=coalesce(data_source.next_run_at,now()),config=excluded.config,updated_at=now()
            """, sourceId, text(provider.get("instance_name")) + " - 自动模型发现", endpoint, providerId,
                "provider-instance:" + providerId, DISCOVERY_SCHEDULE, write(config));
    }

    private static String modelEndpoint(String apiBase) {
        if (apiBase == null || apiBase.isBlank()) return "provider://models";
        String base = apiBase.replaceAll("/+$", "");
        return base.endsWith("/models") ? base : base + "/models";
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    static String managedSourceId(String providerId) {
        return "auto_discovery_" + UUID.nameUUIDFromBytes(
                ("tokensea:model-discovery:" + providerId).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
