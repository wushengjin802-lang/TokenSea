package com.tokensea.governance.pricing.extractor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class ExtractionConfidenceCalculator {
    public BigDecimal calculate(String extractionMethod,
                                Map<String,Object> evidence,
                                PriceExtractionValidator.Validation validation,
                                Object suppliedConfidence) {
        BigDecimal score = supplied(suppliedConfidence);
        if (score == null) {
            score = extractionMethod != null && extractionMethod.startsWith("LLM")
                    ? new BigDecimal("0.80") : new BigDecimal("0.98");
        }
        if (evidence == null || String.valueOf(evidence.getOrDefault("sourceText", "")).isBlank()) {
            score = score.subtract(new BigDecimal("0.40"));
        }
        if (validation != null) {
            score = score.subtract(new BigDecimal("0.05").multiply(BigDecimal.valueOf(validation.warnings().size())));
            if (!validation.errors().isEmpty()) score = score.subtract(new BigDecimal("0.60"));
        }
        if (score.signum() < 0) score = BigDecimal.ZERO;
        if (score.compareTo(BigDecimal.ONE) > 0) score = BigDecimal.ONE;
        return score.setScale(5, RoundingMode.HALF_UP);
    }

    private BigDecimal supplied(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }
}
