package com.tokensea.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_log")
public class AuditLog extends BaseEntity {
    private String actorId;
    private String actorName;
    private String action;
    private String objectType;
    private String objectId;
    private String beforeValue;
    private String afterValue;
    private String ipAddress;
    private String userAgent;
}
