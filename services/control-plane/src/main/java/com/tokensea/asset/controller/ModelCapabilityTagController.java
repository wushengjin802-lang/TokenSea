package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.asset.entity.ModelCapabilityTag;
import com.tokensea.asset.mapper.ModelCapabilityTagMapper;
import com.tokensea.common.BaseCrudController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-capability-tags")
public class ModelCapabilityTagController extends BaseCrudController<ModelCapabilityTag> {
    private final ModelCapabilityTagMapper mapper;
    public ModelCapabilityTagController(ModelCapabilityTagMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<ModelCapabilityTag> mapper() { return mapper; }
}
