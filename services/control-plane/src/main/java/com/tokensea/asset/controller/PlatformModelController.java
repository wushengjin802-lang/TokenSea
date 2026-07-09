package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.BaseCrudController;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform-models")
public class PlatformModelController extends BaseCrudController<PlatformModel> {
    private final PlatformModelMapper mapper;
    public PlatformModelController(PlatformModelMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<PlatformModel> mapper() { return mapper; }

    @PatchMapping("/{id}/publish")
    public ApiResponse<PlatformModel> publish(@PathVariable("id") String id) {
        PlatformModel m = mapper.selectById(id);
        if (m == null) return ApiResponse.fail("平台模型不存在");
        if (blank(m.getModelTemplateIds()) || blank(m.getPricePolicy()) || blank(m.getRoutePolicy()) || blank(m.getVisibilityScope())) {
            return ApiResponse.fail("发布前必须配置实际模型、价格策略、路由策略和可见范围");
        }
        m.setStatus("已发布");
        mapper.updateById(m);
        return ApiResponse.ok(m);
    }

    @PatchMapping("/{id}/visibility")
    public ApiResponse<PlatformModel> visibility(@PathVariable("id") String id, @RequestBody PlatformModel body) {
        PlatformModel m = mapper.selectById(id);
        if (m == null) return ApiResponse.fail("平台模型不存在");
        m.setVisibilityScope(body.getVisibilityScope());
        mapper.updateById(m);
        return ApiResponse.ok(m);
    }

    @PatchMapping("/{id}/route-policy")
    public ApiResponse<PlatformModel> routePolicy(@PathVariable("id") String id, @RequestBody PlatformModel body) {
        PlatformModel m = mapper.selectById(id);
        if (m == null) return ApiResponse.fail("平台模型不存在");
        m.setRoutePolicyId(body.getRoutePolicyId());
        m.setRoutePolicy(body.getRoutePolicy());
        mapper.updateById(m);
        return ApiResponse.ok(m);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank() || "[]".equals(v) || "{}".equals(v);
    }
}
