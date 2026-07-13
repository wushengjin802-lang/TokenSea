package com.tokensea.billing.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.ReadOnlyController;
import com.tokensea.billing.entity.BillingRecord;
import com.tokensea.billing.mapper.BillingRecordMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
public class BillingRecordController extends ReadOnlyController<BillingRecord> {
    private final BillingRecordMapper mapper;
    public BillingRecordController(BillingRecordMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<BillingRecord> mapper() { return mapper; }
}
