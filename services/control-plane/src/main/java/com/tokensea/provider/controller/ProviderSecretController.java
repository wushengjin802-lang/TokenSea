package com.tokensea.provider.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.provider.entity.ProviderSecret;
import com.tokensea.provider.mapper.ProviderSecretMapper;
import com.tokensea.provider.service.CryptoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/provider-secrets")
public class ProviderSecretController {
    private final ProviderSecretMapper mapper;
    private final ProviderInstanceMapper instances;
    private final CryptoService crypto;
    private final AuditLogMapper audits;

    public ProviderSecretController(ProviderSecretMapper mapper, ProviderInstanceMapper instances,
                                    CryptoService crypto, AuditLogMapper audits) {
        this.mapper = mapper; this.instances = instances; this.crypto = crypto; this.audits = audits;
    }

    public record SecretRequest(@NotBlank String providerInstanceId, String secretName, @NotBlank String secretValue) {}

    @GetMapping public ApiResponse<List<ProviderSecret>> list() { return ApiResponse.ok(mapper.selectList(null)); }

    @PostMapping
    @Transactional
    public ApiResponse<ProviderSecret> rotate(@Valid @RequestBody SecretRequest req) {
        ProviderInstance instance = instances.selectById(req.providerInstanceId());
        if (instance == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商渠道不存在");
        String name = req.secretName() == null || req.secretName().isBlank() ? "api_key" : req.secretName().trim();
        for (ProviderSecret old : mapper.selectList(new QueryWrapper<ProviderSecret>()
                .eq("provider_instance_id", instance.getId()).eq("secret_name", name).eq("status", "ACTIVE"))) {
            old.setStatus("DISABLED"); mapper.updateById(old);
        }
        ProviderSecret secret = new ProviderSecret();
        secret.setProviderId(null); secret.setProviderInstanceId(instance.getId()); secret.setSecretName(name);
        secret.setSecretCipher(crypto.encrypt(req.secretValue()));
        secret.setSecretLast4(req.secretValue().length() <= 4 ? req.secretValue() : req.secretValue().substring(req.secretValue().length() - 4));
        secret.setStatus("ACTIVE"); mapper.insert(secret);

        instance.setCredentialRef("secret:" + secret.getId()); instance.setKeyStatus("已托管");
        instance.setHealthStatus("观察"); instance.setLastConnectionTestAt(null);
        instance.setLastConnectionTestStatus(null); instance.setLastConnectionTestError(null);
        instance.setLastConnectionTestHost(null); instance.setLastConnectionTestAddresses(null);
        instance.setLastConnectionTestPort(null);
        instances.updateById(instance);

        AuditLog log = new AuditLog(); log.setId(UUID.randomUUID().toString().replace("-", ""));
        log.setAction("PROVIDER_SECRET_ROTATE"); log.setObjectType("ProviderSecret"); log.setObjectId(secret.getId());
        log.setAfterValue("providerInstanceId=" + instance.getId() + ", secretName=" + name + ", last4=" + secret.getSecretLast4());
        audits.insert(log);
        return ApiResponse.ok(secret);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "供应商密钥禁止物理删除，请轮换");
    }
}
