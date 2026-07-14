package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PriceSourceParser {
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private final ObjectMapper json;

    public PriceSourceParser(ObjectMapper json) {
        this.json = json;
    }

    public record NormalizedPrice(
            String providerType,
            String providerModelName,
            String displayName,
            String currency,
            BigDecimal inputAmountPer1k,
            BigDecimal outputAmountPer1k,
            String region,
            String requestMode,
            String serviceTier,
            String contextTier,
            Map<String,Object> components,
            String sourceRef,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            Map<String,Object> raw
    ) {}

    public List<NormalizedPrice> parse(String adapterCode, String content, String endpoint,
                                       String configuredProvider, String defaultCurrency,
                                       Map<String,Object> config) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("价格来源内容为空");
        return switch (adapterCode) {
            case "LITELLM_COST_MAP" -> parseLiteLlm(content, endpoint, defaultCurrency);
            case "MODELS_DEV" -> parseModelsDev(content, endpoint, defaultCurrency);
            case "DEEPSEEK_OFFICIAL_PAGE" -> parseDeepSeekOfficialPage(content, endpoint);
            case "OFFICIAL_JSON" -> parseOfficialJson(content, endpoint, configuredProvider, defaultCurrency, config);
            case "OFFICIAL_CSV" -> parseOfficialCsv(content, endpoint, configuredProvider, defaultCurrency, config);
            default -> throw new IllegalArgumentException("不支持的价格适配器: " + adapterCode);
        };
    }

    private List<NormalizedPrice> parseLiteLlm(String content, String endpoint, String defaultCurrency) {
        try {
            JsonNode root = json.readTree(content);
            if (!root.isObject()) throw new IllegalArgumentException("LiteLLM Cost Map 必须是 JSON 对象");
            List<NormalizedPrice> result = new ArrayList<>();
            root.fields().forEachRemaining(entry -> {
                String model = entry.getKey();
                JsonNode item = entry.getValue();
                if ("sample_spec".equals(model) || !item.isObject()) return;
                BigDecimal input = decimal(item.get("input_cost_per_token"));
                BigDecimal output = decimal(item.get("output_cost_per_token"));
                Map<String,Object> components = new LinkedHashMap<>();
                componentPerToken(item, components, "INPUT_TOKEN", "input_cost_per_token");
                componentPerToken(item, components, "OUTPUT_TOKEN", "output_cost_per_token");
                componentPerToken(item, components, "CACHE_READ_TOKEN", "cache_read_input_token_cost");
                componentPerToken(item, components, "CACHE_WRITE_TOKEN", "cache_creation_input_token_cost");
                componentPerToken(item, components, "REASONING_TOKEN", "output_cost_per_reasoning_token");
                componentPerToken(item, components, "INPUT_TOKEN_ABOVE_200K", "input_cost_per_token_above_200k_tokens");
                componentPerToken(item, components, "OUTPUT_TOKEN_ABOVE_200K", "output_cost_per_token_above_200k_tokens");
                componentDirect(item, components, "IMAGE_INPUT", "input_cost_per_image", "PER_IMAGE");
                componentDirect(item, components, "IMAGE_OUTPUT", "output_cost_per_image", "PER_IMAGE");
                componentDirect(item, components, "VIDEO_SECOND", "input_cost_per_video_per_second", "PER_SECOND");
                componentDirect(item, components, "AUDIO_SECOND", "input_cost_per_audio_per_second", "PER_SECOND");
                componentDirect(item, components, "REQUEST", "input_cost_per_query", "PER_REQUEST");
                if (input == null && output == null && components.isEmpty()) return;
                String provider = text(item, "litellm_provider");
                if (blank(provider)) provider = providerFromModelKey(model);
                String currency = upper(value(text(item, "currency"), value(defaultCurrency, "USD")));
                String region = firstArray(item.get("supported_regions"), "global");
                String sourceRef = value(text(item, "source"), endpoint);
                Map<String,Object> raw = json.convertValue(item, new TypeReference<>() {});
                result.add(new NormalizedPrice(provider, model, value(text(item, "display_name"), model), currency,
                        perTokenToPer1k(input), perTokenToPer1k(output), region, "STANDARD", "DEFAULT", "DEFAULT",
                        components, sourceRef, OffsetDateTime.now(), null, raw));
            });
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("LiteLLM Cost Map 解析失败", e);
        }
    }

    private List<NormalizedPrice> parseModelsDev(String content, String endpoint, String defaultCurrency) {
        try {
            JsonNode root = json.readTree(content);
            List<NormalizedPrice> result = new ArrayList<>();
            if (root.isObject()) {
                root.fields().forEachRemaining(providerEntry -> parseModelsDevProvider(
                        providerEntry.getKey(), providerEntry.getValue(), endpoint, defaultCurrency, result));
            } else if (root.isArray()) {
                for (JsonNode provider : root) {
                    parseModelsDevProvider(value(text(provider, "id"), text(provider, "name")), provider,
                            endpoint, defaultCurrency, result);
                }
            } else throw new IllegalArgumentException("models.dev 数据必须是对象或数组");
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("models.dev 数据解析失败", e);
        }
    }

    private void parseModelsDevProvider(String providerKey, JsonNode providerNode, String endpoint,
                                        String defaultCurrency, List<NormalizedPrice> result) {
        if (providerNode == null || !providerNode.isObject()) return;
        String provider = value(text(providerNode, "id"), value(providerKey, text(providerNode, "name")));
        JsonNode models = providerNode.get("models");
        if (models == null) return;
        if (models.isObject()) {
            models.fields().forEachRemaining(modelEntry -> addModelsDevPrice(provider, modelEntry.getKey(),
                    modelEntry.getValue(), endpoint, defaultCurrency, result));
        } else if (models.isArray()) {
            for (JsonNode model : models) {
                addModelsDevPrice(provider, value(text(model, "id"), text(model, "name")), model,
                        endpoint, defaultCurrency, result);
            }
        }
    }

    private void addModelsDevPrice(String provider, String modelKey, JsonNode item, String endpoint,
                                   String defaultCurrency, List<NormalizedPrice> result) {
        if (blank(modelKey) || item == null || !item.isObject()) return;
        JsonNode cost = firstNode(item, "cost", "pricing", "price");
        if (cost == null || !cost.isObject()) return;
        BigDecimal inputPerMillion = decimal(firstNode(cost, "input", "input_tokens", "prompt"));
        BigDecimal outputPerMillion = decimal(firstNode(cost, "output", "output_tokens", "completion"));
        Map<String,Object> components = new LinkedHashMap<>();
        componentPerMillion(cost, components, "INPUT_TOKEN", "input", "input_tokens", "prompt");
        componentPerMillion(cost, components, "OUTPUT_TOKEN", "output", "output_tokens", "completion");
        componentPerMillion(cost, components, "CACHE_READ_TOKEN", "cache_read", "cacheRead", "cached_input");
        componentPerMillion(cost, components, "CACHE_WRITE_TOKEN", "cache_write", "cacheWrite", "cache_creation");
        if (inputPerMillion == null && outputPerMillion == null && components.isEmpty()) return;
        String currency = upper(value(text(cost, "currency"), value(text(item, "currency"), value(defaultCurrency, "USD"))));
        Map<String,Object> raw = json.convertValue(item, new TypeReference<>() {});
        result.add(new NormalizedPrice(provider, modelKey, value(text(item, "name"), modelKey), currency,
                perMillionToPer1k(inputPerMillion), perMillionToPer1k(outputPerMillion),
                value(text(item, "region"), "global"), value(text(item, "request_mode"), "STANDARD"),
                value(text(item, "service_tier"), "DEFAULT"), value(text(item, "context_tier"), "DEFAULT"),
                components, endpoint, OffsetDateTime.now(), null, raw));
    }

    private List<NormalizedPrice> parseDeepSeekOfficialPage(String content, String endpoint) {
        Document document = Jsoup.parse(content, endpoint);
        List<String> models = new ArrayList<>();
        List<BigDecimal> cacheHit = new ArrayList<>();
        List<BigDecimal> cacheMiss = new ArrayList<>();
        List<BigDecimal> output = new ArrayList<>();
        for (Element table : document.select("table")) {
            List<String> tableModels = new ArrayList<>();
            List<BigDecimal> tableCacheHit = new ArrayList<>();
            List<BigDecimal> tableCacheMiss = new ArrayList<>();
            List<BigDecimal> tableOutput = new ArrayList<>();
            for (Element row : table.select("tr")) {
                List<Element> cells = row.select("th,td");
                if (cells.size() < 2) continue;
                String label = normalizeLabel(cells.get(0).text());
                if ("MODEL".equals(label)) {
                    for (int i = 1; i < cells.size(); i++) {
                        String model = cleanModelName(cells.get(i).text());
                        if (!blank(model)) tableModels.add(model);
                    }
                } else if (label.contains("1M INPUT TOKENS") && label.contains("CACHE HIT")) {
                    appendMoneyCells(cells, tableCacheHit);
                } else if (label.contains("1M INPUT TOKENS") && label.contains("CACHE MISS")) {
                    appendMoneyCells(cells, tableCacheMiss);
                } else if (label.contains("1M OUTPUT TOKENS")) {
                    appendMoneyCells(cells, tableOutput);
                }
            }
            if (!tableModels.isEmpty() && tableModels.size() == tableCacheHit.size()
                    && tableModels.size() == tableCacheMiss.size() && tableModels.size() == tableOutput.size()) {
                models = tableModels;
                cacheHit = tableCacheHit;
                cacheMiss = tableCacheMiss;
                output = tableOutput;
                break;
            }
        }
        if (models.isEmpty()) {
            String text = document.text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            String modelSection = between(text, "MODEL ", " BASE URL");
            Matcher matcher = Pattern.compile("deepseek-[a-z0-9.-]+", Pattern.CASE_INSENSITIVE).matcher(modelSection);
            LinkedHashSet<String> found = new LinkedHashSet<>();
            while (matcher.find()) found.add(cleanModelName(matcher.group()));
            models.addAll(found);
            cacheHit = dollarValues(between(text, "1M INPUT TOKENS (CACHE HIT)", "1M INPUT TOKENS (CACHE MISS)"));
            cacheMiss = dollarValues(between(text, "1M INPUT TOKENS (CACHE MISS)", "1M OUTPUT TOKENS"));
            output = dollarValues(between(text, "1M OUTPUT TOKENS", "Concurrency Limit"));
        }
        if (models.isEmpty() || models.size() != cacheHit.size() || models.size() != cacheMiss.size()
                || models.size() != output.size()) {
            throw new IllegalArgumentException("DeepSeek 官方价格页结构发生变化，无法安全解析");
        }
        List<NormalizedPrice> prices = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            BigDecimal inputPer1k = perMillionToPer1k(cacheMiss.get(i));
            BigDecimal outputPer1k = perMillionToPer1k(output.get(i));
            BigDecimal cachePer1k = perMillionToPer1k(cacheHit.get(i));
            Map<String,Object> components = new LinkedHashMap<>();
            components.put("INPUT_TOKEN", component(inputPer1k, "PER_1K_TOKENS"));
            components.put("OUTPUT_TOKEN", component(outputPer1k, "PER_1K_TOKENS"));
            components.put("CACHE_READ_TOKEN", component(cachePer1k, "PER_1K_TOKENS"));
            Map<String,Object> raw = new LinkedHashMap<>();
            raw.put("model", models.get(i));
            raw.put("inputPer1MTokensCacheHit", cacheHit.get(i));
            raw.put("inputPer1MTokensCacheMiss", cacheMiss.get(i));
            raw.put("outputPer1MTokens", output.get(i));
            raw.put("officialPage", endpoint);
            prices.add(new NormalizedPrice("deepseek", models.get(i), models.get(i), "USD",
                    inputPer1k, outputPer1k, "global", "STANDARD", "DEFAULT", "DEFAULT",
                    components, endpoint, OffsetDateTime.now(), null, raw));
        }
        return prices;
    }

    private static void appendMoneyCells(List<Element> cells, List<BigDecimal> target) {
        for (int i = 1; i < cells.size(); i++) {
            BigDecimal amount = money(cells.get(i).text());
            if (amount != null) target.add(amount);
        }
    }

    private static List<BigDecimal> dollarValues(String text) {
        List<BigDecimal> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\$\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(value(text, ""));
        while (matcher.find()) result.add(new BigDecimal(matcher.group(1)));
        return result;
    }

    private static BigDecimal money(String value) {
        if (blank(value)) return null;
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(value.replace(",", ""));
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        if (from < 0) return "";
        from += start.length();
        int to = value.indexOf(end, from);
        return to < 0 ? value.substring(from) : value.substring(from, to);
    }

    private static String normalizeLabel(String value) {
        return value(value, "").replace('\u00a0', ' ').replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private static String cleanModelName(String value) {
        if (blank(value)) return null;
        Matcher matcher = Pattern.compile("deepseek-[a-z0-9.-]+", Pattern.CASE_INSENSITIVE).matcher(value);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }

    private List<NormalizedPrice> parseOfficialJson(String content, String endpoint, String configuredProvider,
                                                     String defaultCurrency, Map<String,Object> config) {
        try {
            JsonNode root = json.readTree(content);
            JsonNode records = at(root, string(config, "recordsPath", ""));
            boolean modelFromKey = bool(config, "modelFromKey", false);
            List<Map.Entry<String,JsonNode>> rows = new ArrayList<>();
            if (records.isArray()) {
                int index = 0;
                for (JsonNode node : records) rows.add(Map.entry(String.valueOf(index++), node));
            } else if (records.isObject()) {
                records.fields().forEachRemaining(rows::add);
            } else throw new IllegalArgumentException("官方 JSON recordsPath 未指向数组或对象");
            List<NormalizedPrice> result = new ArrayList<>();
            for (Map.Entry<String,JsonNode> row : rows) {
                JsonNode item = row.getValue();
                if (!item.isObject()) continue;
                String model = modelFromKey ? row.getKey() : textAt(item, string(config, "modelField", "id"));
                if (blank(model)) continue;
                String provider = value(textAt(item, string(config, "providerField", "provider")), configuredProvider);
                if (blank(provider)) throw new IllegalArgumentException("官方 JSON 记录缺少供应商类型");
                String unit = string(config, "unit", "PER_1M_TOKENS");
                BigDecimal input = decimal(at(item, string(config, "inputField", "input")));
                BigDecimal output = decimal(at(item, string(config, "outputField", "output")));
                Map<String,Object> components = new LinkedHashMap<>();
                putTokenComponent(components, "INPUT_TOKEN", input, unit);
                putTokenComponent(components, "OUTPUT_TOKEN", output, unit);
                addConfiguredComponents(item, config, components, unit);
                String currency = upper(value(textAt(item, string(config, "currencyField", "currency")), defaultCurrency));
                Map<String,Object> raw = json.convertValue(item, new TypeReference<>() {});
                result.add(new NormalizedPrice(provider, model,
                        value(textAt(item, string(config, "displayNameField", "name")), model), currency,
                        toPer1k(input, unit), toPer1k(output, unit),
                        value(textAt(item, string(config, "regionField", "region")), string(config, "region", "global")),
                        value(textAt(item, string(config, "requestModeField", "requestMode")), string(config, "requestMode", "STANDARD")),
                        value(textAt(item, string(config, "serviceTierField", "serviceTier")), string(config, "serviceTier", "DEFAULT")),
                        value(textAt(item, string(config, "contextTierField", "contextTier")), string(config, "contextTier", "DEFAULT")),
                        components, endpoint, parseTime(textAt(item, string(config, "effectiveFromField", "effectiveFrom"))),
                        parseTime(textAt(item, string(config, "effectiveToField", "effectiveTo"))), raw));
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("官方 JSON 价格解析失败", e);
        }
    }

    private List<NormalizedPrice> parseOfficialCsv(String content, String endpoint, String configuredProvider,
                                                    String defaultCurrency, Map<String,Object> config) {
        String[] lines = content.split("\\R");
        if (lines.length < 2) return List.of();
        char delimiter = string(config, "delimiter", ",").charAt(0);
        List<String> headers = csvLine(lines[0], delimiter);
        List<NormalizedPrice> result = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> values = csvLine(lines[i], delimiter);
            Map<String,String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) row.put(headers.get(j).trim(), j < values.size() ? values.get(j).trim() : "");
            String model = row.get(string(config, "modelField", "model"));
            if (blank(model)) continue;
            String provider = value(row.get(string(config, "providerField", "provider")), configuredProvider);
            if (blank(provider)) throw new IllegalArgumentException("官方 CSV 记录缺少供应商类型");
            String unit = string(config, "unit", "PER_1M_TOKENS");
            BigDecimal input = decimal(row.get(string(config, "inputField", "input")));
            BigDecimal output = decimal(row.get(string(config, "outputField", "output")));
            Map<String,Object> components = new LinkedHashMap<>();
            putTokenComponent(components, "INPUT_TOKEN", input, unit);
            putTokenComponent(components, "OUTPUT_TOKEN", output, unit);
            result.add(new NormalizedPrice(provider, model,
                    value(row.get(string(config, "displayNameField", "display_name")), model),
                    upper(value(row.get(string(config, "currencyField", "currency")), defaultCurrency)),
                    toPer1k(input, unit), toPer1k(output, unit),
                    value(row.get(string(config, "regionField", "region")), string(config, "region", "global")),
                    value(row.get(string(config, "requestModeField", "request_mode")), string(config, "requestMode", "STANDARD")),
                    value(row.get(string(config, "serviceTierField", "service_tier")), string(config, "serviceTier", "DEFAULT")),
                    value(row.get(string(config, "contextTierField", "context_tier")), string(config, "contextTier", "DEFAULT")),
                    components, endpoint, parseTime(row.get(string(config, "effectiveFromField", "effective_from"))),
                    parseTime(row.get(string(config, "effectiveToField", "effective_to"))), new LinkedHashMap<>(row)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addConfiguredComponents(JsonNode item, Map<String,Object> config, Map<String,Object> components, String defaultUnit) {
        Object mappings = config == null ? null : config.get("componentFields");
        if (!(mappings instanceof Map<?,?> map)) return;
        for (Map.Entry<?,?> entry : map.entrySet()) {
            String component = String.valueOf(entry.getKey());
            String path;
            String unit = defaultUnit;
            if (entry.getValue() instanceof Map<?,?> spec) {
                path = String.valueOf(spec.get("field"));
                if (spec.get("unit") != null) unit = String.valueOf(spec.get("unit"));
            } else path = String.valueOf(entry.getValue());
            putTokenComponent(components, component, decimal(at(item, path)), unit);
        }
    }

    private void componentPerToken(JsonNode item, Map<String,Object> components, String component, String field) {
        BigDecimal value = decimal(item.get(field));
        if (value != null) components.put(component, component(perTokenToPer1k(value), "PER_1K_TOKENS"));
    }

    private void componentPerMillion(JsonNode item, Map<String,Object> components, String component, String... fields) {
        BigDecimal value = decimal(firstNode(item, fields));
        if (value != null) components.put(component, component(perMillionToPer1k(value), "PER_1K_TOKENS"));
    }

    private void componentDirect(JsonNode item, Map<String,Object> components, String component, String field, String basis) {
        BigDecimal value = decimal(item.get(field));
        if (value != null) components.put(component, component(value, basis));
    }

    private void putTokenComponent(Map<String,Object> components, String type, BigDecimal value, String unit) {
        if (value == null) return;
        components.put(type, component(toPer1k(value, unit), "PER_1K_TOKENS"));
    }

    private static Map<String,Object> component(BigDecimal value, String basis) {
        return Map.of("unitPrice", value, "unitBasis", basis);
    }

    private static BigDecimal toPer1k(BigDecimal value, String unit) {
        if (value == null) return BigDecimal.ZERO;
        return switch (value(unit, "PER_1M_TOKENS").toUpperCase(Locale.ROOT)) {
            case "PER_TOKEN" -> perTokenToPer1k(value);
            case "PER_1K_TOKENS" -> value;
            case "PER_1M_TOKENS" -> perMillionToPer1k(value);
            default -> throw new IllegalArgumentException("不支持的 Token 计费单位: " + unit);
        };
    }

    private static BigDecimal perTokenToPer1k(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.multiply(THOUSAND);
    }

    private static BigDecimal perMillionToPer1k(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.multiply(THOUSAND).divide(MILLION, 12, RoundingMode.HALF_UP);
    }

    private static JsonNode at(JsonNode root, String path) {
        if (root == null || blank(path)) return root;
        JsonNode current = root;
        for (String part : path.replace("$.", "").split("\\.")) {
            if (part.isBlank()) continue;
            current = current == null ? null : current.get(part);
            if (current == null) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        return current;
    }

    private static String textAt(JsonNode root, String path) {
        JsonNode node = at(root, path);
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static JsonNode firstNode(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try { return new BigDecimal(node.asText()); } catch (Exception ignored) { return null; }
    }

    private static BigDecimal decimal(String value) {
        if (blank(value)) return null;
        try { return new BigDecimal(value.trim()); } catch (Exception ignored) { return null; }
    }

    private static String firstArray(JsonNode node, String fallback) {
        return node != null && node.isArray() && !node.isEmpty() ? node.get(0).asText(fallback) : fallback;
    }

    private static String providerFromModelKey(String model) {
        int slash = model.indexOf('/');
        return slash > 0 ? model.substring(0, slash) : "unknown";
    }

    private static OffsetDateTime parseTime(String value) {
        if (blank(value)) return OffsetDateTime.now();
        try { return OffsetDateTime.parse(value); } catch (Exception ignored) { return OffsetDateTime.now(); }
    }

    private static String string(Map<String,Object> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static boolean bool(Map<String,Object> config, String key, boolean fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static List<String> csvLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == delimiter && !quoted) { values.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        values.add(current.toString());
        return values;
    }

    private static String upper(String value) { return value == null ? null : value.toUpperCase(Locale.ROOT); }
    private static String value(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
