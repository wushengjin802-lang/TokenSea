package com.tokensea.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_template")
public class ModelTemplate extends BaseEntity {
    private String providerTemplateId;
    private String providerName;
    private String providerModelName;
    private String defaultDisplayName;
    private Integer contextLength;
    private String supportedEndpoints;
    private Boolean supportsStreaming;
    private Boolean supportsTools;
    private String capabilityTags;
    private String defaultCostLevel;
    private String defaultQualityLevel;
    private String complianceTags;
    private String builtIn;
    private String status;
}
