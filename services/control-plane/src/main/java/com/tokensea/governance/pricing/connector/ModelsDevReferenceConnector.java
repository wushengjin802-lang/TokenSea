package com.tokensea.governance.pricing.connector;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelsDevReferenceConnector extends StaticPriceSourceConnector {
    public ModelsDevReferenceConnector() {
        super(new PriceSourceConnectorDefinition(
                "MODELS_DEV", "models.dev", "公共参考数据",
                "REFERENCE_DATASET", "COMMUNITY_REFERENCE", false, "NONE",
                "https://models.dev/api.json", List.of("models.dev"), List.of("MODELS_DEV"),
                List.of("MANUAL_ONLY"),
                ConnectorSchemas.schema(
                        "datasetVersion", "数据集版本", "string", false,
                        "providerFilters", "供应商过滤", "string-list", false,
                        "modelFilters", "模型过滤", "string-list", false)));
    }
}
