package com.tokensea.governance.pricing.extractor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceDocumentTypeDetectorTests {
    private final PriceDocumentTypeDetector detector = new PriceDocumentTypeDetector();

    @Test
    void detectsStructuredAndDocumentTypes() {
        assertEquals("JSON", detector.detect("application/octet-stream", " {\"data\":[]}"));
        assertEquals("HTML", detector.detect("text/html", "<table></table>"));
        assertEquals("CSV", detector.detect("text/plain", "model;input;output"));
        assertEquals("PDF", detector.detect("application/pdf", "JVBERi0="));
        assertEquals("TEXT", detector.detect("text/plain", "plain pricing text"));
    }
}
