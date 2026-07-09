package com.tokensea.tenant.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.tenant.entity.Tenant;
import com.tokensea.tenant.mapper.TenantMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
public class TenantController extends BaseCrudController<Tenant> {
    private final TenantMapper mapper;
    public TenantController(TenantMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<Tenant> mapper() { return mapper; }
}
