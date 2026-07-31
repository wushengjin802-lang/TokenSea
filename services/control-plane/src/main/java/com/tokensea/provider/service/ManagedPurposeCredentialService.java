package com.tokensea.provider.service;

import com.tokensea.provider.entity.ProviderSecret;
import com.tokensea.provider.mapper.ProviderSecretMapper;
import org.springframework.stereotype.Service;

@Service
public class ManagedPurposeCredentialService {
    private final ProviderSecretMapper secrets;
    private final CryptoService crypto;

    public ManagedPurposeCredentialService(ProviderSecretMapper secrets, CryptoService crypto) {
        this.secrets = secrets;
        this.crypto = crypto;
    }

    public String resolve(String credentialRef, String providerInstanceId, String requiredPurpose) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalStateException("未配置独立的 " + requiredPurpose + " 凭据");
        }
        String id = credentialRef.startsWith("secret:") ? credentialRef.substring(7) : credentialRef;
        ProviderSecret secret = secrets.selectById(id);
        if (secret == null || !providerInstanceId.equals(secret.getProviderInstanceId())) {
            throw new IllegalStateException("供应商凭据不存在或不属于当前渠道");
        }
        if (!"ACTIVE".equals(secret.getStatus())) {
            throw new IllegalStateException("供应商凭据未启用");
        }
        if (!requiredPurpose.equals(secret.getSecretPurpose())) {
            throw new IllegalStateException("供应商凭据用途必须为 " + requiredPurpose);
        }
        return crypto.decrypt(secret.getSecretCipher());
    }
}
