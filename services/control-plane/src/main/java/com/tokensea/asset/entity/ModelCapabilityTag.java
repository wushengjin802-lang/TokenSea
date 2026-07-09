package com.tokensea.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_capability_tag")
public class ModelCapabilityTag extends BaseEntity {
    private String name;
    private String description;
}
