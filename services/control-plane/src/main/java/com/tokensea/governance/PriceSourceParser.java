package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.pricing.adapter.PriceSourceAdapterContext;
import com.tokensea.governance.pricing.adapter.PriceSourceAdapterRegistry;
import com.tokensea.governance.pricing.adapter.PriceSourceDocument;
import com.tokensea.governance.pricing.adapter.PriceSourceParseResult;
import com.tokensea.governance.pricing.adapter.PriceStructureFingerprint;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PriceSourceParser {
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final long DEFAULT_TOKEN_QUANTITY = 1_000_000L;
    private final ObjectMapper json;
    private final PriceSourceAdapterRegistry adapters;

    public PriceSourceParser(ObjectMapper json) {
        this(json, new PriceSourceAdapterRegistry(List.of()));
    }

    @Autowired
    public PriceSourceParser(ObjectMapper json, PriceSourceAdapterRegistry adapters) {
        this.json = json;
        this.adapters = adapters;
    }

    public record NormalizedPrice(
            String providerType,
            String providerModelName,
            String displayName,
            String currency,
            String billingBasis,
            long billingQuantity,
            BigDecimal inputUnitPrice,
            BigDecimal outputUnitPrice,
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
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                null, adapterCode, configuredProvider, endpoint,
                config == null ? "global" : String.valueOf(config.getOrDefault("region", "global")),
                defaultCurrency, config == null ? "STANDARD" : String.valueOf(config.getOrDefault("requestMode", "STANDARD")),
                100, "ORIGINAL", "1.0.0", config);
        return parseDetailed(context, new PriceSourceDocument(content, endpoint, "", "")).prices();
    }

    public PriceSourceParseResult parseDetailed(PriceSourceAdapterContext context, PriceSourceDocument document) {
        if (document == null || document.content() == null || document.content().isBlank()) {
            throw new IllegalArgumentException("价格来源内容为空");
        }
        Optional<com.tokensea.governance.pricing.adapter.PriceSourceAdapter> registered = adapters.find(context.adapterCode());
        if (registered.isPresent()) return registered.get().parse(context, document);
        List<NormalizedPrice> prices = switch (context.adapterCode()) {
            case "LITELLM_COST_MAP" -> parseLiteLlm(document.content(), document.endpoint(), context.defaultCurrency());
            case "MODELS_DEV" -> parseModelsDev(document.content(), document.endpoint(), context.defaultCurrency());
            case "DEEPSEEK_OFFICIAL_PAGE" -> parseDeepSeekOfficialPage(document.content(), document.endpoint(), context.defaultCurrency());
            case "OFFICIAL_JSON" -> parseOfficialJson(document.content(), document.endpoint(), context.providerType(), context.defaultCurrency(), context.config());
            case "OFFICIAL_CSV" -> parseOfficialCsv(document.content(), document.endpoint(), context.providerType(), context.defaultCurrency(), context.config());
            default -> throw new IllegalArgumentException("不支持的价格适配器: " + context.adapterCode());
        };
        return new PriceSourceParseResult(prices, List.of(), List.of(), List.of(),
                PriceStructureFingerprint.calculate(json, document.content(), document.contentType()),
                Map.of("endpoint", document.endpoint(), "adapterCode", context.adapterCode()), false);
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
                componentPerTokenVariant(item, components, "CACHE_READ_TOKEN", "ABOVE_200K",
                        "cache_read_input_token_cost_above_200k_tokens", Map.of("minContextTokens", 200000));
                componentPerTokenVariant(item, components, "CACHE_WRITE_TOKEN", "ABOVE_200K",
                        "cache_creation_input_token_cost_above_200k_tokens", Map.of("minContextTokens", 200000));
                componentPerToken(item, components, "REASONING_TOKEN", "output_cost_per_reasoning_token");
                componentPerToken(item, components, "INPUT_TOKEN_ABOVE_200K", "input_cost_per_token_above_200k_tokens");
                componentPerToken(item, components, "OUTPUT_TOKEN_ABOVE_200K", "output_cost_per_token_above_200k_tokens");
                componentDirect(item, components, "IMAGE_INPUT", "input_cost_per_image", "IMAGE");
                componentDirect(item, components, "IMAGE_OUTPUT", "output_cost_per_image", "IMAGE");
                componentDirect(item, components, "VIDEO_SECOND", "input_cost_per_video_per_second", "SECOND");
                componentDirect(item, components, "AUDIO_SECOND", "input_cost_per_audio_per_second", "SECOND");
                componentDirect(item, components, "REQUEST", "input_cost_per_query", "REQUEST");
                if (input == null && output == null && components.isEmpty()) return;
                String provider = text(item, "litellm_provider");
                if (blank(provider)) provider = providerFromModelKey(model);
                String currency = upper(value(text(item, "currency"), value(defaultCurrency, "USD")));
                String region = firstArray(item.get("supported_regions"), "global");
                String sourceRef = value(text(item, "source"), endpoint);
                Map<String,Object> raw = json.convertValue(item, new TypeReference<>() {});
                result.add(new NormalizedPrice(provider, model, value(text(item, "display_name"), model), currency,
                        "TOKEN", DEFAULT_TOKEN_QUANTITY, perTokenToPerMillion(input), perTokenToPerMillion(output),
                        region, "STANDARD", "DEFAULT", "DEFAULT",
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
                "TOKEN", DEFAULT_TOKEN_QUANTITY, amount(inputPerMillion), amount(outputPerMillion),
                value(text(item, "region"), "global"), value(text(item, "request_mode"), "STANDARD"),
                value(text(item, "service_tier"), "DEFAULT"), value(text(item, "context_tier"), "DEFAULT"),
                components, endpoint, OffsetDateTime.now(), null, raw));
    }

    private List<NormalizedPrice> parseDeepSeekOfficialPage(String content, String endpoint, String defaultCurrency) {
        Document document = Jsoup.parse(content, endpoint);
        List<String> models = new ArrayList<>();
        List<BigDecimal> cacheHit = new ArrayList<>();
        List<BigDecimal> cacheMiss = new ArrayList<>();
        List<BigDecimal> output = new ArrayList<>();
        StringBuilder priceEvidence = new StringBuilder();
        for (Element table : document.select("table")) {
            List<String> tableModels = new ArrayList<>();
            List<BigDecimal> tableCacheHit = new ArrayList<>();
            List<BigDecimal> tableCacheMiss = new ArrayList<>();
            List<BigDecimal> tableOutput = new ArrayList<>();
            StringBuilder tableEvidence = new StringBuilder();
            for (Element row : table.select("tr")) {
                List<Element> cells = row.select("th,td");
                if (cells.size() < 2) continue;
                for (int labelIndex = 0; labelIndex < cells.size(); labelIndex++) {
                    String label = normalizeLabel(cells.get(labelIndex).text());
                    if (isDeepSeekModelLabel(label)) {
                        for (int i = labelIndex + 1; i < cells.size(); i++) {
                            String model = cleanModelName(cells.get(i).text());
                            if (!blank(model)) tableModels.add(model);
                        }
                        break;
                    }
                    if (isDeepSeekCacheHitLabel(label)) {
                        appendMoneyCells(cells, labelIndex + 1, tableCacheHit);
                        tableEvidence.append(' ').append(row.text());
                        break;
                    }
                    if (isDeepSeekCacheMissLabel(label)) {
                        appendMoneyCells(cells, labelIndex + 1, tableCacheMiss);
                        tableEvidence.append(' ').append(row.text());
                        break;
                    }
                    if (isDeepSeekOutputLabel(label)) {
                        appendMoneyCells(cells, labelIndex + 1, tableOutput);
                        tableEvidence.append(' ').append(row.text());
                        break;
                    }
                }
            }
            if (!tableModels.isEmpty() && tableModels.size() == tableCacheHit.size()
                    && tableModels.size() == tableCacheMiss.size() && tableModels.size() == tableOutput.size()) {
                models = tableModels;
                cacheHit = tableCacheHit;
                cacheMiss = tableCacheMiss;
                output = tableOutput;
                priceEvidence.append(tableEvidence);
                break;
            }
        }
        if (models.isEmpty()) {
            String text = document.text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            String modelSection = firstNonBlank(
                    betweenIgnoreCase(text, "MODEL ", " BASE URL"),
                    betweenIgnoreCase(text, "模型 ", " BASE URL"));
            Matcher matcher = Pattern.compile("deepseek-[a-z0-9.-]+", Pattern.CASE_INSENSITIVE).matcher(modelSection);
            LinkedHashSet<String> found = new LinkedHashSet<>();
            while (matcher.find()) found.add(cleanModelName(matcher.group()));
            models.addAll(found);
            String hitSection = firstNonBlank(
                    betweenIgnoreCase(text, "1M INPUT TOKENS (CACHE HIT)", "1M INPUT TOKENS (CACHE MISS)"),
                    betweenIgnoreCase(text, "百万TOKENS输入（缓存命中）", "百万TOKENS输入（缓存未命中）"),
                    betweenIgnoreCase(text, "百万 TOKENS 输入（缓存命中）", "百万 TOKENS 输入（缓存未命中）"));
            String missSection = firstNonBlank(
                    betweenIgnoreCase(text, "1M INPUT TOKENS (CACHE MISS)", "1M OUTPUT TOKENS"),
                    betweenIgnoreCase(text, "百万TOKENS输入（缓存未命中）", "百万TOKENS输出"),
                    betweenIgnoreCase(text, "百万 TOKENS 输入（缓存未命中）", "百万 TOKENS 输出"));
            String outputSection = firstNonBlank(
                    betweenIgnoreCase(text, "1M OUTPUT TOKENS", "CONCURRENCY LIMIT"),
                    betweenIgnoreCase(text, "百万TOKENS输出", "并发限制"),
                    betweenIgnoreCase(text, "百万 TOKENS 输出", "并发限制"));
            cacheHit = moneyValues(hitSection);
            cacheMiss = moneyValues(missSection);
            output = moneyValues(outputSection);
            priceEvidence.append(' ').append(hitSection).append(' ').append(missSection).append(' ').append(outputSection);
        }
        if (models.isEmpty() || models.size() != cacheHit.size() || models.size() != cacheMiss.size()
                || models.size() != output.size()) {
            throw new IllegalArgumentException("DeepSeek 官方价格页结构发生变化，无法安全解析");
        }
        String currency = deepSeekCurrency(priceEvidence.toString(), defaultCurrency);
        String region = "global";
        List<NormalizedPrice> prices = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            BigDecimal inputPerMillion = cacheMiss.get(i);
            BigDecimal outputPerMillion = output.get(i);
            BigDecimal cachePerMillion = cacheHit.get(i);
            Map<String,Object> components = new LinkedHashMap<>();
            components.put("INPUT_TOKEN", component(inputPerMillion, "TOKEN", DEFAULT_TOKEN_QUANTITY));
            components.put("OUTPUT_TOKEN", component(outputPerMillion, "TOKEN", DEFAULT_TOKEN_QUANTITY));
            components.put("CACHE_READ_TOKEN", component(cachePerMillion, "TOKEN", DEFAULT_TOKEN_QUANTITY));
            Map<String,Object> raw = new LinkedHashMap<>();
            raw.put("model", models.get(i));
            raw.put("inputPer1MTokensCacheHit", cacheHit.get(i));
            raw.put("inputPer1MTokensCacheMiss", cacheMiss.get(i));
            raw.put("outputPer1MTokens", output.get(i));
            raw.put("currency", currency);
            raw.put("configuredCurrency", upper(defaultCurrency));
            raw.put("officialPage", endpoint);
            prices.add(new NormalizedPrice("deepseek", models.get(i), models.get(i), currency,
                    "TOKEN", DEFAULT_TOKEN_QUANTITY, inputPerMillion, outputPerMillion,
                    region, "STANDARD", "DEFAULT", "DEFAULT",
                    components, endpoint, OffsetDateTime.now(), null, raw));
        }
        return prices;
    }

    private static boolean isDeepSeekModelLabel(String label) {
        String compact = compactLabel(label);
        return "MODEL".equals(compact) || "模型".equals(compact);
    }

    private static boolean isDeepSeekCacheHitLabel(String label) {
        String compact = compactLabel(label);
        return (compact.contains("1MINPUTTOKENS") && compact.contains("CACHEHIT"))
                || (compact.contains("百万TOKENS输入") && compact.contains("缓存命中")
                    && !compact.contains("缓存未命中"));
    }

    private static boolean isDeepSeekCacheMissLabel(String label) {
        String compact = compactLabel(label);
        return (compact.contains("1MINPUTTOKENS") && compact.contains("CACHEMISS"))
                || (compact.contains("百万TOKENS输入") && compact.contains("缓存未命中"));
    }

    private static boolean isDeepSeekOutputLabel(String label) {
        String compact = compactLabel(label);
        return compact.contains("1MOUTPUTTOKENS") || compact.contains("百万TOKENS输出");
    }

    private static String compactLabel(String value) {
        return normalizeLabel(value).replace("（", "(").replace("）", ")").replaceAll("\\s+", "");
    }

    private static String deepSeekCurrency(String evidence, String defaultCurrency) {
        String configured = upper(defaultCurrency);
        if (!Set.of("CNY", "USD").contains(configured)) {
            throw new IllegalArgumentException("DeepSeek 官方价格源默认币种必须是 CNY 或 USD");
        }
        String value = evidence == null ? "" : evidence;
        boolean cny = Pattern.compile("(?:人民币|CNY|[¥￥]|[0-9]\\s*元)", Pattern.CASE_INSENSITIVE).matcher(value).find();
        boolean usd = Pattern.compile("(?:美元|USD|\\$)", Pattern.CASE_INSENSITIVE).matcher(value).find();
        if (cny && usd) throw new IllegalArgumentException("DeepSeek 官方价格页同时出现 CNY 与 USD 标识，无法安全判定币种");
        String detected = cny ? "CNY" : usd ? "USD" : null;
        if (detected != null && !detected.equals(configured)) {
            throw new IllegalArgumentException("DeepSeek 官方价格页币种为 " + detected
                    + "，与价格源默认币种 " + configured + " 不一致");
        }
        return detected == null ? configured : detected;
    }

    private static void appendMoneyCells(List<Element> cells, int startIndex, List<BigDecimal> target) {
        for (int i = startIndex; i < cells.size(); i++) {
            BigDecimal amount = money(cells.get(i).text());
            if (amount != null) target.add(amount);
        }
    }

    private static List<BigDecimal> moneyValues(String text) {
        List<BigDecimal> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?:[$¥￥]|USD|CNY)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:人民币|美元|元|USD|CNY)?",
                Pattern.CASE_INSENSITIVE).matcher(value(text, ""));
        while (matcher.find()) result.add(new BigDecimal(matcher.group(1)));
        return result;
    }

    private static BigDecimal money(String value) {
        if (blank(value)) return null;
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(value.replace(",", ""));
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private static String betweenIgnoreCase(String value, String start, String end) {
        String source = value(value, "");
        String upperSource = source.toUpperCase(Locale.ROOT);
        int from = upperSource.indexOf(start.toUpperCase(Locale.ROOT));
        if (from < 0) return "";
        from += start.length();
        int to = upperSource.indexOf(end.toUpperCase(Locale.ROOT), from);
        return to < 0 ? source.substring(from) : source.substring(from, to);
    }

    private static String firstNonBlank(String... values) {
        for (String candidate : values) if (!blank(candidate)) return candidate;
        return "";
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
                        "TOKEN", DEFAULT_TOKEN_QUANTITY, toPerMillion(input, unit), toPerMillion(output, unit),
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
            addConfiguredCsvComponents(row, config, components, unit);
            result.add(new NormalizedPrice(provider, model,
                    value(row.get(string(config, "displayNameField", "display_name")), model),
                    upper(value(row.get(string(config, "currencyField", "currency")), defaultCurrency)),
                    "TOKEN", DEFAULT_TOKEN_QUANTITY, toPerMillion(input, unit), toPerMillion(output, unit),
                    value(row.get(string(config, "regionField", "region")), string(config, "region", "global")),
                    value(row.get(string(config, "requestModeField", "request_mode")), string(config, "requestMode", "STANDARD")),
                    value(row.get(string(config, "serviceTierField", "service_tier")), string(config, "serviceTier", "DEFAULT")),
                    value(row.get(string(config, "contextTierField", "context_tier")), string(config, "contextTier", "DEFAULT")),
                    components, endpoint, parseTime(row.get(string(config, "effectiveFromField", "effective_from"))),
                    parseTime(row.get(string(config, "effectiveToField", "effective_to"))), new LinkedHashMap<>(row)));
        }
        return result;
    }

    private void addConfiguredComponents(JsonNode item, Map<String,Object> config,
                                         Map<String,Object> components, String defaultUnit) {
        Object mappings = config == null ? null : config.get("componentFields");
        if (!(mappings instanceof Map<?,?> map)) return;
        for (Map.Entry<?,?> entry : map.entrySet()) {
            String componentType = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof List<?> specs) {
                for (Object spec : specs) addConfiguredJsonComponent(item, components, componentType, spec, defaultUnit);
            } else addConfiguredJsonComponent(item, components, componentType, entry.getValue(), defaultUnit);
        }
    }

    private void addConfiguredCsvComponents(Map<String,String> row, Map<String,Object> config,
                                            Map<String,Object> components, String defaultUnit) {
        Object mappings = config == null ? null : config.get("componentFields");
        if (!(mappings instanceof Map<?,?> map)) return;
        for (Map.Entry<?,?> entry : map.entrySet()) {
            String componentType = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof List<?> specs) {
                for (Object spec : specs) addConfiguredCsvComponent(row, components, componentType, spec, defaultUnit);
            } else addConfiguredCsvComponent(row, components, componentType, entry.getValue(), defaultUnit);
        }
    }

    private void addConfiguredJsonComponent(JsonNode item, Map<String,Object> components,
                                            String componentType, Object rawSpec, String defaultUnit) {
        ComponentMapping mapping = componentMapping(rawSpec, defaultUnit);
        BigDecimal value = decimal(at(item, mapping.field()));
        if (value == null) return;
        addComponentValue(components, componentType, configuredComponent(value, mapping));
    }

    private void addConfiguredCsvComponent(Map<String,String> row, Map<String,Object> components,
                                           String componentType, Object rawSpec, String defaultUnit) {
        ComponentMapping mapping = componentMapping(rawSpec, defaultUnit);
        BigDecimal value = decimal(row.get(mapping.field()));
        if (value == null) return;
        addComponentValue(components, componentType, configuredComponent(value, mapping));
    }

    private ComponentMapping componentMapping(Object rawSpec, String defaultUnit) {
        if (rawSpec instanceof Map<?,?> spec) {
            Object field = spec.get("field");
            if (field == null || String.valueOf(field).isBlank()) throw new IllegalArgumentException("价格组件映射缺少 field");
            return new ComponentMapping(String.valueOf(field),
                    spec.get("unit") == null ? defaultUnit : String.valueOf(spec.get("unit")),
                    spec.get("variant") == null ? "DEFAULT" : String.valueOf(spec.get("variant")),
                    spec.get("mode") == null ? "EXPLICIT" : String.valueOf(spec.get("mode")),
                    spec.get("priority") instanceof Number number ? number.intValue() : 100,
                    spec.get("scope") instanceof Map<?,?> scope ? stringMap(scope) : Map.of(),
                    spec.get("metadata") instanceof Map<?,?> metadata ? stringMap(metadata) : Map.of());
        }
        return new ComponentMapping(String.valueOf(rawSpec), defaultUnit, "DEFAULT", "EXPLICIT", 100, Map.of(), Map.of());
    }

    private Map<String,Object> configuredComponent(BigDecimal value, ComponentMapping mapping) {
        Map<String,Object> result = new LinkedHashMap<>(component(toPerMillion(value, mapping.unit()),
                "TOKEN", DEFAULT_TOKEN_QUANTITY));
        result.put("variant", mapping.variant());
        result.put("mode", mapping.mode());
        result.put("priority", mapping.priority());
        result.put("scope", mapping.scope());
        result.put("metadata", mapping.metadata());
        return result;
    }

    private void addComponentValue(Map<String,Object> components, String componentType, Map<String,Object> value) {
        Object existing = components.get(componentType);
        if (existing == null) components.put(componentType, value);
        else if (existing instanceof List<?> list) {
            List<Object> values = new ArrayList<>(list);
            values.add(value);
            components.put(componentType, values);
        } else components.put(componentType, new ArrayList<>(List.of(existing, value)));
    }

    private record ComponentMapping(String field, String unit, String variant, String mode,
                                    int priority, Map<String,Object> scope, Map<String,Object> metadata) {}

    private void componentPerToken(JsonNode item, Map<String,Object> components, String component, String field) {
        BigDecimal value = decimal(item.get(field));
        if (value != null) components.put(component,
                component(perTokenToPerMillion(value), "TOKEN", DEFAULT_TOKEN_QUANTITY));
    }

    private void componentPerTokenVariant(JsonNode item, Map<String,Object> components,
                                          String componentType, String variant, String field,
                                          Map<String,Object> scope) {
        BigDecimal value = decimal(item.get(field));
        if (value == null) return;
        Map<String,Object> spec = new LinkedHashMap<>(
                component(perTokenToPerMillion(value), "TOKEN", DEFAULT_TOKEN_QUANTITY));
        spec.put("variant", variant);
        spec.put("mode", "EXPLICIT");
        spec.put("priority", 50);
        spec.put("scope", scope == null ? Map.of() : scope);
        spec.put("metadata", Map.of("sourceField", field));
        addComponentValue(components, componentType, spec);
    }

    private void componentPerMillion(JsonNode item, Map<String,Object> components, String component, String... fields) {
        BigDecimal value = decimal(firstNode(item, fields));
        if (value != null) components.put(component, component(value, "TOKEN", DEFAULT_TOKEN_QUANTITY));
    }

    private void componentDirect(JsonNode item, Map<String,Object> components, String component, String field, String basis) {
        BigDecimal value = decimal(item.get(field));
        if (value != null) components.put(component, component(value, basis, 1));
    }

    private void putTokenComponent(Map<String,Object> components, String type, BigDecimal value, String unit) {
        if (value == null) return;
        components.put(type, component(toPerMillion(value, unit), "TOKEN", DEFAULT_TOKEN_QUANTITY));
    }

    private static Map<String,Object> component(BigDecimal value, String basis, long quantity) {
        return Map.of("unitPrice", amount(value), "unitBasis", basis, "unitQuantity", quantity);
    }

    private static BigDecimal toPerMillion(BigDecimal value, String unit) {
        if (value == null) return BigDecimal.ZERO;
        return switch (value(unit, "PER_1M_TOKENS").toUpperCase(Locale.ROOT)) {
            case "PER_TOKEN" -> perTokenToPerMillion(value);
            case "PER_1K_TOKENS" -> value.multiply(THOUSAND);
            case "PER_1M_TOKENS" -> value;
            default -> throw new IllegalArgumentException("不支持的 Token 计费单位: " + unit);
        };
    }

    private static BigDecimal perTokenToPerMillion(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.multiply(MILLION);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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

    private static Map<String,Object> stringMap(Map<?,?> source) {
        Map<String,Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String upper(String value) { return value == null ? null : value.toUpperCase(Locale.ROOT); }
    private static String value(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
