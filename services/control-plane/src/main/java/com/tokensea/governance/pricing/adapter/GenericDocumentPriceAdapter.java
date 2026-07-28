package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class GenericDocumentPriceAdapter implements PriceSourceAdapter {
    public static final String ADAPTER_CODE = "GENERIC_DOCUMENT";
    private static final long DEFAULT_TOKEN_QUANTITY = 1_000_000L;

    private final ObjectMapper json;
    private final PriceDocumentLlmExtractor llm;

    @Autowired
    public GenericDocumentPriceAdapter(ObjectMapper json, PriceDocumentLlmExtractor llm) {
        this.json = json;
        this.llm = llm;
    }

    GenericDocumentPriceAdapter(ObjectMapper json) {
        this(json, new PriceDocumentLlmExtractor(json));
    }

    @Override
    public boolean supports(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode);
    }

    @Override
    public PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument source) {
        DocumentContent content = documentContent(source);
        DeterministicResult deterministic = deterministic(context, source, content);
        List<PriceSourceParser.NormalizedPrice> prices = deterministic.prices();
        String extractionMethod = prices.isEmpty() ? "NONE" : "DETERMINISTIC_MAPPING";
        List<String> warnings = new ArrayList<>(deterministic.warnings());

        boolean llmRequested = Boolean.TRUE.equals(context.config().get("llmEnabled"));
        if (prices.isEmpty() && llmRequested) {
            if (llm.available()) {
                prices = llm.extract(context, content.plainText(), source.endpoint());
                extractionMethod = prices.isEmpty() ? "LLM_NO_PRICE_RECORD" : "LLM_SCHEMA_MAPPING";
            } else {
                warnings.add("价格源已启用 LLM Schema 映射，但 Control Plane 尚未配置价格文档 LLM");
            }
        }
        if (prices.isEmpty() && warnings.isEmpty()) {
            warnings.add("通用文档未生成价格记录；请配置字段映射，或在受控环境启用 LLM Schema 映射");
        }

        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("parseStatus", prices.isEmpty() ? "NO_PRICE_RECORD" : "PRICE_PARSED");
        evidence.put("documentType", content.type());
        evidence.put("tableCount", deterministic.tableCount());
        evidence.put("matchedTableCount", deterministic.matchedTableCount());
        evidence.put("rowCount", deterministic.rowCount());
        evidence.put("generatedPriceCount", prices.size());
        evidence.put("extractionMethod", extractionMethod);
        evidence.put("llmRequested", llmRequested);
        evidence.put("llmAvailable", llm.available());
        evidence.put("endpoint", source.endpoint());

        return new PriceSourceParseResult(
                prices,
                List.of(),
                List.of(),
                warnings,
                PriceStructureFingerprint.calculate(json, source.content(), source.contentType()),
                evidence,
                false);
    }

    private DeterministicResult deterministic(PriceSourceAdapterContext context,
                                              PriceSourceDocument source,
                                              DocumentContent content) {
        List<Map<String,Object>> rows = switch (content.type()) {
            case "JSON" -> jsonRows(content.rawText(), context.config());
            case "CSV" -> csvRows(content.rawText(), context.config());
            case "HTML" -> htmlRows(content.rawText(), context.config());
            default -> List.of();
        };
        if (rows.isEmpty()) {
            return new DeterministicResult(List.of(), content.tableCount(), 0, 0, List.of());
        }

        List<PriceSourceParser.NormalizedPrice> prices = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String,Object> row = rows.get(rowIndex);
            try {
                PriceSourceParser.NormalizedPrice price = mapRow(context, source.endpoint(), row, rowIndex);
                if (price == null) continue;
                String key = String.join("|",
                        lower(price.providerType()), lower(price.providerModelName()), lower(price.region()),
                        lower(price.requestMode()), lower(price.serviceTier()), lower(price.contextTier()));
                if (dedupe.add(key)) prices.add(price);
            } catch (IllegalArgumentException exception) {
                warnings.add("第 " + (rowIndex + 1) + " 条价格记录已跳过：" + exception.getMessage());
            }
        }
        return new DeterministicResult(prices, content.tableCount(), prices.isEmpty() ? 0 : Math.max(1, content.tableCount()),
                rows.size(), warnings.stream().limit(50).toList());
    }

    private PriceSourceParser.NormalizedPrice mapRow(PriceSourceAdapterContext context,
                                                      String endpoint,
                                                      Map<String,Object> row,
                                                      int rowIndex) {
        Map<String,Object> config = context.config();
        String model = field(row, config, "modelField", "model", "model_id", "modelId", "providerModelName");
        if (model.isBlank()) return null;
        String displayName = field(row, config, "displayNameField", "display_name", "displayName", "name");
        String currency = value(field(row, config, "currencyField", "currency", "currencyCode"), context.defaultCurrency());
        String billingBasis = value(field(row, config, "billingBasisField", "billing_basis", "billingBasis"), "TOKEN").toUpperCase(Locale.ROOT);
        long billingQuantity = longField(row, config, "billingQuantityField", "billing_quantity", "billingQuantity");
        if (billingQuantity <= 0) billingQuantity = configuredLong(config, "sourceBillingQuantity",
                "TOKEN".equals(billingBasis) ? DEFAULT_TOKEN_QUANTITY : 1L);
        BigDecimal multiplier = configuredDecimal(config, "priceMultiplier", BigDecimal.ONE);
        boolean sourcePerToken = Boolean.TRUE.equals(config.get("pricesPerToken"));

        BigDecimal input = priceField(row, config, "inputField", "input", "input_price", "inputUnitPrice", "prompt");
        BigDecimal output = priceField(row, config, "outputField", "output", "output_price", "outputUnitPrice", "completion");
        BigDecimal cacheRead = priceField(row, config, "cacheReadField", "cache_read", "cacheRead", "cacheReadUnitPrice");
        BigDecimal cacheWrite = priceField(row, config, "cacheWriteField", "cache_write", "cacheWrite", "cacheWriteUnitPrice");
        if (input == null && output == null && cacheRead == null && cacheWrite == null) return null;
        input = normalizePrice(input, multiplier, sourcePerToken, billingQuantity, billingBasis);
        output = normalizePrice(output, multiplier, sourcePerToken, billingQuantity, billingBasis);
        cacheRead = normalizePrice(cacheRead, multiplier, sourcePerToken, billingQuantity, billingBasis);
        cacheWrite = normalizePrice(cacheWrite, multiplier, sourcePerToken, billingQuantity, billingBasis);
        if (sourcePerToken && "TOKEN".equals(billingBasis)) billingQuantity = DEFAULT_TOKEN_QUANTITY;

        String region = value(field(row, config, "regionField", "region", "location"), context.region());
        String requestMode = value(field(row, config, "requestModeField", "request_mode", "requestMode"), context.requestMode());
        String serviceTier = value(field(row, config, "serviceTierField", "service_tier", "serviceTier"), "DEFAULT");
        String contextTier = value(field(row, config, "contextTierField", "context_tier", "contextTier"), "DEFAULT");
        String provider = value(field(row, config, "providerField", "provider", "provider_type", "providerType"), context.providerType());
        if (provider.isBlank()) throw new IllegalArgumentException("缺少供应商类型");

        Map<String,Object> components = new LinkedHashMap<>();
        if (input != null) components.put("INPUT_TOKEN", CatalogPriceAdapterSupport.component(input, billingBasis, billingQuantity));
        if (output != null) components.put("OUTPUT_TOKEN", CatalogPriceAdapterSupport.component(output, billingBasis, billingQuantity));
        if (cacheRead != null) components.put("CACHE_READ_TOKEN", CatalogPriceAdapterSupport.component(cacheRead, billingBasis, billingQuantity));
        if (cacheWrite != null) components.put("CACHE_WRITE_TOKEN", CatalogPriceAdapterSupport.component(cacheWrite, billingBasis, billingQuantity));
        Map<String,Object> raw = new LinkedHashMap<>(row);
        raw.put("sourceRow", rowIndex + 1);
        raw.put("extractionMethod", "DETERMINISTIC_MAPPING");

        return new PriceSourceParser.NormalizedPrice(
                provider,
                model,
                value(displayName, model),
                value(currency, "USD").toUpperCase(Locale.ROOT),
                billingBasis,
                billingQuantity,
                input,
                output,
                value(region, "global"),
                value(requestMode, "STANDARD"),
                value(serviceTier, "DEFAULT"),
                value(contextTier, "DEFAULT"),
                components,
                endpoint,
                OffsetDateTime.now(),
                null,
                raw);
    }

    private List<Map<String,Object>> jsonRows(String content, Map<String,Object> config) {
        try {
            JsonNode root = json.readTree(content);
            String path = Objects.toString(config.get("recordsPath"), "").trim();
            JsonNode rows = path.isBlank() ? root : jsonPath(root, path);
            if (rows == null || rows.isMissingNode() || rows.isNull()) return List.of();
            List<Map<String,Object>> result = new ArrayList<>();
            if (rows.isArray()) {
                for (JsonNode row : rows) if (row.isObject()) result.add(json.convertValue(row, new TypeReference<>() {}));
            } else if (rows.isObject()) {
                if (Boolean.TRUE.equals(config.get("modelFromKey"))) {
                    rows.fields().forEachRemaining(entry -> {
                        if (!entry.getValue().isObject()) return;
                        Map<String,Object> value = json.convertValue(entry.getValue(), new TypeReference<>() {});
                        value.putIfAbsent(Objects.toString(config.getOrDefault("modelField", "model")), entry.getKey());
                        result.add(value);
                    });
                } else result.add(json.convertValue(rows, new TypeReference<>() {}));
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("通用 JSON 文档解析失败", exception);
        }
    }

    private JsonNode jsonPath(JsonNode root, String path) {
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (part.isBlank()) continue;
            current = current.path(part);
        }
        return current;
    }

    private List<Map<String,Object>> htmlRows(String content, Map<String,Object> config) {
        Document document = Jsoup.parse(content);
        int requestedTable = configuredInt(config, "tableIndex", -1);
        List<Map<String,Object>> result = new ArrayList<>();
        List<Element> tables = document.select("table");
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            if (requestedTable >= 0 && requestedTable != tableIndex) continue;
            Element table = tables.get(tableIndex);
            List<Element> rows = table.select("tr");
            if (rows.size() < 2) continue;
            List<String> headers = rows.get(0).select("th,td").stream().map(cell -> normalizeHeader(cell.text())).toList();
            if (headers.isEmpty()) continue;
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                List<Element> cells = rows.get(rowIndex).select("th,td");
                if (cells.isEmpty()) continue;
                Map<String,Object> row = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(headers.size(), cells.size()); i++) row.put(headers.get(i), cells.get(i).text().trim());
                row.put("_tableIndex", tableIndex);
                row.put("_rowIndex", rowIndex);
                result.add(row);
            }
        }
        return result;
    }

    private List<Map<String,Object>> csvRows(String content, Map<String,Object> config) {
        char delimiter = Objects.toString(config.getOrDefault("delimiter", ",")).charAt(0);
        List<List<String>> lines = parseCsv(content, delimiter);
        if (lines.size() < 2) return List.of();
        List<String> headers = lines.get(0).stream().map(this::normalizeHeader).toList();
        List<Map<String,Object>> result = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < lines.size(); rowIndex++) {
            List<String> values = lines.get(rowIndex);
            if (values.stream().allMatch(String::isBlank)) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(headers.size(), values.size()); i++) row.put(headers.get(i), values.get(i).trim());
            result.add(row);
        }
        return result;
    }

    private List<List<String>> parseCsv(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (current == delimiter && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else cell.append(current);
        }
        if (!row.isEmpty() || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private DocumentContent documentContent(PriceSourceDocument source) {
        String contentType = value(source.contentType(), "").toLowerCase(Locale.ROOT);
        if (contentType.contains("pdf")) {
            try {
                byte[] bytes = Base64.getDecoder().decode(source.content());
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    String text = new PDFTextStripper().getText(document);
                    return new DocumentContent("PDF", source.content(), text, 0);
                }
            } catch (Exception exception) {
                throw new IllegalArgumentException("PDF 价格文档文本提取失败", exception);
            }
        }
        String trimmed = source.content().stripLeading();
        if (contentType.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return new DocumentContent("JSON", source.content(), source.content(), 0);
        }
        if (contentType.contains("csv")) return new DocumentContent("CSV", source.content(), source.content(), 0);
        if (contentType.contains("html") || trimmed.startsWith("<")) {
            Document document = Jsoup.parse(source.content());
            return new DocumentContent("HTML", source.content(), document.text(), document.select("table").size());
        }
        String delimiter = source.content().lines().findFirst().orElse("").contains("\t") ? "\t" : ",";
        if (source.content().lines().findFirst().orElse("").contains(delimiter)) {
            return new DocumentContent("CSV", source.content(), source.content(), 0);
        }
        return new DocumentContent("TEXT", source.content(), new String(source.content().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), 0);
    }

    private String field(Map<String,Object> row, Map<String,Object> config, String configKey, String... defaults) {
        String configured = Objects.toString(config.get(configKey), "").trim();
        if (!configured.isBlank()) return text(fieldValue(row, configured));
        for (String field : defaults) {
            Object value = fieldValue(row, field);
            if (value != null && !text(value).isBlank()) return text(value);
        }
        return "";
    }

    private Object fieldValue(Map<String,Object> row, String field) {
        if (row.containsKey(field)) return row.get(field);
        String normalized = normalizeHeader(field);
        if (row.containsKey(normalized)) return row.get(normalized);
        for (Map.Entry<String,Object> entry : row.entrySet()) {
            if (normalizeHeader(entry.getKey()).equals(normalized)) return entry.getValue();
        }
        return null;
    }

    private BigDecimal priceField(Map<String,Object> row, Map<String,Object> config, String configKey, String... defaults) {
        String raw = field(row, config, configKey, defaults);
        if (raw.isBlank()) return null;
        String normalized = raw.replace(",", "").replaceAll("[^0-9+\\-.]", "");
        if (normalized.isBlank() || "-".equals(normalized)) return null;
        try {
            BigDecimal value = new BigDecimal(normalized);
            if (value.signum() < 0) throw new IllegalArgumentException("价格不能为负数");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("价格格式无效：" + raw);
        }
    }

    private BigDecimal normalizePrice(BigDecimal price,
                                      BigDecimal multiplier,
                                      boolean perToken,
                                      long sourceQuantity,
                                      String basis) {
        if (price == null) return null;
        BigDecimal result = price.multiply(multiplier);
        if (perToken && "TOKEN".equals(basis)) return result.multiply(BigDecimal.valueOf(DEFAULT_TOKEN_QUANTITY));
        if ("TOKEN".equals(basis) && sourceQuantity != DEFAULT_TOKEN_QUANTITY) {
            return result.multiply(BigDecimal.valueOf(DEFAULT_TOKEN_QUANTITY))
                    .divide(BigDecimal.valueOf(sourceQuantity), 12, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }
        return result;
    }

    private long longField(Map<String,Object> row, Map<String,Object> config, String configKey, String... defaults) {
        String raw = field(row, config, configKey, defaults);
        if (raw.isBlank()) return 0;
        try {
            return Long.parseLong(raw.replace(",", "").replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String normalizeHeader(String value) {
        String normalized = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT)
                .replaceAll("[（(].*?[）)]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "column" : normalized;
    }

    private int configuredInt(Map<String,Object> config, String key, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(config.get(key)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long configuredLong(Map<String,Object> config, String key, long fallback) {
        try {
            return Long.parseLong(String.valueOf(config.get(key)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private BigDecimal configuredDecimal(Map<String,Object> config, String key, BigDecimal fallback) {
        try {
            return new BigDecimal(String.valueOf(config.get(key)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record DocumentContent(String type, String rawText, String plainText, int tableCount) {}

    private record DeterministicResult(List<PriceSourceParser.NormalizedPrice> prices,
                                       int tableCount,
                                       int matchedTableCount,
                                       int rowCount,
                                       List<String> warnings) {}
}
