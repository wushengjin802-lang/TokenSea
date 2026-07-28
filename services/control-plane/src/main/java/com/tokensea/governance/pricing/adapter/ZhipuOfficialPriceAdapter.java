package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zhipu BigModel official price page adapter.
 *
 * <p>The official page is rendered by JavaScript and Element UI splits every semantic table into
 * separate header/body tables. This adapter first rebuilds semantic tables, then only publishes
 * rows that explicitly expose model, input and output Token prices. Search, fine-tuning, private
 * instance and legacy one-column pricing tables remain evidence and never become guessed prices.</p>
 */
@Component
public class ZhipuOfficialPriceAdapter implements PriceSourceAdapter {
    public static final String ADAPTER_CODE = "ZHIPU_OFFICIAL_PAGE";
    private static final Pattern MODEL_ID = Pattern.compile(
            "\\bglm-[a-z0-9][a-z0-9._-]*\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSED_TIER = Pattern.compile(
            "(?:输入|输出)长度\\s*\\[\\s*([0-9]+(?:\\.[0-9]+)?)\\s*,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\)");
    private static final Pattern OPEN_TIER = Pattern.compile(
            "(?:输入|输出)长度\\s*\\[\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\+\\s*\\)");
    private static final long TOKEN_SECONDS_PER_MILLION_TOKEN_HOUR = 3_600_000_000L;

    private final ObjectMapper json;
    private final OfficialPriceAnalyzer analyzer;

    @Autowired
    public ZhipuOfficialPriceAdapter(ObjectMapper json, OfficialPriceAnalyzer analyzer) {
        this.json = json;
        this.analyzer = analyzer;
    }

    public ZhipuOfficialPriceAdapter(ObjectMapper json) {
        this(json, new OfficialPriceAnalyzer());
    }

    @Override
    public boolean supports(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode);
    }

    @Override
    public PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument source) {
        Document original = Jsoup.parse(source.content(), source.endpoint());
        Document semantic = semanticDocument(original);
        String pageText = OfficialHtmlPriceSupport.normalize(original.text());
        List<PriceSourceParser.NormalizedPrice> prices = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();

        OfficialPriceAnalyzer.ScanResult scan = analyzer.scan(semantic, this::inspectTable);
        for (OfficialPriceAnalyzer.TableContext tableContext : scan.matchedTables()) {
            parseTable(tableContext, context, source, prices, dedupe, warnings);
        }

        boolean headlessRecommended = prices.isEmpty()
                && semantic.select("table").isEmpty()
                && (original.select("script").size() > 5
                || OfficialHtmlPriceSupport.containsAny(pageText, "加载中", "loading", "enable javascript"));
        if (prices.isEmpty()) {
            warnings.add(headlessRecommended
                    ? "智谱官方价格页需要 JavaScript 渲染，建议由独立 Headless Fetcher 获取渲染后 HTML"
                    : "未从智谱官方页面解析出同时具备精确模型 ID、输入价和输出价的 Token 价格记录");
        }

        Map<String,Object> evidence = new LinkedHashMap<>(analyzer.evidence(
                semantic, "zhipu", scan, prices.size(), 0, headlessRecommended));
        evidence.put("originalTableCount", original.select("table").size());
        evidence.put("semanticTableCount", semantic.select("table").size());
        evidence.put("elementUiTableRebuilt", original.select("div.el-table").size());

        return new PriceSourceParseResult(
                prices,
                List.of(),
                List.of(),
                warnings,
                PriceStructureFingerprint.calculate(json, source.content(), source.contentType()),
                evidence,
                headlessRecommended);
    }

    private OfficialPriceAnalyzer.TableDecision inspectTable(OfficialPriceAnalyzer.TableContext tableContext) {
        Element table = tableContext.table();
        List<String> models = modelIds(tableContext.tableText());
        if (models.isEmpty()) return OfficialPriceAnalyzer.TableDecision.ignored();

        PriceHeader header = findHeader(table);
        if (header == null) {
            return OfficialPriceAnalyzer.TableDecision.skipped(
                    "智谱表格包含模型但未同时提供独立输入价和输出价，保留为证据且不推断价格",
                    Map.of("models", models.stream().limit(8).toList()));
        }
        if (!OfficialHtmlPriceSupport.perMillion(tableContext.tableText())) {
            return OfficialPriceAnalyzer.TableDecision.skipped(
                    "智谱价格表未明确声明每百万 Token 计费单位");
        }

        Map<String,Object> details = new LinkedHashMap<>();
        details.put("orientation", "ROW");
        details.put("modelColumn", header.modelIndex());
        details.put("contextColumn", header.contextIndex());
        details.put("inputColumn", header.inputIndex());
        details.put("outputColumn", header.outputIndex());
        details.put("cacheStorageColumn", header.cacheStorageIndex());
        details.put("cacheReadColumn", header.cacheReadIndex());
        details.put("rowspanCarryForward", true);
        return OfficialPriceAnalyzer.TableDecision.matched(details);
    }

    private void parseTable(OfficialPriceAnalyzer.TableContext tableContext,
                            PriceSourceAdapterContext context,
                            PriceSourceDocument source,
                            List<PriceSourceParser.NormalizedPrice> target,
                            Set<String> dedupe,
                            List<String> warnings) {
        Element table = tableContext.table();
        PriceHeader header = findHeader(table);
        if (header == null) return;

        String currency;
        try {
            currency = OfficialHtmlPriceSupport.currency(tableContext.tableText(), context.defaultCurrency());
        } catch (IllegalArgumentException exception) {
            warnings.add("智谱价格表币种校验失败: table[" + tableContext.index() + "] " + exception.getMessage());
            return;
        }

        List<Element> rows = table.select("tr");
        String carriedModel = null;
        for (int rowIndex = header.rowIndex() + 1; rowIndex < rows.size(); rowIndex++) {
            List<Element> cells = rows.get(rowIndex).select("th,td");
            if (cells.isEmpty()) continue;
            int leadingMissing = header.width() - cells.size();
            if (leadingMissing < 0 || leadingMissing > 1 || (leadingMissing == 1 && header.modelIndex() != 0)) {
                warnings.add("智谱价格表行列数量异常，已跳过: table[" + tableContext.index()
                        + "]/row[" + rowIndex + "]");
                continue;
            }

            if (leadingMissing == 0) {
                Element modelCell = logicalCell(cells, header.modelIndex(), 0);
                List<String> rowModels = modelCell == null ? List.of() : modelIds(modelCell.text());
                carriedModel = rowModels.isEmpty() ? null : rowModels.getFirst();
            }
            if (carriedModel == null) continue;

            Element contextCell = logicalCell(cells, header.contextIndex(), leadingMissing);
            Element inputCell = logicalCell(cells, header.inputIndex(), leadingMissing);
            Element outputCell = logicalCell(cells, header.outputIndex(), leadingMissing);
            Element cacheStorageCell = logicalCell(cells, header.cacheStorageIndex(), leadingMissing);
            Element cacheReadCell = logicalCell(cells, header.cacheReadIndex(), leadingMissing);
            PriceValue input = priceValue(inputCell);
            PriceValue output = priceValue(outputCell);
            if (input == null || output == null) continue;

            String contextText = contextCell == null ? "" : OfficialHtmlPriceSupport.normalize(contextCell.text());
            TokenTier inputTier = tokenTier(contextText, "输入");
            TokenTier outputTier = tokenTier(contextText, "输出");
            String tierCode = tierCode(inputTier, outputTier);
            Map<String,Object> componentScope = inputTier.componentScope();
            String rowText = OfficialHtmlPriceSupport.normalize(rows.get(rowIndex).text());
            String requestMode = OfficialHtmlPriceSupport.requestMode(
                    tableContext.heading() + " " + rowText, context.requestMode());
            String region = normalizedRegion(context);
            String key = String.join("|", carriedModel, region, requestMode, "DEFAULT", tierCode);
            if (!dedupe.add(key)) continue;

            PriceValue cacheRead = priceValue(cacheReadCell);
            PriceValue cacheStorage = priceValue(cacheStorageCell);
            String priceNature = input.free() && output.free()
                    ? "FREE_QUOTA"
                    : normalizedPriceNature(context.priceNature());

            Map<String,Object> conditions = new LinkedHashMap<>();
            conditions.put("requestMode", requestMode);
            conditions.put("serviceTier", "DEFAULT");
            if (!inputTier.metadata().isEmpty()) conditions.put("inputTokenTier", inputTier.metadata());
            if (!outputTier.metadata().isEmpty()) conditions.put("outputTokenTier", outputTier.metadata());
            if (cacheStorage != null && cacheStorage.free()) {
                conditions.put("cacheStoragePriceNature", "PROMOTIONAL");
                conditions.put("cacheStoragePromotionText", "限时免费");
            }

            Map<String,Object> components = new LinkedHashMap<>();
            components.put("INPUT_TOKEN", tokenComponent(
                    input.amount(), tierCode, componentScope, source.endpoint(), input.mode(), input.free()));
            components.put("OUTPUT_TOKEN", tokenComponent(
                    output.amount(), tierCode, componentScope, source.endpoint(), output.mode(), output.free()));
            if (cacheRead != null) {
                components.put("CACHE_READ_TOKEN", tokenComponent(
                        cacheRead.amount(), tierCode, componentScope, source.endpoint(),
                        cacheRead.mode(), cacheRead.free()));
            }
            components.put("CACHE_WRITE_TOKEN", cacheWriteNotApplicable(
                    tierCode, componentScope, source.endpoint()));
            if (cacheStorage != null) {
                components.put("CACHE_STORAGE_TOKEN_SECOND", cacheStorageComponent(
                        cacheStorage.amount(), tierCode, componentScope, source.endpoint(),
                        cacheStorage.mode(), cacheStorage.free()));
            }

            String evidencePath = OfficialHtmlPriceSupport.evidencePath(table, rowIndex);
            Map<String,Object> raw = OfficialHtmlPriceSupport.rawMetadata(
                    priceNature, conditions, context.sourcePriority(), evidencePath,
                    carriedModel, source.endpoint());
            raw.put("heading", tableContext.heading());
            raw.put("rowText", rowText);
            raw.put("contextText", contextText);
            raw.put("tableIndex", tableContext.index());
            raw.put("currency", currency);
            raw.put("cacheWritePriceMode", "NOT_APPLICABLE");
            raw.put("cacheStorageLimitedTimeFree", cacheStorage != null && cacheStorage.free());

            target.add(new PriceSourceParser.NormalizedPrice(
                    "zhipu",
                    carriedModel,
                    displayName(carriedModel),
                    currency,
                    "TOKEN",
                    OfficialHtmlPriceSupport.ONE_MILLION,
                    input.amount(),
                    output.amount(),
                    region,
                    requestMode,
                    "DEFAULT",
                    tierCode,
                    components,
                    source.endpoint(),
                    OffsetDateTime.now(),
                    null,
                    raw));
        }
    }

    private Document semanticDocument(Document source) {
        Document semantic = Document.createShell(source.baseUri());
        semantic.title(source.title());
        for (Element wrapper : source.select("div.el-table")) {
            Element header = wrapper.selectFirst("table.el-table__header");
            Element body = wrapper.selectFirst("table.el-table__body");
            if (header == null || body == null) continue;
            appendSemanticTable(semantic, wrapper, header.select("tr"), body.select("tr"));
        }
        for (Element table : source.select("table")) {
            if (insideElementUiTable(table)) continue;
            appendSemanticTable(semantic, table, table.select("tr"), List.of());
        }
        return semantic;
    }

    private void appendSemanticTable(Document semantic, Element evidenceElement,
                                     List<Element> firstRows, List<Element> secondRows) {
        Element section = semantic.body().appendElement("section");
        String heading = OfficialHtmlPriceSupport.nearestHeading(evidenceElement);
        if (!heading.isBlank()) section.appendElement("h3").text(heading);
        Element table = section.appendElement("table");
        for (Element row : firstRows) table.appendChild(row.clone());
        for (Element row : secondRows) table.appendChild(row.clone());
    }

    private static boolean insideElementUiTable(Element table) {
        for (Element parent : table.parents()) {
            if (parent.hasClass("el-table")) return true;
        }
        return false;
    }

    private static PriceHeader findHeader(Element table) {
        List<Element> rows = table.select("tr");
        for (int rowIndex = 0; rowIndex < Math.min(rows.size(), 4); rowIndex++) {
            List<Element> cells = rows.get(rowIndex).select("th,td");
            int model = -1;
            int context = -1;
            int input = -1;
            int output = -1;
            int cacheStorage = -1;
            int cacheRead = -1;
            for (int index = 0; index < cells.size(); index++) {
                String label = OfficialHtmlPriceSupport.compact(cells.get(index).text());
                if (model < 0 && isModelHeader(label)) model = index;
                if (context < 0 && isContextHeader(label)) context = index;
                if (input < 0 && isInputHeader(label)) input = index;
                if (output < 0 && isOutputHeader(label)) output = index;
                if (cacheStorage < 0 && isCacheStorageHeader(label)) cacheStorage = index;
                if (cacheRead < 0 && isCacheReadHeader(label)) cacheRead = index;
            }
            if (model >= 0 && input >= 0 && output >= 0) {
                return new PriceHeader(rowIndex, cells.size(), model, context, input, output,
                        cacheStorage, cacheRead);
            }
        }
        return null;
    }

    private static Element logicalCell(List<Element> cells, int logicalIndex, int leadingMissing) {
        if (logicalIndex < 0) return null;
        int actualIndex = logicalIndex - leadingMissing;
        return actualIndex < 0 || actualIndex >= cells.size() ? null : cells.get(actualIndex);
    }

    private static PriceValue priceValue(Element cell) {
        if (cell == null) return null;
        String text = OfficialHtmlPriceSupport.normalize(cell.text());
        if (OfficialHtmlPriceSupport.containsAny(text, "免费", "free")) {
            return new PriceValue(BigDecimal.ZERO, "EXPLICIT_ZERO", true);
        }
        BigDecimal amount = OfficialHtmlPriceSupport.money(text);
        return amount == null ? null : new PriceValue(amount, "EXPLICIT", false);
    }

    private static Map<String,Object> tokenComponent(BigDecimal price, String variant,
                                                     Map<String,Object> scope, String sourceRef,
                                                     String mode, boolean free) {
        Map<String,Object> component = new LinkedHashMap<>();
        component.put("unitPrice", price);
        component.put("unitBasis", "TOKEN");
        component.put("unitQuantity", OfficialHtmlPriceSupport.ONE_MILLION);
        component.put("variant", variant);
        component.put("mode", mode);
        component.put("priority", 100);
        component.put("scope", scope);
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("officialPage", sourceRef);
        if (free) {
            metadata.put("priceNature", "FREE_QUOTA");
            metadata.put("reason", "官方价格表明确标注免费");
        }
        component.put("metadata", metadata);
        return component;
    }

    private static Map<String,Object> cacheStorageComponent(BigDecimal price, String variant,
                                                            Map<String,Object> scope, String sourceRef,
                                                            String mode, boolean free) {
        Map<String,Object> component = new LinkedHashMap<>();
        component.put("unitPrice", price);
        component.put("unitBasis", "TOKEN_SECOND");
        component.put("unitQuantity", TOKEN_SECONDS_PER_MILLION_TOKEN_HOUR);
        component.put("variant", variant);
        component.put("mode", mode);
        component.put("priority", 100);
        component.put("scope", scope);
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("officialPage", sourceRef);
        metadata.put("officialBillingUnit", "CNY_PER_MILLION_TOKENS_HOUR");
        if (free) {
            metadata.put("priceNature", "PROMOTIONAL");
            metadata.put("promotionText", "限时免费");
        }
        component.put("metadata", metadata);
        return component;
    }

    private static Map<String,Object> cacheWriteNotApplicable(String variant,
                                                              Map<String,Object> scope,
                                                              String sourceRef) {
        Map<String,Object> component = new LinkedHashMap<>();
        component.put("unitBasis", "TOKEN");
        component.put("unitQuantity", OfficialHtmlPriceSupport.ONE_MILLION);
        component.put("variant", variant);
        component.put("mode", "NOT_APPLICABLE");
        component.put("priority", 100);
        component.put("scope", scope);
        component.put("metadata", Map.of(
                "officialPage", sourceRef,
                "reason", "官方价格表仅列缓存存储与缓存命中，未列缓存写入 Token 单价"));
        return component;
    }

    private static TokenTier tokenTier(String text, String dimension) {
        String normalized = OfficialHtmlPriceSupport.normalize(text);
        int marker = normalized.indexOf(dimension + "长度");
        if (marker < 0) return TokenTier.defaultTier();
        String value = normalized.substring(marker);
        int nextMarker = value.indexOf(dimension.equals("输入") ? "输出长度" : "输入长度", 1);
        if (nextMarker > 0) value = value.substring(0, nextMarker);

        Matcher closed = CLOSED_TIER.matcher(value);
        if (closed.find()) {
            long min = thousandTokens(closed.group(1));
            long maxExclusive = thousandTokens(closed.group(2));
            return new TokenTier(min, Math.max(min, maxExclusive - 1), false);
        }
        Matcher open = OPEN_TIER.matcher(value);
        if (open.find()) return new TokenTier(thousandTokens(open.group(1)), null, true);
        return TokenTier.defaultTier();
    }

    private static String tierCode(TokenTier input, TokenTier output) {
        List<String> parts = new ArrayList<>();
        if (!input.isDefault()) parts.add("IN_" + input.code());
        if (!output.isDefault()) parts.add("OUT_" + output.code());
        return parts.isEmpty() ? "DEFAULT" : String.join("_", parts);
    }

    private static long thousandTokens(String value) {
        return new BigDecimal(value).multiply(new BigDecimal("1000")).longValue();
    }

    private static boolean isModelHeader(String label) {
        return label.equals("模型") || label.equals("MODEL") || label.contains("模型名称")
                || label.contains("模型ID") || label.contains("MODELID");
    }

    private static boolean isContextHeader(String label) {
        return label.contains("上下文") || label.equals("CONTEXT");
    }

    private static boolean isInputHeader(String label) {
        return (label.contains("输入") || label.contains("INPUT"))
                && !label.contains("缓存") && !label.contains("CACHE")
                && (label.contains("单价") || label.contains("价格") || label.contains("PRICING"));
    }

    private static boolean isOutputHeader(String label) {
        return (label.contains("输出") || label.contains("OUTPUT"))
                && (label.contains("单价") || label.contains("价格") || label.contains("PRICING"));
    }

    private static boolean isCacheStorageHeader(String label) {
        return (label.contains("缓存") || label.contains("CACHE"))
                && (label.contains("存储") || label.contains("STORAGE"));
    }

    private static boolean isCacheReadHeader(String label) {
        return (label.contains("缓存") || label.contains("CACHE"))
                && (label.contains("命中") || label.contains("HIT") || label.contains("读取"));
    }

    private static List<String> modelIds(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = MODEL_ID.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return List.copyOf(result);
    }

    private static String normalizedRegion(PriceSourceAdapterContext context) {
        return context.region() == null || context.region().isBlank()
                ? "cn"
                : context.region().toLowerCase(Locale.ROOT);
    }

    private static String normalizedPriceNature(String value) {
        return value == null || value.isBlank() ? "ORIGINAL" : value;
    }

    private static String displayName(String model) {
        String value = model.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "glm-5.2" -> "GLM-5.2";
            case "glm-5.1" -> "GLM-5.1";
            case "glm-5-turbo" -> "GLM-5 Turbo";
            case "glm-5" -> "GLM-5";
            case "glm-4.7" -> "GLM-4.7";
            case "glm-4.5-air" -> "GLM-4.5 Air";
            case "glm-4.7-flashx" -> "GLM-4.7 FlashX";
            case "glm-4.7-flash" -> "GLM-4.7 Flash";
            case "glm-5v-turbo" -> "GLM-5V Turbo";
            case "glm-4.6v" -> "GLM-4.6V";
            case "glm-4.6v-flashx" -> "GLM-4.6V FlashX";
            case "glm-4.6v-flash" -> "GLM-4.6V Flash";
            case "glm-4.5v" -> "GLM-4.5V";
            default -> model;
        };
    }

    private record PriceHeader(
            int rowIndex,
            int width,
            int modelIndex,
            int contextIndex,
            int inputIndex,
            int outputIndex,
            int cacheStorageIndex,
            int cacheReadIndex) {}

    private record PriceValue(BigDecimal amount, String mode, boolean free) {}

    private record TokenTier(Long minInclusive, Long maxInclusive, boolean openEnded) {
        static TokenTier defaultTier() {
            return new TokenTier(null, null, false);
        }

        boolean isDefault() {
            return minInclusive == null && maxInclusive == null;
        }

        String code() {
            if (isDefault()) return "DEFAULT";
            return maxInclusive == null
                    ? minInclusive + "_PLUS"
                    : minInclusive + "_" + (maxInclusive + 1);
        }

        Map<String,Object> componentScope() {
            if (isDefault()) return Map.of();
            Map<String,Object> scope = new LinkedHashMap<>();
            scope.put("minContextTokens", minInclusive);
            if (maxInclusive != null) scope.put("maxContextTokens", maxInclusive);
            return scope;
        }

        Map<String,Object> metadata() {
            if (isDefault()) return Map.of();
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("minTokensInclusive", minInclusive);
            if (maxInclusive != null) result.put("maxTokensInclusive", maxInclusive);
            result.put("openEnded", openEnded);
            result.put("pricingApplication", "WHOLE_REQUEST");
            return result;
        }
    }
}
