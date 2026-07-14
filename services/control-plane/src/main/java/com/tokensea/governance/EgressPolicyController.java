package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
public class EgressPolicyController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String policyToken;

    public EgressPolicyController(JdbcTemplate jdbc, ObjectMapper json,
                                  @Value("${tokensea.egress.policy-token:}") String policyToken) {
        this.jdbc = jdbc;
        this.json = json;
        this.policyToken = policyToken;
    }

    @GetMapping("/internal/egress/allowed-hosts")
    public ApiResponse<Map<String,Object>> allowedHosts(
            @RequestHeader(value = "X-TokenSea-Egress-Policy-Token", required = false) String suppliedToken) {
        if (policyToken == null || policyToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "动态出口策略未启用");
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                policyToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "动态出口策略凭据无效");
        }
        Set<String> hosts = new TreeSet<>();
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select official_hosts from provider_price_source
            where status in ('ACTIVE','PAUSED','DEGRADED')
            """);
        for (Map<String,Object> row : rows) hosts.addAll(readHosts(row.get("official_hosts")));
        return ApiResponse.ok(Map.of(
                "allowedHosts", hosts,
                "source", "provider_price_source",
                "revision", Integer.toHexString(hosts.hashCode())));
    }

    private List<String> readHosts(Object value) {
        try {
            List<String> hosts = json.readValue(String.valueOf(value), new TypeReference<>() {});
            return hosts.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank())
                    .map(v -> v.toLowerCase(Locale.ROOT)).distinct().toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
