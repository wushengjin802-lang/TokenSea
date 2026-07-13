package com.tokensea.audit.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.ReadOnlyController;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController extends ReadOnlyController<AuditLog> {
    private final AuditLogMapper mapper;
    public AuditLogController(AuditLogMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<AuditLog> mapper() { return mapper; }
}
