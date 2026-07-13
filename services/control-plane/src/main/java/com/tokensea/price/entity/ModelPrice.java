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
    @TableField("input_cost_per_1k")
    private java.math.BigDecimal inputCostPer1k;
    @TableField("output_cost_per_1k")
    private java.math.BigDecimal outputCostPer1k;
    @TableField("input_price_per_1k")
    private java.math.BigDecimal inputPricePer1k;
    @TableField("output_price_per_1k")
    private java.math.BigDecimal outputPricePer1k;
    private java.time.OffsetDateTime effectiveFrom;
    private java.time.OffsetDateTime effectiveTo;
    private String status;
}
