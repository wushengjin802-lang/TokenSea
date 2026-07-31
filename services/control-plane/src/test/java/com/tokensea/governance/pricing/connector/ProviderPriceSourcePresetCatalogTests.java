package com.tokensea.governance.pricing.connector;

import com.tokensea.asset.entity.ProviderTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPriceSourcePresetCatalogTests {
    private final ProviderPriceSourcePresetCatalog catalog = new ProviderPriceSourcePresetCatalog();

    @Test
    void doubaoUsesOfficialPageAndSafeManualReviewDefaults() {
        ProviderTemplate template = template("火山方舟 / 豆包", "volcengine_ark");

        var option = catalog.option(template);

        assertEquals("GENERIC_DOCUMENT", option.adapterCode());
        assertEquals("HTTP_DOCUMENT", option.connectorCode());
        assertEquals("https://www.volcengine.com/product/doubao/", option.endpoint());
        assertEquals("www.volcengine.com", option.officialHosts());
        assertEquals("cn", option.region());
        assertEquals("CNY", option.defaultCurrency());
        assertEquals("DETERMINISTIC_LLM", option.extractionMode());
        assertTrue(option.requireManualReview());
        assertEquals(true, option.config().get("llmEnabled"));
    }

    @Test
    void zhipuIncludesRequiredStaticAssetHostAndHeadlessFetch() {
        ProviderTemplate template = template("Z.AI / 智谱 GLM", "zhipu");

        var option = catalog.option(template);

        assertEquals("ZHIPU_OFFICIAL_PAGE", option.adapterCode());
        assertEquals("bigmodel.cn, static.bigmodel.cn", option.officialHosts());
        assertEquals("HEADLESS", option.fetchMode());
    }

    @Test
    void unknownProviderFallsBackToGenericDocumentWithoutInventingUrl() {
        ProviderTemplate template = template("自定义供应商", "custom_provider");

        var option = catalog.option(template);

        assertEquals("GENERIC_DOCUMENT", option.adapterCode());
        assertEquals("自定义供应商官方价格", option.recommendedName());
        assertEquals(null, option.endpoint());
        assertEquals("", option.officialHosts());
        assertTrue(option.requireManualReview());
        assertEquals(true, option.config().get("llmEnabled"));
    }

    private static ProviderTemplate template(String name, String type) {
        ProviderTemplate template = new ProviderTemplate();
        template.setProviderName(name);
        template.setProviderType(type);
        return template;
    }
}
