package com.tokensea.provider.service;

import com.tokensea.provider.entity.ProviderSecret;
import com.tokensea.provider.mapper.ProviderSecretMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedPurposeCredentialServiceTests {
    private final CryptoService crypto = new CryptoService(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void resolvesOnlyActiveCredentialWithExpectedPurposeAndOwner() {
        ProviderSecretMapper mapper = mock(ProviderSecretMapper.class);
        ProviderSecret secret = secret("billing-secret", "channel-1", "BILLING_READ", "ACTIVE", "admin-key");
        when(mapper.selectById("billing-secret")).thenReturn(secret);
        ManagedPurposeCredentialService service = new ManagedPurposeCredentialService(mapper, crypto);

        assertEquals("admin-key", service.resolve("secret:billing-secret", "channel-1", "BILLING_READ"));
    }

    @Test
    void rejectsInferenceCredentialForBillingRead() {
        ProviderSecretMapper mapper = mock(ProviderSecretMapper.class);
        when(mapper.selectById("inference-secret"))
                .thenReturn(secret("inference-secret", "channel-1", "INFERENCE", "ACTIVE", "runtime-key"));
        ManagedPurposeCredentialService service = new ManagedPurposeCredentialService(mapper, crypto);

        assertThrows(IllegalStateException.class,
                () -> service.resolve("inference-secret", "channel-1", "BILLING_READ"));
    }

    private ProviderSecret secret(String id,
                                  String instanceId,
                                  String purpose,
                                  String status,
                                  String plainText) {
        ProviderSecret value = new ProviderSecret();
        value.setId(id);
        value.setProviderInstanceId(instanceId);
        value.setSecretPurpose(purpose);
        value.setStatus(status);
        value.setSecretCipher(crypto.encrypt(plainText));
        return value;
    }
}
