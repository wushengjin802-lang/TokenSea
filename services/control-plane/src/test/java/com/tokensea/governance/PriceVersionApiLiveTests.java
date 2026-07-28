package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.security.JwtService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PriceVersionApiLiveTests {
    @Test
    void liveApiReturnsDeepSeekAmounts() throws Exception {
        String baseUrl = System.getProperty("tokensea.live.base-url", "");
        String jwtSecret = System.getenv().getOrDefault("TOKENSEA_JWT_SECRET", "");
        String userId = System.getProperty("tokensea.live.user-id", "");
        Assumptions.assumeTrue(!baseUrl.isBlank() && !jwtSecret.isBlank() && !userId.isBlank(),
                "set live API properties to run this read-only diagnostic test");

        String token = new JwtService(jwtSecret).issue(userId, "admin", List.of("ADMIN"), List.of());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/price-versions?status=ACTIVE"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String,Object> payload = new ObjectMapper().readValue(response.body(), new TypeReference<>() {});
        List<Map<String,Object>> rows = (List<Map<String,Object>>) payload.get("data");
        Map<String,Object> price = rows.stream()
                .filter(row -> "deepseek-v4-pro".equals(row.get("providerModelName")))
                .findFirst()
                .orElseThrow();

        assertThat(price.get("billingBasis")).isEqualTo("TOKEN");
        assertThat(((Number) price.get("billingQuantity")).longValue()).isEqualTo(1_000_000L);
        assertThat(new BigDecimal(String.valueOf(price.get("inputUnitPrice"))))
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(new BigDecimal(String.valueOf(price.get("outputUnitPrice"))))
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(price.get("priceLayer")).isEqualTo("PROVIDER_OFFICIAL");
        assertThat(price.get("status")).isEqualTo("ACTIVE");
    }
}
