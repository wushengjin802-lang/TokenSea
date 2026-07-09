package com.tokensea.deployment.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.deployment.entity.ModelDeployment;
import com.tokensea.deployment.mapper.ModelDeploymentMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-deployments")
public class ModelDeploymentController extends BaseCrudController<ModelDeployment> {
    private final ModelDeploymentMapper mapper;
    public ModelDeploymentController(ModelDeploymentMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<ModelDeployment> mapper() { return mapper; }
}
