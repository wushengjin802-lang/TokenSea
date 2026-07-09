package com.tokensea.app.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app")
public class AppEntity extends BaseEntity {
    private String tenantId;
    private String projectId;
    private String name;
    private String ownerName;
    private String environment;
    private String status;
}
