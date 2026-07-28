package com.tokensea.governance.pricing.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class PriceStructureFingerprint {
    private PriceStructureFingerprint() {}

    public static String calculate(ObjectMapper json, String content, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String normalized;
        if (type.contains("html") || looksLikeHtml(content)) {
            normalized = htmlShape(content);
        } else if (type.contains("json") || looksLikeJson(content)) {
            normalized = jsonShape(json, content);
        } else if (type.contains("csv")) {
            normalized = csvShape(content);
        } else {
            normalized = textShape(content);
        }
        return sha256(normalized);
    }

    private static String htmlShape(String content) {
        Document document = Jsoup.parse(content == null ? "" : content);
        List<String> parts = new ArrayList<>();
        parts.add("title=" + normalize(document.title()));
        for (Element heading : document.select("h1,h2,h3,h4,h5")) {
            parts.add(heading.tagName() + "=" + normalize(heading.text()));
        }
        int tableIndex = 0;
        for (Element table : document.select("table")) {
            List<String> headers = table.select("tr").stream().limit(3)
                    .flatMap(row -> row.select("th").stream())
                    .map(cell -> normalize(cell.text()))
                    .filter(value -> !value.isBlank())
                    .toList();
            int maxColumns = table.select("tr").stream()
                    .mapToInt(row -> row.select("th,td").size()).max().orElse(0);
            parts.add("table" + tableIndex++ + ":headers=" + String.join("|", headers)
                    + ":rows=" + table.select("tr").size() + ":columns=" + maxColumns);
        }
        return String.join("\n", parts);
    }

    private static String jsonShape(ObjectMapper json, String content) {
        try {
            return shape(json.readTree(content), 0);
        } catch (Exception exception) {
            return "invalid-json:" + textShape(content);
        }
    }

    private static String shape(JsonNode node, int depth) {
        if (node == null || node.isNull()) return "NULL";
        if (depth >= 4) return node.getNodeType().name();
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fields().forEachRemaining(entry -> fields.add(entry.getKey() + ':' + shape(entry.getValue(), depth + 1)));
            fields.sort(Comparator.naturalOrder());
            return "OBJECT{" + String.join(",", fields) + "}";
        }
        if (node.isArray()) {
            return "ARRAY[" + (node.isEmpty() ? "" : shape(node.get(0), depth + 1)) + "]";
        }
        return node.getNodeType().name();
    }

    private static String csvShape(String content) {
        String first = content == null ? "" : content.lines().findFirst().orElse("");
        return "CSV:" + normalize(first);
    }

    private static String textShape(String content) {
        String value = content == null ? "" : content;
        return normalize(value.substring(0, Math.min(value.length(), 4000)));
    }

    private static boolean looksLikeHtml(String content) {
        String value = content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("<!doctype html") || value.startsWith("<html") || value.contains("<table");
    }

    private static boolean looksLikeJson(String content) {
        String value = content == null ? "" : content.trim();
        return value.startsWith("{") || value.startsWith("[");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
