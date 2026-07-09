package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.asset.entity.ModelTemplate;
import com.tokensea.asset.mapper.ModelTemplateMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.BaseCrudController;
import com.tokensea.model.entity.ModelAsset;
import com.tokensea.model.mapper.ModelAssetMapper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/model-templates")
public class ModelTemplateController extends BaseCrudController<ModelTemplate> {
    private final ModelTemplateMapper mapper;
    private final ModelAssetMapper legacyModels;

    public ModelTemplateController(ModelTemplateMapper mapper, ModelAssetMapper legacyModels) {
        this.mapper = mapper;
        this.legacyModels = legacyModels;
    }

    @Override protected BaseMapper<ModelTemplate> mapper() { return mapper; }

    @PostMapping("/{id}/enable")
    public ApiResponse<ModelAsset> enable(@PathVariable("id") String id) {
        ModelTemplate t = mapper.selectById(id);
        if (t == null) return ApiResponse.fail("模型模板不存在");
        ModelAsset existing = legacyModels.selectOne(new QueryWrapper<ModelAsset>().eq("alias", t.getProviderModelName()));
        if (existing != null) return ApiResponse.ok(existing);
        ModelAsset m = new ModelAsset();
        m.setAlias(t.getProviderModelName());
        m.setDisplayName(t.getDefaultDisplayName());
        m.setContextLength(t.getContextLength());
        m.setCapabilityTags(t.getCapabilityTags());
        m.setSupportedEndpoints(t.getSupportedEndpoints());
        m.setStatus("启用");
        legacyModels.insert(m);
        return ApiResponse.ok(m);
    }
}
