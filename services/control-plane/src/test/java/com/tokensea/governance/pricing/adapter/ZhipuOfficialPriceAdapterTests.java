package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PricingComponentService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZhipuOfficialPriceAdapterTests {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ZhipuOfficialPriceAdapter adapter = new ZhipuOfficialPriceAdapter(json);

    @Test
    void rebuildsElementUiTablesAndCarriesRowspanModelAcrossPriceTiers() {
        String html = """
            <html><head><title>ZHIPU AI OPEN PLATFORM</title></head><body>
              <h2>旗舰模型</h2>
              <h3>文本模型</h3>
              <div class="el-table">
                <table class="el-table__header">
                  <tr>
                    <th>模型名称</th><th>上下文<br>(千tokens)</th>
                    <th>输入单价<br>(百万tokens)</th><th>输出单价<br>(百万tokens)</th>
                    <th>缓存存储<br>(百万tokens/小时)</th><th>缓存命中<br>(百万tokens)</th>
                  </tr>
                </table>
                <table class="el-table__body">
                  <tr>
                    <td rowspan="2">GLM-5.1</td><td>输入长度 [0, 32)</td>
                    <td>6元</td><td>24元</td><td>限时免费</td><td>1.3元</td>
                  </tr>
                  <tr>
                    <td>输入长度 [32+)</td><td>8元</td><td>28元</td><td>限时免费</td><td>2元</td>
                  </tr>
                  <tr>
                    <td>GLM-4.7-Flash</td><td>200K</td>
                    <td>免费</td><td>免费</td><td>免费</td><td>免费</td>
                  </tr>
                </table>
              </div>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(3);
        assertThat(result.generatedPriceCount()).isEqualTo(3);
        assertThat(result.parseStatus()).isEqualTo("PRICE_PARSED");
        assertThat(result.matchedTableCount()).isEqualTo(1);
        assertThat(result.sourceEvidence()).containsEntry("originalTableCount", 2);
        assertThat(result.sourceEvidence()).containsEntry("semanticTableCount", 1);

        var lowTier = result.prices().stream()
                .filter(price -> price.providerModelName().equals("glm-5.1"))
                .filter(price -> price.contextTier().equals("IN_0_32000"))
                .findFirst().orElseThrow();
        var highTier = result.prices().stream()
                .filter(price -> price.providerModelName().equals("glm-5.1"))
                .filter(price -> price.contextTier().equals("IN_32000_PLUS"))
                .findFirst().orElseThrow();

        assertThat(lowTier.providerType()).isEqualTo("zhipu");
        assertThat(lowTier.currency()).isEqualTo("CNY");
        assertThat(lowTier.region()).isEqualTo("cn");
        assertThat(lowTier.inputUnitPrice()).isEqualByComparingTo("6");
        assertThat(lowTier.outputUnitPrice()).isEqualByComparingTo("24");
        assertThat(highTier.inputUnitPrice()).isEqualByComparingTo("8");
        assertThat(highTier.outputUnitPrice()).isEqualByComparingTo("28");

        Map<?,?> input = (Map<?,?>) lowTier.components().get("INPUT_TOKEN");
        Map<?,?> cacheRead = (Map<?,?>) lowTier.components().get("CACHE_READ_TOKEN");
        Map<?,?> cacheWrite = (Map<?,?>) lowTier.components().get("CACHE_WRITE_TOKEN");
        Map<?,?> cacheStorage = (Map<?,?>) lowTier.components().get("CACHE_STORAGE_TOKEN_SECOND");
        Map<?,?> inputScope = (Map<?,?>) input.get("scope");
        assertThat(inputScope.get("minContextTokens")).isEqualTo(0L);
        assertThat(inputScope.get("maxContextTokens")).isEqualTo(31_999L);
        assertThat(cacheRead.get("unitPrice")).isEqualTo(new BigDecimal("1.3"));
        assertThat(cacheWrite.get("mode")).isEqualTo("NOT_APPLICABLE");
        assertThat(cacheWrite.containsKey("unitPrice")).isFalse();
        assertThat(cacheStorage.get("mode")).isEqualTo("EXPLICIT_ZERO");
        assertThat(cacheStorage.get("unitBasis")).isEqualTo("TOKEN_SECOND");
        assertThat(cacheStorage.get("unitQuantity")).isEqualTo(3_600_000_000L);
        var normalizedComponents = new PricingComponentService(json).normalizeParsed(
                lowTier.inputUnitPrice(), lowTier.outputUnitPrice(), lowTier.providerType(),
                lowTier.billingBasis(), lowTier.billingQuantity(), lowTier.components(), lowTier.sourceRef());
        assertThat(normalizedComponents).anyMatch(component ->
                "CACHE_STORAGE_TOKEN_SECOND".equals(component.get("componentType"))
                        && "TOKEN_SECOND".equals(component.get("unitBasis")));
        assertThat(normalizedComponents).anyMatch(component ->
                "CACHE_WRITE_TOKEN".equals(component.get("componentType"))
                        && "NOT_APPLICABLE".equals(component.get("mode")));

        var free = result.prices().stream()
                .filter(price -> price.providerModelName().equals("glm-4.7-flash"))
                .findFirst().orElseThrow();
        assertThat(free.inputUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(free.outputUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(free.raw()).containsEntry("priceNature", "FREE_QUOTA");
        assertThat(((Map<?,?>) free.components().get("INPUT_TOKEN")).get("mode"))
                .isEqualTo("EXPLICIT_ZERO");
    }

    @Test
    void keepsInputAndOutputLengthTiersDistinct() {
        String html = """
            <html><head><title>智谱价格</title></head><body>
              <table id="glm-pricing">
                <tr><th>模型名称</th><th>上下文（千tokens）</th><th>输入单价（百万tokens）</th>
                    <th>输出单价（百万tokens）</th><th>缓存存储（百万tokens/小时）</th><th>缓存命中（百万tokens）</th></tr>
                <tr><td rowspan="3">GLM-4.7</td><td>输入长度 [0, 32) 输出长度 [0, 0.2)</td>
                    <td>2元</td><td>8元</td><td>限时免费</td><td>0.4元</td></tr>
                <tr><td>输入长度 [0, 32) 输出长度 [0.2+)</td>
                    <td>3元</td><td>14元</td><td>限时免费</td><td>0.6元</td></tr>
                <tr><td>输入长度 [32, 200)</td>
                    <td>4元</td><td>16元</td><td>限时免费</td><td>0.8元</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).extracting(price -> price.contextTier())
                .containsExactlyInAnyOrder(
                        "IN_0_32000_OUT_0_200",
                        "IN_0_32000_OUT_200_PLUS",
                        "IN_32000_200000");
        assertThat(result.prices()).extracting(price -> price.outputUnitPrice())
                .containsExactlyInAnyOrder(new BigDecimal("8"), new BigDecimal("14"), new BigDecimal("16"));
        var longOutput = result.prices().stream()
                .filter(price -> price.contextTier().equals("IN_0_32000_OUT_200_PLUS"))
                .findFirst().orElseThrow();
        Map<?,?> conditions = (Map<?,?>) longOutput.raw().get("pricingConditions");
        Map<?,?> outputTier = (Map<?,?>) conditions.get("outputTokenTier");
        assertThat(outputTier.get("minTokensInclusive")).isEqualTo(200L);
        assertThat(outputTier.get("openEnded")).isEqualTo(true);
    }

    @Test
    void convertsNumericCacheStoragePerMillionTokenHourToTokenSeconds() {
        String html = """
            <html><head><title>智谱价格</title></head><body>
              <table>
                <tr><th>模型名称</th><th>上下文（千tokens）</th><th>输入单价（百万tokens）</th>
                    <th>输出单价（百万tokens）</th><th>缓存存储（百万tokens/小时）</th><th>缓存命中（百万tokens）</th></tr>
                <tr><td>GLM-5.2</td><td>1M</td><td>8元</td><td>28元</td><td>0.5元</td><td>2元</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(1);
        Map<?,?> storage = (Map<?,?>) result.prices().getFirst().components()
                .get("CACHE_STORAGE_TOKEN_SECOND");
        assertThat(storage.get("unitPrice")).isEqualTo(new BigDecimal("0.5"));
        assertThat(storage.get("unitBasis")).isEqualTo("TOKEN_SECOND");
        assertThat(storage.get("unitQuantity")).isEqualTo(3_600_000_000L);
        assertThat(storage.get("mode")).isEqualTo("EXPLICIT");
    }

    @Test
    void skipsLegacyCombinedPricingTablesInsteadOfGuessingInputAndOutput() {
        String html = """
            <html><head><title>智谱价格</title></head><body>
              <h3>模型推理</h3>
              <table>
                <tr><th>Model</th><th>Description</th><th>Context</th><th>Pricing</th><th>Pricing with Batch API</th></tr>
                <tr><td>GLM-4-Plus</td><td>Flagship</td><td>128K</td><td>¥5 / M Tokens</td><td>¥2.5 / M Tokens</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).isEmpty();
        assertThat(result.skippedTableCount()).isEqualTo(1);
        assertThat(result.warnings()).anyMatch(message -> message.contains("未从智谱官方页面解析出"));
    }

    @Test
    void recommendsHeadlessFetcherForStaticJavascriptShell() {
        String html = """
            <html><head><title>智谱AI开放平台</title></head><body>
              <div id="app"><div id="loader-wrapper">加载中</div></div>
              <script></script><script></script><script></script>
              <script></script><script></script><script></script>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).isEmpty();
        assertThat(result.headlessRecommended()).isTrue();
        assertThat(result.warnings()).anyMatch(message -> message.contains("Headless Fetcher"));
    }

    @Test
    void failsSafelyWhenConfiguredCurrencyDoesNotMatchOfficialEvidence() {
        String html = """
            <html><head><title>智谱价格</title></head><body>
              <table>
                <tr><th>模型名称</th><th>输入单价（百万tokens）</th><th>输出单价（百万tokens）</th></tr>
                <tr><td>GLM-5.2</td><td>8元</td><td>28元</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("USD");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).isEmpty();
        assertThat(result.warnings()).anyMatch(message -> message.contains("币种校验失败"));
    }

    private PriceSourceAdapterContext context(String currency) {
        return new PriceSourceAdapterContext(
                "zhipu-source",
                ZhipuOfficialPriceAdapter.ADAPTER_CODE,
                "zhipu",
                "https://bigmodel.cn/pricing",
                "cn",
                currency,
                "STANDARD",
                100,
                "ORIGINAL",
                "1.0.0",
                Map.of("official", true, "scope", "EXPLICIT_INPUT_OUTPUT_TOKEN"));
    }
}
