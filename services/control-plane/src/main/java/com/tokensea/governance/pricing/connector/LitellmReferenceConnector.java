package com.tokensea.governance.pricing.connector;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LitellmReferenceConnector extends StaticPriceSourceConnector {
    public LitellmReferenceConnector() {
        super(new PriceSourceConnectorDefinition(
                "LITELLM_COST_MAP", "LiteLLM Cost Map", "公共参考数据",
                "REFERENCE_DATASET", "COMMUNITY_REFERENCE", false, "NONE",
                "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json",
                List.of("raw.githubusercontent.com"), List.of("LITELLM_COST_MAP"),
                List.of("MANUAL_ONLY"),
                ConnectorSchemas.schema(
                        "version", "LiteLLM 版本或 Commit", "string", false,
                        "providerFilters", "供应商过滤", "string-list", false,
                        "modelFilters", "模型过滤", "string-list", false)));
    }
}
