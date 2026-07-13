package com.tokensea.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String credentialRef;
    private String keyStatus;
    private String environment;
    private String healthStatus;
    private String enabledModels;
    private String owner;
    private String status;
    private Integer rateLimitRpm;
    private Integer rateLimitTpm;
    private java.time.OffsetDateTime lastConnectionTestAt;
    private String lastConnectionTestStatus;
    private String lastConnectionTestError;
    private String lastConnectionTestHost;
    private String lastConnectionTestAddresses;
    private Integer lastConnectionTestPort;
}
