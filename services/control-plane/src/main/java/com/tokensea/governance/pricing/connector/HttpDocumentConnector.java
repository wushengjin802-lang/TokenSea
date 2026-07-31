package com.tokensea.governance.pricing.connector;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HttpDocumentConnector extends StaticPriceSourceConnector {
    public HttpDocumentConnector() {
        super(new PriceSourceConnectorDefinition(
                "HTTP_DOCUMENT", "通用价格文档（HTML/CSV/JSON/PDF）", "官方网页或结构化价格文档",
                "DOCUMENT", "OFFICIAL_PUBLIC", false, "NONE", "", List.of(),
                List.of(
                        "DEEPSEEK_OFFICIAL_PAGE", "QWEN_OFFICIAL_PAGE", "KIMI_OFFICIAL_PAGE",
                        "XIAOMI_MIMO_OFFICIAL_PAGE", "ZHIPU_OFFICIAL_PAGE",
                        "OFFICIAL_JSON", "OFFICIAL_CSV", "GENERIC_DOCUMENT"),
                ConnectorSchemas.manualOrLowRisk(),
                ConnectorSchemas.schema(
                        "fetchMode", "获取方式", "string", false,
                        "requiresHeadless", "是否需要无头浏览器", "boolean", false,
                        "maxResponseBytes", "最大响应字节数", "integer", false)));
    }
}
