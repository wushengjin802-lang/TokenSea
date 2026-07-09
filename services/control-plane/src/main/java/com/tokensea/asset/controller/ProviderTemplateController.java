package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.entity.ProviderTemplate;
import com.tokensea.asset.entity.ModelTemplate;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.mapper.ProviderTemplateMapper;
import com.tokensea.asset.mapper.ModelTemplateMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.BaseCrudController;
import com.tokensea.provider.entity.Provider;
import com.tokensea.provider.mapper.ProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/provider-templates")
public class ProviderTemplateController extends BaseCrudController<ProviderTemplate> {
    private final ProviderTemplateMapper mapper;
    private final ProviderInstanceMapper instances;
    private final ProviderMapper legacyProviders;
    private final ModelTemplateMapper modelTemplates;

    public ProviderTemplateController(ProviderTemplateMapper mapper, ProviderInstanceMapper instances, ProviderMapper legacyProviders, ModelTemplateMapper modelTemplates) {
        this.mapper = mapper;
        this.instances = instances;
        this.legacyProviders = legacyProviders;
        this.modelTemplates = modelTemplates;
    }

    @Override protected BaseMapper<ProviderTemplate> mapper() { return mapper; }

    @PostMapping("/{id}/enable")
    public ApiResponse<ProviderInstance> enable(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> req) {
        ProviderTemplate t = mapper.selectById(id);
        if (t == null) return ApiResponse.fail("供应商模板不存在");
        ProviderInstance i = new ProviderInstance();
        i.setProviderTemplateId(t.getId());
        i.setInstanceName(text(req, "instanceName", t.getProviderName() + "-生产"));
        i.setProviderType(t.getProviderType());
        i.setApiStyle(protocolToApiStyle(t.getProtocol()));
        i.setApiBase(text(req, "apiBase", t.getDefaultApiBase()));
        i.setRegion(text(req, "region", "CN"));
        i.setCredentialRef(text(req, "credentialRef", null));
        i.setKeyStatus(text(req, "keyStatus", "未配置"));
        i.setEnvironment(text(req, "environment", "生产"));
        i.setHealthStatus("观察");
        i.setEnabledModels("[]");
        i.setOwner(text(req, "owner", null));
        i.setStatus(text(req, "status", "启用"));
        i.setRateLimitRpm(t.getDefaultRateLimitRpm());
        i.setRateLimitTpm(t.getDefaultRateLimitTpm());
        instances.insert(i);

        Provider p = new Provider();
        p.setName(i.getInstanceName());
        p.setProviderType(i.getProviderType());
        p.setApiStyle(i.getApiStyle());
        p.setBaseUrl(i.getApiBase());
        p.setRegion(i.getRegion());
        p.setStatus(i.getStatus());
        p.setRateLimitRpm(i.getRateLimitRpm());
        p.setRateLimitTpm(i.getRateLimitTpm());
        legacyProviders.insert(p);
        return ApiResponse.ok(i);
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<ProviderTemplate> copy(@PathVariable("id") String id) {
        ProviderTemplate t = mapper.selectById(id);
        if (t == null) return ApiResponse.fail("供应商模板不存在");
        ProviderTemplate c = new ProviderTemplate();
        c.setProviderName(t.getProviderName() + "-自定义");
        c.setProviderType(t.getProviderType() + "_custom");
        c.setProtocol(t.getProtocol());
        c.setDefaultApiBase(t.getDefaultApiBase());
        c.setAuthType(t.getAuthType());
        c.setSupportedEndpoints(t.getSupportedEndpoints());
        c.setErrorMapping(t.getErrorMapping());
        c.setHealthCheckPath(t.getHealthCheckPath());
        c.setDefaultRateLimitRpm(t.getDefaultRateLimitRpm());
        c.setDefaultRateLimitTpm(t.getDefaultRateLimitTpm());
        c.setModelTemplateCount(t.getModelTemplateCount());
        c.setBuiltIn("否");
        c.setStatus("可配置");
        c.setDescription("复制自 " + t.getProviderName());
        mapper.insert(c);
        return ApiResponse.ok(c);
    }

    @GetMapping("/{id}/model-templates")
    public ApiResponse<List<ModelTemplate>> modelTemplates(@PathVariable("id") String id) {
        return ApiResponse.ok(modelTemplates.selectList(new QueryWrapper<ModelTemplate>().eq("provider_template_id", id)));
    }

    private static String text(Map<String, Object> req, String key, String fallback) {
        Object v = req == null ? null : req.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    private static String protocolToApiStyle(String protocol) {
        if (protocol == null) return "openai_compatible";
        String p = protocol.toLowerCase();
        if (p.contains("azure")) return "azure";
        if (p.contains("anthropic")) return "anthropic";
        if (p.contains("gemini")) return "gemini";
        if (p.contains("vllm")) return "vllm";
        return "openai_compatible";
    }
}
