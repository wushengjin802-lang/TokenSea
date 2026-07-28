package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingComponentServiceTests {
    private final PricingComponentService service = new PricingComponentService(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void normalizesFourPricesAndCalculatesCompleteCacheStatus() {
        List<Map<String,Object>> components = service.normalize(
                new BigDecimal("2.5"), new BigDecimal("0.5"), new BigDecimal("3.0"),
                new BigDecimal("10"), "EXPLICIT", "EXPLICIT", "TOKEN", 1_000_000L,
                List.of(), "https://provider.example/pricing");

        PricingComponentService.Summary summary = service.summarize(
                components, new BigDecimal("2.5"), new BigDecimal("10"));

        assertThat(components).hasSize(4);
        assertThat(summary.cacheReadUnitPrice()).isEqualByComparingTo("0.5");
        assertThat(summary.cacheWriteUnitPrice()).isEqualByComparingTo("3.0");
        assertThat(summary.priceCompletenessStatus()).isEqualTo("COMPLETE");
        assertThat(summary.cachePricingStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void distinguishesUnsupportedCacheFromUnknownCachePrice() {
        PricingComponentService.Summary unsupported = service.summarize(
                service.normalize(new BigDecimal("1"), null, null, new BigDecimal("2"),
                        "NOT_APPLICABLE", "NOT_APPLICABLE", "TOKEN", 1_000_000L,
                        List.of(), "manual://test"), new BigDecimal("1"), new BigDecimal("2"));
        PricingComponentService.Summary unknown = service.summarize(
                service.normalize(new BigDecimal("1"), null, null, new BigDecimal("2"),
                        "UNKNOWN", "UNKNOWN", "TOKEN", 1_000_000L,
                        List.of(), "manual://test"), new BigDecimal("1"), new BigDecimal("2"));

        assertThat(unsupported.priceCompletenessStatus()).isEqualTo("UNSUPPORTED_CACHE");
        assertThat(unknown.priceCompletenessStatus()).isEqualTo("UNKNOWN_CACHE_PRICE");
    }

    @Test
    void supportsMultipleCacheWriteVariantsWithDifferentScopes() {
        List<PricingComponentService.ComponentInput> advanced = List.of(
                new PricingComponentService.ComponentInput(
                        "CACHE_WRITE_TOKEN", "TTL_5M", new BigDecimal("3"), "TOKEN", 1_000_000L,
                        "EXPLICIT", Map.of("cacheTtlSeconds", 300), 50,
                        "https://provider.example/pricing", Map.of()),
                new PricingComponentService.ComponentInput(
                        "CACHE_WRITE_TOKEN", "TTL_1H", new BigDecimal("4"), "TOKEN", 1_000_000L,
                        "EXPLICIT", Map.of("cacheTtlSeconds", 3600), 50,
                        "https://provider.example/pricing", Map.of()));

        List<Map<String,Object>> components = service.normalize(
                new BigDecimal("1"), new BigDecimal("0.1"), null, new BigDecimal("2"),
                "EXPLICIT", "NOT_APPLICABLE", "TOKEN", 1_000_000L,
                advanced, "https://provider.example/pricing");

        assertThat(components.stream()
                .filter(component -> "CACHE_WRITE_TOKEN".equals(component.get("componentType"))))
                .hasSize(3);
        assertThat(service.summarize(components, BigDecimal.ONE, new BigDecimal("2"))
                .cacheWriteVariantCount()).isEqualTo(3);
    }

    @Test
    void parsedCacheWriteCanInheritUncachedInputPrice() {
        Map<String,Object> parserComponents = Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", new BigDecimal("6.50"), "unitBasis", "TOKEN",
                        "unitQuantity", 1_000_000L, "mode", "EXPLICIT"),
                "CACHE_READ_TOKEN", Map.of("unitPrice", new BigDecimal("1.10"), "unitBasis", "TOKEN",
                        "unitQuantity", 1_000_000L, "mode", "EXPLICIT"),
                "CACHE_WRITE_TOKEN", Map.of("unitBasis", "TOKEN", "unitQuantity", 1_000_000L,
                        "mode", "INHERIT_INPUT"),
                "OUTPUT_TOKEN", Map.of("unitPrice", new BigDecimal("27.00"), "unitBasis", "TOKEN",
                        "unitQuantity", 1_000_000L, "mode", "EXPLICIT"));

        List<Map<String,Object>> components = service.normalizeParsed(
                new BigDecimal("6.50"), new BigDecimal("27.00"), "moonshot",
                "TOKEN", 1_000_000L, parserComponents, "https://platform.kimi.com/docs/pricing/chat-k26");
        PricingComponentService.Summary summary = service.summarize(
                components, new BigDecimal("6.50"), new BigDecimal("27.00"));

        assertThat(summary.cacheWriteMode()).isEqualTo("INHERIT_INPUT");
        assertThat(summary.cacheWriteUnitPrice()).isEqualByComparingTo("6.50");
        assertThat(summary.priceCompletenessStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void usesScopedExplicitCacheComponentsWhenDefaultCacheModeIsUnknown() {
        Map<String,Object> parserComponents = Map.of(
                "CACHE_READ_TOKEN", List.of(Map.of("variant", "0_256000", "unitPrice", new BigDecimal("0.2"),
                        "unitBasis", "TOKEN", "unitQuantity", 1_000_000L, "mode", "EXPLICIT",
                        "scope", Map.of("maxInputTokensInclusive", 256000))),
                "CACHE_WRITE_TOKEN", List.of(Map.of("variant", "0_256000", "unitPrice", new BigDecimal("2.5"),
                        "unitBasis", "TOKEN", "unitQuantity", 1_000_000L, "mode", "EXPLICIT",
                        "scope", Map.of("maxInputTokensInclusive", 256000))));

        List<Map<String,Object>> components = service.normalizeParsed(
                new BigDecimal("2"), new BigDecimal("8"), "qwen",
                "TOKEN", 1_000_000L, parserComponents, "https://help.aliyun.com/zh/model-studio/model-pricing");
        PricingComponentService.Summary summary = service.summarize(
                components, new BigDecimal("2"), new BigDecimal("8"));

        assertThat(summary.cacheReadUnitPrice()).isEqualByComparingTo("0.2");
        assertThat(summary.cacheWriteUnitPrice()).isEqualByComparingTo("2.5");
        assertThat(summary.cacheWriteMode()).isEqualTo("EXPLICIT");
        assertThat(summary.priceCompletenessStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void readsComponentsFromPostgresJsonbValue() throws Exception {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue("""
                [{"componentType":"INPUT_TOKEN","variant":"DEFAULT","unitPrice":6.5,
                  "unitBasis":"TOKEN","unitQuantity":1000000,"mode":"EXPLICIT",
                  "priority":100,"scope":{},"sourceRef":"https://platform.kimi.com","metadata":{}}]
                """);

        List<Map<String,Object>> components = service.readComponents(jsonb);

        assertThat(components).hasSize(1);
        assertThat(components.getFirst().get("componentType")).isEqualTo("INPUT_TOKEN");
        assertThat(String.valueOf(components.getFirst().get("unitPrice"))).isEqualTo("6.5");
    }

    @Test
    void rejectsDuplicateComponentVariantAndScope() {
        PricingComponentService.ComponentInput duplicate = new PricingComponentService.ComponentInput(
                "CACHE_READ_TOKEN", "ABOVE_200K", new BigDecimal("0.2"), "TOKEN", 1_000_000L,
                "EXPLICIT", Map.of("minContextTokens", 200000), 50,
                "https://provider.example/pricing", Map.of());

        assertThatThrownBy(() -> service.normalize(
                BigDecimal.ONE, new BigDecimal("0.1"), null, new BigDecimal("2"),
                "EXPLICIT", "NOT_APPLICABLE", "TOKEN", 1_000_000L,
                List.of(duplicate, duplicate), "https://provider.example/pricing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("只能配置一次");
    }

    @Test
    void rejectsUnknownModeWithPriceValue() {
        assertThatThrownBy(() -> service.normalize(
                BigDecimal.ONE, new BigDecimal("0.1"), null, new BigDecimal("2"),
                "UNKNOWN", "NOT_APPLICABLE", "TOKEN", 1_000_000L,
                List.of(), "manual://test"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能填写单价");
    }
}
