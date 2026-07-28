package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceSourceParserTests {
    private final PriceSourceParser parser = new PriceSourceParser(new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesLiteLlmCostMapAndNormalizesPerTokenPrices() {
        String source = """
            {
              "sample_spec": {"input_cost_per_token": 0},
              "deepseek/deepseek-chat": {
                "litellm_provider": "deepseek",
                "mode": "chat",
                "input_cost_per_token": 0.00000014,
                "output_cost_per_token": 0.00000028,
                "cache_read_input_token_cost": 0.000000014,
                "source": "https://api-docs.deepseek.com/quick_start/pricing"
              }
            }
            """;

        var prices = parser.parse("LITELLM_COST_MAP", source, "https://example.test/map.json",
                null, "USD", Map.of());

        assertThat(prices).hasSize(1);
        var price = prices.get(0);
        assertThat(price.providerType()).isEqualTo("deepseek");
        assertThat(price.providerModelName()).isEqualTo("deepseek/deepseek-chat");
        assertThat(price.billingBasis()).isEqualTo("TOKEN");
        assertThat(price.billingQuantity()).isEqualTo(1_000_000L);
        assertThat(price.inputUnitPrice()).isEqualByComparingTo(new BigDecimal("0.14"));
        assertThat(price.outputUnitPrice()).isEqualByComparingTo(new BigDecimal("0.28"));
        assertThat(price.components()).containsKeys("INPUT_TOKEN", "OUTPUT_TOKEN", "CACHE_READ_TOKEN");
    }

    @Test
    void parsesOfficialJsonWithConfiguredPathsAndMillionTokenUnit() {
        String source = """
            {"data":[{"model":"model-a","input":1.25,"output":5,"currency":"USD"}]}
            """;
        Map<String,Object> config = Map.of(
                "recordsPath", "data",
                "modelField", "model",
                "inputField", "input",
                "outputField", "output",
                "currencyField", "currency",
                "unit", "PER_1M_TOKENS"
        );

        var prices = parser.parse("OFFICIAL_JSON", source, "https://provider.example/prices",
                "provider-a", "USD", config);

        assertThat(prices).hasSize(1);
        var price = prices.get(0);
        assertThat(price.providerModelName()).isEqualTo("model-a");
        assertThat(price.billingBasis()).isEqualTo("TOKEN");
        assertThat(price.billingQuantity()).isEqualTo(1_000_000L);
        assertThat(price.inputUnitPrice()).isEqualByComparingTo("1.25");
        assertThat(price.outputUnitPrice()).isEqualByComparingTo("5");
    }

    @Test
    void parsesDeepSeekOfficialPricingTable() {
        String source = """
            <html><body><table>
              <tr><th>MODEL</th><th>deepseek-v4-flash(1)</th><th>deepseek-v4-pro</th></tr>
              <tr><td>1M INPUT TOKENS (CACHE HIT)</td><td>$0.0028</td><td>$0.003625</td></tr>
              <tr><td>1M INPUT TOKENS (CACHE MISS)</td><td>$0.14</td><td>$0.435</td></tr>
              <tr><td>1M OUTPUT TOKENS</td><td>$0.28</td><td>$0.87</td></tr>
            </table></body></html>
            """;

        var prices = parser.parse("DEEPSEEK_OFFICIAL_PAGE", source,
                "https://api-docs.deepseek.com/quick_start/pricing/", "deepseek", "USD", Map.of());

        assertThat(prices).hasSize(2);
        assertThat(prices.get(0).providerModelName()).isEqualTo("deepseek-v4-flash");
        assertThat(prices.get(0).currency()).isEqualTo("USD");
        assertThat(prices.get(0).region()).isEqualTo("global");
        assertThat(prices.get(0).inputUnitPrice()).isEqualByComparingTo("0.14");
        assertThat(prices.get(0).outputUnitPrice()).isEqualByComparingTo("0.28");
        assertThat(prices.get(0).components()).containsKey("CACHE_READ_TOKEN");
        assertThat(prices.get(1).inputUnitPrice()).isEqualByComparingTo("0.435");
    }

    @Test
    void parsesDeepSeekChineseOfficialPricingTableAsCny() {
        String source = """
            <html><body><table>
              <tr><th>模型</th><th>deepseek-v4-flash(1)</th><th>deepseek-v4-pro</th></tr>
              <tr><td rowspan="3">价格</td><td>百万 tokens 输入（缓存命中）</td><td>0.02元</td><td>0.025元</td></tr>
              <tr><td>百万 tokens 输入（缓存未命中）</td><td>1元</td><td>3元</td></tr>
              <tr><td>百万 tokens 输出</td><td>2元</td><td>6元</td></tr>
            </table></body></html>
            """;

        var prices = parser.parse("DEEPSEEK_OFFICIAL_PAGE", source,
                "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/", "deepseek", "CNY", Map.of());

        assertThat(prices).hasSize(2);
        assertThat(prices.get(0).providerModelName()).isEqualTo("deepseek-v4-flash");
        assertThat(prices.get(0).currency()).isEqualTo("CNY");
        assertThat(prices.get(0).region()).isEqualTo("global");
        assertThat(prices.get(0).inputUnitPrice()).isEqualByComparingTo("1");
        assertThat(prices.get(0).outputUnitPrice()).isEqualByComparingTo("2");
        assertThat(((Map<?,?>) prices.get(0).components().get("CACHE_READ_TOKEN")).get("unitPrice"))
                .isEqualTo(new BigDecimal("0.02"));
        assertThat(prices.get(1).inputUnitPrice()).isEqualByComparingTo("3");
        assertThat(prices.get(1).outputUnitPrice()).isEqualByComparingTo("6");
    }

    @Test
    void rejectsDeepSeekPageWhenDetectedCurrencyConflictsWithSourceConfiguration() {
        String source = """
            <html><body><table>
              <tr><th>模型</th><th>deepseek-v4-flash</th></tr>
              <tr><td>百万 tokens 输入（缓存命中）</td><td>0.02元</td></tr>
              <tr><td>百万 tokens 输入（缓存未命中）</td><td>1元</td></tr>
              <tr><td>百万 tokens 输出</td><td>2元</td></tr>
            </table></body></html>
            """;

        assertThatThrownBy(() -> parser.parse("DEEPSEEK_OFFICIAL_PAGE", source,
                "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/", "deepseek", "USD", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("与价格源默认币种 USD 不一致");
    }

    @Test
    void parsesDeepSeekChineseRenderedTextFallback() {
        String source = """
            <html><body>
              模型 deepseek-v4-flash(1) deepseek-v4-pro BASE URL https://api.deepseek.com
              价格 百万tokens输入（缓存命中） 0.02元 0.025元
              百万tokens输入（缓存未命中） 1元 3元
              百万tokens输出 2元 6元 并发限制 2500 500
            </body></html>
            """;

        var prices = parser.parse("DEEPSEEK_OFFICIAL_PAGE", source,
                "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/", "deepseek", "CNY", Map.of());

        assertThat(prices).hasSize(2);
        assertThat(prices.get(0).currency()).isEqualTo("CNY");
        assertThat(prices.get(0).inputUnitPrice()).isEqualByComparingTo("1");
        assertThat(prices.get(1).outputUnitPrice()).isEqualByComparingTo("6");
    }

    @Test
    void parsesModelsDevProviderModelShape() {
        String source = """
            {
              "openai": {
                "id":"openai",
                "models": {
                  "gpt-test": {
                    "name":"GPT Test",
                    "cost":{"input":2.5,"output":10,"cache_read":1.25}
                  }
                }
              }
            }
            """;

        var prices = parser.parse("MODELS_DEV", source, "https://models.dev/api.json",
                null, "USD", Map.of());

        assertThat(prices).hasSize(1);
        assertThat(prices.get(0).providerType()).isEqualTo("openai");
        assertThat(prices.get(0).inputUnitPrice()).isEqualByComparingTo("2.5");
        assertThat(prices.get(0).components()).containsKey("CACHE_READ_TOKEN");
    }
}
