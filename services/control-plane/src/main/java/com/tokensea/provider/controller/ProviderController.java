package com.tokensea.provider.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.ReadOnlyController;
import com.tokensea.provider.entity.Provider;
import com.tokensea.provider.mapper.ProviderMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
public class ProviderController extends ReadOnlyController<Provider> {
    private final ProviderMapper mapper;
    public ProviderController(ProviderMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<Provider> mapper() { return mapper; }
}
