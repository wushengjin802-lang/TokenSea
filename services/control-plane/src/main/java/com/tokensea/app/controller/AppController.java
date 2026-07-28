package com.tokensea.app.controller;

import com.tokensea.app.entity.AppEntity;
import com.tokensea.app.mapper.AppEntityMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.organization.service.ResourceLinkageService;
import com.tokensea.project.entity.Project;
import com.tokensea.project.mapper.ProjectMapper;
import com.tokensea.tenant.entity.Tenant;
import com.tokensea.tenant.mapper.TenantMapper;
import org.springframework.http.HttpStatus;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/apps")
public class AppController {
    private static final Set<String> STATES = Set.of("DRAFT", "ACTIVE", "SUSPENDED");

    private final AppEntityMapper mapper;
    private final ProjectMapper projects;
    private final TenantMapper tenants;
    private final AuditService audits;
    private final ResourceLinkageService linkage;

    public AppController(AppEntityMapper mapper, ProjectMapper projects, TenantMapper tenants,
                         AuditService audits, ResourceLinkageService linkage) {
        this.mapper = mapper;
        this.projects = projects;
        this.tenants = tenants;
        this.audits = audits;
        this.linkage = linkage;
    }

    public record Request(String tenantId, String projectId, String name, String ownerName, String environment) {}
    public record StateRequest(String status) {}

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(linkage.apps(tenantId, projectId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<AppEntity> get(@PathVariable String id) {
        return ApiResponse.ok(require(id));
    }

    @GetMapping("/{id}/overview")
    public ApiResponse<Map<String, Object>> overview(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(linkage.appOverview(id));
    }

    @GetMapping("/{id}/keys")
    public ApiResponse<List<Map<String, Object>>> keys(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(linkage.appKeys(id));
    }

    @GetMapping("/{id}/usage")
    public ApiResponse<List<Map<String, Object>>> usage(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(linkage.appUsage(id));
    }

    @PostMapping
    @Transactional
    public ApiResponse<AppEntity> create(@RequestBody Request request) {
        validate(request);
        requireActiveHierarchy(request.tenantId(), request.projectId());
        AppEntity value = new AppEntity();
        apply(value, request);
        value.setStatus("ACTIVE");
        mapper.insert(value);
        audits.record("APP_CREATE", "App", value.getId(), null, value);
        return ApiResponse.ok(value);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<AppEntity> update(@PathVariable String id, @RequestBody Request request) {
        validate(request);
        AppEntity value = require(id);
        if ("ACTIVE".equals(value.getStatus())) requireActiveHierarchy(request.tenantId(), request.projectId());
        AppEntity before = audits.snapshot(value, AppEntity.class);
        apply(value, request);
        mapper.updateById(value);
        audits.record("APP_UPDATE", "App", id, before, value);
        return ApiResponse.ok(value);
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ApiResponse<AppEntity> status(@PathVariable String id, @RequestBody StateRequest request) {
        if (request == null || !STATES.contains(request.status())) bad("应用状态无效");
        AppEntity value = require(id);
        AppEntity before = audits.snapshot(value, AppEntity.class);
        if ("ACTIVE".equals(request.status())) {
            Project project = projects.selectById(value.getProjectId());
            Tenant tenant = tenants.selectById(value.getTenantId());
            if (project == null || !"ACTIVE".equals(project.getStatus())
                    || tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "所属租户或项目未启用");
            }
        }
        value.setStatus(request.status());
        mapper.updateById(value);
        Map<String, Object> impact = linkage.appOverview(id);
        audits.record("APP_STATE_CHANGE", "App", id, before,
                Map.of("app", value, "impact", impact));
        return ApiResponse.ok(value);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "应用禁止物理删除");
    }

    private void validate(Request request) {
        if (request == null || blank(request.tenantId()) || blank(request.projectId()) || blank(request.name())) {
            bad("租户、项目和应用名称不能为空");
        }
        Tenant tenant = tenants.selectById(request.tenantId());
        Project project = projects.selectById(request.projectId());
        if (tenant == null || project == null || !request.tenantId().equals(project.getTenantId())) {
            bad("租户与项目关系无效");
        }
        if (!Set.of("DEV", "TEST", "PROD").contains(normalizedEnvironment(request.environment()))) {
            bad("应用环境无效");
        }
    }

    private AppEntity require(String id) {
        AppEntity value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "应用不存在");
        return value;
    }

    private void requireActiveHierarchy(String tenantId, String projectId) {
        Tenant tenant = tenants.selectById(tenantId);
        Project project = projects.selectById(projectId);
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())
                || project == null || !tenantId.equals(project.getTenantId())
                || !"ACTIVE".equals(project.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "启用应用必须归属于已启用租户和项目");
        }
    }

    private void apply(AppEntity value, Request request) {
        value.setTenantId(request.tenantId());
        value.setProjectId(request.projectId());
        value.setName(request.name());
        value.setOwnerName(request.ownerName());
        value.setEnvironment(normalizedEnvironment(request.environment()));
    }

    private static String normalizedEnvironment(String value) {
        return blank(value) ? "DEV" : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
