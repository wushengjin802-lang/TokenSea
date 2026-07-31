package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import com.tokensea.governance.pricing.extractor.PriceDocumentTypeDetector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GenericDocumentPriceAdapter implements PriceSourceAdapter {
    public static final String ADAPTER_CODE = "GENERIC_DOCUMENT";
    private static final long DEFAULT_TOKEN_QUANTITY = 1_000_000L;

    private final ObjectMapper json;
    private final PriceDocumentLlmExtractor llm;
    private final PriceDocumentTypeDetector typeDetector;

    @Autowired
    public GenericDocumentPriceAdapter(ObjectMapper json,
                                       PriceDocumentLlmExtractor llm,
                                       PriceDocumentTypeDetector typeDetector) {
        this.json = json;
        this.llm = llm;
        this.typeDetector = typeDetector;
    }

    GenericDocumentPriceAdapter(ObjectMapper json) {
        this(json, new PriceDocumentLlmExtractor(json), new PriceDocumentTypeDetector());
    }

    @Override
    public boolean supports(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode);
    }

    @Override
    public PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument source) {
        DocumentContent content = documentContent(source, context.config());
        DeterministicResult deterministic = deterministic(context, source, content);
        List<PriceSourceParser.NormalizedPrice> prices = deterministic.prices();
        String extractionMethod = prices.isEmpty() ? "NONE" : "DETERMINISTIC_MAPPING";
        List<String> warnings = new ArrayList<>(deterministic.warnings());

        boolean llmRequested = Boolean.TRUE.equals(context.config().get("llmEnabled"));
        boolean supplementaryLlm = "DETERMINISTIC_LLM".equalsIgnoreCase(
                Objects.toString(context.config().get("extractionMode"), ""));
        int deterministicCount = prices.size();
        PriceDocumentLlmExtractor.LlmExtractionResult llmResult = PriceDocumentLlmExtractor.LlmExtractionResult.empty("");
        if (llmRequested && (prices.isEmpty() || supplementaryLlm)) {
            if (llm.available()) {
                llmResult = llm.extractDetailed(context, content.plainText(), source.endpoint());
                prices = mergePrices(prices, llmResult.prices());
                warnings.addAll(llmResult.warnings());
                extractionMethod = prices.isEmpty() ? "LLM_NO_PRICE_RECORD"
                        : deterministicCount > 0 && prices.size() > deterministicCount
                        ? "DETERMINISTIC_WITH_LLM_SUPPLEMENT" : deterministicCount > 0
                        ? "DETERMINISTIC_MAPPING" : "LLM_SCHEMA_MAPPING";
            } else {
                warnings.add("价格源已启用 LLM Schema 映射，但 Control Plane 尚未配置价格文档 LLM Virtual Key");
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
        evidence.put("deterministicRecordCount", deterministicCount);
        evidence.put("llmRecordCount", llmResult.prices().size());
        evidence.put("extractionMethod", extractionMethod);
        evidence.put("llmRequested", llmRequested);
        evidence.put("llmAvailable", llm.available());
        evidence.put("llmModel", llmResult.model());
        evidence.put("llmRequestId", llmResult.requestId());
        evidence.put("llmPromptHash", llmResult.promptHash());
        evidence.put("llmResponseHash", llmResult.responseHash());
        evidence.put("llmLatencyMs", llmResult.latencyMs());
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

    private List<PriceSourceParser.NormalizedPrice> mergePrices(
            List<PriceSourceParser.NormalizedPrice> deterministic,
            List<PriceSourceParser.NormalizedPrice> supplemental) {
        Map<String,PriceSourceParser.NormalizedPrice> merged = new LinkedHashMap<>();
        for (PriceSourceParser.NormalizedPrice price : deterministic) merged.put(priceKey(price), price);
        for (PriceSourceParser.NormalizedPrice price : supplemental) merged.putIfAbsent(priceKey(price), price);
        return new ArrayList<>(merged.values());
    }

    private String priceKey(PriceSourceParser.NormalizedPrice price) {
        return String.join("|", lower(price.providerType()), lower(price.providerModelName()),
                lower(price.region()), lower(price.requestMode()), lower(price.serviceTier()), lower(price.contextTier()));
    }

    private DeterministicResult deterministic(PriceSourceAdapterContext context,
                                              PriceSourceDocument source,
                                              DocumentContent content) {
        List<Map<String,Object>> rows = switch (content.type()) {
            case "JSON" -> jsonRows(content.rawText(), context.config());
            case "CSV" -> csvRows(content.rawText(), context.config());
            case "HTML" -> htmlRows(content.rawText(), context.config());
            case "PDF", "TEXT", "BINARY" -> textRows(content.pages(), context.config());
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
        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("pageNumber", integerOrNull(row.get("_pageNumber")));
        evidence.put("tableIndex", integerOrNull(row.get("_tableIndex")));
        evidence.put("rowIndex", integerOrNull(row.get("_rowIndex")));
        evidence.put("sourceText", value(text(row.get("_sourceText")), row.toString()));
        evidence.put("coordinates", row.get("_coordinates") instanceof Map<?,?> coordinates
                ? coordinates : Map.of());
        raw.put("evidence", evidence);
        raw.put("confidence", new BigDecimal("0.98"));

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
                for (JsonNode row : rows) {
                    if (!row.isObject()) continue;
                    Map<String,Object> value = json.convertValue(row, new TypeReference<>() {});
                    value.put("_sourceText", row.toString());
                    result.add(value);
                }
            } else if (rows.isObject()) {
                if (Boolean.TRUE.equals(config.get("modelFromKey"))) {
                    rows.fields().forEachRemaining(entry -> {
                        if (!entry.getValue().isObject()) return;
                        Map<String,Object> value = json.convertValue(entry.getValue(), new TypeReference<>() {});
                        value.putIfAbsent(Objects.toString(config.getOrDefault("modelField", "model")), entry.getKey());
                        value.put("_sourceText", entry.getKey() + ": " + entry.getValue());
                        result.add(value);
                    });
                } else {
                    Map<String,Object> value = json.convertValue(rows, new TypeReference<>() {});
                    value.put("_sourceText", rows.toString());
                    result.add(value);
                }
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("通用 JSON 文档解析失败", exception);
        }
    }

    private JsonNode jsonPath(JsonNode root, String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.equals("$")) return root;
        if (normalized.startsWith("$.")) normalized = normalized.substring(2);
        else if (normalized.startsWith("$")) normalized = normalized.substring(1);
        if (normalized.contains("..") || normalized.contains("?") || normalized.contains("(")
                || normalized.contains("@") || normalized.length() > 500) {
            throw new IllegalArgumentException("recordsPath 仅支持安全的字段路径和末尾 [*]");
        }
        JsonNode current = root;
        int depth = 0;
        for (String part : normalized.split("\\.")) {
            if (part.isBlank()) continue;
            if (++depth > 12) throw new IllegalArgumentException("recordsPath 最大深度为 12");
            boolean array = part.endsWith("[*]");
            String field = array ? part.substring(0, part.length() - 3) : part;
            if (!field.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("recordsPath 包含不允许的字段名");
            current = current.path(field);
            if (array && !current.isArray()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
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
                row.put("_sourceText", rows.get(rowIndex).text().trim());
                result.add(row);
            }
        }
        return result;
    }

    private List<Map<String,Object>> csvRows(String content, Map<String,Object> config) {
        String normalizedContent = content != null && content.startsWith("\uFEFF") ? content.substring(1) : content;
        char delimiter = delimiter(normalizedContent, config);
        List<List<String>> lines = parseCsv(normalizedContent, delimiter);
        if (lines.size() < 2) return List.of();
        int headerRow = Math.max(0, configuredInt(config, "headerRow", configuredInt(config, "skipRows", 0)));
        if (headerRow >= lines.size() - 1) return List.of();
        List<String> headers = lines.get(headerRow).stream().map(this::normalizeHeader).toList();
        int maxRows = Math.max(1, Math.min(configuredInt(config, "maxRows", 20_000), 100_000));
        List<Map<String,Object>> result = new ArrayList<>();
        for (int rowIndex = headerRow + 1; rowIndex < lines.size() && result.size() < maxRows; rowIndex++) {
            List<String> values = lines.get(rowIndex);
            if (values.stream().allMatch(String::isBlank)) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(headers.size(), values.size()); i++) row.put(headers.get(i), values.get(i).trim());
            row.put("_rowIndex", rowIndex);
            row.put("_sourceText", String.join(String.valueOf(delimiter), values));
            result.add(row);
        }
        return result;
    }

    private char delimiter(String content, Map<String,Object> config) {
        String configured = Objects.toString(config.get("delimiter"), "");
        if (!configured.isBlank()) return "\\t".equals(configured) ? '\t' : configured.charAt(0);
        String first = content == null ? "" : content.lines().findFirst().orElse("");
        long tabs = first.chars().filter(value -> value == '\t').count();
        long semicolons = first.chars().filter(value -> value == ';').count();
        long commas = first.chars().filter(value -> value == ',').count();
        if (tabs > semicolons && tabs > commas) return '\t';
        return semicolons > commas ? ';' : ',';
    }

    private List<Map<String,Object>> textRows(List<PageText> pages, Map<String,Object> config) {
        String expression = Objects.toString(config.get("linePattern"), "").trim();
        if (expression.isBlank()) return List.of();
        Pattern pattern;
        try {
            pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("linePattern 正则表达式无效", exception);
        }
        List<Map<String,Object>> result = new ArrayList<>();
        int maxRows = Math.max(1, Math.min(configuredInt(config, "maxRows", 20_000), 100_000));
        for (PageText page : pages) {
            int lineNumber = 0;
            List<PositionedLine> lines = page.lines().isEmpty()
                    ? page.text().lines().map(text -> new PositionedLine(text, 0, 0, 0, 0)).toList()
                    : page.lines();
            for (PositionedLine positioned : lines) {
                lineNumber++;
                String line = positioned.text();
                Matcher matcher = pattern.matcher(line);
                if (!matcher.find()) continue;
                Map<String,Object> row = new LinkedHashMap<>();
                for (String field : List.of("model", "displayName", "provider", "currency", "input", "output",
                        "cacheRead", "cacheWrite", "region", "requestMode", "serviceTier", "contextTier",
                        "billingBasis", "billingQuantity")) {
                    String value = namedGroup(matcher, field);
                    if (value != null) row.put(field, value);
                }
                row.put("_pageNumber", page.pageNumber());
                row.put("_rowIndex", lineNumber);
                row.put("_sourceText", line.trim());
                row.put("_coordinates", Map.of(
                        "x", positioned.x(), "y", positioned.y(),
                        "width", positioned.width(), "height", positioned.height()));
                if (row.containsKey("model")) result.add(row);
                if (result.size() >= maxRows) return result;
            }
        }
        return result;
    }

    private String namedGroup(Matcher matcher, String name) {
        try { return matcher.group(name); }
        catch (IllegalArgumentException ignored) { return null; }
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

    private DocumentContent documentContent(PriceSourceDocument source, Map<String,Object> config) {
        String contentType = value(source.contentType(), "").toLowerCase(Locale.ROOT);
        String forcedType = Objects.toString(config.get("documentType"), "AUTO").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO","HTML","JSON","CSV","PDF","TEXT","BINARY").contains(forcedType)) {
            throw new IllegalArgumentException("不支持的价格文档类型: " + forcedType);
        }
        String effectiveType = "AUTO".equals(forcedType)
                ? typeDetector.detect(contentType, source.content()) : forcedType;
        if ("PDF".equals(effectiveType)) {
            try {
                byte[] bytes = Base64.getDecoder().decode(source.content());
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    int maxPages = Math.max(1, Math.min(configuredInt(config, "maxPages", document.getNumberOfPages()), 500));
                    List<PageText> pages = new ArrayList<>();
                    StringBuilder plain = new StringBuilder();
                    for (int page = 1; page <= Math.min(document.getNumberOfPages(), maxPages); page++) {
                        PdfPositionStripper stripper = new PdfPositionStripper();
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String pageText = stripper.getText(document).trim();
                        pages.add(new PageText(page, pageText, stripper.lines()));
                        plain.append("\n[PAGE ").append(page).append("]\n").append(pageText);
                    }
                    return new DocumentContent("PDF", source.content(), plain.toString().trim(), 0, pages);
                }
            } catch (Exception exception) {
                throw new IllegalArgumentException("PDF 价格文档文本提取失败", exception);
            }
        }
        if ("JSON".equals(effectiveType)) {
            return new DocumentContent("JSON", source.content(), source.content(), 0,
                    List.of(new PageText(1, source.content(), List.of())));
        }
        if ("CSV".equals(effectiveType))
            return new DocumentContent("CSV", source.content(), source.content(), 0,
                List.of(new PageText(1, source.content(), List.of())));
        if ("HTML".equals(effectiveType)) {
            Document document = Jsoup.parse(source.content());
            return new DocumentContent("HTML", source.content(), document.text(), document.select("table").size(),
                    List.of(new PageText(1, document.text(), List.of())));
        }
        String plain = new String(source.content().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        String fallbackType = effectiveType;
        return new DocumentContent(fallbackType,
                source.content(), plain, 0, List.of(new PageText(1, plain, List.of())));
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
        if (field != null && field.contains(".")) {
            Object current = row;
            int depth = 0;
            for (String part : field.split("\\.")) {
                if (++depth > 12 || !(current instanceof Map<?,?> map)) return null;
                current = map.get(part);
            }
            if (current != null) return current;
        }
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

    private Integer integerOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private static final class PdfPositionStripper extends PDFTextStripper {
        private final List<PositionedLine> lines = new ArrayList<>();

        private PdfPositionStripper() throws java.io.IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
            super.writeString(text, positions);
            String normalized = text == null ? "" : text.trim();
            if (normalized.isBlank() || positions == null || positions.isEmpty()) return;
            float x = positions.stream().map(TextPosition::getXDirAdj).min(Float::compare).orElse(0f);
            float y = positions.stream().map(TextPosition::getYDirAdj).min(Float::compare).orElse(0f);
            float width = positions.stream().map(TextPosition::getWidthDirAdj).reduce(0f, Float::sum);
            float height = positions.stream().map(TextPosition::getHeightDir).max(Float::compare).orElse(0f);
            lines.add(new PositionedLine(normalized, x, y, width, height));
        }

        private List<PositionedLine> lines() { return List.copyOf(lines); }
    }

    private record PositionedLine(String text, float x, float y, float width, float height) {}

    private record PageText(int pageNumber, String text, List<PositionedLine> lines) {
        private PageText {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    private record DocumentContent(String type, String rawText, String plainText, int tableCount,
                                   List<PageText> pages) {}

    private record DeterministicResult(List<PriceSourceParser.NormalizedPrice> prices,
                                       int tableCount,
                                       int matchedTableCount,
                                       int rowCount,
                                       List<String> warnings) {}
}
