package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XiaomiMimoOfficialPriceAdapterTests {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final XiaomiMimoOfficialPriceAdapter adapter = new XiaomiMimoOfficialPriceAdapter(json);

    @Test
    void parsesDomesticTextTokenPricesAndExplicitFreeCacheWrite() {
        String html = """
            <html><head><title>Xiaomi MiMo API 定价</title></head><body>
              <p>计费单位：国内：元 / 百万 tokens；海外：美元 / 百万 tokens</p>
              <p>缓存写入：限时免费</p>
              <h3>模型国内定价</h3>
              <h4>MiMo-V2.5 系列</h4>
              <table id="mimo-cn">
                <tr><th></th><th>输入（命中缓存）</th><th>输入（未命中缓存）</th><th>输出</th></tr>
                <tr><td><code>mimo-v2.5-pro</code></td><td>¥0.025</td><td>¥3.00</td><td>¥6.00</td></tr>
                <tr><td><code>mimo-v2.5</code></td><td>¥0.02</td><td>¥1.00</td><td>¥2.00</td></tr>
              </table>
              <h3>模型海外定价</h3>
              <h4>MiMo-V2.5 系列</h4>
              <table id="mimo-global">
                <tr><th></th><th>输入（命中缓存）</th><th>输入（未命中缓存）</th><th>输出</th></tr>
                <tr><td><code>mimo-v2.5-pro</code></td><td>$0.0036</td><td>$0.435</td><td>$0.87</td></tr>
                <tr><td><code>mimo-v2.5</code></td><td>$0.0028</td><td>$0.14</td><td>$0.28</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("cn", "CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(2);
        var pro = result.prices().stream()
                .filter(price -> price.providerModelName().equals("mimo-v2.5-pro"))
                .findFirst().orElseThrow();
        var standard = result.prices().stream()
                .filter(price -> price.providerModelName().equals("mimo-v2.5"))
                .findFirst().orElseThrow();

        assertThat(pro.providerType()).isEqualTo("xiaomi_mimo");
        assertThat(pro.currency()).isEqualTo("CNY");
        assertThat(pro.region()).isEqualTo("cn");
        assertThat(pro.billingQuantity()).isEqualTo(1_000_000L);
        assertThat(pro.inputUnitPrice()).isEqualByComparingTo("3.00");
        assertThat(pro.outputUnitPrice()).isEqualByComparingTo("6.00");
        assertThat(standard.inputUnitPrice()).isEqualByComparingTo("1.00");
        assertThat(standard.outputUnitPrice()).isEqualByComparingTo("2.00");

        Map<?,?> cacheRead = (Map<?,?>) pro.components().get("CACHE_READ_TOKEN");
        Map<?,?> cacheWrite = (Map<?,?>) pro.components().get("CACHE_WRITE_TOKEN");
        assertThat(cacheRead.get("unitPrice")).isEqualTo(new BigDecimal("0.025"));
        assertThat(cacheWrite.get("unitPrice")).isEqualTo(BigDecimal.ZERO);
        assertThat(cacheWrite.get("mode")).isEqualTo("EXPLICIT_ZERO");
        assertThat(pro.raw()).containsEntry("cacheWriteLimitedTimeFree", true);
        assertThat(result.parseStatus()).isEqualTo("PRICE_PARSED");
        assertThat(result.tableCount()).isEqualTo(2);
        assertThat(result.matchedTableCount()).isEqualTo(1);
        assertThat(result.generatedPriceCount()).isEqualTo(2);
    }

    @Test
    void parsesOverseasPricesWhenUsdSourceIsConfigured() {
        String html = """
            <html><head><title>Xiaomi MiMo API Pricing</title></head><body>
              <p>国内：元 / 百万 tokens；海外：美元 / 百万 tokens</p>
              <h3>模型国内定价</h3>
              <table>
                <tr><th>模型</th><th>输入（命中缓存）</th><th>输入（未命中缓存）</th><th>输出</th></tr>
                <tr><td>mimo-v2.5-pro</td><td>¥0.025</td><td>¥3.00</td><td>¥6.00</td></tr>
              </table>
              <h3>模型海外定价</h3>
              <table>
                <tr><th>模型</th><th>输入（命中缓存）</th><th>输入（未命中缓存）</th><th>输出</th></tr>
                <tr><td>mimo-v2.5-pro</td><td>$0.0036</td><td>$0.435</td><td>$0.87</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("global", "USD");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(1);
        var price = result.prices().getFirst();
        assertThat(price.currency()).isEqualTo("USD");
        assertThat(price.region()).isEqualTo("global");
        assertThat(price.inputUnitPrice()).isEqualByComparingTo("0.435");
        assertThat(price.outputUnitPrice()).isEqualByComparingTo("0.87");
        Map<?,?> cacheRead = (Map<?,?>) price.components().get("CACHE_READ_TOKEN");
        assertThat(cacheRead.get("unitPrice")).isEqualTo(new BigDecimal("0.0036"));
    }

    @Test
    void ignoresAsrTtsAndSearchPricesInsteadOfProducingZeroTokenPrices() {
        String html = """
            <html><head><title>Xiaomi MiMo API 定价</title></head><body>
              <p>计费单位：国内：元 / 百万 tokens</p>
              <h3>ASR 系列</h3>
              <table>
                <tr><th></th><th>输入音频时长</th></tr>
                <tr><td>mimo-v2.5-asr</td><td>¥0.5 /小时</td></tr>
              </table>
              <h3>TTS 系列</h3>
              <p>mimo-v2.5-tts、mimo-v2.5-tts-voiceclone 限时免费</p>
              <h3>联网服务插件定价</h3>
              <table>
                <tr><th>服务项</th><th>价格</th></tr>
                <tr><td>国内联网服务</td><td>¥16 /1000 次</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("cn", "CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).isEmpty();
        assertThat(result.skippedTableCount()).isEqualTo(1);
        assertThat(result.warnings()).anyMatch(message -> message.contains("未从 Xiaomi MiMo 官方页面解析出"));
    }

    @Test
    void supportsOfficialTableWhoseFirstHeaderCellIsOmitted() {
        String html = """
            <html><head><title>Xiaomi MiMo API 定价</title></head><body>
              <p>国内：元 / 百万 tokens</p>
              <h3>模型国内定价</h3>
              <table>
                <tr><th>输入（命中缓存）</th><th>输入（未命中缓存）</th><th>输出</th></tr>
                <tr><td>mimo-v2.5</td><td>¥0.02</td><td>¥1.00</td><td>¥2.00</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("cn", "CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(1);
        assertThat(result.prices().getFirst().inputUnitPrice()).isEqualByComparingTo("1.00");
        assertThat(result.prices().getFirst().outputUnitPrice()).isEqualByComparingTo("2.00");
    }

    @Test
    void parsesCurrentOfficialPageWhenLivePriceTestIsEnabled() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(
                System.getenv().getOrDefault("TOKENSEA_LIVE_PRICE_TESTS", "false")));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://mimo.mi.com/docs/zh-CN/price/pay-as-you-go"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "TokenSea-PriceParser-Test/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Assumptions.assumeTrue(response.statusCode() == 200, "Xiaomi MiMo 官方价格页当前不可访问");
        PriceSourceAdapterContext context = context("cn", "CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(response.body(), context.endpoint(),
                        response.headers().firstValue("Content-Type").orElse("text/html"), "live"));

        assertThat(result.prices()).extracting(price -> price.providerModelName())
                .contains("mimo-v2.5", "mimo-v2.5-pro");
        assertThat(result.prices()).allSatisfy(price -> {
            assertThat(price.currency()).isEqualTo("CNY");
            assertThat(price.inputUnitPrice()).isPositive();
            assertThat(price.outputUnitPrice()).isPositive();
            assertThat(price.components()).containsKeys(
                    "INPUT_TOKEN", "CACHE_READ_TOKEN", "CACHE_WRITE_TOKEN", "OUTPUT_TOKEN");
        });
    }

    @Test
    void recommendsHeadlessFetcherWhenRenderedPageContainsNoTable() {
        String html = """
            <html><head><title>Xiaomi MiMo API 定价</title></head><body>
              <div id="app">加载中</div>
              <script></script><script></script><script></script>
              <script></script><script></script><script></script>
            </body></html>
            """;
        PriceSourceAdapterContext context = context("cn", "CNY");

        PriceSourceParseResult result = adapter.parse(
                context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).isEmpty();
        assertThat(result.headlessRecommended()).isTrue();
        assertThat(result.warnings()).anyMatch(message -> message.contains("Headless Fetcher"));
    }

    private PriceSourceAdapterContext context(String region, String currency) {
        return new PriceSourceAdapterContext(
                "mimo-source",
                XiaomiMimoOfficialPriceAdapter.ADAPTER_CODE,
                "xiaomi_mimo",
                "https://mimo.mi.com/docs/zh-CN/price/pay-as-you-go",
                region,
                currency,
                "STANDARD",
                100,
                "ORIGINAL",
                "1.0.0",
                Map.of("official", true, "scope", "TEXT_REALTIME_STANDARD"));
    }
}
