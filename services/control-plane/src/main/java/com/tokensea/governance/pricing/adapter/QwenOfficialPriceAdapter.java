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

@Component
public class QwenOfficialPriceAdapter implements PriceSourceAdapter {
    private static final Pattern MODEL_ID = Pattern.compile("\\b(?:qwen|qwq)[a-z0-9._-]+\\b", Pattern.CASE_INSENSITIVE);
    private static final BigDecimal CACHE_READ_DISCOUNT = new BigDecimal("0.10");
    private static final BigDecimal CACHE_WRITE_MULTIPLIER = new BigDecimal("1.25");
    private final ObjectMapper json;
    private final OfficialPriceAnalyzer analyzer;

    @Autowired
    public QwenOfficialPriceAdapter(ObjectMapper json, OfficialPriceAnalyzer analyzer) {
        this.json = json;
        this.analyzer = analyzer;
    }

    public QwenOfficialPriceAdapter(ObjectMapper json) {
        this(json, new OfficialPriceAnalyzer());
    }

    @Override
    public boolean supports(String adapterCode) {
        return "QWEN_OFFICIAL_PAGE".equals(adapterCode);
    }

    @Override
    public PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument source) {
        Document document = Jsoup.parse(source.content(), source.endpoint());
        String pageText = OfficialHtmlPriceSupport.normalize(document.text());
        List<PriceSourceParser.NormalizedPrice> prices = new ArrayList<>();
        List<PriceSourceParseResult.ModelAliasCandidate> aliases = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        OfficialPriceAnalyzer.HeaderAliases headerAliases = headerAliases();
        OfficialPriceAnalyzer.ScanResult scan = analyzer.scan(document, table -> inspectTable(table, context, headerAliases));

        for (OfficialPriceAnalyzer.TableContext tableContext : scan.matchedTables()) {
            Element table = tableContext.table();
            OfficialPriceAnalyzer.RowHeader header = analyzer.findRowHeader(table, headerAliases, false);
            if (header == null) continue;
            if (!OfficialHtmlPriceSupport.perMillion(tableContext.scopeText())
                    && !OfficialHtmlPriceSupport.perMillion(pageText)) {
                warnings.add("千问价格表未明确声明每百万 Token 计费单位: table[" + tableContext.index() + "]");
                continue;
            }
            String currency = OfficialHtmlPriceSupport.currency(tableContext.scopeText(), context.defaultCurrency());
            List<Element> rows = table.select("tr");
            for (int rowIndex = header.rowIndex() + 1; rowIndex < rows.size(); rowIndex++) {
                List<Element> cells = rows.get(rowIndex).select("th,td");
                if (cells.size() <= header.modelIndex()) continue;
                List<String> modelIds = modelIds(cells.get(header.modelIndex()).text());
                if (modelIds.isEmpty()) continue;
                BigDecimal input = priceAt(cells, header.inputIndex());
                BigDecimal output = priceAt(cells, header.outputIndex());
                BigDecimal cacheRead = priceAt(cells, header.cacheReadIndex());
                if (input == null && output == null) continue;
                String rowText = OfficialHtmlPriceSupport.normalize(rows.get(rowIndex).text());
                String semanticText = tableContext.heading() + " " + rowText;
                OfficialHtmlPriceSupport.RangeTier tier = OfficialHtmlPriceSupport.rangeTier(semanticText);
                String requestMode = OfficialHtmlPriceSupport.requestMode(semanticText, context.requestMode());
                String serviceTier = OfficialHtmlPriceSupport.serviceTier(semanticText);
                String priceNature = OfficialHtmlPriceSupport.priceNature(semanticText, context.priceNature());
                String evidencePath = OfficialHtmlPriceSupport.evidencePath(table, rowIndex);
                Map<String,Object> conditions = new LinkedHashMap<>();
                conditions.put("requestMode", requestMode);
                conditions.put("serviceTier", serviceTier);
                if (!tier.scope().isEmpty()) conditions.put("inputTokenTier", tier.scope());
                if ("PROMOTIONAL".equals(priceNature)) conditions.put("promotionEvidence", rowText);
                Map<String,Object> components = new LinkedHashMap<>();
                if (input != null) components.put("INPUT_TOKEN",
                        OfficialHtmlPriceSupport.tokenComponent(input, tier.code(), tier.scope(), source.endpoint()));
                if (output != null) components.put("OUTPUT_TOKEN",
                        OfficialHtmlPriceSupport.tokenComponent(output, tier.code(), tier.scope(), source.endpoint()));
                if (cacheRead != null) components.put("CACHE_READ_TOKEN",
                        OfficialHtmlPriceSupport.tokenComponent(cacheRead, tier.code(), tier.scope(), source.endpoint()));
                if (supportsContextCache(rowText) && input != null) {
                    components.put("CACHE_READ_TOKEN", OfficialHtmlPriceSupport.tokenComponent(
                            input.multiply(CACHE_READ_DISCOUNT), tier.code(), tier.scope(), source.endpoint()));
                    components.put("CACHE_WRITE_TOKEN", OfficialHtmlPriceSupport.tokenComponent(
                            input.multiply(CACHE_WRITE_MULTIPLIER), tier.code(), tier.scope(), source.endpoint()));
                }

                for (String modelId : modelIds) {
                    String key = String.join("|", modelId, normalizeRegion(context.region()), requestMode, serviceTier, tier.code());
                    if (!dedupe.add(key)) continue;
                    Map<String,Object> raw = OfficialHtmlPriceSupport.rawMetadata(
                            priceNature, conditions, context.sourcePriority(), evidencePath, modelId, source.endpoint());
                    raw.put("heading", tableContext.heading());
                    raw.put("rowText", rowText);
                    raw.put("tableIndex", tableContext.index());
                    raw.put("currency", currency);
                    prices.add(new PriceSourceParser.NormalizedPrice(
                            "qwen", modelId, modelId, currency, "TOKEN", OfficialHtmlPriceSupport.ONE_MILLION,
                            input == null ? BigDecimal.ZERO : input,
                            output == null ? BigDecimal.ZERO : output,
                            normalizeRegion(context.region()), requestMode, serviceTier, tier.code(), components,
                            source.endpoint(), OffsetDateTime.now(), null, raw));
                }
                aliases.addAll(aliasCandidates(modelIds, rowText, context, source.endpoint(), evidencePath));
            }
        }

        List<PriceSourceParseResult.OfficialSubPage> subPages = discoverPricingLinks(document, source.endpoint());
        boolean headlessRecommended = prices.isEmpty() && document.select("script").size() > 5
                && document.select("table").isEmpty();
        if (prices.isEmpty()) {
            warnings.add(headlessRecommended
                    ? "普通 HTTP 内容未包含可解析价格表，建议由独立 Headless Fetcher 获取渲染后 HTML"
                    : "未从千问官方页面解析出安全可用的价格记录");
        }
        return new PriceSourceParseResult(prices, aliases, subPages, warnings,
                PriceStructureFingerprint.calculate(json, source.content(), source.contentType()),
                analyzer.evidence(document, "qwen", scan, prices.size(), subPages.size(), headlessRecommended),
                headlessRecommended);
    }

    private OfficialPriceAnalyzer.TableDecision inspectTable(
            OfficialPriceAnalyzer.TableContext table,
            PriceSourceAdapterContext context,
            OfficialPriceAnalyzer.HeaderAliases aliases) {
        if (!OfficialHtmlPriceSupport.containsAny(table.scopeText(), "千问", "qwen", "qwq")) {
            return OfficialPriceAnalyzer.TableDecision.ignored();
        }
        if (isOtherRegion(table.scopeText(), context.region())) {
            return OfficialPriceAnalyzer.TableDecision.skipped("价格区域与当前价格源配置不匹配");
        }
        OfficialPriceAnalyzer.RowHeader header = analyzer.findRowHeader(table.table(), aliases, false);
        if (header == null) {
            return OfficialPriceAnalyzer.TableDecision.skipped("未发现模型、输入或输出价格字段");
        }
        Map<String,Object> details = new LinkedHashMap<>();
        details.put("orientation", "ROW");
        details.put("modelColumn", header.modelIndex());
        details.put("inputColumn", header.inputIndex());
        details.put("outputColumn", header.outputIndex());
        details.put("cacheReadColumn", header.cacheReadIndex());
        return OfficialPriceAnalyzer.TableDecision.matched(details);
    }

    private OfficialPriceAnalyzer.HeaderAliases headerAliases() {
        return new OfficialPriceAnalyzer.HeaderAliases(
                QwenOfficialPriceAdapter::isModelHeader,
                QwenOfficialPriceAdapter::isInputHeader,
                QwenOfficialPriceAdapter::isOutputHeader,
                label -> label.contains("缓存")
                        && (label.contains("读取") || label.contains("命中") || label.contains("CACHE"))
        );
    }

    private List<PriceSourceParseResult.ModelAliasCandidate> aliasCandidates(
            List<String> modelIds, String rowText, PriceSourceAdapterContext context,
            String sourceRef, String evidencePath) {
        if (modelIds.size() < 2 || !OfficialHtmlPriceSupport.containsAny(rowText, "等同于", "等价", "指向", "对应")) {
            return List.of();
        }
        String alias = modelIds.get(0);
        String target = modelIds.get(1);
        return List.of(new PriceSourceParseResult.ModelAliasCandidate(
                "qwen", alias, target, "STABLE_ALIAS", normalizeRegion(context.region()), sourceRef,
                OfficialHtmlPriceSupport.hash(alias + ':' + target + ':' + rowText),
                Map.of("evidencePath", evidencePath, "rowText", rowText)));
    }

    private List<PriceSourceParseResult.OfficialSubPage> discoverPricingLinks(Document document, String endpoint) {
        List<PriceSourceParseResult.OfficialSubPage> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href]")) {
            String href = link.absUrl("href");
            if (href.isBlank() || href.equals(endpoint) || !href.contains("help.aliyun.com/zh/model-studio/")) continue;
            String label = OfficialHtmlPriceSupport.normalize(link.text());
            if (!OfficialHtmlPriceSupport.containsAny(label + " " + href, "价格", "pricing", "计费")) continue;
            if (seen.add(href)) result.add(new PriceSourceParseResult.OfficialSubPage(
                    href, label, OfficialHtmlPriceSupport.hash(href + ':' + label)));
        }
        return result;
    }

    private static List<String> modelIds(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = MODEL_ID.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return List.copyOf(result);
    }

    private static BigDecimal priceAt(List<Element> cells, int index) {
        if (index < 0 || index >= cells.size()) return null;
        return OfficialHtmlPriceSupport.money(cells.get(index).text());
    }

    private static boolean isOtherRegion(String value, String configuredRegion) {
        if (!"cn".equalsIgnoreCase(configuredRegion)) return false;
        String text = OfficialHtmlPriceSupport.normalize(value);
        boolean china = OfficialHtmlPriceSupport.containsAny(text, "中国内地", "中国大陆", "大陆地区");
        boolean international = OfficialHtmlPriceSupport.containsAny(text, "国际", "新加坡", "美国", "欧洲", "海外");
        return international && !china;
    }

    private static String normalizeRegion(String value) {
        return value == null || value.isBlank() ? "cn" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isModelHeader(String label) {
        return OfficialHtmlPriceSupport.containsAny(label,
                "MODELID", "模型", "模型名称", "模型ID", "MODEL", "MODELNAME", "模型编号");
    }

    private static boolean isInputHeader(String label) {
        return OfficialHtmlPriceSupport.containsAny(label,
                "输入", "INPUT", "输入TOKEN", "INPUTTOKEN", "输入价格", "输入费用")
                && !OfficialHtmlPriceSupport.containsAny(label, "缓存", "范围", "区间", "RANGE");
    }

    private static boolean supportsContextCache(String rowText) {
        return OfficialHtmlPriceSupport.containsAny(rowText, "上下文缓存", "CONTEXT CACHE");
    }

    private static boolean isOutputHeader(String label) {
        return OfficialHtmlPriceSupport.containsAny(label,
                "输出", "OUTPUT", "输出TOKEN", "OUTPUTTOKEN", "输出价格", "输出费用");
    }
}
