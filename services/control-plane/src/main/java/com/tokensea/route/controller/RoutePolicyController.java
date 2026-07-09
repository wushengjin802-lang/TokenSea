package com.tokensea.route.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.route.entity.RoutePolicy;
import com.tokensea.route.mapper.RoutePolicyMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routes")
public class RoutePolicyController extends BaseCrudController<RoutePolicy> {
    private final RoutePolicyMapper mapper;
    public RoutePolicyController(RoutePolicyMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<RoutePolicy> mapper() { return mapper; }
}
