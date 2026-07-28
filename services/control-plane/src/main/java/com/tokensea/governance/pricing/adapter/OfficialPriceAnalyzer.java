package com.tokensea.governance.pricing.adapter;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
public class OfficialPriceAnalyzer {
    public record TableContext(int index, Element table, String heading, String tableText, String scopeText) {}

    public record HeaderAliases(
            Predicate<String> model,
            Predicate<String> input,
            Predicate<String> output,
            Predicate<String> cacheRead
    ) {}

    public record RowHeader(
            int rowIndex,
            int modelIndex,
            int inputIndex,
            int outputIndex,
            int cacheReadIndex
    ) {}

    public record ColumnHeader(
            int modelRowIndex,
            int inputRowIndex,
            int outputRowIndex,
            int cacheReadRowIndex
    ) {}

    public record TableDecision(
            boolean relevant,
            boolean matched,
            String reason,
            Map<String,Object> details
    ) {
        public TableDecision {
            reason = reason == null ? "" : reason;
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static TableDecision ignored() {
            return new TableDecision(false, false, "", Map.of());
        }

        public static TableDecision skipped(String reason) {
            return new TableDecision(true, false, reason, Map.of());
        }

        public static TableDecision skipped(String reason, Map<String,Object> details) {
            return new TableDecision(true, false, reason, details);
        }

        public static TableDecision matched(Map<String,Object> details) {
            return new TableDecision(true, true, "", details);
        }
    }

    public record ScanResult(
            int tableCount,
            int relevantTableCount,
            int matchedTableCount,
            int skippedTableCount,
            List<TableContext> matchedTables,
            List<Map<String,Object>> tableDiagnostics
    ) {
        public ScanResult {
            matchedTables = matchedTables == null ? List.of() : List.copyOf(matchedTables);
            tableDiagnostics = tableDiagnostics == null ? List.of() : List.copyOf(tableDiagnostics);
        }
    }

    public ScanResult scan(Document document, Function<TableContext,TableDecision> inspector) {
        List<TableContext> matchedTables = new ArrayList<>();
        List<Map<String,Object>> diagnostics = new ArrayList<>();
        int relevant = 0;
        int matched = 0;
        int index = 0;
        for (Element table : document.select("table")) {
            String heading = OfficialHtmlPriceSupport.nearestHeading(table);
            String tableText = OfficialHtmlPriceSupport.normalize(table.text());
            TableContext context = new TableContext(index, table, heading, tableText, heading + " " + tableText);
            TableDecision decision = inspector.apply(context);
            if (decision != null && decision.relevant()) {
                relevant++;
                Map<String,Object> diagnostic = new LinkedHashMap<>();
                diagnostic.put("tableIndex", index);
                diagnostic.put("matched", decision.matched());
                if (!heading.isBlank()) diagnostic.put("heading", heading);
                if (!decision.reason().isBlank()) diagnostic.put("reason", decision.reason());
                diagnostic.putAll(decision.details());
                diagnostics.add(diagnostic);
                if (decision.matched()) {
                    matched++;
                    matchedTables.add(context);
                }
            }
            index++;
        }
        return new ScanResult(index, relevant, matched, Math.max(0, relevant - matched), matchedTables, diagnostics);
    }

    public RowHeader findRowHeader(Element table, HeaderAliases aliases, boolean requireInputAndOutput) {
        List<Element> rows = table.select("tr");
        for (int rowIndex = 0; rowIndex < Math.min(rows.size(), 4); rowIndex++) {
            List<Element> cells = rows.get(rowIndex).select("th,td");
            int model = -1;
            int input = -1;
            int output = -1;
            int cacheRead = -1;
            for (int index = 0; index < cells.size(); index++) {
                String label = OfficialHtmlPriceSupport.compact(cells.get(index).text());
                if (model < 0 && aliases.model().test(label)) model = index;
                if (input < 0 && aliases.input().test(label)) input = index;
                if (output < 0 && aliases.output().test(label)) output = index;
                if (cacheRead < 0 && aliases.cacheRead().test(label)) cacheRead = index;
            }
            boolean priceColumnsMatched = requireInputAndOutput ? input >= 0 && output >= 0 : input >= 0 || output >= 0;
            if (model >= 0 && priceColumnsMatched) {
                return new RowHeader(rowIndex, model, input, output, cacheRead);
            }
        }
        return null;
    }

    public ColumnHeader findColumnHeader(Element table, HeaderAliases aliases, boolean requireInputAndOutput) {
        List<Element> rows = table.select("tr");
        int modelRow = -1;
        int inputRow = -1;
        int outputRow = -1;
        int cacheReadRow = -1;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<Element> cells = rows.get(rowIndex).select("th,td");
            if (cells.size() < 2) continue;
            String label = OfficialHtmlPriceSupport.compact(cells.get(0).text());
            if (modelRow < 0 && aliases.model().test(label)) modelRow = rowIndex;
            if (inputRow < 0 && aliases.input().test(label)) inputRow = rowIndex;
            if (outputRow < 0 && aliases.output().test(label)) outputRow = rowIndex;
            if (cacheReadRow < 0 && aliases.cacheRead().test(label)) cacheReadRow = rowIndex;
        }
        boolean priceRowsMatched = requireInputAndOutput
                ? inputRow >= 0 && outputRow >= 0
                : inputRow >= 0 || outputRow >= 0;
        return modelRow >= 0 && priceRowsMatched
                ? new ColumnHeader(modelRow, inputRow, outputRow, cacheReadRow)
                : null;
    }

    public Map<String,Object> evidence(Document document, String providerType, ScanResult scan,
                                       int generatedPriceCount, int discoveredPageCount,
                                       boolean headlessRecommended) {
        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("pageTitle", document.title());
        evidence.put("providerType", providerType);
        evidence.put("parseStatus", parseStatus(scan, generatedPriceCount));
        evidence.put("tableCount", scan.tableCount());
        evidence.put("relevantTableCount", scan.relevantTableCount());
        evidence.put("matchedTableCount", scan.matchedTableCount());
        evidence.put("skippedTableCount", scan.skippedTableCount());
        evidence.put("generatedPriceCount", generatedPriceCount);
        evidence.put("discoveredPageCount", discoveredPageCount);
        evidence.put("headlessRecommended", headlessRecommended);
        evidence.put("tableDiagnostics", scan.tableDiagnostics());
        return evidence;
    }

    private String parseStatus(ScanResult scan, int generatedPriceCount) {
        if (generatedPriceCount > 0) return "PRICE_PARSED";
        if (scan.matchedTableCount() > 0) return "PRICE_TABLE_MATCHED_NO_RECORDS";
        if (scan.relevantTableCount() > 0) return "PRICE_TABLE_NOT_MATCHED";
        return "NO_PRICE_TABLE";
    }
}
