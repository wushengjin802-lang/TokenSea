package com.tokensea.apikey.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.apikey.entity.ApiKeyEntity;
import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {
    private final ApiKeyEntityMapper mapper;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    public ApiKeyController(ApiKeyEntityMapper mapper, AuditLogMapper audits, ObjectMapper json) {
        this.mapper = mapper; this.audits = audits; this.json = json;
    }

    public record KeyRequest(String tenantId, String projectId, String appId, String name, String modelScope,
                             java.math.BigDecimal budgetAmount, Integer rpmLimit, Integer tpmLimit,
                             Integer qpsLimit, String ipWhitelist, OffsetDateTime expiresAt) {}
    public record KeyResponse(String id, String tenantId, String projectId, String appId, String name,
                              String keyPrefix, String status, String approvalStatus, String modelScope,
                              java.math.BigDecimal budgetAmount, Integer rpmLimit, Integer tpmLimit,
                              Integer qpsLimit, String ipWhitelist, OffsetDateTime expiresAt,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record GeneratedKey(String id, String keyPrefix, String plainTextKey) {}

    @GetMapping public ApiResponse<List<KeyResponse>> list() { return ApiResponse.ok(mapper.selectList(null).stream().map(this::response).toList()); }
    @GetMapping("/{id}") public ApiResponse<KeyResponse> get(@PathVariable String id) { return ApiResponse.ok(response(require(id))); }

    @PostMapping
    @Transactional
    public ApiResponse<KeyResponse> create(@RequestBody KeyRequest req) {
        if (req == null || blank(req.tenantId()) || blank(req.name())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "租户和 Key 名称不能为空");
        requireNonEmptyList(req.modelScope(), "模型范围");
        if (!blank(req.ipWhitelist())) requireStringList(req.ipWhitelist(), "IP 白名单");
        ApiKeyEntity value = new ApiKeyEntity();
        value.setTenantId(req.tenantId()); value.setProjectId(req.projectId()); value.setAppId(req.appId()); value.setName(req.name());
        value.setKeyHash("pending:" + UUID.randomUUID()); value.setKeyPrefix("pending");
        value.setStatus("PENDING"); value.setApprovalStatus("PENDING"); value.setModelScope(req.modelScope());
        value.setBudgetAmount(req.budgetAmount()); value.setRpmLimit(req.rpmLimit()); value.setTpmLimit(req.tpmLimit());
        value.setQpsLimit(req.qpsLimit()); value.setIpWhitelist(blank(req.ipWhitelist()) ? "[]" : req.ipWhitelist()); value.setExpiresAt(req.expiresAt());
        mapper.insert(value); audit("CREATE_KEY", value); return ApiResponse.ok(response(value));
    }

    @PostMapping("/{id}/approve") @Transactional
    public ApiResponse<KeyResponse> approve(@PathVariable String id) {
        ApiKeyEntity value = require(id); requireNonEmptyList(value.getModelScope(), "模型范围");
        value.setApprovalStatus("APPROVED"); value.setStatus("ACTIVE"); value.setApprovedAt(OffsetDateTime.now());
        mapper.updateById(value); audit("KEY_APPROVE", value); return ApiResponse.ok(response(value));
    }
    @PostMapping("/{id}/reject") @Transactional
    public ApiResponse<KeyResponse> reject(@PathVariable String id) {
        ApiKeyEntity value = require(id); value.setApprovalStatus("REJECTED"); value.setStatus("DISABLED");
        mapper.updateById(value); audit("KEY_REJECT", value); return ApiResponse.ok(response(value));
    }
    @PostMapping("/{id}/generate") @Transactional
    public ApiResponse<GeneratedKey> generate(@PathVariable String id) throws Exception {
        ApiKeyEntity value = require(id);
        if (!"APPROVED".equals(value.getApprovalStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Key 未审批通过");
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

    private KeyResponse response(ApiKeyEntity v) {
        String masked = "[]".equals(v.getIpWhitelist()) || blank(v.getIpWhitelist()) ? "[]" : "[\"***\"]";
        return new KeyResponse(v.getId(), v.getTenantId(), v.getProjectId(), v.getAppId(), v.getName(), v.getKeyPrefix(),
                v.getStatus(), v.getApprovalStatus(), v.getModelScope(), v.getBudgetAmount(), v.getRpmLimit(), v.getTpmLimit(),
                v.getQpsLimit(), masked, v.getExpiresAt(), v.getCreatedAt(), v.getUpdatedAt());
    }
    private ApiKeyEntity require(String id) {
        ApiKeyEntity value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key 不存在");
        return value;
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
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
