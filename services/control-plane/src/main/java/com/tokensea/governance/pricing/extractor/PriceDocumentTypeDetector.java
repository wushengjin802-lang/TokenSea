package com.tokensea.governance.pricing.extractor;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class PriceDocumentTypeDetector {
    public String detect(String contentType, String content) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String value = content == null ? "" : content.stripLeading();
        if (type.contains("pdf")) return "PDF";
        if (type.contains("json") || value.startsWith("{") || value.startsWith("[")) return "JSON";
        if (type.contains("csv")) return "CSV";
        if (type.contains("html") || value.startsWith("<")) return "HTML";
        if (type.contains("text")) return looksDelimited(value) ? "CSV" : "TEXT";
        if (type.contains("octet-stream")) return looksDelimited(value) ? "CSV" : "BINARY";
        return looksDelimited(value) ? "CSV" : "TEXT";
    }

    private boolean looksDelimited(String content) {
        String first = content.lines().findFirst().orElse("");
        return first.contains(",") || first.contains("\t") || first.contains(";");
    }
}
