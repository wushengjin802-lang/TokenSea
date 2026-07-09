package com.tokensea.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model")
public class ModelAsset extends BaseEntity {
    private String alias;
    private String displayName;
    private Integer contextLength;
    private String capabilityTags;
    private String supportedEndpoints;
    private String status;
}
