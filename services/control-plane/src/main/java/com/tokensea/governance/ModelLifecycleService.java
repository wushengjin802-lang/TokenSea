package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.OperationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ModelLifecycleService {
    public static final int DEFAULT_MISSING_CONFIRMATIONS = 4;

    private final JdbcTemplate jdbc;
    private final AuditService audits;
    private final ObjectMapper json;

    public ModelLifecycleService(JdbcTemplate jdbc, AuditService audits, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audits = audits;
        this.json = json;
    }

    public record SeenDecision(boolean probeRequired, boolean recovered) {}
    public record MissingDecision(int missingStreak, boolean probeRequired,
                                  String discoveryStatus, String healthStatus) {}

    @Transactional
    public void markNewDeployment(String deploymentId) {
        Map<String,Object> before = deployment(deploymentId);
        jdbc.update("""
            update channel_model_deployment set discovery_status='DISCOVERED',health_status='PROBE_PENDING',
              price_status='MISSING',production_status='CANDIDATE',missing_streak=0,
              recovery_requires_review=false,updated_at=now() where id=?
            """, deploymentId);
        auditChange("MODEL_DEPLOYMENT_DISCOVERED", deploymentId, before,
                Map.of("reason", "NEW_CHANNEL_MODEL"));
    }

    @Transactional
    public SeenDecision markSeen(String deploymentId, String snapshotId) {
        Map<String,Object> before = deployment(deploymentId);
        String discovery = text(before.get("discovery_status"));
        int streak = integer(before.get("missing_streak"));
        boolean confirmedMissing = "MISSING_CONFIRMED".equals(discovery)
                || "MISSING".equals(text(before.get("review_status")));
        boolean transientMissing = "SUSPECTED_MISSING".equals(discovery) || streak > 0;
        if (confirmedMissing) {
            jdbc.update("""
                update channel_model_deployment set discovery_status='RECOVERED',health_status='PROBE_PENDING',
                  missing_streak=0,last_missing_at=null,missing_at=null,last_seen_at=now(),source_snapshot_id=?,
                  recovery_requires_review=true,review_status='PENDING_REVIEW',routing_status='INELIGIBLE',
                  production_status='SUSPENDED',updated_at=now() where id=?
                """, snapshotId, deploymentId);
            resolveMissingAlerts(deploymentId, false);
            enqueueOutbox("MODEL_RECOVERED", deploymentId, Map.of("deploymentId", deploymentId));
            auditChange("MODEL_DEPLOYMENT_RECOVERED_PENDING_PROBE", deploymentId, before,
                    Map.of("reason", "MODEL_REAPPEARED_AFTER_CONFIRMED_MISSING"));
            return new SeenDecision(true, true);
        }
        jdbc.update("""
            update channel_model_deployment set discovery_status='DISCOVERED',missing_streak=0,
              last_missing_at=null,missing_at=null,last_seen_at=now(),source_snapshot_id=?,updated_at=now()
            where id=?
            """, snapshotId, deploymentId);
        if (transientMissing) {
            resolveMissingAlerts(deploymentId, true);
            auditChange("MODEL_DEPLOYMENT_DISCOVERY_RECOVERED", deploymentId, before,
                    Map.of("reason", "TRANSIENT_DISCOVERY_GAP_RECOVERED"));
        }
        return new SeenDecision(false, transientMissing);
    }

    @Transactional
    public MissingDecision recordMissingObservation(String deploymentId, int requiredConfirmations) {
        int threshold = requiredConfirmations <= 0 ? DEFAULT_MISSING_CONFIRMATIONS : requiredConfirmations;
        Map<String,Object> before = deployment(deploymentId);
        if ("MISSING_CONFIRMED".equals(text(before.get("discovery_status")))) {
            return new MissingDecision(integer(before.get("missing_streak")), false,
                    "MISSING_CONFIRMED", text(before.get("health_status")));
        }
        int streak = integer(before.get("missing_streak")) + 1;
        boolean probeRequired = streak >= threshold && streak % threshold == 0;
        String health = probeRequired ? "PROBE_PENDING" : value(text(before.get("health_status")), "UNKNOWN");
        jdbc.update("""
            update channel_model_deployment set discovery_status='SUSPECTED_MISSING',missing_streak=?,
              last_missing_at=now(),health_status=?,updated_at=now() where id=?
            """, streak, health, deploymentId);
        ensureMissingAlert(before, streak, threshold, probeRequired);
        auditChange("MODEL_DEPLOYMENT_MISSING_OBSERVED", deploymentId, before,
                Map.of("missingStreak", streak, "requiredConfirmations", threshold,
                        "probeRequired", probeRequired));
        return new MissingDecision(streak, probeRequired, "SUSPECTED_MISSING", health);
    }

    @Transactional
    public void markProbeResult(String deploymentId, boolean passed) {
        Map<String,Object> before = deployment(deploymentId);
        String discovery = text(before.get("discovery_status"));
        int streak = integer(before.get("missing_streak"));
        String priceStatus = text(before.get("price_status"));
        String productionStatus = text(before.get("production_status"));
        if (passed) {
            if ("RECOVERED".equals(discovery)) {
                String nextProduction = hasFormalPrice(priceStatus) ? "READY_FOR_REVIEW" : "CANDIDATE";
                jdbc.update("""
                    update channel_model_deployment set health_status='HEALTHY',last_probe_at=now(),
                      last_probe_status='PASSED',review_status='PENDING_REVIEW',routing_status='INELIGIBLE',
                      production_status=?,recovery_requires_review=true,updated_at=now() where id=?
                    """, nextProduction, deploymentId);
            } else if ("SUSPECTED_MISSING".equals(discovery) && streak >= DEFAULT_MISSING_CONFIRMATIONS) {
                jdbc.update("""
                    update channel_model_deployment set health_status='HEALTHY',last_probe_at=now(),
                      last_probe_status='PASSED',updated_at=now() where id=?
                    """, deploymentId);
                ensureDirectoryAnomalyAlert(deploymentId, before);
            } else {
                String nextProduction = hasFormalPrice(priceStatus) ? "READY_FOR_REVIEW" : "CANDIDATE";
                if ("APPROVED".equals(productionStatus)) nextProduction = "APPROVED";
                jdbc.update("""
                    update channel_model_deployment set health_status='HEALTHY',last_probe_at=now(),
                      last_probe_status='PASSED',production_status=?,
                      review_status=case when production_status='APPROVED' then review_status else 'PENDING_REVIEW' end,
                      routing_status=case when production_status='APPROVED' then routing_status else 'INELIGIBLE' end,
                      updated_at=now() where id=?
                    """, nextProduction, deploymentId);
            }
            enqueueOutbox("MODEL_PROBE_PASSED", deploymentId, Map.of("deploymentId", deploymentId));
            auditChange("MODEL_DEPLOYMENT_PROBE_PASSED", deploymentId, before,
                    Map.of("discoveryStatus", discovery, "missingStreak", streak));
            return;
        }

        if ("SUSPECTED_MISSING".equals(discovery) && streak >= DEFAULT_MISSING_CONFIRMATIONS) {
            jdbc.update("""
                update channel_model_deployment set discovery_status='MISSING_CONFIRMED',health_status='UNAVAILABLE',
                  last_probe_at=now(),last_probe_status='FAILED',missing_at=coalesce(missing_at,now()),
                  review_status='MISSING',routing_status='SUSPENDED',production_status='SUSPENDED',
                  recovery_requires_review=true,updated_at=now() where id=?
                """, deploymentId);
            escalateMissingAlert(deploymentId, before);
        } else if ("APPROVED".equals(productionStatus)) {
            jdbc.update("""
                update channel_model_deployment set health_status='UNAVAILABLE',last_probe_at=now(),
                  last_probe_status='FAILED',routing_status='SUSPENDED',production_status='SUSPENDED',
                  recovery_requires_review=true,updated_at=now() where id=?
                """, deploymentId);
        } else {
            jdbc.update("""
                update channel_model_deployment set health_status='UNAVAILABLE',last_probe_at=now(),
                  last_probe_status='FAILED',routing_status='INELIGIBLE',production_status='CANDIDATE',
                  updated_at=now() where id=?
                """, deploymentId);
        }
        auditChange("MODEL_DEPLOYMENT_PROBE_FAILED", deploymentId, before,
                Map.of("discoveryStatus", discovery, "missingStreak", streak));
    }

    @Transactional
    public void updatePriceStatus(String deploymentId, String requestedStatus) {
        Map<String,Object> before = deployment(deploymentId);
        String status = Set.of("MISSING","MATCHED_OFFICIAL","MATCHED_CHANNEL","MATCHED_CONTRACT","CONFLICT")
                .contains(requestedStatus) ? requestedStatus : "MISSING";
        String production = text(before.get("production_status"));
        if (!"APPROVED".equals(production) && !"SUSPENDED".equals(production)) {
            production = "HEALTHY".equals(text(before.get("health_status"))) && hasFormalPrice(status)
                    ? "READY_FOR_REVIEW" : "CANDIDATE";
        }
        jdbc.update("update channel_model_deployment set price_status=?,production_status=?,updated_at=now() where id=?",
                status, production, deploymentId);
        if (!status.equals(text(before.get("price_status")))) {
            auditChange("MODEL_DEPLOYMENT_PRICE_STATUS_CHANGED", deploymentId, before,
                    Map.of("priceStatus", status));
        }
    }

    @Transactional
    public Map<String,Object> approveProduction(String deploymentId, String actor, String reason) {
        Map<String,Object> before = deployment(deploymentId);
        requireProductionReady(before);
        jdbc.update("""
            update channel_model_deployment set production_status='APPROVED',review_status='APPROVED',
              routing_status='ELIGIBLE',production_approved_by=?,production_approved_at=now(),
              production_decision_reason=?,recovery_requires_review=false,updated_at=now() where id=?
            """, actor, value(reason, "管理员确认模型可进入生产路由"), deploymentId);
        Map<String,Object> after = deployment(deploymentId);
        audits.record("MODEL_DEPLOYMENT_PRODUCTION_APPROVE", "ChannelModelDeployment", deploymentId, before, after);
        return after;
    }

    @Transactional
    public Map<String,Object> rejectProduction(String deploymentId, String actor, String reason) {
        Map<String,Object> before = deployment(deploymentId);
        jdbc.update("""
            update channel_model_deployment set production_status='REJECTED',review_status='REJECTED',
              routing_status='INELIGIBLE',production_approved_by=null,production_approved_at=null,
              production_decision_reason=?,recovery_requires_review=false,updated_at=now() where id=?
            """, value(reason, "管理员拒绝模型进入生产路由"), deploymentId);
        Map<String,Object> after = deployment(deploymentId);
        audits.record("MODEL_DEPLOYMENT_PRODUCTION_REJECT", "ChannelModelDeployment", deploymentId, before,
                Map.of("value", after, "actor", actor));
        return after;
    }

    @Transactional
    public Map<String,Object> suspendProduction(String deploymentId, String actor, String reason) {
        Map<String,Object> before = deployment(deploymentId);
        jdbc.update("""
            update channel_model_deployment set production_status='SUSPENDED',routing_status='SUSPENDED',
              production_decision_reason=?,recovery_requires_review=true,updated_at=now() where id=?
            """, value(reason, "管理员暂停模型生产路由"), deploymentId);
        Map<String,Object> after = deployment(deploymentId);
        audits.record("MODEL_DEPLOYMENT_PRODUCTION_SUSPEND", "ChannelModelDeployment", deploymentId, before,
                Map.of("value", after, "actor", actor));
        return after;
    }

    public Map<String,Object> deployment(String deploymentId) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from channel_model_deployment where id=?", deploymentId);
        if (rows.isEmpty()) {
            throw OperationException.conflict("MODEL_DEPLOYMENT_NOT_FOUND", "模型部署治理",
                    "指定模型部署不存在", "重新执行供应商模型发现后再操作");
        }
        return rows.getFirst();
    }

    private void requireProductionReady(Map<String,Object> deployment) {
        if ("MISSING_CONFIRMED".equals(text(deployment.get("discovery_status")))) {
            notReady("模型已确认从供应商目录消失");
        }
        if (!"HEALTHY".equals(text(deployment.get("health_status")))) {
            notReady("模型真实能力探测尚未通过");
        }
        if (!hasFormalPrice(text(deployment.get("price_status")))) {
            notReady("模型缺少正式有效成本价格");
        }
        String deploymentId = text(deployment.get("id"));
        Integer formalPrice = jdbc.queryForObject("""
            select count(*) from price_version where deployment_id=?
              and price_layer in ('CONTRACT_PRICE','CHANNEL_ACTUAL','PROVIDER_OFFICIAL')
              and status='ACTIVE' and effective_from<=now() and (effective_to is null or effective_to>now())
            """, Integer.class, deploymentId);
        if (formalPrice == null || formalPrice == 0) notReady("模型价格状态与有效价格版本不一致");
        List<String> statuses = jdbc.queryForList("""
            select status from capability_validation where deployment_id=? and test_type='LIVE_PROBE'
            order by validated_at desc limit 1
            """, String.class, deploymentId);
        if (statuses.isEmpty() || !"PASSED".equals(statuses.getFirst())) {
            notReady("模型最近一次真实能力探测未通过");
        }
        Integer channelReady = jdbc.queryForObject("""
            select count(*) from provider_instance where id=? and status in ('启用','ACTIVE')
            """, Integer.class, deployment.get("provider_instance_id"));
        if (channelReady == null || channelReady == 0) notReady("供应商渠道未启用");
    }

    private void ensureMissingAlert(Map<String,Object> deployment, int streak, int threshold, boolean probeRequired) {
        String deploymentId = text(deployment.get("id"));
        Integer exists = jdbc.queryForObject("""
            select count(*) from alert_event where alert_type='MODEL_DISCOVERY_MISSING_SUSPECTED'
              and resource_type='MODEL_DEPLOYMENT' and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class, deploymentId);
        Map<String,Object> detail = Map.of(
                "providerInstanceId", deployment.get("provider_instance_id"),
                "providerModelName", deployment.get("provider_model_name"),
                "missingStreak", streak, "requiredConfirmations", threshold,
                "probeRequired", probeRequired);
        if (exists != null && exists > 0) {
            jdbc.update("""
                update alert_event set severity=?,detail=cast(? as jsonb),updated_at=now()
                where alert_type='MODEL_DISCOVERY_MISSING_SUSPECTED' and resource_type='MODEL_DEPLOYMENT'
                  and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
                """, probeRequired ? "HIGH" : "WARNING", json(detail), deploymentId);
            return;
        }
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'MODEL_DISCOVERY_MISSING_SUSPECTED',?,'MODEL_DEPLOYMENT',?,
              '供应商模型连续未出现在发现列表',cast(? as jsonb))
            """, id(), probeRequired ? "HIGH" : "WARNING", deploymentId, json(detail));
    }

    private void ensureDirectoryAnomalyAlert(String deploymentId, Map<String,Object> deployment) {
        Integer exists = jdbc.queryForObject("""
            select count(*) from alert_event where alert_type='MODEL_DIRECTORY_INCONSISTENT'
              and resource_type='MODEL_DEPLOYMENT' and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class, deploymentId);
        if (exists != null && exists > 0) return;
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'MODEL_DIRECTORY_INCONSISTENT','HIGH','MODEL_DEPLOYMENT',?,
              '模型未出现在目录但真实调用仍可用',cast(? as jsonb))
            """, id(), deploymentId, json(Map.of("providerModelName", deployment.get("provider_model_name"),
                    "missingStreak", deployment.get("missing_streak"))));
    }

    private void escalateMissingAlert(String deploymentId, Map<String,Object> deployment) {
        jdbc.update("""
            update alert_event set status='RESOLVED',resolved_by='SYSTEM',resolved_at=now(),updated_at=now()
            where resource_type='MODEL_DEPLOYMENT' and resource_id=?
              and alert_type in ('MODEL_DISCOVERY_MISSING_SUSPECTED','MODEL_DIRECTORY_INCONSISTENT')
              and status<>'RESOLVED'
            """, deploymentId);
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'MODEL_DISAPPEARED','HIGH','MODEL_DEPLOYMENT',?,
              '供应商模型已确认不可用并退出生产路由',cast(? as jsonb))
            """, id(), deploymentId, json(Map.of("providerInstanceId", deployment.get("provider_instance_id"),
                    "providerModelName", deployment.get("provider_model_name"),
                    "missingStreak", deployment.get("missing_streak"))));
    }

    private void resolveMissingAlerts(String deploymentId, boolean transientRecovery) {
        jdbc.update("""
            update alert_event set status='RESOLVED',resolved_by='SYSTEM',resolved_at=now(),updated_at=now(),
              detail=detail || jsonb_build_object('transientRecovery',?::boolean)
            where resource_type='MODEL_DEPLOYMENT' and resource_id=?
              and alert_type in ('MODEL_DISCOVERY_MISSING_SUSPECTED','MODEL_DIRECTORY_INCONSISTENT','MODEL_DISAPPEARED')
              and status<>'RESOLVED'
            """, transientRecovery, deploymentId);
    }

    private void enqueueOutbox(String eventType, String deploymentId, Map<String,Object> payload) {
        jdbc.update("""
            insert into governance_event_outbox(id,event_type,aggregate_type,aggregate_id,payload)
            values(?,?,'ChannelModelDeployment',?,cast(? as jsonb))
            """, id(), eventType, deploymentId, json(payload));
    }

    private void auditChange(String action, String deploymentId, Map<String,Object> before, Map<String,Object> detail) {
        Map<String,Object> after = deployment(deploymentId);
        audits.record(action, "ChannelModelDeployment", deploymentId, before,
                Map.of("value", after, "detail", detail));
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean hasFormalPrice(String status) {
        return Set.of("MATCHED_OFFICIAL","MATCHED_CHANNEL","MATCHED_CONTRACT").contains(status);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static void notReady(String problem) {
        throw OperationException.conflict("MODEL_PRODUCTION_NOT_READY", "模型部署 / 生产确认",
                problem, "完成真实能力探测、正式价格匹配和渠道检查后，再由管理员确认进入生产");
    }
}
