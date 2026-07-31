package com.tokensea.provider.controller;

import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.provider.entity.ProviderSecret;
import com.tokensea.provider.mapper.ProviderSecretMapper;
import com.tokensea.provider.service.CryptoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSecretControllerTests {
    @Test
    void billingCredentialDoesNotReplaceInferenceCredentialReference() {
        ProviderSecretMapper secrets = mock(ProviderSecretMapper.class);
        ProviderInstanceMapper instances = mock(ProviderInstanceMapper.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        ProviderInstance channel = new ProviderInstance();
        channel.setId("channel-1");
        channel.setCredentialRef("secret:inference-existing");
        channel.setKeyStatus("已托管");
        when(instances.selectById("channel-1")).thenReturn(channel);
        when(secrets.selectList(any())).thenReturn(List.of());
        CryptoService crypto = new CryptoService(Base64.getEncoder().encodeToString(new byte[32]));
        ProviderSecretController controller = new ProviderSecretController(secrets, instances, crypto, audits);

        controller.rotate(new ProviderSecretController.SecretRequest(
                "channel-1", "openai-admin", "billing-secret", "BILLING_READ"));

        ArgumentCaptor<ProviderSecret> inserted = ArgumentCaptor.forClass(ProviderSecret.class);
        verify(secrets).insert(inserted.capture());
        assertEquals("BILLING_READ", inserted.getValue().getSecretPurpose());
        assertEquals("secret:inference-existing", channel.getCredentialRef());
        verify(instances, never()).updateById(any(ProviderInstance.class));
    }

    @Test
    void inferenceCredentialStillUpdatesRuntimeReference() {
        ProviderSecretMapper secrets = mock(ProviderSecretMapper.class);
        ProviderInstanceMapper instances = mock(ProviderInstanceMapper.class);
        ProviderInstance channel = new ProviderInstance();
        channel.setId("channel-1");
        when(instances.selectById("channel-1")).thenReturn(channel);
        when(secrets.selectList(any())).thenReturn(List.of());
        ProviderSecretController controller = new ProviderSecretController(
                secrets, instances,
                new CryptoService(Base64.getEncoder().encodeToString(new byte[32])),
                mock(AuditLogMapper.class));

        ProviderSecret created = controller.rotate(new ProviderSecretController.SecretRequest(
                "channel-1", null, "runtime-secret", "INFERENCE")).data();

        assertEquals("INFERENCE", created.getSecretPurpose());
        assertEquals("secret:" + created.getId(), channel.getCredentialRef());
        verify(instances).updateById(channel);
    }
}
