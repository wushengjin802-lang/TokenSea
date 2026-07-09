package com.tokensea.app.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.app.entity.AppEntity;
import com.tokensea.app.mapper.AppEntityMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/apps")
public class AppController extends BaseCrudController<AppEntity> {
    private final AppEntityMapper mapper;
    public AppController(AppEntityMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<AppEntity> mapper() { return mapper; }
}
