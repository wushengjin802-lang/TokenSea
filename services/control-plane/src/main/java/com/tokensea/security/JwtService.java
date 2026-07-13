package com.tokensea.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {
    private final SecretKey key;
    public JwtService(@Value("${tokensea.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("TOKENSEA_JWT_SECRET must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(bytes);
    }
    public String issue(String userId, String username, List<String> roles, List<String> tenantIds) {
        Instant now = Instant.now();
        return Jwts.builder().subject(userId).claim("username", username).claim("roles", roles).claim("tenant_ids", tenantIds)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(86400))).signWith(key).compact();
    }
    public Identity identity(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Object raw = claims.get("roles");
        List<String> roles = raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        Object rawTenants = claims.get("tenant_ids");
        List<String> tenantIds = rawTenants instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        return new Identity(claims.getSubject(), roles, tenantIds);
    }
    public record Identity(String userId, List<String> roles, List<String> tenantIds) {}
}
