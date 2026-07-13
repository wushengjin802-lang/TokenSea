package com.tokensea.tenant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/tenant")
public class TenantWorkspaceController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final NamedParameterJdbcTemplate jdbc;
    public TenantWorkspaceController(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    @GetMapping("/context")
    public ApiResponse<Map<String,Object>> context(Authentication authentication) {
        JwtService.Identity identity=identity(authentication);
        return ApiResponse.ok(Map.of("userId",identity.userId(),"tenantIds",authorizedTenantIds(identity)));
    }
    @GetMapping("/tenants") public ApiResponse<List<Map<String,Object>>> tenants(Authentication a){return query(a,"select id,name,type,status,owner_name,monthly_budget,created_at,updated_at from tenant where id in (:ids) order by name");}
    @GetMapping("/projects") public ApiResponse<List<Map<String,Object>>> projects(Authentication a){return query(a,"select id,tenant_id,name,owner_name,monthly_budget,status,created_at,updated_at from project where tenant_id in (:ids) order by created_at desc");}
    @GetMapping("/apps") public ApiResponse<List<Map<String,Object>>> apps(Authentication a){return query(a,"select id,tenant_id,project_id,name,owner_name,environment,status,created_at,updated_at from app where tenant_id in (:ids) order by created_at desc");}
    @GetMapping("/usage") public ApiResponse<List<Map<String,Object>>> usage(Authentication a){return query(a,"select id,request_id,tenant_id,project_id,app_id,api_key_id,model_alias,prompt_tokens,completion_tokens,total_tokens,cost_amount,currency,status,error_code,latency_ms,created_at from usage_record where tenant_id in (:ids) order by created_at desc limit 1000");}
    @GetMapping("/billing") public ApiResponse<List<Map<String,Object>>> billing(Authentication a){return query(a,"select id,tenant_id,period_start,period_end,total_tokens,total_cost,status,created_at,updated_at from billing_record where tenant_id in (:ids) order by period_start desc");}
    @GetMapping("/keys") public ApiResponse<List<Map<String,Object>>> keys(Authentication a){return query(a,"select id,tenant_id,project_id,app_id,name,key_prefix,status,approval_status,model_scope,budget_amount,rpm_limit,tpm_limit,qps_limit,expires_at,created_at,updated_at from api_key where tenant_id in (:ids) order by created_at desc");}
    @GetMapping("/models")
    public ApiResponse<List<Map<String,Object>>> models(Authentication authentication) {
        List<String> tenantIds = authorizedTenantIds(identity(authentication));
        Set<String> tenantTypes = new HashSet<>(jdbc.queryForList(
                "select distinct type from tenant where id in (:ids)",
                new MapSqlParameterSource("ids", tenantIds),
                String.class));
        List<Map<String,Object>> models = jdbc.queryForList(
                "select distinct pm.id,pm.platform_model_name,pm.display_name,pm.status,pm.visibility_scope,pm.created_at,pm.updated_at " +
                        "from platform_model pm where pm.status='已发布' order by pm.platform_model_name",
                new MapSqlParameterSource());
        return ApiResponse.ok(models.stream()
                .filter(model -> visibleToAnyTenant(model.get("visibility_scope"), tenantIds, tenantTypes))
                .toList());
    }

    private ApiResponse<List<Map<String,Object>>> query(Authentication authentication,String sql){
        List<String> ids=authorizedTenantIds(identity(authentication));
        return ApiResponse.ok(jdbc.queryForList(sql,new MapSqlParameterSource("ids",ids)));
    }
    private List<String> authorizedTenantIds(JwtService.Identity identity) {
        if (identity.tenantIds() == null || identity.tenantIds().isEmpty()) {
            throw new AccessDeniedException("没有有效的租户成员关系");
        }
        List<String> ids = jdbc.queryForList(
                "select tenant_id from user_tenant where user_id=:userId and status='ACTIVE' and tenant_id in (:jwtIds)",
                new MapSqlParameterSource("userId", identity.userId()).addValue("jwtIds", identity.tenantIds()),
                String.class);
        if (ids.isEmpty()) throw new AccessDeniedException("没有有效的租户成员关系");
        return ids;
    }
    private JwtService.Identity identity(Authentication authentication){
        if(authentication==null || !(authentication.getPrincipal() instanceof JwtService.Identity identity)) throw new AccessDeniedException("租户身份无效");
        return identity;
    }

    static boolean visibleToAnyTenant(Object raw, List<String> tenantIds, Set<String> tenantTypes) {
        if (raw == null) return false;
        String scope = String.valueOf(raw);
        if (scope.equals("全部租户") || scope.equals("ALL") || scope.equals("*")) return true;
        if (scope.equals("内部租户") && tenantTypes.stream().anyMatch(type -> "INTERNAL".equalsIgnoreCase(type))) {
            return true;
        }
        List<?> values;
        if (scope.stripLeading().startsWith("[")) {
            try {
                values = JSON.readValue(scope, new TypeReference<List<Object>>() {});
            } catch (Exception ignored) {
                values = List.of();
            }
        } else {
            values = List.of(raw);
        }
        return values.stream().anyMatch(value -> tenantIds.contains(value) || tenantTypes.contains(value));
    }
}
