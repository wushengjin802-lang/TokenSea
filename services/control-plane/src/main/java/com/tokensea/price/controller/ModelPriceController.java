package com.tokensea.price.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.price.entity.ModelPrice;
import com.tokensea.price.mapper.ModelPriceMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-prices")
public class ModelPriceController extends BaseCrudController<ModelPrice> {
    private final ModelPriceMapper mapper;
    public ModelPriceController(ModelPriceMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<ModelPrice> mapper() { return mapper; }
}
