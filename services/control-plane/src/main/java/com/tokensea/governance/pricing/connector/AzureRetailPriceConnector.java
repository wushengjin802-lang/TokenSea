package com.tokensea.governance.pricing.connector;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AzureRetailPriceConnector extends StaticPriceSourceConnector {
    public AzureRetailPriceConnector() {
        super(new PriceSourceConnectorDefinition(
                "AZURE_RETAIL_PRICES", "Azure Retail Prices", "官方目录 API",
                "PUBLIC_CATALOG", "OFFICIAL_PUBLIC", false, "NONE",
                "https://prices.azure.com/api/retail/prices",
                List.of("prices.azure.com"), List.of("AZURE_RETAIL_PRICES"),
                ConnectorSchemas.manualOrLowRisk(),
                ConnectorSchemas.schema(
                        "currencyCode", "币种", "string", false,
                        "serviceFilters", "服务过滤", "string-list", false,
                        "regionFilters", "区域过滤", "string-list", false,
                        "modelPattern", "模型匹配表达式", "string", true,
                        "mappingProfile", "映射配置版本", "string", false,
                        "maxPages", "最大分页数", "integer", false)));
    }
}
