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
 * Xiaomi MiMo official pay-as-you-go price page adapter.
 *
 * <p>The first implementation deliberately publishes only text-model Token prices. ASR, TTS and
 * web-search prices use different billing bases and remain source evidence until their runtime
 * metering paths are implemented.</p>
 */
@Component
public class XiaomiMimoOfficialPriceAdapter implements PriceSourceAdapter {
    public static final String ADAPTER_CODE = "XIAOMI_MIMO_OFFICIAL_PAGE";
    private static final Pattern MODEL_ID = Pattern.compile(
            "\\bmimo-v[0-9]+(?:\\.[0-9]+)*(?:-[a-z0-9._-]+)?\\b",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper json;
    private final OfficialPriceAnalyzer analyzer;

    @Autowired
    public XiaomiMimoOfficialPriceAdapter(ObjectMapper json, OfficialPriceAnalyzer analyzer) {
        this.json = json;
        this.analyzer = analyzer;
    }

    public XiaomiMimoOfficialPriceAdapter(ObjectMapper json) {
        this(json, new OfficialPriceAnalyzer());
    }

    @Override
    public boolean supports(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode);
    }

    @Override
    public PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument source) {
        Document document = Jsoup.parse(source.content(), source.endpoint());
        String pageText = OfficialHtmlPriceSupport.normalize(document.text());
        List<PriceSourceParser.NormalizedPrice> prices = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();

        OfficialPriceAnalyzer.ScanResult scan = analyzer.scan(
                document,
                table -> inspectTable(table, context));

        if (!OfficialHtmlPriceSupport.perMillion(pageText)) {
            warnings.add("Xiaomi MiMo 官方页面未明确声明每百万 Token 计费单位");
        } else {
            for (OfficialPriceAnalyzer.TableContext tableContext : scan.matchedTables()) {
                parseTable(tableContext, context, source, prices, dedupe, warnings);
            }
        }

        boolean headlessRecommended = prices.isEmpty()
                && (document.select("script").size() > 5
                || OfficialHtmlPriceSupport.containsAny(pageText, "加载中", "loading"))
                && document.select("table").isEmpty();
        if (prices.isEmpty()) {
            warnings.add(headlessRecommended
                    ? "普通 HTTP 内容未包含可安全解析的 Xiaomi MiMo 价格，建议由独立 Headless Fetcher 获取渲染后 HTML"
                    : "未从 Xiaomi MiMo 官方页面解析出同时具备精确模型 ID、缓存未命中输入价和输出价的文本模型记录");
        }

        return new PriceSourceParseResult(
                prices,
                List.of(),
                List.of(),
                warnings,
                PriceStructureFingerprint.calculate(json, source.content(), source.contentType()),
                analyzer.evidence(document, "xiaomi_mimo", scan, prices.size(), 0, headlessRecommended),
                headlessRecommended);
    }

    private OfficialPriceAnalyzer.TableDecision inspectTable(
            OfficialPriceAnalyzer.TableContext table,
            PriceSourceAdapterContext context) {
        List<String> models = modelIds(table.tableText());
        if (models.isEmpty()) return OfficialPriceAnalyzer.TableDecision.ignored();

        if (models.stream().allMatch(XiaomiMimoOfficialPriceAdapter::isNonTextModel)) {
            return OfficialPriceAnalyzer.TableDecision.skipped(
                    "首期仅解析文本模型 Token 价格，ASR/TTS 等非 Token 计价保留为原始证据");
        }
        if (wrongRegion(table.scopeText(), context)) {
            return OfficialPriceAnalyzer.TableDecision.skipped("价格区域或币种与当前价格源配置不匹配");
        }

        PriceHeader header = findHeader(table.table());
        if (header == null) {
            return OfficialPriceAnalyzer.TableDecision.skipped(
                    "未发现可安全关联模型、缓存未命中输入价和输出价的价格表结构");
        }
        Map<String,Object> details = new LinkedHashMap<>();
        details.put("orientation", "ROW");
        details.put("modelColumn", header.modelIndex());
        details.put("inputColumn", header.inputIndex());
        details.put("outputColumn", header.outputIndex());
        details.put("cacheReadColumn", header.cacheReadIndex());
        details.put("cacheWriteMode", "EXPLICIT_ZERO");
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
            currency = OfficialHtmlPriceSupport.currency(tableContext.scopeText(), context.defaultCurrency());
        } catch (IllegalArgumentException exception) {
            warnings.add("Xiaomi MiMo 价格表币种校验失败: table[" + tableContext.index() + "] " + exception.getMessage());
            return;
        }

        List<Element> rows = table.select("tr");
        for (int rowIndex = header.rowIndex() + 1; rowIndex < rows.size(); rowIndex++) {
            List<Element> cells = rows.get(rowIndex).select("th,td");
            if (cells.size() <= header.modelIndex()) continue;
            List<String> models = modelIds(cells.get(header.modelIndex()).text()).stream()
                    .filter(model -> !isNonTextModel(model))
                    .toList();
            if (models.isEmpty()) continue;

            BigDecimal input = priceAt(cells, header.inputIndex());
            BigDecimal output = priceAt(cells, header.outputIndex());
            BigDecimal cacheRead = priceAt(cells, header.cacheReadIndex());
            if (input == null || output == null) continue;

            String rowText = OfficialHtmlPriceSupport.normalize(rows.get(rowIndex).text());
            String semanticText = tableContext.heading() + " " + rowText;
            String requestMode = OfficialHtmlPriceSupport.requestMode(semanticText, context.requestMode());
            String priceNature = OfficialHtmlPriceSupport.priceNature(semanticText, context.priceNature());
            String region = normalizedRegion(context);
            String evidencePath = OfficialHtmlPriceSupport.evidencePath(table, rowIndex);

            for (String model : models) {
                String key = String.join("|", model, region, requestMode, "DEFAULT", "DEFAULT");
                if (!dedupe.add(key)) continue;

                Map<String,Object> pricingConditions = new LinkedHashMap<>();
                pricingConditions.put("requestMode", requestMode);
                pricingConditions.put("serviceTier", "DEFAULT");
                pricingConditions.put("cacheWritePriceNature", "PROMOTIONAL");
                pricingConditions.put("cacheWritePromotionText", "缓存写入限时免费");

                Map<String,Object> components = new LinkedHashMap<>();
                components.put("INPUT_TOKEN", OfficialHtmlPriceSupport.tokenComponent(
                        input, "DEFAULT", Map.of(), source.endpoint()));
                components.put("OUTPUT_TOKEN", OfficialHtmlPriceSupport.tokenComponent(
                        output, "DEFAULT", Map.of(), source.endpoint()));
                if (cacheRead != null) {
                    components.put("CACHE_READ_TOKEN", OfficialHtmlPriceSupport.tokenComponent(
                            cacheRead, "DEFAULT", Map.of(), source.endpoint()));
                }
                components.put("CACHE_WRITE_TOKEN", freeCacheWriteComponent(source.endpoint()));

                Map<String,Object> raw = OfficialHtmlPriceSupport.rawMetadata(
                        priceNature,
                        pricingConditions,
                        context.sourcePriority(),
                        evidencePath,
                        model,
                        source.endpoint());
                raw.put("heading", tableContext.heading());
                raw.put("rowText", rowText);
                raw.put("tableIndex", tableContext.index());
                raw.put("currency", currency);
                raw.put("cacheWriteLimitedTimeFree", true);

                target.add(new PriceSourceParser.NormalizedPrice(
                        "xiaomi_mimo",
                        model,
                        displayName(model),
                        currency,
                        "TOKEN",
                        OfficialHtmlPriceSupport.ONE_MILLION,
                        input,
                        output,
                        region,
                        requestMode,
                        "DEFAULT",
                        "DEFAULT",
                        components,
                        source.endpoint(),
                        OffsetDateTime.now(),
                        null,
                        raw));
            }
        }
    }

    private PriceHeader findHeader(Element table) {
        List<Element> rows = table.select("tr");
        for (int rowIndex = 0; rowIndex < Math.min(rows.size(), 4); rowIndex++) {
            List<Element> headerCells = rows.get(rowIndex).select("th,td");
            int modelIndex = -1;
            int inputIndex = -1;
            int outputIndex = -1;
            int cacheReadIndex = -1;

            for (int index = 0; index < headerCells.size(); index++) {
                String label = OfficialHtmlPriceSupport.compact(headerCells.get(index).text());
                if (modelIndex < 0 && isModelHeader(label)) modelIndex = index;
                if (inputIndex < 0 && isUncachedInputHeader(label)) inputIndex = index;
                if (outputIndex < 0 && isOutputHeader(label)) outputIndex = index;
                if (cacheReadIndex < 0 && isCacheReadHeader(label)) cacheReadIndex = index;
            }

            if (inputIndex < 0 || outputIndex < 0) continue;
            if (modelIndex < 0) {
                int dataWidth = firstModelRowWidth(rows, rowIndex + 1);
                if (dataWidth <= 0) continue;
                int shift = Math.max(0, dataWidth - headerCells.size());
                modelIndex = 0;
                inputIndex += shift;
                outputIndex += shift;
                if (cacheReadIndex >= 0) cacheReadIndex += shift;
            }
            return new PriceHeader(rowIndex, modelIndex, inputIndex, outputIndex, cacheReadIndex);
        }
        return null;
    }

    private int firstModelRowWidth(List<Element> rows, int startIndex) {
        for (int rowIndex = startIndex; rowIndex < rows.size(); rowIndex++) {
            List<Element> cells = rows.get(rowIndex).select("th,td");
            if (!cells.isEmpty() && !modelIds(cells.get(0).text()).isEmpty()) return cells.size();
        }
        return -1;
    }

    private static Map<String,Object> freeCacheWriteComponent(String sourceRef) {
        Map<String,Object> component = new LinkedHashMap<>();
        component.put("unitPrice", BigDecimal.ZERO);
        component.put("unitBasis", "TOKEN");
        component.put("unitQuantity", OfficialHtmlPriceSupport.ONE_MILLION);
        component.put("variant", "DEFAULT");
        component.put("mode", "EXPLICIT_ZERO");
        component.put("priority", 100);
        component.put("scope", Map.of());
        component.put("metadata", Map.of(
                "officialPage", sourceRef,
                "priceNature", "PROMOTIONAL",
                "promotionText", "缓存写入限时免费"));
        return component;
    }

    private static BigDecimal priceAt(List<Element> cells, int index) {
        if (index < 0 || index >= cells.size()) return null;
        return OfficialHtmlPriceSupport.money(cells.get(index).text());
    }

    private static boolean wrongRegion(String scopeText, PriceSourceAdapterContext context) {
        boolean cnyEvidence = Pattern.compile("人民币|CNY|RMB|[¥￥]|[0-9]\\s*元", Pattern.CASE_INSENSITIVE)
                .matcher(scopeText).find();
        boolean usdEvidence = Pattern.compile("美元|USD|\\$", Pattern.CASE_INSENSITIVE)
                .matcher(scopeText).find();
        boolean wantsChina = "cn".equalsIgnoreCase(context.region())
                || "CNY".equalsIgnoreCase(context.defaultCurrency());
        if (wantsChina) return usdEvidence && !cnyEvidence;
        return cnyEvidence && !usdEvidence;
    }

    private static String normalizedRegion(PriceSourceAdapterContext context) {
        if (context.region() != null && !context.region().isBlank()) {
            return context.region().toLowerCase(Locale.ROOT);
        }
        return "CNY".equalsIgnoreCase(context.defaultCurrency()) ? "cn" : "global";
    }

    private static boolean isModelHeader(String label) {
        return label.equals("模型") || label.equals("MODEL") || label.contains("模型ID")
                || label.contains("MODELID") || label.contains("模型名称");
    }

    private static boolean isUncachedInputHeader(String label) {
        if (!(label.contains("输入") || label.contains("INPUT"))) return false;
        if (label.contains("未命中") || label.contains("CACHEMISS") || label.contains("UNCACHED")) return true;
        return !label.contains("缓存") && !label.contains("CACHE");
    }

    private static boolean isCacheReadHeader(String label) {
        return (label.contains("输入") || label.contains("INPUT"))
                && (label.contains("缓存") || label.contains("CACHE"))
                && !label.contains("未命中")
                && !label.contains("MISS")
                && (label.contains("命中") || label.contains("HIT") || label.contains("读取"));
    }

    private static boolean isOutputHeader(String label) {
        return label.contains("输出") || label.contains("OUTPUT");
    }

    private static boolean isNonTextModel(String model) {
        String value = model.toLowerCase(Locale.ROOT);
        return value.contains("-asr") || value.contains("-tts");
    }

    private static List<String> modelIds(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = MODEL_ID.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return List.copyOf(result);
    }

    private static String displayName(String model) {
        return switch (model.toLowerCase(Locale.ROOT)) {
            case "mimo-v2.5-pro" -> "MiMo V2.5 Pro";
            case "mimo-v2.5" -> "MiMo V2.5";
            default -> model;
        };
    }

    private record PriceHeader(
            int rowIndex,
            int modelIndex,
            int inputIndex,
            int outputIndex,
            int cacheReadIndex) {}
}
