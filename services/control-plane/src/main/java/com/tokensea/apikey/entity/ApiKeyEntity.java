package com.tokensea.apikey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("api_key")
public class ApiKeyEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String appId;
    private String name;
    private String keyHash;
    private String keyPrefix;
    private String status;
    private String approvalStatus;
    private String modelScope;
    private java.math.BigDecimal budgetAmount;
    private Integer rpmLimit;
    private Integer tpmLimit;
    private Integer qpsLimit;
    private String ipWhitelist;
    private java.time.OffsetDateTime expiresAt;
    private String createdBy;
    private String approvedBy;
    private java.time.OffsetDateTime approvedAt;
}
