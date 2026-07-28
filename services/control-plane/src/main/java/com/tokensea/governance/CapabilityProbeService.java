package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.OperationException;
import com.tokensea.security.JwtService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CapabilityProbeService {
    private final JdbcTemplate jdbc;
    private final ProviderInstanceMapper instances;
    private final ProviderConnectionService connections;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AuditService audits;
    private final ModelLifecycleService lifecycle;

    public CapabilityProbeService(JdbcTemplate jdbc,
                                  ProviderInstanceMapper instances,
                                  ProviderConnectionService connections,
                                  TransactionTemplate transactions,
                                  ObjectMapper json,
                                  AuditService audits,
                                  ModelLifecycleService lifecycle) {
        this.jdbc = jdbc;
        this.instances = instances;
        this.connections = connections;
        this.transactions = transactions;
        this.json = json;
        this.audits = audits;
        this.lifecycle = lifecycle;
    }

    public Map<String, Object> ensureEligible(String providerInstanceId,
                                               String providerModelName,
                                               Authentication authentication) {
        Map<String, Object> deployment = findDeployment(providerInstanceId, providerModelName);
        String deploymentId = String.valueOf(deployment.get("id"));
        Integer eligible = jdbc.queryForObject(
                "select count(*) from channel_model_deployment d " +
                        "where d.id=? and d.production_status='APPROVED' and d.health_status='HEALTHY' " +
                        "and d.discovery_status<>'MISSING_CONFIRMED' and d.routing_status='ELIGIBLE' " +
                        "and (select v.status from capability_validation v where v.deployment_id=d.id " +
                        "and v.test_type='LIVE_PROBE' order by v.validated_at desc limit 1)='PASSED'",
                Integer.class, deploymentId);
        if (eligible != null && eligible > 0) return deployment;
        Integer technicallyVerified = jdbc.queryForObject(
                "select count(*) from channel_model_deployment d where d.id=? and d.health_status='HEALTHY' " +
                        "and (select v.status from capability_validation v where v.deployment_id=d.id " +
                        "and v.test_type='LIVE_PROBE' order by v.validated_at desc limit 1)='PASSED'",
                Integer.class, deploymentId);
        if (technicallyVerified != null && technicallyVerified > 0) {
            throw OperationException.conflict(
                    "ROUTE_PRODUCTION_APPROVAL_REQUIRED",
                    "路由策略 / 校验并生效 / 生产准入",
                    "模型已经通过真实能力探测，但尚未由管理员确认进入生产",
                    "进入“模型部署治理”确认价格、模型映射和探测证据后，执行“确认进入生产”");
        }
        try {
            probe(deploymentId, "CHAT", authentication);
            throw OperationException.conflict(
                    "ROUTE_PRODUCTION_APPROVAL_REQUIRED",
                    "路由策略 / 校验并生效 / 生产准入",
                    "自动能力探测已通过，但模型仍需管理员确认后才能进入生产路由",
                    "进入“模型部署治理”执行“确认进入生产”，再重新校验路由策略");
        } catch (OperationException exception) {
            if ("ROUTE_PRODUCTION_APPROVAL_REQUIRED".equals(exception.code())) throw exception;
            throw OperationException.conflict(
                    "ROUTE_AUTO_PROBE_FAILED",
                    "路由策略 / 校验并生效 / 自动能力探测",
                    "候选渠道“" + providerInstanceId + "”的模型“" + providerModelName + "”探测失败：" + exception.problem(),
                    exception.action());
        }
    }

    public Map<String, Object> probe(String deploymentId,
                                     String capabilityCode,
                                     Authentication authentication) {
        if (capabilityCode == null || !Set.of("CHAT", "STREAM", "EMBEDDING").contains(capabilityCode)) {
            throw OperationException.badRequest(
                    "CAPABILITY_PROBE_TYPE_INVALID",
                    "能力验证 / 发起探测",
                    "探测能力类型无效",
                    "请选择“对话”“流式对话”或“向量嵌入”后重新探测");
        }
        Map<String, Object> deployment = one(
                "select * from channel_model_deployment where id=?", deploymentId);
        String providerInstanceId = String.valueOf(deployment.get("provider_instance_id"));
        String providerModelName = String.valueOf(deployment.get("provider_model_name"));
        ProviderInstance instance = instances.selectById(providerInstanceId);
        if (instance == null) {
            throw OperationException.conflict(
                    "CAPABILITY_PROBE_CHANNEL_MISSING",
                    "能力验证 / 发起探测 / 供应商渠道",
                    "模型部署关联的供应商渠道不存在",
                    "返回“模型发现”重新同步供应商模型，或修复该部署关联的供应商渠道");
        }
        if (!connections.matchesVerifiedTarget(instance)) {
            throw OperationException.conflict(
                    "CAPABILITY_PROBE_CONNECTION_REQUIRED",
                    "能力验证 / 发起探测 / 供应商渠道",
                    "渠道“" + instance.getInstanceName() + "”没有有效连接验证，或 DNS 解析已变化",
                    "进入“供应商渠道”重新执行连接测试，成功后再次探测");
        }

        String requestId = UUID.randomUUID().toString().replace("-", "");
        ProviderConnectionService.CapabilityProbeResult result = connections.probeCapability(
                instance, providerModelName, capabilityCode);
        Map<String, Object> saved = transactions.execute(tx -> {
            String validationId = UUID.randomUUID().toString().replace("-", "");
            String status = result.success() ? "PASSED" : "FAILED";
            jdbc.update(
                    "insert into capability_validation(" +
                            "id,deployment_id,capability_code,test_type,request_summary,response_summary,status," +
                            "evidence_ref,latency_ms,validated_by,probe_endpoint,http_status,stream_verified,probe_request_id" +
                            ") values(?,?,?,'LIVE_PROBE',cast(? as jsonb),cast(? as jsonb),?,?,?,?,?,?,?,?)",
                    validationId, deploymentId, capabilityCode,
                    write(Map.of("model", providerModelName)),
                    write(summary(result)), status, "probe:" + requestId, result.latencyMs(),
                    actor(authentication), result.endpoint(), result.httpStatus(),
                    result.streamVerified(), requestId);
            lifecycle.markProbeResult(deploymentId, result.success());
            Map<String, Object> validation = one(
                    "select * from capability_validation where id=?", validationId);
            audits.record("CAPABILITY_LIVE_PROBE", "CapabilityValidation", validationId, null, validation);
            return validation;
        });

        if (!result.success()) {
            String reason = result.error() == null || result.error().isBlank()
                    ? "上游未返回可识别的失败原因" : result.error();
            String code = result.errorCode() == null ? "UNKNOWN" : result.errorCode();
            String httpStatus = result.httpStatus() == null ? "未返回" : String.valueOf(result.httpStatus());
            throw OperationException.conflict(
                    "CAPABILITY_LIVE_PROBE_FAILED",
                    "能力验证 / 发起探测 / " + capabilityCode,
                    "渠道“" + instance.getInstanceName() + "”、模型“" + providerModelName +
                            "”真实探测失败，HTTP " + httpStatus + "，错误码 " + code + "：" + reason,
                    "检查供应商 API 地址、托管密钥、模型名称和账号权限；重新执行渠道连接测试后再次探测。失败证据已保存在验证记录中");
        }
        return saved;
    }

    private Map<String, Object> findDeployment(String providerInstanceId, String providerModelName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from channel_model_deployment where provider_instance_id=? and provider_model_name=? " +
                        "order by updated_at desc limit 1",
                providerInstanceId, providerModelName);
        if (rows.isEmpty()) {
            throw OperationException.conflict(
                    "ROUTE_DEPLOYMENT_NOT_FOUND",
                    "路由策略 / 校验并生效 / 模型部署",
                    "候选渠道中不存在模型“" + providerModelName + "”的部署记录",
                    "进入“模型发现”重新同步该供应商渠道，确认模型出现后再校验并生效");
        }
        return rows.getFirst();
    }

    private Map<String, Object> summary(ProviderConnectionService.CapabilityProbeResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", result.success());
        value.put("errorCode", result.errorCode());
        value.put("error", result.error());
        value.put("responseBytes", result.responseBytes());
        return value;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw OperationException.conflict(
                    "CAPABILITY_DEPLOYMENT_NOT_FOUND",
                    "能力验证 / 发起探测",
                    "指定模型部署不存在",
                    "返回“模型发现”重新同步供应商模型后再试");
        }
        return rows.getFirst();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }
}
