package com.tokensea.asset.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.entity.ProviderTemplate;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.mapper.ProviderTemplateMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderInstanceControllerTests {
    @Test
    void createUsesTemplateProtocolAndApiBaseWhenRequestOmitsDefaults() {
        ProviderInstanceMapper mapper = mock(ProviderInstanceMapper.class);
        ProviderTemplateMapper templates = mock(ProviderTemplateMapper.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        ProviderTemplate template = new ProviderTemplate();
        template.setId("template-1");
        template.setProviderType("deepseek");
        template.setProtocol("openai_compatible");
        template.setDefaultApiBase("https://api.deepseek.com/v1");
        when(templates.selectById("template-1")).thenReturn(template);
        when(mapper.selectCount(any())).thenReturn(0L);

        ProviderInstanceController controller = new ProviderInstanceController(
                mapper, templates, mock(ProviderConnectionService.class),
                mock(AuditLogMapper.class), new ObjectMapper().findAndRegisterModules(), transactions);

        ProviderInstance result = controller.create(new ProviderInstanceController.CreateRequest(
                "template-1", "DeepSeek 生产渠道", null, null, null,
                "CN", "生产", "平台管理员", null, null)).data();

        assertEquals("openai_compatible", result.getApiStyle());
        assertEquals("https://api.deepseek.com/v1", result.getApiBase());
        assertEquals("deepseek", result.getProviderType());
    }

    @Test
    void activationUsesConfiguredConnectionTestValidity() {
        ProviderInstanceMapper mapper = mock(ProviderInstanceMapper.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        ProviderInstance channel = new ProviderInstance();
        channel.setId("channel-1");
        channel.setStatus("暂停");
        channel.setKeyStatus("已托管");
        channel.setLastConnectionTestStatus("成功");
        channel.setLastConnectionTestAt(OffsetDateTime.now().minusMinutes(60));
        when(mapper.selectById("channel-1")).thenReturn(channel);
        ProviderInstanceController controller = new ProviderInstanceController(
                mapper, mock(ProviderTemplateMapper.class), mock(ProviderConnectionService.class),
                mock(AuditLogMapper.class), new ObjectMapper().findAndRegisterModules(), transactions);
        ReflectionTestUtils.setField(controller, "testValidMinutes", 120L);

        ProviderInstance result = controller.status(
                "channel-1", new ProviderInstanceController.StatusRequest("启用")).data();

        assertEquals("启用", result.getStatus());
    }
}
