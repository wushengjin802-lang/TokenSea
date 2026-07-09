package com.tokensea.model.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.model.entity.ModelAsset;
import com.tokensea.model.mapper.ModelAssetMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
public class ModelAssetController extends BaseCrudController<ModelAsset> {
    private final ModelAssetMapper mapper;
    public ModelAssetController(ModelAssetMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<ModelAsset> mapper() { return mapper; }
}
