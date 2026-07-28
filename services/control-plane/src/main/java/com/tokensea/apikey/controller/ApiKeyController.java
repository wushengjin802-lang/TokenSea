package com.tokensea.apikey.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.apikey.entity.ApiKeyEntity;
import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.app.entity.AppEntity;
import com.tokensea.app.mapper.AppEntityMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.OperationException;
import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import com.tokensea.project.entity.Project;
import com.tokensea.project.mapper.ProjectMapper;
import com.tokensea.security.JwtService;
import com.tokensea.tenant.entity.Tenant;
import com.tokensea.tenant.mapper.TenantMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {
    private final ApiKeyEntityMapper mapper;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final TenantMapper tenants;
    private final ProjectMapper projects;
    private final AppEntityMapper apps;
    private final SecureRandom random = new SecureRandom();
    public ApiKeyController(ApiKeyEntityMapper mapper, AuditLogMapper audits, ObjectMapper json, TenantMapper tenants,
                            ProjectMapper projects, AppEntityMapper apps) {
        this.mapper = mapper;
        this.audits = audits;
        this.json = json;
        this.tenants = tenants;
        this.projects = projects;
        this.apps = apps;
    }

    public record KeyRequest(String tenantId, String scopeLevel, String projectId, String appId,
                             String name, String modelScope, java.math.BigDecimal budgetAmount,
                             Integer rpmLimit, Integer tpmLimit, Integer qpsLimit,
                             String ipWhitelist, OffsetDateTime expiresAt) {}
    public record KeyResponse(String id, String tenantId, String tenantName, String scopeLevel,
                              String projectId, String projectName, String appId, String appName,
                              String name, String keyPrefix, String status, String approvalStatus, String modelScope,
                              java.math.BigDecimal budgetAmount, Integer rpmLimit, Integer tpmLimit,
                              Integer qpsLimit, String ipWhitelist, OffsetDateTime expiresAt,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record GeneratedKey(String id, String keyPrefix, String plainTextKey) {}

    @GetMapping
    public ApiResponse<PageResult<KeyResponse>> list(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String sort,
                                                      @RequestParam(required = false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.ofEntries(
                Map.entry("id", "id"),
                Map.entry("tenantId", "tenant_id"),
                Map.entry("tenantName", "tenant_id"),
                Map.entry("scopeLevel", "app_id"),
                Map.entry("projectId", "project_id"),
                Map.entry("projectName", "project_id"),
                Map.entry("appId", "app_id"),
                Map.entry("appName", "app_id"),
                Map.entry("name", "name"),
                Map.entry("keyPrefix", "key_prefix"),
                Map.entry("status", "status"),
                Map.entry("approvalStatus", "approval_status"),
                Map.entry("modelScope", "model_scope"),
                Map.entry("budgetAmount", "budget_amount"),
                Map.entry("rpmLimit", "rpm_limit"),
                Map.entry("tpmLimit", "tpm_limit"),
                Map.entry("qpsLimit", "qps_limit"),
                Map.entry("expiresAt", "expires_at"),
                Map.entry("createdAt", "created_at"),
                Map.entry("updatedAt", "updated_at")
        ), "createdAt", "desc");
        QueryWrapper<ApiKeyEntity> query = keyQuery(keyword, status);
        query.orderBy(true, paging.ascending(), paging.sortColumn())
                .orderBy(!"id".equals(paging.sortColumn()), paging.ascending(), "id")
                .last("limit " + paging.size() + " offset " + paging.offset());
        long total = mapper.selectCount(keyQuery(keyword, status));
        List<KeyResponse> items = responses(mapper.selectList(query));
        return ApiResponse.ok(new PageResult<>(items, total, paging.page(), paging.size()));
    }

    @GetMapping("/{id}") public ApiResponse<KeyResponse> get(@PathVariable String id) { return ApiResponse.ok(response(require(id))); }

    @PostMapping
    @Transactional
    public ApiResponse<KeyResponse> create(@RequestBody KeyRequest req, Authentication authentication) {
        if (req == null || blank(req.tenantId()) || blank(req.name())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "租户和 Key 名称不能为空");
        String scopeLevel = normalizeScopeLevel(req.scopeLevel());
        validateScopeLevel(scopeLevel, req.projectId(), req.appId());
        validateHierarchy(req.tenantId(), req.projectId(), req.appId(), true);
        List<String> requestedModels = requireNonEmptyList(req.modelScope(), "允许调用的服务模型");
        validateTenantModelScope(req.tenantId(), requestedModels);
        if (!blank(req.ipWhitelist())) requireStringList(req.ipWhitelist(), "IP 白名单");
        ApiKeyEntity value = new ApiKeyEntity();
        value.setTenantId(req.tenantId()); value.setProjectId(req.projectId()); value.setAppId(req.appId()); value.setName(req.name());
        value.setCreatedBy(actor(authentication));
        value.setKeyHash("pending:" + UUID.randomUUID()); value.setKeyPrefix("pending");
        value.setStatus("PENDING");
        if (isPlatformAdmin(authentication)) {
            value.setApprovalStatus("APPROVED");
            value.setApprovedBy(actor(authentication));
            value.setApprovedAt(OffsetDateTime.now());
        } else {
            value.setApprovalStatus("PENDING");
        }
        value.setModelScope(req.modelScope());
        value.setBudgetAmount(req.budgetAmount()); value.setRpmLimit(req.rpmLimit()); value.setTpmLimit(req.tpmLimit());
        value.setQpsLimit(req.qpsLimit()); value.setIpWhitelist(blank(req.ipWhitelist()) ? "[]" : req.ipWhitelist()); value.setExpiresAt(req.expiresAt());
        mapper.insert(value); audit("CREATE_KEY", value); return ApiResponse.ok(response(value));
    }

    @PostMapping("/{id}/approve") @Transactional
    public ApiResponse<KeyResponse> approve(@PathVariable String id, Authentication authentication) {
        ApiKeyEntity value = require(id);
        validateScopeLevel(deriveScopeLevel(value.getProjectId(), value.getAppId()), value.getProjectId(), value.getAppId());
        validateHierarchy(value.getTenantId(), value.getProjectId(), value.getAppId(), true);
        requireNonEmptyList(value.getModelScope(), "允许调用的服务模型");
        value.setApprovalStatus("APPROVED"); value.setStatus("ACTIVE");
        value.setApprovedBy(actor(authentication)); value.setApprovedAt(OffsetDateTime.now());
        mapper.updateById(value); audit("KEY_APPROVE", value); return ApiResponse.ok(response(value));
    }
    @PostMapping("/{id}/reject") @Transactional
    public ApiResponse<KeyResponse> reject(@PathVariable String id) {
        ApiKeyEntity value = require(id); value.setApprovalStatus("REJECTED"); value.setStatus("DISABLED");
        mapper.updateById(value); audit("KEY_REJECT", value); return ApiResponse.ok(response(value));
    }
    @PostMapping("/{id}/generate") @Transactional
    public ApiResponse<GeneratedKey> generate(@PathVariable String id, Authentication authentication) throws Exception {
        ApiKeyEntity value = require(id);
        validateScopeLevel(deriveScopeLevel(value.getProjectId(), value.getAppId()), value.getProjectId(), value.getAppId());
        validateHierarchy(value.getTenantId(), value.getProjectId(), value.getAppId(), true);
        if (!"pending".equals(value.getKeyPrefix()) && !"PENDING".equals(value.getStatus())) {
            throw OperationException.conflict(
                    "KEY_ALREADY_GENERATED",
                    "API Key / 生成密钥",
                    "该 Key 已经生成，明文密钥不能再次查看或重复生成",
                    "继续使用现有 Key；需要更换密钥时请新建 Key，后续版本将提供双 Key 轮换流程");
        }
        if (!"APPROVED".equals(value.getApprovalStatus())) {
            if (isPlatformAdmin(authentication) && !"REJECTED".equals(value.getApprovalStatus())) {
                value.setApprovalStatus("APPROVED");
                value.setApprovedBy(actor(authentication));
                value.setApprovedAt(OffsetDateTime.now());
            } else {
                throw OperationException.conflict(
                        "KEY_APPROVAL_REQUIRED",
                        "API Key / 生成密钥",
                        "该 Key 当前审批状态为 " + value.getApprovalStatus() + "，不能生成",
                        "由平台管理员审批后再生成；平台管理员新建的 Key 会自动授权，无需重复审批");
            }
        }
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = "ts_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        value.setKeyHash(sha256(token)); value.setKeyPrefix(token.substring(0, 12)); value.setStatus("ACTIVE");
        mapper.updateById(value); audit("GENERATE_KEY", value); return ApiResponse.ok(new GeneratedKey(value.getId(), value.getKeyPrefix(), token));
    }
    @PostMapping("/{id}/disable") @Transactional
    public ApiResponse<KeyResponse> disable(@PathVariable String id) {
        ApiKeyEntity value = require(id); value.setStatus("DISABLED"); mapper.updateById(value);
        audit("KEY_DISABLE", value); return ApiResponse.ok(response(value));
    }
    @PutMapping("/{id}") public void update(@PathVariable String id) { throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Key 禁止整实体更新"); }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id) { throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Key 禁止物理删除，请禁用"); }

    private QueryWrapper<ApiKeyEntity> keyQuery(String keyword, String status) {
        QueryWrapper<ApiKeyEntity> query = new QueryWrapper<>();
        if (!blank(keyword)) {
            String value = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            query.and(group -> group
                    .apply("lower(name) like {0}", value)
                    .or().apply("lower(coalesce(key_prefix,'')) like {0}", value)
                    .or().apply("lower(coalesce(model_scope,'')) like {0}", value)
                    .or().apply("lower(coalesce(tenant_id,'')) like {0}", value)
                    .or().apply("lower(coalesce(project_id,'')) like {0}", value)
                    .or().apply("lower(coalesce(app_id,'')) like {0}", value));
        }
        if (!blank(status)) query.eq("status", status.trim().toUpperCase(Locale.ROOT));
        return query;
    }

    private List<KeyResponse> responses(List<ApiKeyEntity> values) {
        if (values.isEmpty()) return List.of();
        Set<String> tenantIds = ids(values, ApiKeyEntity::getTenantId);
        Set<String> projectIds = ids(values, ApiKeyEntity::getProjectId);
        Set<String> appIds = ids(values, ApiKeyEntity::getAppId);
        Map<String, Tenant> tenantMap = tenantIds.isEmpty() ? Map.of() : tenants.selectBatchIds(tenantIds).stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));
        Map<String, Project> projectMap = projectIds.isEmpty() ? Map.of() : projects.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        Map<String, AppEntity> appMap = appIds.isEmpty() ? Map.of() : apps.selectBatchIds(appIds).stream()
                .collect(Collectors.toMap(AppEntity::getId, Function.identity()));
        return values.stream().map(value -> response(value, tenantMap, projectMap, appMap)).toList();
    }

    private Set<String> ids(List<ApiKeyEntity> values, Function<ApiKeyEntity, String> extractor) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ApiKeyEntity value : values) {
            String id = extractor.apply(value);
            if (!blank(id)) ids.add(id);
        }
        return ids;
    }

    private KeyResponse response(ApiKeyEntity value) {
        Tenant tenant = tenants.selectById(value.getTenantId());
        Project project = blank(value.getProjectId()) ? null : projects.selectById(value.getProjectId());
        AppEntity app = blank(value.getAppId()) ? null : apps.selectById(value.getAppId());
        return response(value,
                tenant == null ? Map.of() : Map.of(tenant.getId(), tenant),
                project == null ? Map.of() : Map.of(project.getId(), project),
                app == null ? Map.of() : Map.of(app.getId(), app));
    }

    private KeyResponse response(ApiKeyEntity value,
                                 Map<String, Tenant> tenantMap,
                                 Map<String, Project> projectMap,
                                 Map<String, AppEntity> appMap) {
        String masked = "[]".equals(value.getIpWhitelist()) || blank(value.getIpWhitelist()) ? "[]" : "[\"***\"]";
        Tenant tenant = blank(value.getTenantId()) ? null : tenantMap.get(value.getTenantId());
        Project project = blank(value.getProjectId()) ? null : projectMap.get(value.getProjectId());
        AppEntity app = blank(value.getAppId()) ? null : appMap.get(value.getAppId());
        return new KeyResponse(value.getId(), value.getTenantId(), tenant == null ? null : tenant.getName(),
                deriveScopeLevel(value.getProjectId(), value.getAppId()), value.getProjectId(),
                project == null ? null : project.getName(), value.getAppId(),
                app == null ? null : app.getName(), value.getName(), value.getKeyPrefix(), value.getStatus(),
                value.getApprovalStatus(), value.getModelScope(), value.getBudgetAmount(), value.getRpmLimit(),
                value.getTpmLimit(), value.getQpsLimit(), masked, value.getExpiresAt(),
                value.getCreatedAt(), value.getUpdatedAt());
    }
    private ApiKeyEntity require(String id) {
        ApiKeyEntity value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key 不存在");
        return value;
    }
    private void validateHierarchy(String tenantId, String projectId, String appId, boolean requireActive) {
        Tenant tenant = tenants.selectById(tenantId);
        if (tenant == null) {
            throw OperationException.badRequest(
                    "KEY_TENANT_NOT_FOUND",
                    "API Key / 资源归属",
                    "所选租户不存在",
                    "重新选择有效租户后再创建 Key");
        }
        if (requireActive && !"ACTIVE".equals(tenant.getStatus())) {
            throw OperationException.badRequest(
                    "KEY_TENANT_INACTIVE",
                    "API Key / 资源归属",
                    "所选租户尚未启用",
                    "先启用租户，再创建或生成 Key");
        }
        Project project = null;
        if (!blank(projectId)) {
            project = projects.selectById(projectId);
            if (project == null || !tenantId.equals(project.getTenantId())) {
                throw OperationException.badRequest(
                        "KEY_PROJECT_SCOPE_INVALID",
                        "API Key / 资源归属",
                        "所选项目不存在或不属于当前租户",
                        "重新选择当前租户下的项目");
            }
            if (requireActive && !"ACTIVE".equals(project.getStatus())) {
                throw OperationException.badRequest(
                        "KEY_PROJECT_INACTIVE",
                        "API Key / 资源归属",
                        "所选项目尚未启用",
                        "先启用项目，再创建或生成 Key");
            }
        }
        if (!blank(appId)) {
            if (project == null) {
                throw OperationException.badRequest(
                        "KEY_APP_PROJECT_REQUIRED",
                        "API Key / 资源归属",
                        "选择应用时必须同时选择其所属项目",
                        "先选择项目，再选择该项目下的应用");
            }
            AppEntity app = apps.selectById(appId);
            if (app == null || !tenantId.equals(app.getTenantId()) || !projectId.equals(app.getProjectId())) {
                throw OperationException.badRequest(
                        "KEY_APP_SCOPE_INVALID",
                        "API Key / 资源归属",
                        "所选应用不存在，或不属于当前租户和项目",
                        "重新选择当前项目下的应用");
            }
            if (requireActive && !"ACTIVE".equals(app.getStatus())) {
                throw OperationException.badRequest(
                        "KEY_APP_INACTIVE",
                        "API Key / 资源归属",
                        "所选应用尚未启用",
                        "先启用应用，再创建或生成 Key");
            }
        }
    }

    private String normalizeScopeLevel(String scopeLevel) {
        String normalized = blank(scopeLevel) ? "APPLICATION" : scopeLevel.trim().toUpperCase();
        if (!List.of("APPLICATION", "PROJECT", "TENANT").contains(normalized)) {
            throw OperationException.badRequest(
                    "KEY_SCOPE_LEVEL_INVALID",
                    "API Key / 归属层级",
                    "Key 归属层级无效",
                    "请选择应用级、项目级或租户级");
        }
        return normalized;
    }

    private void validateScopeLevel(String scopeLevel, String projectId, String appId) {
        if ("APPLICATION".equals(scopeLevel) && (blank(projectId) || blank(appId))) {
            throw OperationException.badRequest(
                    "KEY_APPLICATION_SCOPE_REQUIRED",
                    "API Key / 资源归属",
                    "应用级 Key 必须同时选择项目和应用",
                    "选择所属项目和应用；仅特殊场景才改用项目级或租户级 Key");
        }
        if ("PROJECT".equals(scopeLevel) && (blank(projectId) || !blank(appId))) {
            throw OperationException.badRequest(
                    "KEY_PROJECT_SCOPE_REQUIRED",
                    "API Key / 资源归属",
                    "项目级 Key 必须选择项目且不能选择应用",
                    "选择所属项目，并清空应用");
        }
        if ("TENANT".equals(scopeLevel) && (!blank(projectId) || !blank(appId))) {
            throw OperationException.badRequest(
                    "KEY_TENANT_SCOPE_REQUIRED",
                    "API Key / 资源归属",
                    "租户级 Key 不能绑定项目或应用",
                    "清空项目和应用，或改用项目级、应用级 Key");
        }
    }

    private String deriveScopeLevel(String projectId, String appId) {
        if (!blank(appId)) return "APPLICATION";
        if (!blank(projectId)) return "PROJECT";
        return "TENANT";
    }

    private void validateTenantModelScope(String tenantId, List<String> requestedModels) {
        Tenant tenant = tenants.selectById(tenantId);
        if (tenant == null) {
            throw OperationException.badRequest(
                    "KEY_TENANT_NOT_FOUND",
                    "API Key / 新建",
                    "所选租户不存在",
                    "重新选择有效租户后再创建 Key");
        }
        List<String> tenantModels = requireNonEmptyList(tenant.getModelScope(), "租户模型范围");
        if (tenantModels.contains("*")) return;
        List<String> denied = requestedModels.stream().filter(model -> !tenantModels.contains(model)).toList();
        if (!denied.isEmpty()) {
            throw OperationException.badRequest(
                    "KEY_MODEL_SCOPE_EXCEEDS_TENANT",
                    "API Key / 新建",
                    "Key 模型范围超出租户授权范围：" + String.join("、", denied),
                    "先在租户管理中将这些企业服务模型加入租户模型范围，或从 Key 模型范围中移除");
        }
    }

    private List<String> requireNonEmptyList(String raw, String field) {
        List<String> values = requireStringList(raw, field);
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能为空");
        return values;
    }
    private List<String> requireStringList(String raw, String field) {
        try {
            List<String> values = json.readValue(raw, new TypeReference<>() {});
            if (values.stream().anyMatch(v -> v == null || v.isBlank())) throw new IllegalArgumentException();
            return values;
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "必须是有效列表"); }
    }
    private void audit(String action, ApiKeyEntity value) {
        AuditLog log = new AuditLog(); log.setId(UUID.randomUUID().toString().replace("-", ""));
        log.setAction(action); log.setObjectType("ApiKey"); log.setObjectId(value.getId());
        log.setAfterValue("keyPrefix=" + value.getKeyPrefix() + ", status=" + value.getStatus() + ", approvalStatus=" + value.getApprovalStatus()); audits.insert(log);
    }
    private static String sha256(String s) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    private static boolean isPlatformAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof JwtService.Identity identity
                && identity.roles().contains("ADMIN");
    }
    private static String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
