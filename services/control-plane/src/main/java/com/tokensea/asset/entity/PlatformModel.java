package com.tokensea.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("platform_model")
public class PlatformModel extends BaseEntity {
    private String platformModelName;
    private String displayName;
    private String modelTemplateIds;
    private String providerInstanceIds;
    private String actualModels;
    private String routePolicyId;
    private String routePolicy;
    private String pricePolicyId;
    private String pricePolicy;
    private String visibilityScope;
    private Boolean approvalRequired;
    private String status;
}
