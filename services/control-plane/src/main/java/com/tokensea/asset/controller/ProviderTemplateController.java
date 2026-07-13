package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.asset.entity.ModelTemplate;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.entity.ProviderTemplate;
import com.tokensea.asset.mapper.ModelTemplateMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.mapper.ProviderTemplateMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/provider-templates")
public class ProviderTemplateController {
    private static final String NOT_ENABLED = "未启用";
    private static final String ENABLED = "已启用";
    private static final String DISABLED = "已停用";
    private static final Set<String> STATES = Set.of(NOT_ENABLED, ENABLED, DISABLED);

    private final ProviderTemplateMapper mapper;
    private final ProviderInstanceMapper instances;
    private final ModelTemplateMapper modelTemplates;
    private final AuditService audits;

    public ProviderTemplateController(ProviderTemplateMapper mapper, ProviderInstanceMapper instances,
                                      ModelTemplateMapper modelTemplates, AuditService audits) {
        this.mapper = mapper;
        this.instances = instances;
        this.modelTemplates = modelTemplates;
        this.audits = audits;
    }

    public record TemplateRequest(String providerName, String providerType, String protocol,
                                  String defaultApiBase, String authType, String supportedEndpoints,
                                  String errorMapping, String healthCheckPath,
                                  Integer defaultRateLimitRpm, Integer defaultRateLimitTpm,
                                  String description) {}
    public record StatusRequest(String status) {}
    public record EnableRequest(String instanceName, String apiBase, String region,
                                String environment, String owner) {}

    @GetMapping
    public ApiResponse<List<ProviderTemplate>> list() {
        List<ProviderTemplate> values = mapper.selectList(null);
        values.forEach(this::applyEnableStatus);
        return ApiResponse.ok(values);
    }

    @GetMapping("/{id}")
    public ApiResponse<ProviderTemplate> get(@PathVariable String id) {
        ProviderTemplate value = require(id);
        applyEnableStatus(value);
        return ApiResponse.ok(value);
    }

    @PostMapping
    @Transactional
    public ApiResponse<ProviderTemplate> create(@RequestBody TemplateRequest request) {
        validate(request);
        ProviderTemplate value = new ProviderTemplate();
        apply(value, request);
        value.setBuiltIn("否");
        value.setStatus(NOT_ENABLED);
        value.setModelTemplateCount(0);
        mapper.insert(value);
        audits.record("PROVIDER_TEMPLATE_CREATE", "ProviderTemplate", value.getId(), null, value);
        return ApiResponse.ok(value);
    }

    @PatchMapping("/{id}")
    @Transactional
    public ApiResponse<ProviderTemplate> edit(@PathVariable String id, @RequestBody TemplateRequest request) {
        validate(request);
        ProviderTemplate value = require(id);
        ProviderTemplate before = audits.snapshot(value, ProviderTemplate.class);
        apply(value, request);
        mapper.updateById(value);
        audits.record("PROVIDER_TEMPLATE_UPDATE", "ProviderTemplate", id, before, value);
        return ApiResponse.ok(value);
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ApiResponse<ProviderTemplate> status(@PathVariable String id, @RequestBody StatusRequest request) {
        if (request == null || !STATES.contains(request.status())) bad("供应商模板状态无效");
        ProviderTemplate value = require(id);
        if (request.status().equals(value.getStatus())) return ApiResponse.ok(value);
        ProviderTemplate before = audits.snapshot(value, ProviderTemplate.class);
        value.setStatus(request.status());
        mapper.updateById(value);
        audits.record("PROVIDER_TEMPLATE_STATE_CHANGE", "ProviderTemplate", id, before, value);
        return ApiResponse.ok(value);
    }

    @PostMapping("/{id}/enable")
    @Transactional
    public ApiResponse<ProviderInstance> enable(@PathVariable String id,
                                                @RequestBody(required = false) EnableRequest request) {
        ProviderTemplate template = require(id);
        if (DISABLED.equals(template.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该供应商模板已停用，不能启用");
        }
        ProviderInstance existing = findActiveInstance(id);
        if (existing != null) {
            markEnabledWithAudit(template);
            return ApiResponse.ok(existing);
        }
        ProviderInstance value = new ProviderInstance();
        value.setProviderTemplateId(template.getId());
        value.setInstanceName(text(request == null ? null : request.instanceName(), template.getProviderName() + "-生产"));
        value.setProviderType(template.getProviderType());
        value.setApiStyle(protocolToApiStyle(template.getProtocol()));
        value.setApiBase(text(request == null ? null : request.apiBase(), template.getDefaultApiBase()));
        value.setRegion(text(request == null ? null : request.region(), "CN"));
        value.setCredentialRef(null);
        value.setKeyStatus("未配置");
        value.setEnvironment(text(request == null ? null : request.environment(), "生产"));
        value.setHealthStatus("观察");
        value.setEnabledModels("[]");
        value.setOwner(request == null ? null : request.owner());
        value.setStatus("暂停");
        value.setRateLimitRpm(template.getDefaultRateLimitRpm());
        value.setRateLimitTpm(template.getDefaultRateLimitTpm());
        instances.insert(value);
        markEnabledWithAudit(template);
        audits.record("PROVIDER_TEMPLATE_ENABLE", "ProviderInstance", value.getId(), null, value);
        return ApiResponse.ok(value);
    }

    @PostMapping("/{id}/copy")
    @Transactional
    public ApiResponse<ProviderTemplate> copy(@PathVariable String id) {
        ProviderTemplate source = require(id);
        ProviderTemplate copy = new ProviderTemplate();
        copy.setProviderName(nextCopyName(source.getProviderName()));
        copy.setProviderType(nextCopyType(source.getProviderType()));
        copy.setProtocol(source.getProtocol());
        copy.setDefaultApiBase(source.getDefaultApiBase());
        copy.setAuthType(source.getAuthType());
        copy.setSupportedEndpoints(source.getSupportedEndpoints());
        copy.setErrorMapping(source.getErrorMapping());
        copy.setHealthCheckPath(source.getHealthCheckPath());
        copy.setDefaultRateLimitRpm(source.getDefaultRateLimitRpm());
        copy.setDefaultRateLimitTpm(source.getDefaultRateLimitTpm());
        copy.setModelTemplateCount(source.getModelTemplateCount());
        copy.setBuiltIn("否");
        copy.setStatus(NOT_ENABLED);
        copy.setDescription("复制自 " + source.getProviderName());
        mapper.insert(copy);
        audits.record("PROVIDER_TEMPLATE_COPY", "ProviderTemplate", copy.getId(), source, copy);
        return ApiResponse.ok(copy);
    }

    @GetMapping("/{id}/model-templates")
    public ApiResponse<List<ModelTemplate>> modelTemplates(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(modelTemplates.selectList(
                new QueryWrapper<ModelTemplate>().eq("provider_template_id", id)));
    }

    @PutMapping("/{id}")
    public void rejectEntityPut(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "请使用字段白名单编辑接口");
    }

    @DeleteMapping("/{id}")
    public void rejectDelete(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "供应商模板禁止物理删除");
    }

    private void validate(TemplateRequest request) {
        if (request == null || blank(request.providerName()) || blank(request.providerType()) ||
                blank(request.protocol()) || blank(request.defaultApiBase())) {
            bad("供应商名称、类型、协议和默认地址不能为空");
        }
        if (request.defaultRateLimitRpm() != null && request.defaultRateLimitRpm() < 0 ||
                request.defaultRateLimitTpm() != null && request.defaultRateLimitTpm() < 0) {
            bad("默认限流不能为负数");
        }
    }

    private void apply(ProviderTemplate value, TemplateRequest request) {
        value.setProviderName(request.providerName());
        value.setProviderType(request.providerType());
        value.setProtocol(request.protocol());
        value.setDefaultApiBase(request.defaultApiBase());
        value.setAuthType(request.authType());
        value.setSupportedEndpoints(text(request.supportedEndpoints(), "[]"));
        value.setErrorMapping(text(request.errorMapping(), "{}"));
        value.setHealthCheckPath(request.healthCheckPath());
        value.setDefaultRateLimitRpm(request.defaultRateLimitRpm());
        value.setDefaultRateLimitTpm(request.defaultRateLimitTpm());
        value.setDescription(request.description());
    }

    private ProviderTemplate require(String id) {
        ProviderTemplate value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商模板不存在");
        return value;
    }

    private ProviderInstance findActiveInstance(String templateId) {
        return instances.selectOne(new QueryWrapper<ProviderInstance>()
                .eq("provider_template_id", templateId).ne("status", "停用").last("limit 1"));
    }

    private void markEnabledWithAudit(ProviderTemplate template) {
        if (ENABLED.equals(template.getStatus())) return;
        ProviderTemplate before = audits.snapshot(template, ProviderTemplate.class);
        template.setStatus(ENABLED);
        mapper.updateById(template);
        audits.record("PROVIDER_TEMPLATE_STATE_CHANGE", "ProviderTemplate", template.getId(), before, template);
    }

    private void applyEnableStatus(ProviderTemplate template) {
        if (template == null || DISABLED.equals(template.getStatus())) return;
        Long count = instances.selectCount(new QueryWrapper<ProviderInstance>()
                .eq("provider_template_id", template.getId()).ne("status", "停用"));
        template.setEnabledInstanceCount(count == null ? 0 : count.intValue());
        template.setStatus(count != null && count > 0 ? ENABLED : NOT_ENABLED);
    }

    private String nextCopyName(String source) { return unique(source + "-自定义", "provider_name"); }
    private String nextCopyType(String source) { return unique(source + "_custom", "provider_type"); }
    private String unique(String base, String column) {
        if (mapper.selectCount(new QueryWrapper<ProviderTemplate>().eq(column, base)) == 0) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + (column.equals("provider_name") ? "-" : "_") + i;
            if (mapper.selectCount(new QueryWrapper<ProviderTemplate>().eq(column, candidate)) == 0) return candidate;
        }
        return base + "_" + System.currentTimeMillis();
    }

    private static String protocolToApiStyle(String protocol) {
        String value = protocol == null ? "" : protocol.toLowerCase();
        if (value.contains("azure")) return "azure";
        if (value.contains("anthropic")) return "anthropic";
        if (value.contains("gemini")) return "gemini";
        if (value.contains("vllm")) return "vllm";
        return "openai_compatible";
    }
    private static String text(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
