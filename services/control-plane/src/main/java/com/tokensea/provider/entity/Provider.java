package com.tokensea.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("provider")
public class Provider extends BaseEntity {
    private String name;
    private String providerType;
    private String apiStyle;
    private String baseUrl;
    private String region;
    private String status;
    private String healthCheckUrl;
    private Integer rateLimitRpm;
    private Integer rateLimitTpm;
}
