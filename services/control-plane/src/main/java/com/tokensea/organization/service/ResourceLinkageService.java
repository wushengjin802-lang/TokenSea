package com.tokensea.organization.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResourceLinkageService {
    private final JdbcTemplate jdbc;

    public ResourceLinkageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> projects(String tenantId, String status) {
        return jdbc.queryForList("""
            select p.id,p.tenant_id,t.name tenant_name,p.name,p.owner_name,p.monthly_budget,p.status,
                   p.created_at,p.updated_at,
                   coalesce(apps.app_count,0) app_count,
                   coalesce(keys.key_count,0) key_count,
                   coalesce(keys.active_key_count,0) active_key_count,
                   coalesce(usage.monthly_requests,0) monthly_requests,
                   coalesce(usage.monthly_tokens,0) monthly_tokens,
                   coalesce(usage.monthly_cost_cny,0) monthly_cost_cny,
                   usage.last_call_at,
                   coalesce(usage.unconverted_cost_count,0) unconverted_cost_count,
                   case when p.monthly_budget is not null and p.monthly_budget>0
                     then round(coalesce(usage.monthly_cost_cny,0) / p.monthly_budget * 100,2)
                     else null end budget_usage_percent
            from project p
            join tenant t on t.id=p.tenant_id
            left join lateral (
              select count(*) app_count from app a where a.project_id=p.id
            ) apps on true
            left join lateral (
              select count(*) key_count,
                     count(*) filter(where k.status='ACTIVE') active_key_count
              from api_key k where k.project_id=p.id
            ) keys on true
            left join lateral (
              select count(*) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai') monthly_requests,
                     coalesce(sum(u.total_tokens) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'),0) monthly_tokens,
                     coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
                         and u.status='SUCCESS'),0) monthly_cost_cny,
                     max(u.created_at) last_call_at,
                     count(*) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
                         and u.status='SUCCESS' and upper(u.currency)<>'CNY'
                         and tokensea_fx_rate(u.created_at,u.currency,'CNY') is null) unconverted_cost_count
              from usage_record u where u.project_id=p.id
            ) usage on true
            where (?::text is null or p.tenant_id=?)
              and (?::text is null or p.status=?)
            order by p.created_at desc,p.id
            """, blankToNull(tenantId), blankToNull(tenantId), upperOrNull(status), upperOrNull(status));
    }

    public Map<String, Object> projectOverview(String id) {
        return projects(null, null).stream()
                .filter(row -> id.equals(String.valueOf(row.get("id"))))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
    }

    public List<Map<String, Object>> apps(String tenantId, String projectId, String status) {
        return jdbc.queryForList("""
            select a.id,a.tenant_id,t.name tenant_name,a.project_id,p.name project_name,a.name,a.owner_name,
                   a.environment,a.status,a.created_at,a.updated_at,
                   coalesce(keys.key_count,0) key_count,
                   coalesce(keys.active_key_count,0) active_key_count,
                   coalesce(usage.monthly_requests,0) monthly_requests,
                   coalesce(usage.monthly_tokens,0) monthly_tokens,
                   coalesce(usage.monthly_cost_cny,0) monthly_cost_cny,
                   usage.success_rate,
                   usage.last_call_at,
                   coalesce(usage.unconverted_cost_count,0) unconverted_cost_count
            from app a
            join tenant t on t.id=a.tenant_id
            left join project p on p.id=a.project_id
            left join lateral (
              select count(*) key_count,
                     count(*) filter(where k.status='ACTIVE') active_key_count
              from api_key k where k.app_id=a.id
            ) keys on true
            left join lateral (
              select count(*) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai') monthly_requests,
                     coalesce(sum(u.total_tokens) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'),0) monthly_tokens,
                     coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
                         and u.status='SUCCESS'),0) monthly_cost_cny,
                     round(100.0 * count(*) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
                         and u.status='SUCCESS')
                       / nullif(count(*) filter(
                         where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'),0),2) success_rate,
                     max(u.created_at) last_call_at,
                     count(*) filter(
                       where u.created_at>=date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
                         and u.status='SUCCESS' and upper(u.currency)<>'CNY'
                         and tokensea_fx_rate(u.created_at,u.currency,'CNY') is null) unconverted_cost_count
              from usage_record u where u.app_id=a.id
            ) usage on true
            where (?::text is null or a.tenant_id=?)
              and (?::text is null or a.project_id=?)
              and (?::text is null or a.status=?)
            order by a.created_at desc,a.id
            """, blankToNull(tenantId), blankToNull(tenantId), blankToNull(projectId), blankToNull(projectId),
                upperOrNull(status), upperOrNull(status));
    }

    public Map<String, Object> appOverview(String id) {
        return apps(null, null, null).stream()
                .filter(row -> id.equals(String.valueOf(row.get("id"))))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "应用不存在"));
    }

    public List<Map<String, Object>> projectApps(String projectId) {
        return apps(null, projectId, null);
    }

    public List<Map<String, Object>> projectKeys(String projectId) {
        return keys("k.project_id=?", projectId);
    }

    public List<Map<String, Object>> appKeys(String appId) {
        return keys("k.app_id=?", appId);
    }

    public List<Map<String, Object>> projectUsage(String projectId) {
        return usage("u.project_id=?", projectId);
    }

    public List<Map<String, Object>> appUsage(String appId) {
        return usage("u.app_id=?", appId);
    }

    private List<Map<String, Object>> keys(String predicate, String id) {
        String sql = """
            select k.id,k.tenant_id,t.name tenant_name,k.project_id,p.name project_name,k.app_id,a.name app_name,
                   k.name,k.key_prefix,k.status,k.approval_status,k.model_scope,k.budget_amount,
                   k.rpm_limit,k.tpm_limit,k.qps_limit,k.expires_at,k.created_at,k.updated_at
            from api_key k
            join tenant t on t.id=k.tenant_id
            left join project p on p.id=k.project_id
            left join app a on a.id=k.app_id
            where %s
            order by k.created_at desc,k.id
            """.formatted(predicate);
        return jdbc.queryForList(sql, id);
    }

    private List<Map<String, Object>> usage(String predicate, String id) {
        String sql = """
            select u.request_id,k.name api_key_name,u.model_alias,u.runtime_model_name,u.total_tokens,
                   u.cost_amount,u.currency,tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY') cost_cny,
                   u.status,u.error_code,u.latency_ms,u.created_at
            from usage_record u
            left join api_key k on k.id=u.api_key_id
            where %s
            order by u.created_at desc,u.request_id
            limit 100
            """.formatted(predicate);
        return jdbc.queryForList(sql, id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String upperOrNull(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
