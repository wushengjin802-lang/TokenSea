package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
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
public class KimiOfficialPriceAdapter implements PriceSourceAdapter {
    private static final Pattern MODEL_ID = Pattern.compile("\\b(?:kimi|moonshot)[a-z0-9._-]+\\b", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper json;
    private final OfficialPriceAnalyzer analyzer;

    @Autowired
    public KimiOfficialPriceAdapter(ObjectMapper json, OfficialPriceAnalyzer analyzer) {
        this.json = json;
        this.analyzer = analyzer;
    }

    public KimiOfficialPriceAdapter(ObjectMapper json) {
        this(json, new OfficialPriceAnalyzer());
    }

    @Override
    public boolean supports(String adapterCode) {
        return "KIMI_OFFICIAL_PAGE".equals(adapterCode);
    }

    @Override
    public PriceSourceParseResult parse(PriceSourceAdapterContext context, PriceSourceDocument source) {
        Document document = Jsoup.parse(source.content(), source.endpoint());
        String pageText = OfficialHtmlPriceSupport.normalize(document.text());
        String currency = OfficialHtmlPriceSupport.currency(pageText, context.defaultCurrency());
        List<PriceSourceParser.NormalizedPrice> prices = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        OfficialPriceAnalyzer.HeaderAliases aliases = headerAliases();
        OfficialPriceAnalyzer.ScanResult scan = analyzer.scan(document, table -> inspectTable(table, aliases));

        if (!OfficialHtmlPriceSupport.perMillion(pageText)) {
            warnings.add("Kimi 官方页面未明确声明每百万 Token 计费单位");
        } else {
            parseColumnTables(scan.matchedTables(), context, source, currency, prices, dedupe, warnings, aliases);
            parseRowTables(scan.matchedTables(), context, source, currency, prices, dedupe, aliases);
            if (prices.isEmpty()) parseSingleModelPage(document, context, source, currency, prices, dedupe, warnings);
        }

        List<PriceSourceParseResult.OfficialSubPage> subPages = discoverPricingLinks(document, source.endpoint());
        boolean headlessRecommended = prices.isEmpty() && (document.select("script").size() > 5
                || OfficialHtmlPriceSupport.containsAny(pageText, "加载中", "loading"));
        if (prices.isEmpty()) {
            warnings.add(headlessRecommended
                    ? "普通 HTTP 内容未包含可安全解析的 Kimi 价格，建议由独立 Headless Fetcher 获取渲染后 HTML"
                    : "未从 Kimi 官方页面解析出同时具备精确模型 ID、输入价和输出价的记录");
        }
        return new PriceSourceParseResult(prices, List.of(), subPages, warnings,
                PriceStructureFingerprint.calculate(json, source.content(), source.contentType()),
                analyzer.evidence(document, "moonshot", scan, prices.size(), subPages.size(), headlessRecommended),
                headlessRecommended);
    }

    private OfficialPriceAnalyzer.TableDecision inspectTable(
            OfficialPriceAnalyzer.TableContext table,
            OfficialPriceAnalyzer.HeaderAliases aliases) {
        boolean providerRelated = OfficialHtmlPriceSupport.containsAny(
                table.scopeText(), "kimi", "moonshot", "模型", "输入价格", "输出价格");
        if (!providerRelated) return OfficialPriceAnalyzer.TableDecision.ignored();

        OfficialPriceAnalyzer.RowHeader rowHeader = analyzer.findRowHeader(table.table(), aliases, true);
        OfficialPriceAnalyzer.ColumnHeader columnHeader = analyzer.findColumnHeader(table.table(), aliases, true);
        if (rowHeader == null && columnHeader == null) {
            return OfficialPriceAnalyzer.TableDecision.skipped("未发现可安全关联模型、输入价和输出价的价格表结构");
        }
        Map<String,Object> details = new LinkedHashMap<>();
        if (rowHeader != null) {
            details.put("orientation", "ROW");
            details.put("modelColumn", rowHeader.modelIndex());
            details.put("inputColumn", rowHeader.inputIndex());
            details.put("outputColumn", rowHeader.outputIndex());
            details.put("cacheReadColumn", rowHeader.cacheReadIndex());
        } else {
            details.put("orientation", "COLUMN");
            details.put("modelRow", columnHeader.modelRowIndex());
            details.put("inputRow", columnHeader.inputRowIndex());
            details.put("outputRow", columnHeader.outputRowIndex());
            details.put("cacheReadRow", columnHeader.cacheReadRowIndex());
        }
        return OfficialPriceAnalyzer.TableDecision.matched(details);
    }

    private OfficialPriceAnalyzer.HeaderAliases headerAliases() {
        return new OfficialPriceAnalyzer.HeaderAliases(
                label -> label.equals("模型") || label.contains("MODELID") || label.contains("模型ID")
                        || label.equals("MODEL") || label.contains("模型名称"),
                KimiOfficialPriceAdapter::isUncachedInputLabel,
                label -> label.contains("输出") || label.contains("OUTPUT"),
                KimiOfficialPriceAdapter::isCacheReadLabel
        );
    }

    private void parseColumnTables(List<OfficialPriceAnalyzer.TableContext> tables,
                                   PriceSourceAdapterContext context,
                                   PriceSourceDocument source, String currency,
                                   List<PriceSourceParser.NormalizedPrice> prices,
                                   Set<String> dedupe, List<String> warnings,
                                   OfficialPriceAnalyzer.HeaderAliases aliases) {
        for (OfficialPriceAnalyzer.TableContext tableContext : tables) {
            Element table = tableContext.table();
            if (analyzer.findColumnHeader(table, aliases, true) == null) continue;
            List<Element> rows = table.select("tr");
            List<String> models = List.of();
            List<BigDecimal> input = List.of();
            List<BigDecimal> output = List.of();
            List<BigDecimal> cacheRead = List.of();
            int evidenceRow = 0;
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                List<Element> cells = rows.get(rowIndex).select("th,td");
                if (cells.size() < 2) continue;
                String label = OfficialHtmlPriceSupport.compact(cells.get(0).text());
                if (aliases.model().test(label)) {
                    List<String> found = new ArrayList<>();
                    for (int index = 1; index < cells.size(); index++) found.addAll(modelIds(cells.get(index).text()));
                    if (!found.isEmpty()) {
                        models = List.copyOf(found);
                        evidenceRow = rowIndex;
                    }
                } else if (aliases.input().test(label)) {
                    input = prices(cells, 1);
                } else if (aliases.output().test(label)) {
                    output = prices(cells, 1);
                } else if (aliases.cacheRead().test(label)) {
                    cacheRead = prices(cells, 1);
                }
            }
            if (models.isEmpty()) continue;
            if (input.size() != models.size() || output.size() != models.size()) {
                warnings.add("Kimi 横向价格表模型列与输入/输出价格列数量不一致: table[" + tableContext.index() + "]");
                continue;
            }
            for (int index = 0; index < models.size(); index++) {
                BigDecimal cache = cacheRead.size() == models.size() ? cacheRead.get(index) : null;
                addPrice(models.get(index), input.get(index), output.get(index), cache,
                        tableContext.heading() + " " + table.text(), context, source, currency,
                        OfficialHtmlPriceSupport.evidencePath(table, evidenceRow), prices, dedupe);
            }
        }
    }

    private void parseRowTables(List<OfficialPriceAnalyzer.TableContext> tables,
                                PriceSourceAdapterContext context,
                                PriceSourceDocument source, String currency,
                                List<PriceSourceParser.NormalizedPrice> prices,
                                Set<String> dedupe,
                                OfficialPriceAnalyzer.HeaderAliases aliases) {
        for (OfficialPriceAnalyzer.TableContext tableContext : tables) {
            Element table = tableContext.table();
            OfficialPriceAnalyzer.RowHeader header = analyzer.findRowHeader(table, aliases, true);
            if (header == null) continue;
            List<Element> rows = table.select("tr");
            for (int rowIndex = header.rowIndex() + 1; rowIndex < rows.size(); rowIndex++) {
                List<Element> cells = rows.get(rowIndex).select("th,td");
                if (cells.size() <= header.modelIndex()) continue;
                List<String> models = modelIds(cells.get(header.modelIndex()).text());
                if (models.isEmpty()) continue;
                BigDecimal input = priceAt(cells, header.inputIndex());
                BigDecimal output = priceAt(cells, header.outputIndex());
                BigDecimal cache = priceAt(cells, header.cacheReadIndex());
                if (input == null || output == null) continue;
                for (String model : models) {
                    addPrice(model, input, output, cache,
                            tableContext.heading() + " " + rows.get(rowIndex).text(),
                            context, source, currency, OfficialHtmlPriceSupport.evidencePath(table, rowIndex),
                            prices, dedupe);
                }
            }
        }
    }

    private void parseSingleModelPage(Document document, PriceSourceAdapterContext context,
                                      PriceSourceDocument source, String currency,
                                      List<PriceSourceParser.NormalizedPrice> prices,
                                      Set<String> dedupe, List<String> warnings) {
        List<String> models = preferredModelIds(document);
        if (models.size() != 1) {
            if (models.size() > 1) warnings.add("Kimi 页面包含多个模型 ID，但价格结构不能安全关联到具体模型");
            return;
        }
        BigDecimal input = null;
        BigDecimal output = null;
        BigDecimal cache = null;
        String evidence = "document";
        for (Element element : document.select("tr,li,p,div")) {
            String text = OfficialHtmlPriceSupport.normalize(element.text());
            if (text.length() > 500) continue;
            String label = OfficialHtmlPriceSupport.compact(text);
            if (input == null && isUncachedInputLabel(label)) {
                input = OfficialHtmlPriceSupport.explicitMoney(text);
                if (input != null) evidence = element.cssSelector();
            }
            if (output == null && (label.contains("输出") || label.contains("OUTPUT"))) {
                output = OfficialHtmlPriceSupport.explicitMoney(text);
                if (output != null) evidence = element.cssSelector();
            }
            if (cache == null && isCacheReadLabel(label)) {
                cache = OfficialHtmlPriceSupport.explicitMoney(text);
            }
        }
        if (input != null && output != null) {
            addPrice(models.get(0), input, output, cache, document.title() + " " + document.body().text(),
                    context, source, currency, evidence, prices, dedupe);
        }
    }

    private void addPrice(String model, BigDecimal input, BigDecimal output, BigDecimal cache,
                          String semanticText, PriceSourceAdapterContext context,
                          PriceSourceDocument source, String currency, String evidencePath,
                          List<PriceSourceParser.NormalizedPrice> target, Set<String> dedupe) {
        String requestMode = OfficialHtmlPriceSupport.requestMode(semanticText, context.requestMode());
        String serviceTier = OfficialHtmlPriceSupport.serviceTier(model);
        if ("DEFAULT".equals(serviceTier)) {
            String semanticTier = OfficialHtmlPriceSupport.serviceTier(semanticText);
            if (Set.of("THINKING", "NON_THINKING").contains(semanticTier)) serviceTier = semanticTier;
        }
        OfficialHtmlPriceSupport.RangeTier tier = OfficialHtmlPriceSupport.rangeTier(semanticText);
        String priceNature = OfficialHtmlPriceSupport.priceNature(semanticText, context.priceNature());
        String region = context.region() == null || context.region().isBlank() ? "cn" : context.region().toLowerCase(Locale.ROOT);
        String key = String.join("|", model, region, requestMode, serviceTier, tier.code());
        if (!dedupe.add(key)) return;

        Map<String,Object> conditions = new LinkedHashMap<>();
        conditions.put("requestMode", requestMode);
        conditions.put("serviceTier", serviceTier);
        if (!tier.scope().isEmpty()) conditions.put("inputTokenTier", tier.scope());
        if ("PROMOTIONAL".equals(priceNature)) conditions.put("promotionEvidence", OfficialHtmlPriceSupport.normalize(semanticText));
        Map<String,Object> components = new LinkedHashMap<>();
        components.put("INPUT_TOKEN", OfficialHtmlPriceSupport.tokenComponent(input, tier.code(), tier.scope(), source.endpoint()));
        components.put("OUTPUT_TOKEN", OfficialHtmlPriceSupport.tokenComponent(output, tier.code(), tier.scope(), source.endpoint()));
        if (cache != null) components.put("CACHE_READ_TOKEN",
                OfficialHtmlPriceSupport.tokenComponent(cache, tier.code(), tier.scope(), source.endpoint()));
        components.put("CACHE_WRITE_TOKEN",
                OfficialHtmlPriceSupport.inheritedInputTokenComponent(
                        tier.code(), tier.scope(), source.endpoint()));
        Map<String,Object> raw = OfficialHtmlPriceSupport.rawMetadata(
                priceNature, conditions, context.sourcePriority(), evidencePath, model, source.endpoint());
        raw.put("semanticText", OfficialHtmlPriceSupport.normalize(semanticText));
        raw.put("currency", currency);

        target.add(new PriceSourceParser.NormalizedPrice(
                "moonshot", model.toLowerCase(Locale.ROOT), model, currency,
                "TOKEN", OfficialHtmlPriceSupport.ONE_MILLION, input, output, region,
                requestMode, serviceTier, tier.code(), components, source.endpoint(),
                OffsetDateTime.now(), null, raw));
    }

    private List<PriceSourceParseResult.OfficialSubPage> discoverPricingLinks(Document document, String endpoint) {
        List<PriceSourceParseResult.OfficialSubPage> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String canonicalEndpoint = canonicalPricingUrl(endpoint);
        for (Element link : document.select("a[href]")) {
            String canonical = canonicalPricingUrl(link.absUrl("href"));
            if (canonical.isBlank() || canonical.equals(canonicalEndpoint)
                    || !isSupportedPricingPage(canonical)) continue;
            String label = OfficialHtmlPriceSupport.normalize(link.text());
            if (seen.add(canonical)) result.add(new PriceSourceParseResult.OfficialSubPage(
                    canonical, label, OfficialHtmlPriceSupport.hash(canonical + ':' + label)));
        }
        return result;
    }

    public static boolean isSupportedPricingPage(String raw) {
        String canonical = canonicalPricingUrl(raw);
        if (canonical.isBlank()) return false;
        try {
            URI uri = URI.create(canonical);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"platform.kimi.com".equalsIgnoreCase(uri.getHost())) return false;
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return path.matches("^/docs/pricing/chat-[a-z0-9][a-z0-9-]*$")
                    || path.equals("/docs/pricing/batch");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String canonicalPricingUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI uri = URI.create(raw.trim());
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    path, uri.getQuery(), null).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<String> preferredModelIds(Document document) {
        Set<String> result = new LinkedHashSet<>();
        for (Element code : document.select("code")) result.addAll(modelIds(code.text()));
        if (result.isEmpty()) result.addAll(modelIds(document.select("h1,h2,h3").text()));
        if (result.isEmpty()) result.addAll(modelIds(document.text()));
        return List.copyOf(result);
    }

    private static List<String> modelIds(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = MODEL_ID.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return List.copyOf(result);
    }

    private static boolean isUncachedInputLabel(String label) {
        return (label.contains("输入") || label.contains("INPUT"))
                && (!label.contains("缓存") || label.contains("缓存未命中")
                || label.contains("CACHEMISS") || label.contains("UNCACHED"));
    }

    private static boolean isCacheReadLabel(String label) {
        return (label.contains("缓存") || label.contains("CACHE"))
                && !label.contains("未命中")
                && !label.contains("MISS")
                && (label.contains("命中") || label.contains("读取") || label.contains("CACHEHIT"));
    }

    private static List<BigDecimal> prices(List<Element> cells, int startIndex) {
        List<BigDecimal> result = new ArrayList<>();
        for (int index = startIndex; index < cells.size(); index++) {
            BigDecimal value = OfficialHtmlPriceSupport.money(cells.get(index).text());
            if (value != null) result.add(value);
        }
        return result;
    }

    private static BigDecimal priceAt(List<Element> cells, int index) {
        if (index < 0 || index >= cells.size()) return null;
        return OfficialHtmlPriceSupport.money(cells.get(index).text());
    }
}
