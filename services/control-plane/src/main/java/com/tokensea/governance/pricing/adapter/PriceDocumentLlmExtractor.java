package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PriceDocumentLlmExtractor {
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final boolean enabled;
    private final int maxChars;

    @Autowired
    public PriceDocumentLlmExtractor(ObjectMapper json,
                                     @Value("${tokensea.price-document-llm.enabled:false}") boolean enabled,
                                     @Value("${tokensea.price-document-llm.url:}") String endpoint,
                                     @Value("${tokensea.price-document-llm.model:}") String model,
                                     @Value("${tokensea.price-document-llm.api-key:}") String apiKey,
                                     @Value("${tokensea.price-document-llm.max-chars:60000}") int maxChars,
                                     @Value("${tokensea.egress.proxy-host:}") String proxyHost,
                                     @Value("${tokensea.egress.proxy-port:18080}") int proxyPort) {
        this.json = json;
        this.enabled = enabled;
        this.endpoint = endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint);
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxChars = Math.max(5_000, Math.min(maxChars, 120_000));
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1);
        if (proxyHost != null && !proxyHost.isBlank()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.http = builder.build();
    }

    PriceDocumentLlmExtractor(ObjectMapper json) {
        this(json, false, "", "", "", 60_000, "", 18080);
    }

    public boolean available() {
        return enabled && endpoint != null && !model.isBlank() && !apiKey.isBlank();
    }

    public List<PriceSourceParser.NormalizedPrice> extract(PriceSourceAdapterContext context,
                                                           String documentText,
                                                           String sourceRef) {
        if (!available()) return List.of();
        String text = documentText == null ? "" : documentText.trim();
        if (text.isBlank()) return List.of();
        if (text.length() > maxChars) text = text.substring(0, maxChars);
        try {
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", 5000);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt()),
                    Map.of("role", "user", "content", userPrompt(context, sourceRef, text))));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("价格文档 LLM 返回 HTTP " + response.statusCode());
            }
            JsonNode root = json.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) content = root.path("output_text").asText("");
            if (content.isBlank()) throw new IllegalStateException("价格文档 LLM 未返回结构化结果");
            content = stripCodeFence(content);
            JsonNode extracted = json.readTree(content);
            JsonNode prices = extracted.path("prices");
            if (!prices.isArray()) throw new IllegalStateException("价格文档 LLM 响应缺少 prices 数组");
            List<PriceSourceParser.NormalizedPrice> result = new ArrayList<>();
            for (JsonNode item : prices) {
                PriceSourceParser.NormalizedPrice normalized = normalize(context, item, sourceRef);
                if (normalized != null) result.add(normalized);
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("价格文档 LLM 调用被中断", exception);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("价格文档 LLM 提取失败", exception);
        }
    }

    private PriceSourceParser.NormalizedPrice normalize(PriceSourceAdapterContext context,
                                                         JsonNode item,
                                                         String sourceRef) {
        String modelName = CatalogPriceAdapterSupport.text(item, "providerModelName", "model");
        if (modelName.isBlank()) return null;
        String provider = CatalogPriceAdapterSupport.value(
                CatalogPriceAdapterSupport.text(item, "providerType"), context.providerType());
        String currency = CatalogPriceAdapterSupport.value(
                CatalogPriceAdapterSupport.text(item, "currency"), context.defaultCurrency()).toUpperCase(Locale.ROOT);
        String basis = CatalogPriceAdapterSupport.value(
                CatalogPriceAdapterSupport.text(item, "billingBasis"), "TOKEN").toUpperCase(Locale.ROOT);
        long quantity = item.path("billingQuantity").asLong(
                "TOKEN".equals(basis) ? CatalogPriceAdapterSupport.MILLION_TOKENS : 1L);
        BigDecimal input = CatalogPriceAdapterSupport.decimal(item.get("inputUnitPrice"));
        BigDecimal output = CatalogPriceAdapterSupport.decimal(item.get("outputUnitPrice"));
        BigDecimal cacheRead = CatalogPriceAdapterSupport.decimal(item.get("cacheReadUnitPrice"));
        BigDecimal cacheWrite = CatalogPriceAdapterSupport.decimal(item.get("cacheWriteUnitPrice"));
        if (input == null && output == null && cacheRead == null && cacheWrite == null) return null;

        Map<String,Object> components = new LinkedHashMap<>();
        if (input != null) components.put("INPUT_TOKEN", CatalogPriceAdapterSupport.component(input, basis, quantity));
        if (output != null) components.put("OUTPUT_TOKEN", CatalogPriceAdapterSupport.component(output, basis, quantity));
        if (cacheRead != null) components.put("CACHE_READ_TOKEN", CatalogPriceAdapterSupport.component(cacheRead, basis, quantity));
        if (cacheWrite != null) components.put("CACHE_WRITE_TOKEN", CatalogPriceAdapterSupport.component(cacheWrite, basis, quantity));
        Map<String,Object> raw = json.convertValue(item, new TypeReference<>() {});
        raw.put("extractionMethod", "LLM_SCHEMA_MAPPING");
        return new PriceSourceParser.NormalizedPrice(
                provider,
                modelName,
                CatalogPriceAdapterSupport.value(CatalogPriceAdapterSupport.text(item, "displayName"), modelName),
                currency,
                basis,
                quantity,
                input,
                output,
                CatalogPriceAdapterSupport.value(CatalogPriceAdapterSupport.text(item, "region"), context.region()),
                CatalogPriceAdapterSupport.value(CatalogPriceAdapterSupport.text(item, "requestMode"), context.requestMode()),
                CatalogPriceAdapterSupport.value(CatalogPriceAdapterSupport.text(item, "serviceTier"), "DEFAULT"),
                CatalogPriceAdapterSupport.value(CatalogPriceAdapterSupport.text(item, "contextTier"), "DEFAULT"),
                components,
                sourceRef,
                OffsetDateTime.now(),
                null,
                raw);
    }

    private String systemPrompt() {
        return """
                你是价格文档结构化抽取器。只提取文档中明确出现、可由原文证据支持的模型价格，不得推测、补全或换算缺失价格。
                输出必须是 JSON 对象，顶层只有 prices 数组。每条记录字段：providerType、providerModelName、displayName、currency、
                billingBasis、billingQuantity、inputUnitPrice、outputUnitPrice、cacheReadUnitPrice、cacheWriteUnitPrice、region、requestMode、
                serviceTier、contextTier、evidence。金额必须是数字或 null；Token 价格优先统一为每百万 Token，billingQuantity=1000000。
                无法确认模型标识、币种、计费单位或至少一个价格时不要输出该记录。促销、免费额度、合同折扣必须在 evidence 中说明，不要混入普通公开价。
                """;
    }

    private String userPrompt(PriceSourceAdapterContext context, String sourceRef, String text) {
        return """
                供应商类型：%s
                默认币种：%s
                默认区域：%s
                默认调用模式：%s
                来源：%s

                价格文档正文：
                %s
                """.formatted(
                CatalogPriceAdapterSupport.value(context.providerType(), "unknown"),
                CatalogPriceAdapterSupport.value(context.defaultCurrency(), "USD"),
                CatalogPriceAdapterSupport.value(context.region(), "global"),
                CatalogPriceAdapterSupport.value(context.requestMode(), "STANDARD"),
                sourceRef,
                text);
    }

    private String stripCodeFence(String value) {
        String text = value.trim();
        if (!text.startsWith("```")) return text;
        int firstLine = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstLine >= 0 && lastFence > firstLine) return text.substring(firstLine + 1, lastFence).trim();
        return text;
    }
}
