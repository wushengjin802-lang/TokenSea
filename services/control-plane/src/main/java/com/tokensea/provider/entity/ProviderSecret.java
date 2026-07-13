package com.tokensea.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("provider_secret")
public class ProviderSecret extends BaseEntity {
    private String providerId;
    private String providerInstanceId;
    private String secretName;
    @JsonIgnore
    private String secretCipher;
    private String secretLast4;
    private String status;
}
