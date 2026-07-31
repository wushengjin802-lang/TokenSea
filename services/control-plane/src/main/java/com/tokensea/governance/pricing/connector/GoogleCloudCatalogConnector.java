package com.tokensea.governance.pricing.connector;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GoogleCloudCatalogConnector extends StaticPriceSourceConnector {
    public GoogleCloudCatalogConnector() {
        super(new PriceSourceConnectorDefinition(
                "GOOGLE_CLOUD_CATALOG", "Google Cloud Billing Catalog", "官方目录 API",
                "PUBLIC_CATALOG", "OFFICIAL_PUBLIC", true, "PRICING_READ",
                "", List.of("cloudbilling.googleapis.com"), List.of("GOOGLE_CLOUD_CATALOG"),
                ConnectorSchemas.manualOrLowRisk(),
                ConnectorSchemas.schema(
                        "serviceId", "Cloud Billing 服务 ID", "string", true,
                        "projectId", "项目 ID", "string", false,
                        "currencyCode", "币种", "string", false,
                        "modelPattern", "模型匹配表达式", "string", true,
                        "mappingProfile", "映射配置版本", "string", false,
                        "maxPages", "最大分页数", "integer", false)));
    }
}
