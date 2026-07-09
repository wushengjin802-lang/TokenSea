package com.tokensea.deployment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_deployment")
public class ModelDeployment extends BaseEntity {
    private String modelId;
    private String providerId;
    private String deploymentName;
    private String runtimeModelName;
    private Integer priority;
    private Integer weight;
    private String status;
    private Integer timeoutSeconds;
}
