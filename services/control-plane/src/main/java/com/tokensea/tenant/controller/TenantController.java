package com.tokensea.tenant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import com.tokensea.tenant.entity.Tenant;
import com.tokensea.tenant.mapper.TenantMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {
    private static final Set<String> STATES = Set.of("DRAFT", "ACTIVE", "SUSPENDED");

    private final TenantMapper mapper;
    private final AuditService audits;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public TenantController(TenantMapper mapper, AuditService audits, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.audits = audits;
        this.jdbc = jdbc;
    }

    public record Request(String name, String type, String ownerName, String contactEmail,
                          String modelScope, BigDecimal monthlyBudget, String remark) {}

    public record StateRequest(String status) {}

    @GetMapping
    public ApiResponse<PageResult<Tenant>> list(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String sort,
                                                @RequestParam(required = false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "id", "id",
                "name", "name",
                "type", "type",
                "ownerName", "owner_name",
                "contactEmail", "contact_email",
                "modelScope", "model_scope",
                "monthlyBudget", "monthly_budget",
                "status", "status",
                "createdAt", "created_at",
                "updatedAt", "updated_at"
        ), "id", "asc");
        QueryWrapper<Tenant> query = tenantQuery(keyword, status);
        query.orderBy(true, paging.ascending(), paging.sortColumn())
                .orderBy(!"id".equals(paging.sortColumn()), paging.ascending(), "id")
                .last("limit " + paging.size() + " offset " + paging.offset());
        long total = mapper.selectCount(tenantQuery(keyword, status));
        return ApiResponse.ok(new PageResult<>(mapper.selectList(query), total, paging.page(), paging.size()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Tenant> get(@PathVariable String id) {
        return ApiResponse.ok(require(id));
    }

    @GetMapping("/{id}/service-models")
    public ApiResponse<List<Map<String, Object>>> serviceModels(@PathVariable String id) {
        Tenant tenant = require(id);
        List<String> scope = parseScope(tenant.getModelScope());
        if (scope.isEmpty()) return ApiResponse.ok(List.of());
        if (scope.contains("*")) {
            return ApiResponse.ok(jdbc.queryForList("""
                select platform_model_name as "platformModelName", display_name as "displayName", status
                from platform_model
                where status='已发布'
                order by display_name, platform_model_name
                """));
        }
        String placeholders = String.join(",", Collections.nCopies(scope.size(), "?"));
        return ApiResponse.ok(jdbc.queryForList("""
            select platform_model_name as "platformModelName", display_name as "displayName", status
            from platform_model
            where status='已发布' and platform_model_name in (%s)
            order by display_name, platform_model_name
            """.formatted(placeholders), scope.toArray()));
    }

    @PostMapping
    @Transactional
    public ApiResponse<Tenant> create(@RequestBody Request request) {
        validate(request);
        Tenant tenant = new Tenant();
        apply(tenant, request);
        tenant.setStatus("DRAFT");
        mapper.insert(tenant);
        audits.record("TENANT_CREATE", "Tenant", tenant.getId(), null, tenant);
        return ApiResponse.ok(tenant);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<Tenant> update(@PathVariable String id, @RequestBody Request request) {
        validate(request);
        Tenant tenant = require(id);
        Tenant before = audits.snapshot(tenant, Tenant.class);
        List<String> beforeScope = parseScope(tenant.getModelScope());
        apply(tenant, request);
        if ("ACTIVE".equals(tenant.getStatus())) requireModelScope(tenant);
        mapper.updateById(tenant);
        audits.record("TENANT_UPDATE", "Tenant", id, before, tenant);
        recordRevokedModelAccess(tenant, beforeScope, parseScope(tenant.getModelScope()));
        return ApiResponse.ok(tenant);
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ApiResponse<Tenant> status(@PathVariable String id, @RequestBody StateRequest request) {
        if (request == null || !STATES.contains(request.status())) bad("租户状态无效");
        if ("ACTIVE".equals(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请使用租户启用接口完成启用");
        }
        Tenant tenant = require(id);
        Tenant before = audits.snapshot(tenant, Tenant.class);
        if ("DRAFT".equals(tenant.getStatus()) && "SUSPENDED".equals(request.status())) {
            bad("草稿租户不能直接暂停");
        }
        tenant.setStatus(request.status());
        mapper.updateById(tenant);
        audits.record("TENANT_STATE_CHANGE", "Tenant", id, before, tenant);
        return ApiResponse.ok(tenant);
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public ApiResponse<Tenant> activate(@PathVariable String id) {
        Tenant tenant = require(id);
        requireModelScope(tenant);
        if ("ACTIVE".equals(tenant.getStatus())) return ApiResponse.ok(tenant);
        Tenant before = audits.snapshot(tenant, Tenant.class);
        tenant.setStatus("ACTIVE");
        mapper.updateById(tenant);
        audits.record("TENANT_ACTIVATE", "Tenant", id, before, tenant);
        return ApiResponse.ok(tenant);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "租户禁止物理删除");
    }

    private Tenant require(String id) {
        Tenant tenant = mapper.selectById(id);
        if (tenant == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        return tenant;
    }

    private QueryWrapper<Tenant> tenantQuery(String keyword, String status) {
        QueryWrapper<Tenant> query = new QueryWrapper<>();
        if (!blank(keyword)) {
            String value = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            query.and(group -> group
                    .apply("lower(name) like {0}", value)
                    .or().apply("lower(type) like {0}", value)
                    .or().apply("lower(coalesce(owner_name,'')) like {0}", value)
                    .or().apply("lower(coalesce(contact_email,'')) like {0}", value)
                    .or().apply("lower(coalesce(model_scope,'')) like {0}", value));
        }
        if (!blank(status)) query.eq("status", status.trim().toUpperCase(Locale.ROOT));
        return query;
    }

    private void validate(Request request) {
        if (request == null || blank(request.name())) bad("租户名称不能为空");
        if (request.monthlyBudget() != null && request.monthlyBudget().signum() < 0) bad("预算不能为负数");
        if (!blank(request.modelScope())) validatePublishedModels(parseScope(request.modelScope()));
    }

    private void apply(Tenant tenant, Request request) {
        tenant.setName(request.name());
        tenant.setType(blank(request.type()) ? "INTERNAL" : request.type());
        tenant.setOwnerName(request.ownerName());
        tenant.setContactEmail(request.contactEmail());
        tenant.setModelScope(blank(request.modelScope()) ? "[]" : request.modelScope());
        tenant.setMonthlyBudget(request.monthlyBudget());
        tenant.setRemark(request.remark());
    }

    private void requireModelScope(Tenant tenant) {
        if (parseScope(tenant.getModelScope()).isEmpty()) {
            bad("启用租户前请至少配置一个可用服务模型");
        }
    }

    private List<String> parseScope(String raw) {
        if (blank(raw)) return List.of();
        try {
            List<String> values = json.readValue(raw, new TypeReference<>() {});
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank()) bad("可用服务模型包含空值");
                normalized.add(value.trim());
            }
            return new ArrayList<>(normalized);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "可用服务模型格式无效");
        }
    }

    private void validatePublishedModels(List<String> scope) {
        if (scope.isEmpty() || scope.contains("*")) return;
        String placeholders = String.join(",", Collections.nCopies(scope.size(), "?"));
        List<String> published = jdbc.queryForList("""
            select platform_model_name from platform_model
            where status='已发布' and platform_model_name in (%s)
            """.formatted(placeholders), String.class, scope.toArray());
        Set<String> missing = new LinkedHashSet<>(scope);
        missing.removeAll(published);
        if (!missing.isEmpty()) {
            bad("以下企业服务模型尚未发布或不存在：" + String.join("、", missing));
        }
    }

    private List<String> expandedScope(List<String> scope) {
        if (!scope.contains("*")) return scope;
        return jdbc.queryForList("select platform_model_name from platform_model where status='已发布'", String.class);
    }

    private void recordRevokedModelAccess(Tenant tenant, List<String> beforeScope, List<String> afterScope) {
        if (beforeScope.isEmpty()) return;
        Set<String> removed = new LinkedHashSet<>(expandedScope(beforeScope));
        removed.removeAll(expandedScope(afterScope));
        if (removed.isEmpty()) return;

        List<Map<String, Object>> affectedKeys = jdbc.queryForList("""
            select id,name,model_scope from api_key
            where tenant_id=? and status<>'DISABLED'
            order by created_at
            """, tenant.getId()).stream().filter(row -> {
                List<String> keyScope = parseScope(String.valueOf(row.get("model_scope")));
                return keyScope.contains("*") || keyScope.stream().anyMatch(removed::contains);
            }).toList();

        audits.record("TENANT_MODEL_ACCESS_REVOKED", "Tenant", tenant.getId(),
                Map.of("removedModels", removed),
                Map.of("removedModels", removed,
                        "affectedKeyCount", affectedKeys.size(),
                        "affectedKeyIds", affectedKeys.stream().map(row -> row.get("id")).toList(),
                        "enforcement", "RUNTIME_INTERSECTION"));
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
