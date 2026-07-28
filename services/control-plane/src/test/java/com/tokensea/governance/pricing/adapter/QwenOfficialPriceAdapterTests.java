package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class QwenOfficialPriceAdapterTests {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final QwenOfficialPriceAdapter adapter = new QwenOfficialPriceAdapter(json);

    @Test
    void parsesChinaRealtimeTokenPriceWithCacheEvidence() {
        String html = """
            <html><head><title>百炼模型价格</title></head><body>
              <h2>中国内地 文本生成-千问 价格（每百万 Token）</h2>
              <table id="qwen-cn">
                <tr><th>模型 ID</th><th>输入价格</th><th>输出价格</th><th>缓存命中价格</th></tr>
                <tr><td>qwen-plus</td><td>0.8元</td><td>2元</td><td>0.2元</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                "qwen-source", "QWEN_OFFICIAL_PAGE", "qwen", "https://help.aliyun.com/zh/model-studio/model-pricing",
                "cn", "CNY", "STANDARD", 100, "ORIGINAL", "1.0.0", Map.of());

        PriceSourceParseResult result = adapter.parse(context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).hasSize(1);
        var price = result.prices().getFirst();
        assertThat(price.providerType()).isEqualTo("qwen");
        assertThat(price.providerModelName()).isEqualTo("qwen-plus");
        assertThat(price.currency()).isEqualTo("CNY");
        assertThat(price.billingQuantity()).isEqualTo(1_000_000L);
        assertThat(price.inputUnitPrice()).isEqualByComparingTo("0.8");
        assertThat(price.outputUnitPrice()).isEqualByComparingTo("2");
        assertThat(price.components()).containsKeys("INPUT_TOKEN", "OUTPUT_TOKEN", "CACHE_READ_TOKEN");
        assertThat(price.raw()).containsEntry("priceNature", "ORIGINAL");
        assertThat(price.raw().get("sourceEvidencePath")).isEqualTo("table#qwen-cn/row[1]");
        assertThat(result.structureFingerprint()).hasSize(64);
        assertThat(result.parseStatus()).isEqualTo("PRICE_PARSED");
        assertThat(result.tableCount()).isEqualTo(1);
        assertThat(result.matchedTableCount()).isEqualTo(1);
        assertThat(result.generatedPriceCount()).isEqualTo(1);
        assertThat(result.headlessRecommended()).isFalse();
    }

    @Test
    void ignoresInternationalTableWhenSourceRegionIsChina() {
        String html = """
            <html><body>
              <h2>国际（新加坡）千问 每百万 Token</h2>
              <table><tr><th>模型 ID</th><th>输入价格</th><th>输出价格</th></tr>
                <tr><td>qwen-plus</td><td>$1</td><td>$3</td></tr></table>
              <h2>中国内地千问 每百万 Token</h2>
              <table><tr><th>模型 ID</th><th>输入价格</th><th>输出价格</th></tr>
                <tr><td>qwen-turbo</td><td>0.3元</td><td>0.6元</td></tr></table>
            </body></html>
            """;
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                "qwen-source", "QWEN_OFFICIAL_PAGE", "qwen", "https://help.aliyun.com/zh/model-studio/model-pricing",
                "cn", "CNY", "STANDARD", 100, "ORIGINAL", "1.0.0", Map.of());

        PriceSourceParseResult result = adapter.parse(context,
                new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"));

        assertThat(result.prices()).extracting(price -> price.providerModelName())
                .containsExactly("qwen-turbo");
    }

    @Test
    void parsesTieredQwenInputAndOfficialContextCacheRates() {
        String html = """
            <html><body>中国内地 千问 每百万 Token
              <table><tr><th rowspan="2">模型 ID</th><th rowspan="2">单次请求的输入 Token 范围</th>
                <th rowspan="2">输入单价（每百万 Token）</th><th>输出单价（每百万 Token）</th></tr>
                <tr><th>非思考模式</th></tr>
                <tr><td rowspan="1">qwen3.7-plus 上下文缓存享有折扣</td><td>0&lt;Token≤256K</td><td>2元</td><td>8元</td></tr>
              </table>
            </body></html>
            """;
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                "qwen-source", "QWEN_OFFICIAL_PAGE", "qwen", "https://help.aliyun.com/zh/model-studio/model-pricing",
                "cn", "CNY", "STANDARD", 100, "ORIGINAL", "1.0.0", Map.of());

        var price = adapter.parse(context, new PriceSourceDocument(html, context.endpoint(), "text/html", "fixture"))
                .prices().getFirst();

        assertThat(price.inputUnitPrice()).isEqualByComparingTo("2");
        assertThat(price.outputUnitPrice()).isEqualByComparingTo("8");
        assertThat(componentPrice(price.components(), "CACHE_READ_TOKEN")).isEqualByComparingTo("0.2");
        assertThat(componentPrice(price.components(), "CACHE_WRITE_TOKEN")).isEqualByComparingTo("2.5");
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal componentPrice(Map<String, Object> components, String type) {
        return (BigDecimal) ((Map<String, Object>) components.get(type)).get("unitPrice");
    }
}
