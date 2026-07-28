package com.tokensea.asset.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private java.time.OffsetDateTime lastConnectionTestAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastConnectionTestStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastConnectionTestError;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastConnectionTestHost;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastConnectionTestAddresses;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer lastConnectionTestPort;
}
