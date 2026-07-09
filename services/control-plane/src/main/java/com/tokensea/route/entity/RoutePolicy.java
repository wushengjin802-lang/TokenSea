package com.tokensea.route.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tokensea.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("route_policy")
public class RoutePolicy extends BaseEntity {
    private String name;
    private String modelAlias;
    private String strategy;
    private Boolean fallbackEnabled;
    private String status;
    private String config;
}
