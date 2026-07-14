package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(price.inputAmountPer1k()).isEqualByComparingTo(new BigDecimal("0.00014000"));
        assertThat(price.outputAmountPer1k()).isEqualByComparingTo(new BigDecimal("0.00028000"));
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
        assertThat(price.inputAmountPer1k()).isEqualByComparingTo("0.00125");
        assertThat(price.outputAmountPer1k()).isEqualByComparingTo("0.005");
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
        assertThat(prices.get(0).inputAmountPer1k()).isEqualByComparingTo("0.00014");
        assertThat(prices.get(0).outputAmountPer1k()).isEqualByComparingTo("0.00028");
        assertThat(prices.get(0).components()).containsKey("CACHE_READ_TOKEN");
        assertThat(prices.get(1).inputAmountPer1k()).isEqualByComparingTo("0.000435");
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
        assertThat(prices.get(0).inputAmountPer1k()).isEqualByComparingTo("0.0025");
        assertThat(prices.get(0).components()).containsKey("CACHE_READ_TOKEN");
    }
}
