package com.tokensea.governance.pricing.reference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BuiltInReferenceSourceCatalog {
    public static final String BUNDLE_SOURCE_ID = "builtin_reference_price_bundle";
    private final String scheduleExpression;
    private final int staleAfterHours;
    private final int hardStaleAfterHours;

    public BuiltInReferenceSourceCatalog(
            @Value("${tokensea.reference-price.default-schedule:P1D}") String scheduleExpression,
            @Value("${tokensea.reference-price.stale-after-hours:168}") int staleAfterHours,
            @Value("${tokensea.reference-price.hard-stale-after-hours:720}") int hardStaleAfterHours) {
        this.scheduleExpression = scheduleExpression;
        this.staleAfterHours = staleAfterHours;
        this.hardStaleAfterHours = hardStaleAfterHours;
    }

    public List<ReferenceSourceDefinition> sources() {
        return List.of(
                new ReferenceSourceDefinition(
                        "builtin_litellm_cost_map",
                        "LiteLLM 公共成本参考",
                        "LITELLM_COST_MAP",
                        "LITELLM_COST_MAP",
                        "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json",
                        List.of("raw.githubusercontent.com"),
                        scheduleExpression,
                        200,
                        staleAfterHours,
                        true),
                new ReferenceSourceDefinition(
                        "builtin_models_dev",
                        "models.dev 公共模型参考",
                        "MODELS_DEV",
                        "MODELS_DEV",
                        "https://models.dev/api.json",
                        List.of("models.dev"),
                        scheduleExpression,
                        150,
                        staleAfterHours,
                        true),
                new ReferenceSourceDefinition(
                        BUNDLE_SOURCE_ID,
                        "TokenSea 内置参考价格快照",
                        "BUNDLED_REFERENCE",
                        "BUNDLED_REFERENCE",
                        "classpath:reference-prices/reference-price-bootstrap.json",
                        List.of(),
                        "P3650D",
                        10,
                        hardStaleAfterHours,
                        false)
        );
    }

    public record ReferenceSourceDefinition(
            String id,
            String name,
            String adapterCode,
            String connectorCode,
            String endpoint,
            List<String> officialHosts,
            String scheduleExpression,
            int sourcePriority,
            int staleAfterHours,
            boolean onlineSync
    ) {}
}
