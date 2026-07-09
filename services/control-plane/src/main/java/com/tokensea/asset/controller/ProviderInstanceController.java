package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.BaseCrudController;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/provider-instances")
public class ProviderInstanceController extends BaseCrudController<ProviderInstance> {
    private final ProviderInstanceMapper mapper;
    public ProviderInstanceController(ProviderInstanceMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<ProviderInstance> mapper() { return mapper; }

    @Override
    @PostMapping
    public ApiResponse<ProviderInstance> create(@RequestBody ProviderInstance body) {
        String validation = validate(body);
        if (validation != null) return ApiResponse.fail(validation);
        ProviderInstance existing = mapper.selectOne(new QueryWrapper<ProviderInstance>()
                .eq("instance_name", body.getInstanceName())
                .last("limit 1"));
        if (existing != null) return ApiResponse.fail("供应商实例名称已存在，请使用不同的实例名称");
        mapper.insert(body);
        return ApiResponse.ok(body);
    }

    @Override
    @PutMapping("/{id}")
    public ApiResponse<ProviderInstance> update(@PathVariable("id") String id, @RequestBody ProviderInstance body) {
        String validation = validate(body);
        if (validation != null) return ApiResponse.fail(validation);
        ProviderInstance existing = mapper.selectOne(new QueryWrapper<ProviderInstance>()
                .eq("instance_name", body.getInstanceName())
                .ne("id", id)
                .last("limit 1"));
        if (existing != null) return ApiResponse.fail("供应商实例名称已存在，请使用不同的实例名称");
        body.setId(id);
        mapper.updateById(body);
        return ApiResponse.ok(body);
    }

    @PostMapping("/{id}/test-connection")
    public ApiResponse<ProviderInstance> testConnection(@PathVariable("id") String id) {
        ProviderInstance i = mapper.selectById(id);
        if (i == null) return ApiResponse.fail("供应商实例不存在");
        i.setHealthStatus(i.getApiBase() == null || i.getApiBase().isBlank() ? "异常" : "健康");
        mapper.updateById(i);
        return ApiResponse.ok(i);
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<ProviderInstance> status(@PathVariable("id") String id, @RequestBody ProviderInstance body) {
        ProviderInstance i = mapper.selectById(id);
        if (i == null) return ApiResponse.fail("供应商实例不存在");
        i.setStatus(body.getStatus());
        mapper.updateById(i);
        return ApiResponse.ok(i);
    }

    private static String validate(ProviderInstance body) {
        if (body == null) return "供应商实例不能为空";
        if (blank(body.getInstanceName())) return "请填写实例名称";
        if (blank(body.getProviderType())) return "请选择来源模板";
        if (blank(body.getApiStyle())) return "请选择协议";
        if (blank(body.getStatus())) body.setStatus("暂停");
        if (blank(body.getEnvironment())) body.setEnvironment("生产");
        if (blank(body.getKeyStatus())) body.setKeyStatus("未配置");
        if (blank(body.getHealthStatus())) body.setHealthStatus("观察");
        if (blank(body.getEnabledModels())) body.setEnabledModels("[]");
        return null;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
