package com.tokensea.asset.controller;

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
}
