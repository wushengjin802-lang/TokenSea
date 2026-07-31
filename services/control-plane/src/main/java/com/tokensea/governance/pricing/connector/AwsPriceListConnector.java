package com.tokensea.governance.pricing.connector;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AwsPriceListConnector extends StaticPriceSourceConnector {
    public AwsPriceListConnector() {
        super(new PriceSourceConnectorDefinition(
                "AWS_PRICE_LIST_BULK", "AWS Price List", "官方目录 API",
                "PUBLIC_CATALOG", "OFFICIAL_PUBLIC", false, "NONE",
                "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/index.json",
                List.of("pricing.us-east-1.amazonaws.com"), List.of("AWS_PRICE_LIST_BULK"),
                ConnectorSchemas.manualOrLowRisk(),
                ConnectorSchemas.schema(
                        "serviceCode", "服务代码", "string", false,
                        "regionCodes", "区域", "string-list", false,
                        "currencyCode", "币种", "string", false,
                        "modelPattern", "模型匹配表达式", "string", true,
                        "mappingProfile", "映射配置版本", "string", false,
                        "maxResponseBytes", "最大响应字节数", "integer", false)));
    }
}
