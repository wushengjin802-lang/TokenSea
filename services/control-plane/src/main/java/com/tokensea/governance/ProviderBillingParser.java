package com.tokensea.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ProviderBillingParser {
    private final ObjectMapper json;

    public ProviderBillingParser(ObjectMapper json) {
        this.json = json;
    }

    public record BillingRecord(
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            String currency,
            BigDecimal amount,
            Long inputTokens,
            Long outputTokens,
            Long requestCount,
            String lineItem,
            String providerModelName,
            String providerProjectId,
            String sourceRef,
            Map<String,Object> raw
    ) {}

    public List<BillingRecord> parse(String adapterCode,
                                     String content,
                                     String sourceRef,
                                     String defaultCurrency,
                                     Map<String,Object> config) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("供应商账单响应为空");
        return switch (adapterCode) {
            case "OPENAI_COSTS_API" -> parseOpenAi(content, sourceRef, defaultCurrency);
            case "GENERIC_BILLING_JSON" -> parseGeneric(content, sourceRef, defaultCurrency,
                    config == null ? Map.of() : config);
            default -> throw new IllegalArgumentException("不支持的供应商账单适配器: " + adapterCode);
        };
    }

    private List<BillingRecord> parseOpenAi(String content, String sourceRef, String defaultCurrency) {
        try {
            JsonNode root = json.readTree(content);
            JsonNode buckets = root.path("data");
            if (!buckets.isArray()) throw new IllegalArgumentException("OpenAI Costs 响应缺少 data 数组");
            List<BillingRecord> result = new ArrayList<>();
            for (JsonNode bucket : buckets) {
                OffsetDateTime start = epoch(bucket.path("start_time"));
                OffsetDateTime end = epoch(bucket.path("end_time"));
                if (start == null || end == null || !end.isAfter(start)) continue;
                JsonNode records = bucket.path("results");
                if (!records.isArray()) continue;
                for (JsonNode item : records) {
                    BigDecimal amount = decimal(item.path("amount").get("value"));
                    if (amount == null || amount.signum() < 0) continue;
                    String currency = text(item.path("amount").get("currency"));
                    if (currency.isBlank()) currency = defaultCurrency;
                    result.add(new BillingRecord(
                            start,
                            end,
                            currency.toUpperCase(Locale.ROOT),
                            amount,
                            longNullable(item.get("input_tokens")),
                            longNullable(item.get("output_tokens")),
                            longNullable(item.get("num_model_requests")),
                            nullable(item.get("line_item")),
                            nullable(item.get("model")),
                            nullable(item.get("project_id")),
                            sourceRef,
                            json.convertValue(item, Map.class)));
                }
            }
            return result;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("OpenAI Costs 响应解析失败", exception);
        }
    }

    private List<BillingRecord> parseGeneric(String content,
                                             String sourceRef,
                                             String defaultCurrency,
                                             Map<String,Object> config) {
        try {
            JsonNode root = json.readTree(content);
            JsonNode records = at(root, value(config.get("recordsPath"), "data"));
            if (records == null || !records.isArray()) {
                throw new IllegalArgumentException("通用账单 JSON 的 recordsPath 未指向数组");
            }
            List<BillingRecord> result = new ArrayList<>();
            for (JsonNode item : records) {
                OffsetDateTime start = time(at(item, value(config.get("periodStartField"), "period_start")));
                OffsetDateTime end = time(at(item, value(config.get("periodEndField"), "period_end")));
                BigDecimal amount = decimal(at(item, value(config.get("amountField"), "amount")));
                if (start == null || end == null || !end.isAfter(start) || amount == null || amount.signum() < 0) continue;
                String currency = text(at(item, value(config.get("currencyField"), "currency")));
                if (currency.isBlank()) currency = defaultCurrency;
                result.add(new BillingRecord(
                        start,
                        end,
                        currency.toUpperCase(Locale.ROOT),
                        amount,
                        longNullable(at(item, value(config.get("inputTokensField"), "input_tokens"))),
                        longNullable(at(item, value(config.get("outputTokensField"), "output_tokens"))),
                        longNullable(at(item, value(config.get("requestCountField"), "request_count"))),
                        nullable(at(item, value(config.get("lineItemField"), "line_item"))),
                        nullable(at(item, value(config.get("modelField"), "model"))),
                        nullable(at(item, value(config.get("projectField"), "project_id"))),
                        sourceRef,
                        json.convertValue(item, Map.class)));
            }
            return result;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("通用供应商账单 JSON 解析失败", exception);
        }
    }

    private JsonNode at(JsonNode node, String path) {
        if (node == null || path == null || path.isBlank()) return node;
        JsonNode current = node;
        for (String part : path.split("\\.")) current = current == null ? null : current.get(part);
        return current;
    }

    private OffsetDateTime epoch(JsonNode node) {
        if (node == null || !node.canConvertToLong()) return null;
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(node.asLong()), ZoneOffset.UTC);
    }

    private OffsetDateTime time(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.canConvertToLong() && !node.isTextual()) return epoch(node);
        String value = node.asText("").trim();
        if (value.isBlank()) return null;
        try {
            if (value.matches("^[0-9]+$")) return OffsetDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(value)), ZoneOffset.UTC);
            return OffsetDateTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isObject()) return decimal(node.get("value"));
        try {
            return new BigDecimal(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long longNullable(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return Long.parseLong(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String nullable(JsonNode node) {
        String value = text(node);
        return value.isBlank() ? null : value;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "" : node.asText("").trim();
    }

    private String value(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }
}
