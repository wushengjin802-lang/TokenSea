package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GovernanceOutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(GovernanceOutboxProcessor.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProviderInstanceMapper instances;
    private final ProviderPriceCatalogService prices;
    private final String owner = UUID.randomUUID().toString();

    public GovernanceOutboxProcessor(JdbcTemplate jdbc, ObjectMapper json,
                                     ProviderInstanceMapper instances,
                                     ProviderPriceCatalogService prices) {
        this.jdbc = jdbc;
        this.json = json;
        this.instances = instances;
        this.prices = prices;
    }

    @Scheduled(fixedDelayString = "${tokensea.governance-outbox.poll-ms:15000}")
    public void poll() {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select * from governance_event_outbox
            where status in ('PENDING','FAILED') and next_retry_at<=now()
            order by created_at limit 10
            """);
        for (Map<String,Object> event : rows) process(event);
    }

    private void process(Map<String,Object> event) {
        String id = text(event.get("id"));
        int claimed = jdbc.update("""
            update governance_event_outbox set status='PROCESSING',updated_at=now(),
              payload=payload || jsonb_build_object('lockOwner',?)
            where id=? and status in ('PENDING','FAILED') and next_retry_at<=now()
            """, owner, id);
        if (claimed != 1) return;
        try {
            handle(text(event.get("event_type")), payload(event.get("payload")),
                    text(event.get("aggregate_id")));
            jdbc.update("""
                update governance_event_outbox set status='PROCESSED',processed_at=now(),
                  last_error=null,updated_at=now() where id=?
                """, id);
        } catch (RuntimeException exception) {
            Integer retry = jdbc.queryForObject("select retry_count from governance_event_outbox where id=?",
                    Integer.class, id);
            int nextRetry = retry == null ? 1 : retry + 1;
            String nextStatus = nextRetry >= 10 ? "CANCELLED" : "FAILED";
            jdbc.update("""
                update governance_event_outbox set status=?,retry_count=?,last_error=?,
                  next_retry_at=?,updated_at=now() where id=?
                """, nextStatus, nextRetry, safe(exception.getMessage()),
                    OffsetDateTime.now().plusMinutes(Math.min(60, 1L << Math.min(nextRetry, 6))), id);
            log.warn("治理事件处理失败，eventId={}, eventType={}", id, event.get("event_type"), exception);
        }
    }

    private void handle(String eventType, Map<String,Object> payload, String aggregateId) {
        switch (eventType) {
            case "PRICE_CATALOG_PUBLISHED" -> prices.rematchCatalog(value(text(payload.get("catalogId")), aggregateId));
            case "MODEL_DISCOVERY_COMPLETED" -> rematchProvider(
                    value(text(payload.get("providerInstanceId")), aggregateId));
            case "MODEL_PROBE_PASSED", "MODEL_RECOVERED" -> rematchDeployment(
                    value(text(payload.get("deploymentId")), aggregateId));
            case "MODEL_ALIAS_APPROVED" -> rematchAlias(payload);
            default -> throw new IllegalArgumentException("不支持的治理事件类型: " + eventType);
        }
    }

    private void rematchProvider(String providerInstanceId) {
        ProviderInstance instance = instances.selectById(providerInstanceId);
        if (instance == null) return;
        List<Map<String,Object>> deployments = jdbc.queryForList("""
            select id,provider_model_name from channel_model_deployment where provider_instance_id=?
            """, providerInstanceId);
        for (Map<String,Object> deployment : deployments) {
            prices.autoFill(instance, text(deployment.get("id")), text(deployment.get("provider_model_name")));
        }
    }

    private void rematchDeployment(String deploymentId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select d.id,d.provider_model_name,d.provider_instance_id
            from channel_model_deployment d where d.id=?
            """, deploymentId);
        if (rows.isEmpty()) return;
        Map<String,Object> deployment = rows.getFirst();
        ProviderInstance instance = instances.selectById(text(deployment.get("provider_instance_id")));
        if (instance != null) prices.autoFill(instance, deploymentId, text(deployment.get("provider_model_name")));
    }

    private void rematchAlias(Map<String,Object> payload) {
        List<String> catalogIds = jdbc.queryForList("""
            select id from provider_model_price_catalog where status='ACTIVE'
              and lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?)
            """, String.class, payload.get("providerType"), payload.get("targetProviderModelName"));
        for (String catalogId : catalogIds) prices.rematchCatalog(catalogId);
    }

    private Map<String,Object> payload(Object value) {
        if (value instanceof Map<?,?> map) {
            java.util.LinkedHashMap<String,Object> result = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try { return json.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (Exception exception) { return Map.of(); }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String safe(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
