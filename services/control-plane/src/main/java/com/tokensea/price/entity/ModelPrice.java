package com.tokensea.price.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_price")
public class ModelPrice extends BaseEntity {
    private String modelId;
    private String currency;
    private java.math.BigDecimal inputCostPer1k;
    private java.math.BigDecimal outputCostPer1k;
    private java.math.BigDecimal inputPricePer1k;
    private java.math.BigDecimal outputPricePer1k;
    private java.time.OffsetDateTime effectiveFrom;
    private java.time.OffsetDateTime effectiveTo;
    private String status;
}
