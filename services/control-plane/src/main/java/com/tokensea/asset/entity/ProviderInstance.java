package com.tokensea.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("provider_instance")
public class ProviderInstance extends BaseEntity {
    private String providerTemplateId;
    private String instanceName;
    private String providerType;
    private String apiStyle;
    private String apiBase;
    private String region;
    private String credentialRef;
    private String keyStatus;
    private String environment;
    private String healthStatus;
    private String enabledModels;
    private String owner;
    private String status;
    private Integer rateLimitRpm;
    private Integer rateLimitTpm;
}
