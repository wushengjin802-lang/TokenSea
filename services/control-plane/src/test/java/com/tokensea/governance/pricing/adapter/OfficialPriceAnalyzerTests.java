package com.tokensea.governance.pricing.adapter;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialPriceAnalyzerTests {
    private final OfficialPriceAnalyzer analyzer = new OfficialPriceAnalyzer();

    @Test
    void scansRelevantTablesAndBuildsStructuredEvidence() {
        var document = Jsoup.parse("""
                <html><head><title>官方价格</title></head><body>
                  <table><tr><th>说明</th><th>内容</th></tr><tr><td>活动</td><td>无</td></tr></table>
                  <h2>模型价格</h2>
                  <table><tr><th>模型</th><th>输入价格</th><th>输出价格</th></tr>
                    <tr><td>model-a</td><td>1</td><td>2</td></tr></table>
                </body></html>
                """);
        var aliases = new OfficialPriceAnalyzer.HeaderAliases(
                label -> label.contains("模型"),
                label -> label.contains("输入"),
                label -> label.contains("输出"),
                label -> label.contains("缓存")
        );

        var scan = analyzer.scan(document, table -> {
            if (!table.scopeText().contains("模型")) return OfficialPriceAnalyzer.TableDecision.ignored();
            var header = analyzer.findRowHeader(table.table(), aliases, true);
            return header == null
                    ? OfficialPriceAnalyzer.TableDecision.skipped("表头不匹配")
                    : OfficialPriceAnalyzer.TableDecision.matched(Map.of(
                            "orientation", "ROW",
                            "modelColumn", header.modelIndex(),
                            "inputColumn", header.inputIndex(),
                            "outputColumn", header.outputIndex()
                    ));
        });
        var evidence = analyzer.evidence(document, "fixture", scan, 1, 0, false);

        assertThat(scan.tableCount()).isEqualTo(2);
        assertThat(scan.matchedTableCount()).isEqualTo(1);
        assertThat(scan.matchedTables()).hasSize(1);
        assertThat(evidence).containsEntry("parseStatus", "PRICE_PARSED")
                .containsEntry("matchedTableCount", 1)
                .containsEntry("generatedPriceCount", 1);
    }
}
