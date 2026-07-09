package com.tokensea.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {
    private String tenantId;
    private String name;
    private String ownerName;
    private java.math.BigDecimal monthlyBudget;
    private String status;
}
