package com.tokensea.project.controller;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private static final Set<String> STATES = Set.of("DRAFT", "ACTIVE", "SUSPENDED");

    private final ProjectMapper mapper;
    private final TenantMapper tenants;
    private final AuditService audits;
    private final ResourceLinkageService linkage;

    public ProjectController(ProjectMapper mapper, TenantMapper tenants, AuditService audits,
                             ResourceLinkageService linkage) {
        this.mapper = mapper;
        this.tenants = tenants;
        this.audits = audits;
        this.linkage = linkage;
    }

    public record Request(String tenantId, String name, String ownerName, BigDecimal monthlyBudget) {}
    public record StateRequest(String status) {}

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(linkage.projects(tenantId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Project> get(@PathVariable String id) {
        return ApiResponse.ok(require(id));
    }

    @GetMapping("/{id}/overview")
    public ApiResponse<Map<String, Object>> overview(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(linkage.projectOverview(id));
    }

    @GetMapping("/{id}/apps")
    public ApiResponse<List<Map<String, Object>>> apps(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(linkage.projectApps(id));
    }

    @GetMapping("/{id}/keys")
    public ApiResponse<List<Map<String, Object>>> keys(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(linkage.projectKeys(id));
    }

    @GetMapping("/{id}/usage")
    public ApiResponse<List<Map<String, Object>>> usage(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(linkage.projectUsage(id));
    }

    @PostMapping
    @Transactional
    public ApiResponse<Project> create(@RequestBody Request request) {
        validate(request);
        requireActiveTenant(request.tenantId());
        Project value = new Project();
        apply(value, request);
        value.setStatus("ACTIVE");
        mapper.insert(value);
        audits.record("PROJECT_CREATE", "Project", value.getId(), null, value);
        return ApiResponse.ok(value);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<Project> update(@PathVariable String id, @RequestBody Request request) {
        validate(request);
        Project value = require(id);
        if ("ACTIVE".equals(value.getStatus())) requireActiveTenant(request.tenantId());
        Project before = audits.snapshot(value, Project.class);
        apply(value, request);
        mapper.updateById(value);
        audits.record("PROJECT_UPDATE", "Project", id, before, value);
        return ApiResponse.ok(value);
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ApiResponse<Project> status(@PathVariable String id, @RequestBody StateRequest request) {
        if (request == null || !STATES.contains(request.status())) bad("项目状态无效");
        Project value = require(id);
        Project before = audits.snapshot(value, Project.class);
        if ("ACTIVE".equals(request.status())) {
            Tenant tenant = tenants.selectById(value.getTenantId());
            if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "所属租户未启用");
            }
        }
        value.setStatus(request.status());
        mapper.updateById(value);
        Map<String, Object> impact = linkage.projectOverview(id);
        audits.record("PROJECT_STATE_CHANGE", "Project", id, before,
                Map.of("project", value, "impact", impact));
        return ApiResponse.ok(value);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "项目禁止物理删除");
    }

    private void validate(Request request) {
        if (request == null || blank(request.tenantId()) || blank(request.name())) {
            bad("租户和项目名称不能为空");
        }
        if (tenants.selectById(request.tenantId()) == null) bad("所属租户不存在");
        if (request.monthlyBudget() != null && request.monthlyBudget().signum() < 0) bad("预算不能为负数");
    }

    private Project require(String id) {
        Project value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
        return value;
    }

    private void requireActiveTenant(String tenantId) {
        Tenant tenant = tenants.selectById(tenantId);
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "启用项目必须归属于已启用租户");
        }
    }

    private void apply(Project value, Request request) {
        value.setTenantId(request.tenantId());
        value.setName(request.name());
        value.setOwnerName(request.ownerName());
        value.setMonthlyBudget(request.monthlyBudget());
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
