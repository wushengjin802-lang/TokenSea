package com.tokensea.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("provider_template")
public class ProviderTemplate extends BaseEntity {
    private String providerName;
    private String providerType;
    private String protocol;
    private String defaultApiBase;
    private String authType;
    private String supportedEndpoints;
    private String errorMapping;
    private String healthCheckPath;
    private Integer defaultRateLimitRpm;
    private Integer defaultRateLimitTpm;
    private Integer modelTemplateCount;
    private String builtIn;
    private String status;
    private String description;
    @TableField(exist = false)
    private Integer enabledInstanceCount;
}
