package com.tokensea.price.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_price")
public class ModelPrice extends BaseEntity {
    private String modelId;
    private String platformModelId;
    private String providerInstanceId;
    private String currency;
    private String billingBasis;
    private Long billingQuantity;
    @TableField("input_cost_unit_price")
    private java.math.BigDecimal inputCostUnitPrice;
    @TableField("output_cost_unit_price")
    private java.math.BigDecimal outputCostUnitPrice;
    @TableField("input_price_unit_price")
    private java.math.BigDecimal inputPriceUnitPrice;
    @TableField("output_price_unit_price")
    private java.math.BigDecimal outputPriceUnitPrice;
    private java.time.OffsetDateTime effectiveFrom;
    private java.time.OffsetDateTime effectiveTo;
    private String status;
}
