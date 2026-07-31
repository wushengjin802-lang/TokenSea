package com.tokensea.governance.pricing.connector;

import com.tokensea.asset.entity.ProviderTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class ProviderPriceSourcePresetCatalog {
    private static final Map<String, Preset> PRESETS = Map.ofEntries(
            Map.entry("deepseek", preset(
                    "DEEPSEEK_OFFICIAL_PAGE",
                    "https://api-docs.deepseek.com/quick_start/pricing/",
                    "api-docs.deepseek.com", "global", "USD",
                    "AUTO", "SPECIALIZED", false, "AUTO")),
            Map.entry("qwen", preset(
                    "QWEN_OFFICIAL_PAGE",
                    "https://help.aliyun.com/zh/model-studio/model-pricing",
                    "help.aliyun.com", "cn", "CNY",
                    "AUTO", "SPECIALIZED", false, "AUTO")),
            Map.entry("moonshot", preset(
                    "KIMI_OFFICIAL_PAGE",
                    "https://platform.kimi.com/docs/pricing/chat-k26",
                    "platform.kimi.com", "cn", "CNY",
                    "AUTO", "SPECIALIZED", false, "AUTO")),
            Map.entry("xiaomi_mimo", preset(
                    "XIAOMI_MIMO_OFFICIAL_PAGE",
                    "https://mimo.mi.com/docs/zh-CN/price/pay-as-you-go",
                    "mimo.mi.com", "cn", "CNY",
                    "AUTO", "SPECIALIZED", false, "AUTO")),
            Map.entry("zhipu", preset(
                    "ZHIPU_OFFICIAL_PAGE",
                    "https://bigmodel.cn/pricing",
                    "bigmodel.cn, static.bigmodel.cn", "cn", "CNY",
                    "HTML", "SPECIALIZED", false, "HEADLESS")),
            Map.entry("volcengine_ark", preset(
                    "GENERIC_DOCUMENT",
                    "https://www.volcengine.com/product/doubao/",
                    "www.volcengine.com", "cn", "CNY",
                    "HTML", "DETERMINISTIC_LLM", true, "AUTO"))
    );

    public ProviderPriceSourceOption option(ProviderTemplate template) {
        Preset preset = PRESETS.getOrDefault(template.getProviderType(), Preset.generic());
        return new ProviderPriceSourceOption(
                template.getProviderName(),
                template.getProviderType(),
                template.getProviderName() + "官方价格",
                preset.adapterCode(),
                "HTTP_DOCUMENT",
                preset.endpoint(),
                preset.officialHosts(),
                preset.region(),
                preset.currency(),
                preset.documentType(),
                preset.extractionMode(),
                BigDecimal.valueOf(0.85),
                preset.requireManualReview(),
                200,
                20_000_000,
                preset.fetchMode(),
                List.of("MANUAL_ONLY"),
                preset.llmEnabled() ? Map.of("llmEnabled", true) : Map.of()
        );
    }

    private static Preset preset(String adapterCode, String endpoint, String officialHosts,
                                 String region, String currency, String documentType,
                                 String extractionMode, boolean requireManualReview,
                                 String fetchMode) {
        return new Preset(adapterCode, endpoint, officialHosts, region, currency,
                documentType, extractionMode, requireManualReview, fetchMode,
                "DETERMINISTIC_LLM".equals(extractionMode));
    }

    public record ProviderPriceSourceOption(
            String providerName,
            String providerType,
            String recommendedName,
            String adapterCode,
            String connectorCode,
            String endpoint,
            String officialHosts,
            String region,
            String defaultCurrency,
            String documentType,
            String extractionMode,
            BigDecimal minimumConfidence,
            boolean requireManualReview,
            int maxDocumentPages,
            int maxDocumentBytes,
            String fetchMode,
            List<String> supportedPublishPolicies,
            Map<String, Object> config
    ) {}

    private record Preset(
            String adapterCode,
            String endpoint,
            String officialHosts,
            String region,
            String currency,
            String documentType,
            String extractionMode,
            boolean requireManualReview,
            String fetchMode,
            boolean llmEnabled
    ) {
        private static Preset generic() {
            return new Preset("GENERIC_DOCUMENT", null, "", "global", "USD",
                    "AUTO", "DETERMINISTIC_LLM", true, "AUTO", true);
        }
    }
}
