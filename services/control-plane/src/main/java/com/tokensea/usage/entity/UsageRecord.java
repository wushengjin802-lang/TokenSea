package com.tokensea.usage.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usage_record")
public class UsageRecord extends BaseEntity {
    private String requestId;
    private String tenantId;
    private String projectId;
    private String appId;
    private String apiKeyId;
    private String modelAlias;
    private String runtimeModelName;
    private String providerId;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private java.math.BigDecimal costAmount;
    private java.math.BigDecimal salesAmount;
    private String currency;
    private String status;
    private String errorCode;
    private Integer latencyMs;
    private String fallbackChain;
}
