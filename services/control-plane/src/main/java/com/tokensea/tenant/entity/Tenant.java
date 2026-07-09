package com.tokensea.tenant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tenant")
public class Tenant extends BaseEntity {
    private String name;
    private String type;
    private String status;
    private String ownerName;
    private String contactEmail;
    private String modelScope;
    private java.math.BigDecimal monthlyBudget;
    private String remark;
}
