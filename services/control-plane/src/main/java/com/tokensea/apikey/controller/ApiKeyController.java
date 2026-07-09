package com.tokensea.apikey.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.apikey.entity.ApiKeyEntity;
import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.BaseCrudController;
import org.springframework.beans.factory.annotation.Autowired;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.audit.entity.AuditLog;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.security.SecureRandom;
import java.util.UUID;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController extends BaseCrudController<ApiKeyEntity> {
    private final ApiKeyEntityMapper mapper;
    @Autowired(required = false)
    private AuditLogMapper auditLogMapper;
    private final SecureRandom random = new SecureRandom();
    public ApiKeyController(ApiKeyEntityMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<ApiKeyEntity> mapper() { return mapper; }

    @Override
    @PostMapping
    public ApiResponse<ApiKeyEntity> create(@RequestBody ApiKeyEntity body) {
        String pending = "pending:" + UUID.randomUUID();
        body.setKeyHash(pending);
        body.setKeyPrefix("pending");
        if (body.getStatus() == null || body.getStatus().isBlank()) body.setStatus("PENDING");
        if (body.getApprovalStatus() == null || body.getApprovalStatus().isBlank()) body.setApprovalStatus("PENDING");
        mapper.insert(body);
        audit("CREATE_KEY", body);
        return ApiResponse.ok(body);
    }


    public record GeneratedKey(String id, String keyPrefix, String plainTextKey) {}

    @PostMapping("/{id}/approve")
    public ApiResponse<ApiKeyEntity> approve(@PathVariable("id") String id) {
        ApiKeyEntity k = mapper.selectById(id);
        if (k == null) return ApiResponse.fail("Key 不存在");
        k.setApprovalStatus("APPROVED");
        k.setStatus("ACTIVE");
        k.setApprovedAt(OffsetDateTime.now());
        mapper.updateById(k);
        audit("KEY_STATE_CHANGE", k);
        return ApiResponse.ok(k);
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ApiKeyEntity> reject(@PathVariable("id") String id) {
        ApiKeyEntity k = mapper.selectById(id);
        if (k == null) return ApiResponse.fail("Key 不存在");
        k.setApprovalStatus("REJECTED");
        k.setStatus("DISABLED");
        mapper.updateById(k);
        audit("KEY_STATE_CHANGE", k);
        return ApiResponse.ok(k);
    }

    @PostMapping("/{id}/generate")
    public ApiResponse<GeneratedKey> generate(@PathVariable("id") String id) throws Exception {
        ApiKeyEntity k = mapper.selectById(id);
        if (k == null) return ApiResponse.fail("Key 不存在");
        if (!"APPROVED".equalsIgnoreCase(k.getApprovalStatus())) return ApiResponse.fail("Key 未审批通过");
        byte[] b = new byte[32]; random.nextBytes(b);
        String token = "ts_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String hash = sha256(token);
        String prefix = token.substring(0, Math.min(12, token.length()));
        k.setKeyHash(hash);
        k.setKeyPrefix(prefix);
        k.setStatus("ACTIVE");
        mapper.updateById(k);
        audit("GENERATE_KEY", k);
        return ApiResponse.ok(new GeneratedKey(k.getId(), prefix, token));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<ApiKeyEntity> disable(@PathVariable("id") String id) {
        ApiKeyEntity k = mapper.selectById(id);
        if (k == null) return ApiResponse.fail("Key 不存在");
        k.setStatus("DISABLED");
        mapper.updateById(k);
        audit("KEY_STATE_CHANGE", k);
        return ApiResponse.ok(k);
    }


    private void audit(String action, ApiKeyEntity key) {
        if (auditLogMapper == null || key == null) return;
        try {
            AuditLog log = new AuditLog();
            log.setId(UUID.randomUUID().toString().replace("-", ""));
            log.setAction(action);
            log.setObjectType("ApiKey");
            log.setObjectId(key.getId());
            log.setAfterValue("keyPrefix=" + key.getKeyPrefix() + ", status=" + key.getStatus() + ", approvalStatus=" + key.getApprovalStatus());
            auditLogMapper.insert(log);
        } catch (Exception ignored) {
        }
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
