package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KimiOfficialPriceAdapterTests {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final KimiOfficialPriceAdapter adapter = new KimiOfficialPriceAdapter(json);

    @Test
    void parsesHorizontalOfficialPricingTableAndSeparatesHighSpeedVariant() {
        String html = """
            <html><head><title>Kimi 定价</title></head><body>
              <h2>Kimi K2.6 标准实时推理 每 1M Token</h2>
              <table id="kimi-k26">
                <tr><th>模型</th><th>kimi-k2.6</th><th>kimi-k2.6-highspeed-preview</th></tr>
                <tr><td>输入价格</td><td>4元</td><td>8元</td></tr>
                <tr><td>输出价格</td><td>16元</td><td>32元</td></tr>
                <tr><td>缓存命中读取</td><td>1元</td><td>2元</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                "kimi-source", "KIMI_OFFICIAL_PAGE", "moonshot", "https://platform.kimi.com/docs/pricing/chat-k26",
                "cn", "CNY", "STANDARD", 100, "ORIGINAL", "1.0.0", Map.of());

        PriceSourceParseResult result = adapter.parse(context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(2);
        var standard = result.prices().stream()
                .filter(price -> price.providerModelName().equals("kimi-k2.6"))
                .findFirst().orElseThrow();
        var highSpeed = result.prices().stream()
                .filter(price -> price.providerModelName().contains("highspeed"))
                .findFirst().orElseThrow();
        assertThat(standard.inputUnitPrice()).isEqualByComparingTo("4");
        assertThat(standard.outputUnitPrice()).isEqualByComparingTo("16");
        assertThat(standard.components()).containsKey("CACHE_READ_TOKEN");
        assertThat(standard.serviceTier()).isEqualTo("DEFAULT");
        assertThat(highSpeed.serviceTier()).isEqualTo("HIGH_SPEED");
        assertThat(highSpeed.inputUnitPrice()).isEqualByComparingTo("8");
        assertThat(result.structureFingerprint()).hasSize(64);
        assertThat(result.parseStatus()).isEqualTo("PRICE_PARSED");
        assertThat(result.tableCount()).isEqualTo(1);
        assertThat(result.matchedTableCount()).isEqualTo(1);
        assertThat(result.generatedPriceCount()).isEqualTo(2);
    }

    @Test
    void parsesCurrentKimiK26RowTableWithoutTreatingModelVersionAsPrice() {
        String html = """
            <html><head><title>Kimi K2.6 模型定价</title></head><body>
              <p>所有价格均按每 1M tokens 计费。</p>
              <a href="https://platform.kimi.com/docs/pricing/chat-k26#产品定价">当前页锚点</a>
              <a href="https://platform.kimi.com/docs/pricing/batch#批量推理">Batch 定价</a>
              <a href="https://platform.kimi.com/docs/pricing/batch">Batch 重复链接</a>
              <a href="https://platform.kimi.com/docs/pricing/promotion">充值活动</a>
              <a href="https://platform.kimi.com/docs/pricing/limits">充值与限速</a>
              <a href="https://platform.kimi.com/docs/pricing/tools">联网搜索定价</a>
              <a href="https://platform.kimi.com/docs/pricing/chat">产品定价导航</a>
              <table id="kimi-k26-current">
                <tr>
                  <th>模型</th><th>计费单位</th><th>输入价格（缓存命中）</th>
                  <th>输入价格（缓存未命中）</th><th>输出价格</th><th>上下文窗口</th>
                </tr>
                <tr><td>kimi-k2.6</td><td>1M tokens</td><td>¥1.10</td><td>¥6.50</td><td>¥27.00</td><td>262,144 tokens</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                "kimi-source", "KIMI_OFFICIAL_PAGE", "moonshot", "https://platform.kimi.com/docs/pricing/chat-k26",
                "cn", "CNY", "STANDARD", 100, "ORIGINAL", "1.0.0", Map.of());

        PriceSourceParseResult result = adapter.parse(context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(1);
        var price = result.prices().getFirst();
        assertThat(price.providerModelName()).isEqualTo("kimi-k2.6");
        assertThat(price.inputUnitPrice()).isEqualByComparingTo("6.50");
        assertThat(price.outputUnitPrice()).isEqualByComparingTo("27.00");
        assertThat(price.requestMode()).isEqualTo("STANDARD");
        assertThat(price.serviceTier()).isEqualTo("DEFAULT");
        assertThat(price.components()).containsKeys("CACHE_READ_TOKEN", "CACHE_WRITE_TOKEN");
        Map<?,?> cacheRead = (Map<?,?>) price.components().get("CACHE_READ_TOKEN");
        Map<?,?> cacheWrite = (Map<?,?>) price.components().get("CACHE_WRITE_TOKEN");
        assertThat(cacheRead.get("unitPrice")).isEqualTo(new java.math.BigDecimal("1.10"));
        assertThat(cacheWrite.get("mode")).isEqualTo("INHERIT_INPUT");
        assertThat(result.discoveredPricePages()).hasSize(1);
        assertThat(result.discoveredPricePages().getFirst().url())
                .isEqualTo("https://platform.kimi.com/docs/pricing/batch");
    }

    @Test
    void onlyDiscoversModelPricingPagesSupportedByThisAdapter() {
        assertThat(KimiOfficialPriceAdapter.isSupportedPricingPage(
                "https://platform.kimi.com/docs/pricing/chat-k3#产品定价")).isTrue();
        assertThat(KimiOfficialPriceAdapter.isSupportedPricingPage(
                "https://platform.kimi.com/docs/pricing/chat-k27-code")).isTrue();
        assertThat(KimiOfficialPriceAdapter.isSupportedPricingPage(
                "https://platform.kimi.com/docs/pricing/batch")).isTrue();
        assertThat(KimiOfficialPriceAdapter.isSupportedPricingPage(
                "https://platform.kimi.com/docs/pricing/promotion")).isFalse();
        assertThat(KimiOfficialPriceAdapter.isSupportedPricingPage(
                "https://platform.kimi.com/docs/pricing/limits")).isFalse();
        assertThat(KimiOfficialPriceAdapter.isSupportedPricingPage(
                "https://platform.kimi.com/docs/pricing/tools")).isFalse();
        assertThat(KimiOfficialPriceAdapter.isSupportedPricingPage(
                "https://platform.kimi.com/docs/pricing/chat")).isFalse();
    }

    @Test
    void singleModelFallbackDoesNotTreatModelVersionAsMoney() {
        String html = """
            <html><head><title>Kimi K2.6 模型定价</title></head><body>
              <p>每 1M tokens 计费，输入价格和输出价格请查看控制台。</p>
              <code>kimi-k2.6</code>
            </body></html>
            """;
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                "kimi-source", "KIMI_OFFICIAL_PAGE", "moonshot", "https://platform.kimi.com/docs/pricing/chat-k26",
                "cn", "CNY", "STANDARD", 100, "ORIGINAL", "1.0.0", Map.of());

        PriceSourceParseResult result = adapter.parse(context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).isEmpty();
    }

    @Test
    void recommendsIndependentHeadlessFetcherWhenRenderedPriceIsMissing() {
        String html = """
            <html><head><title>Kimi 价格</title></head><body>
              <div id="app">加载中</div>
              <script></script><script></script><script></script>
              <script></script><script></script><script></script>
            </body></html>
            """;
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                "kimi-source", "KIMI_OFFICIAL_PAGE", "moonshot", "https://platform.kimi.com/docs/pricing/chat-k26",
                "cn", "CNY", "STANDARD", 100, "ORIGINAL", "1.0.0", Map.of());

        PriceSourceParseResult result = adapter.parse(context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).isEmpty();
        assertThat(result.headlessRecommended()).isTrue();
        assertThat(result.warnings()).anyMatch(message -> message.contains("Headless Fetcher"));
    }
}
