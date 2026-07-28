package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ModelCatalogGovernanceController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audits;
    private final ProviderPriceCatalogService prices;

    public ModelCatalogGovernanceController(JdbcTemplate jdbc, ObjectMapper json, AuditService audits,
                                            ProviderPriceCatalogService prices) {
        this.jdbc = jdbc;
        this.json = json;
        this.audits = audits;
        this.prices = prices;
    }

    public record AliasRequest(String providerType, String providerModelName,
                               String targetProviderModelName, String relationType,
                               String region, String sourceRef, String reason,
                               OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {}
    public record DecisionRequest(String reason) {}

    @GetMapping("/model-discovery-candidates")
    public ApiResponse<List<Map<String,Object>>> candidates(
            @RequestParam(value="providerType", required=false) String providerType,
            @RequestParam(value="status", required=false) String status) {
        return ApiResponse.ok(jdbc.queryForList("""
            select c.*,
              (select count(*) from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
               where lower(p.provider_type)=lower(c.provider_type)
                 and lower(d.provider_model_name)=lower(c.candidate_model_name)
                 and (lower(c.region)='global' or lower(p.region)=lower(c.region))) current_channel_count,
              (select count(*) from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
               where lower(p.provider_type)=lower(c.provider_type)
                 and lower(d.provider_model_name)=lower(c.candidate_model_name)
                 and (lower(c.region)='global' or lower(p.region)=lower(c.region))
                 and p.status in ('启用','ACTIVE') and p.health_status in ('健康','HEALTHY')
                 and p.last_connection_test_status in ('成功','SUCCESS')
                 and d.discovery_status in ('DISCOVERED','RECOVERED') and d.health_status='HEALTHY'
                 and d.price_status in ('MATCHED_OFFICIAL','MATCHED_CHANNEL','MATCHED_CONTRACT')
                 and d.production_status='APPROVED' and d.routing_status='ELIGIBLE') usable_deployment_count,
              case
                when exists(select 1 from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
                  where lower(p.provider_type)=lower(c.provider_type)
                    and lower(d.provider_model_name)=lower(c.candidate_model_name)
                    and (lower(c.region)='global' or lower(p.region)=lower(c.region))
                    and p.status in ('启用','ACTIVE') and p.health_status in ('健康','HEALTHY')
                    and p.last_connection_test_status in ('成功','SUCCESS')
                    and d.discovery_status in ('DISCOVERED','RECOVERED') and d.health_status='HEALTHY'
                    and d.price_status in ('MATCHED_OFFICIAL','MATCHED_CHANNEL','MATCHED_CONTRACT')
                    and d.production_status='APPROVED' and d.routing_status='ELIGIBLE') then 'AVAILABLE'
                when exists(select 1 from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
                  where lower(p.provider_type)=lower(c.provider_type)
                    and lower(d.provider_model_name)=lower(c.candidate_model_name)
                    and (lower(c.region)='global' or lower(p.region)=lower(c.region))) then 'CHANNEL_DISCOVERED'
                else 'PRICE_ONLY'
              end availability_status,
              (select count(*) from provider_model_price_catalog p
               where lower(p.provider_type)=lower(c.provider_type)
                 and lower(p.provider_model_name)=lower(c.candidate_model_name) and p.status='ACTIVE') active_price_count
            from model_discovery_candidate c
            where (?::text is null or lower(c.provider_type)=lower(?))
              and (?::text is null or c.status=?)
            order by c.last_seen_at desc,c.provider_type,c.candidate_model_name
            """, providerType, providerType, status, status));
    }

    @PostMapping("/model-discovery-candidates/{id}/verify")
    @Transactional
    public ApiResponse<Map<String,Object>> verifyCandidate(@PathVariable("id") String id,
                                                           Authentication authentication) {
        requirePlatformAdmin(authentication);
        Map<String,Object> before = require("model_discovery_candidate", id);
        Integer count = jdbc.queryForObject("""
            select count(*) from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
            where lower(p.provider_type)=lower(?) and lower(d.provider_model_name)=lower(?)
              and (lower(?)='global' or lower(p.region)=lower(?))
            """, Integer.class, before.get("provider_type"), before.get("candidate_model_name"),
                before.get("region"), before.get("region"));
        int verified = count == null ? 0 : count;
        String verificationResult = verified > 0 ? "PASSED" : "FAILED";
        jdbc.update("""
            update model_discovery_candidate set channel_verified_count=?,status=?,
              verified_at=case when ?>0 then now() else verified_at end,
              last_verification_at=now(),last_verification_result=?,
              verification_attempt_count=verification_attempt_count+1,updated_at=now() where id=?
            """, verified, verified > 0 ? "CHANNEL_VERIFIED" : "PRICE_ONLY", verified,
                verificationResult, id);
        if (verified == 0) {
            jdbc.update("""
                update data_source s set next_run_at=now(),status='ACTIVE',updated_at=now()
                where s.source_type='PROVIDER_API' and s.config->>'managedBy'='PROVIDER_MODEL_DISCOVERY_SCHEDULER'
                  and exists(select 1 from provider_instance p where p.id=s.provider_instance_id
                    and lower(p.provider_type)=lower(?) and p.status in ('启用','ACTIVE'))
                """, before.get("provider_type"));
        }
        Integer providerChannels = jdbc.queryForObject("""
            select count(*) from provider_instance p
            where lower(p.provider_type)=lower(?)
              and (lower(?)='global' or lower(p.region)=lower(?))
            """, Integer.class, before.get("provider_type"), before.get("region"), before.get("region"));
        Integer readyProviderChannels = jdbc.queryForObject("""
            select count(*) from provider_instance p
            where lower(p.provider_type)=lower(?)
              and (lower(?)='global' or lower(p.region)=lower(?))
              and p.status in ('启用','ACTIVE') and p.health_status in ('健康','HEALTHY')
              and p.last_connection_test_status in ('成功','SUCCESS')
            """, Integer.class, before.get("provider_type"), before.get("region"), before.get("region"));
        Integer usableDeployments = jdbc.queryForObject("""
            select count(*) from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
            where lower(p.provider_type)=lower(?) and lower(d.provider_model_name)=lower(?)
              and (lower(?)='global' or lower(p.region)=lower(?))
              and p.status in ('启用','ACTIVE') and p.health_status in ('健康','HEALTHY')
              and p.last_connection_test_status in ('成功','SUCCESS')
              and d.discovery_status in ('DISCOVERED','RECOVERED') and d.health_status='HEALTHY'
              and d.production_status='APPROVED' and d.routing_status='ELIGIBLE'
            """, Integer.class, before.get("provider_type"), before.get("candidate_model_name"),
                before.get("region"), before.get("region"));
        int providerCount = providerChannels == null ? 0 : providerChannels;
        int readyProviderCount = readyProviderChannels == null ? 0 : readyProviderChannels;
        int usableCount = usableDeployments == null ? 0 : usableDeployments;
        String message;
        if (usableCount > 0) {
            message = "渠道核验通过：当前已有 " + usableCount + " 个生产可调用部署";
        } else if (verified > 0) {
            message = "渠道核验通过：已发现 " + verified + " 个匹配渠道部署；价格信息缺失不影响渠道连通性验证";
        } else if (providerCount == 0) {
            message = "渠道核验未通过：尚未配置该供应商在当前区域的渠道";
        } else if (readyProviderCount == 0) {
            message = "渠道核验未通过：已存在供应商渠道，但尚未启用或连接测试未通过；请先完成连接测试并启用渠道";
        } else {
            message = "渠道核验未通过：渠道已可连接，但供应商模型发现尚未返回该模型；系统已安排重新发现";
        }
        Map<String,Object> after = require("model_discovery_candidate", id);
        Map<String,Object> result = new LinkedHashMap<>(after);
        result.put("verificationPassed", verified > 0);
        result.put("matchedChannelCount", verified);
        result.put("providerChannelCount", providerCount);
        result.put("readyProviderChannelCount", readyProviderCount);
        result.put("usableDeploymentCount", usableCount);
        result.put("currentlyUsable", usableCount > 0);
        result.put("availabilityStatus", usableCount > 0 ? "AVAILABLE" : verified > 0 ? "CHANNEL_VERIFIED" : "PRICE_ONLY");
        result.put("feedbackType", usableCount > 0 ? "success" : "warning");
        result.put("message", message);
        result.put("verificationResult", verificationResult);
        result.put("verificationReason", message);
        audits.record("MODEL_DISCOVERY_CANDIDATE_VERIFY", "ModelDiscoveryCandidate", id, before,
                Map.of("value", result, "actor", actor(authentication),
                        "verificationResult", verificationResult,
                        "verificationReason", message));
        return ApiResponse.ok(result);
    }

    @GetMapping("/provider-model-aliases")
    public ApiResponse<List<Map<String,Object>>> aliases(
            @RequestParam(value="providerType", required=false) String providerType,
            @RequestParam(value="reviewStatus", required=false) String reviewStatus) {
        return ApiResponse.ok(jdbc.queryForList("""
            select * from provider_model_alias
            where (?::text is null or lower(provider_type)=lower(?))
              and (?::text is null or review_status=?)
            order by updated_at desc,provider_type,provider_model_name
            """, providerType, providerType, reviewStatus, reviewStatus));
    }

    @PostMapping("/provider-model-aliases")
    @Transactional
    public ApiResponse<Map<String,Object>> createAlias(@RequestBody AliasRequest request,
                                                       Authentication authentication) {
        requirePlatformAdmin(authentication);
        validateAlias(request);
        String id = id();
        String region = value(request.region(), "global");
        String sourceRef = value(request.sourceRef(), "manual:" + actor(authentication));
        String evidenceHash = sha256(String.join("|", request.providerType(), request.providerModelName(),
                request.targetProviderModelName(), request.relationType(), region, sourceRef,
                value(request.reason(), "")));
        jdbc.update("""
            insert into provider_model_alias(
              id,provider_type,provider_model_name,target_provider_model_name,relation_type,region,
              source_type,source_ref,evidence_hash,review_status,review_reason,effective_from,effective_to)
            values(?,?,?,?,?,?,'MANUAL_VERIFIED',?,?,'PENDING_REVIEW',?,?,?)
            """, id, request.providerType(), request.providerModelName(), request.targetProviderModelName(),
                request.relationType(), region, sourceRef, evidenceHash, request.reason(),
                request.effectiveFrom() == null ? OffsetDateTime.now() : request.effectiveFrom(), request.effectiveTo());
        Map<String,Object> created = require("provider_model_alias", id);
        audits.record("PROVIDER_MODEL_ALIAS_CREATE", "ProviderModelAlias", id, null, created);
        return ApiResponse.ok(created);
    }

    @PostMapping("/provider-model-aliases/{id}/approve")
    @Transactional
    public ApiResponse<Map<String,Object>> approveAlias(@PathVariable("id") String id,
                                                        @RequestBody(required=false) DecisionRequest request,
                                                        Authentication authentication) {
        requirePlatformAdmin(authentication);
        Map<String,Object> before = require("provider_model_alias", id);
        if (!"PENDING_REVIEW".equals(before.get("review_status"))) conflict("仅待审核别名可以批准");
        ensureNoAliasCycle(before);
        jdbc.update("""
            update provider_model_alias set review_status='APPROVED',review_reason=?,reviewed_by=?,
              reviewed_at=now(),updated_at=now() where id=?
            """, request == null ? null : request.reason(), actor(authentication), id);
        enqueueOutbox("MODEL_ALIAS_APPROVED", "ProviderModelAlias", id,
                Map.of("providerType", before.get("provider_type"),
                        "providerModelName", before.get("provider_model_name"),
                        "targetProviderModelName", before.get("target_provider_model_name")));
        rematchAlias(before);
        Map<String,Object> after = require("provider_model_alias", id);
        audits.record("PROVIDER_MODEL_ALIAS_APPROVE", "ProviderModelAlias", id, before, after);
        return ApiResponse.ok(after);
    }

    @PostMapping("/provider-model-aliases/{id}/reject")
    @Transactional
    public ApiResponse<Map<String,Object>> rejectAlias(@PathVariable("id") String id,
                                                       @RequestBody(required=false) DecisionRequest request,
                                                       Authentication authentication) {
        requirePlatformAdmin(authentication);
        Map<String,Object> before = require("provider_model_alias", id);
        if (!"PENDING_REVIEW".equals(before.get("review_status"))) conflict("仅待审核别名可以拒绝");
        jdbc.update("""
            update provider_model_alias set review_status='REJECTED',review_reason=?,reviewed_by=?,
              reviewed_at=now(),updated_at=now() where id=?
            """, request == null ? null : request.reason(), actor(authentication), id);
        Map<String,Object> after = require("provider_model_alias", id);
        audits.record("PROVIDER_MODEL_ALIAS_REJECT", "ProviderModelAlias", id, before, after);
        return ApiResponse.ok(after);
    }

    private void rematchAlias(Map<String,Object> alias) {
        List<String> catalogs = jdbc.queryForList("""
            select id from provider_model_price_catalog
            where lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?) and status='ACTIVE'
            """, String.class, alias.get("provider_type"), alias.get("target_provider_model_name"));
        for (String catalogId : catalogs) prices.rematchCatalog(catalogId);
    }

    private void ensureNoAliasCycle(Map<String,Object> alias) {
        Integer cycle = jdbc.queryForObject("""
            select count(*) from provider_model_alias
            where lower(provider_type)=lower(?)
              and lower(provider_model_name)=lower(?)
              and lower(target_provider_model_name)=lower(?)
              and review_status in ('APPROVED','MIGRATED_APPROVED')
              and effective_from<=now() and (effective_to is null or effective_to>now())
            """, Integer.class, alias.get("provider_type"), alias.get("target_provider_model_name"),
                alias.get("provider_model_name"));
        if (cycle != null && cycle > 0) conflict("别名关系会形成双向循环，不能批准");
    }

    private void validateAlias(AliasRequest request) {
        if (request == null || blank(request.providerType()) || blank(request.providerModelName())
                || blank(request.targetProviderModelName())
                || !Set.of("EXACT_ALIAS","STABLE_ALIAS","VERSION_OF","VARIANT_OF").contains(request.relationType())) {
            bad("供应商、别名、目标模型和关系类型不能为空");
        }
        if (request.providerModelName().equalsIgnoreCase(request.targetProviderModelName())) bad("别名不能指向自身");
        if (request.effectiveTo() != null && request.effectiveFrom() != null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) bad("别名失效时间必须晚于生效时间");
    }

    private void enqueueOutbox(String eventType, String aggregateType, String aggregateId, Map<String,Object> payload) {
        jdbc.update("""
            insert into governance_event_outbox(id,event_type,aggregate_type,aggregate_id,payload)
            values(?,?,?,?,cast(? as jsonb))
            """, id(), eventType, aggregateType, aggregateId, write(payload));
    }

    private Map<String,Object> require(String table, String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from " + table + " where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        return rows.getFirst();
    }

    private void requirePlatformAdmin(Authentication authentication) {
        if (!(authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前登录会话无效");
        }
        Boolean admin = jdbc.queryForObject("""
            select exists(select 1 from user_role ur join role r on r.id=ur.role_id
              where ur.user_id=? and r.code='ADMIN' and r.status='ACTIVE')
            """, Boolean.class, identity.userId());
        if (!Boolean.TRUE.equals(admin)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不是平台管理员");
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String value(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
