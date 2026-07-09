package com.tokensea.usage.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.usage.entity.UsageRecord;
import com.tokensea.usage.mapper.UsageRecordMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageRecordController extends BaseCrudController<UsageRecord> {
    private final UsageRecordMapper mapper;
    public UsageRecordController(UsageRecordMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<UsageRecord> mapper() { return mapper; }
}
