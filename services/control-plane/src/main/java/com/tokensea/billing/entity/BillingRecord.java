package com.tokensea.billing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("billing_record")
public class BillingRecord extends BaseEntity {
    private String tenantId;
    private java.time.LocalDate periodStart;
    private java.time.LocalDate periodEnd;
    private Long totalTokens;
    private java.math.BigDecimal totalCost;
    private java.math.BigDecimal totalSales;
    private String status;
}
