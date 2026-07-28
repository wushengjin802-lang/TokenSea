package com.tokensea.governance;

import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model-deployment-governance")
public class ModelDeploymentGovernanceController {
    private final JdbcTemplate jdbc;
    private final ModelLifecycleService lifecycle;
    private final EffectiveCostPriceResolver prices;

    public ModelDeploymentGovernanceController(JdbcTemplate jdbc, ModelLifecycleService lifecycle,
                                               EffectiveCostPriceResolver prices) {
        this.jdbc = jdbc;
        this.lifecycle = lifecycle;
        this.prices = prices;
    }

    public record ProductionDecisionRequest(String reason) {}
    public record ProductionTransitionRequest(String decision, String reason) {}

    @GetMapping
    public ApiResponse<List<Map<String,Object>>> list(
            @RequestParam(value="providerInstanceId", required=false) String providerInstanceId,
            @RequestParam(value="productionStatus", required=false) String productionStatus) {
        StringBuilder sql = new StringBuilder("""
            select d.*,p.instance_name,p.provider_type,p.region provider_region,
              (select v.status from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE'
               order by v.validated_at desc limit 1) latest_probe_status,
              (select v.validated_at from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE'
               order by v.validated_at desc limit 1) latest_probe_at
            from channel_model_deployment d
            join provider_instance p on p.id=d.provider_instance_id where 1=1
            """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (providerInstanceId != null && !providerInstanceId.isBlank()) {
            sql.append(" and d.provider_instance_id=?"); args.add(providerInstanceId);
        }
        if (productionStatus != null && !productionStatus.isBlank()) {
            sql.append(" and d.production_status=?"); args.add(productionStatus);
        }
        sql.append(" order by d.updated_at desc,d.provider_model_name");
        return ApiResponse.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable("id") String id) {
        Map<String,Object> deployment = lifecycle.deployment(id);
        deployment.put("validations", jdbc.queryForList("""
            select * from capability_validation where deployment_id=? order by validated_at desc
            """, id));
        deployment.put("activePrices", jdbc.queryForList("""
            select * from price_version where deployment_id=? and status='ACTIVE'
            order by case price_layer when 'CONTRACT_PRICE' then 1 when 'CHANNEL_ACTUAL' then 2
              when 'PROVIDER_OFFICIAL' then 3 else 9 end,effective_from desc
            """, id));
        deployment.put("aliases", jdbc.queryForList("""
            select a.* from provider_model_alias a join provider_instance p on lower(p.provider_type)=lower(a.provider_type)
            where p.id=? and (lower(a.provider_model_name)=lower(?) or lower(a.target_provider_model_name)=lower(?))
            order by a.updated_at desc
            """, deployment.get("provider_instance_id"), deployment.get("provider_model_name"),
                deployment.get("provider_model_name")));
        return ApiResponse.ok(deployment);
    }

    @GetMapping("/{id}/effective-cost-price")
    public ApiResponse<EffectiveCostPriceResolver.ResolvedPrice> effectivePrice(
            @PathVariable("id") String id,
            @RequestParam(value="region", required=false) String region,
            @RequestParam(value="requestMode", required=false) String requestMode,
            @RequestParam(value="serviceTier", required=false) String serviceTier,
            @RequestParam(value="contextTier", required=false) String contextTier) {
        lifecycle.deployment(id);
        return ApiResponse.ok(prices.resolve(id, java.time.OffsetDateTime.now(), region,
                requestMode, serviceTier, contextTier));
    }

    @PostMapping("/{id}/approve-production")
    public ApiResponse<Map<String,Object>> approve(@PathVariable("id") String id,
                                                    @RequestBody(required=false) ProductionDecisionRequest request,
                                                    Authentication authentication) {
        requirePlatformAdmin(authentication);
        return ApiResponse.ok(lifecycle.approveProduction(id, actor(authentication),
                request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/reject-production")
    public ApiResponse<Map<String,Object>> reject(@PathVariable("id") String id,
                                                   @RequestBody(required=false) ProductionDecisionRequest request,
                                                   Authentication authentication) {
        requirePlatformAdmin(authentication);
        return ApiResponse.ok(lifecycle.rejectProduction(id, actor(authentication),
                request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/suspend-production")
    public ApiResponse<Map<String,Object>> suspend(@PathVariable("id") String id,
                                                    @RequestBody(required=false) ProductionDecisionRequest request,
                                                    Authentication authentication) {
        requirePlatformAdmin(authentication);
        return ApiResponse.ok(lifecycle.suspendProduction(id, actor(authentication),
                request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/production-transition")
    public ApiResponse<Map<String,Object>> transitionProduction(@PathVariable("id") String id,
                                                                 @RequestBody ProductionTransitionRequest request,
                                                                 Authentication authentication) {
        requirePlatformAdmin(authentication);
        if (request == null || request.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择生产状态");
        }
        return ApiResponse.ok(switch (request.decision()) {
            case "APPROVE" -> lifecycle.approveProduction(id, actor(authentication), request.reason());
            case "REJECT" -> lifecycle.rejectProduction(id, actor(authentication), request.reason());
            case "SUSPEND" -> lifecycle.suspendProduction(id, actor(authentication), request.reason());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的生产状态操作");
        });
    }

    private void requirePlatformAdmin(Authentication authentication) {
        if (!(authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前登录会话无效");
        }
        Boolean admin = jdbc.queryForObject("""
            select exists(select 1 from user_role ur join role r on r.id=ur.role_id
              where ur.user_id=? and r.code='ADMIN' and r.status='ACTIVE')
            """, Boolean.class, identity.userId());
        if (!Boolean.TRUE.equals(admin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不是平台管理员，不能审批模型生产准入");
        }
    }

    private static String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }
}
