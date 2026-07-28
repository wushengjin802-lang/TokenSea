package com.tokensea.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAuthFilterTests {
    private static final String SECRET = "01234567890123456789012345678901";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyUsernameSubjectResolvesToCanonicalUserIdAndAdminRole() throws Exception {
        JwtService jwt = new JwtService(SECRET);
        JwtAuthFilter filter = new JwtAuthFilter(jwt, new StubJdbcTemplate());
        String token = jwt.issue("admin", "admin", List.of("ADMIN"), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/provider-price-diffs/diff-1/approve");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chained.set(true);

        filter.doFilter(request, response, chain);

        assertThat(chained).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        JwtService.Identity identity = (JwtService.Identity) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        assertThat(identity.userId()).isEqualTo("user-admin-id");
        assertThat(identity.roles()).containsExactly("ADMIN");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void unknownSubjectReturnsUnauthorized() throws Exception {
        JwtService jwt = new JwtService(SECRET);
        JwtAuthFilter filter = new JwtAuthFilter(jwt, new EmptyUserJdbcTemplate());
        String token = jwt.issue("missing-user", "missing-user", List.of("ADMIN"), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/provider-price-diffs/diff-1/approve");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertThat(chained).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void databaseFailureIsNotMisreportedAsUnauthorized() {
        JwtService jwt = new JwtService(SECRET);
        JwtAuthFilter filter = new JwtAuthFilter(jwt, new BrokenJdbcTemplate());
        String token = jwt.issue("admin", "admin", List.of("ADMIN"), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {}))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
                .hasMessageContaining("database unavailable");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static class StubJdbcTemplate extends JdbcTemplate {
        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (sql.contains("from user_account")) return (List<T>) List.of("user-admin-id");
            if (sql.contains("from role")) return (List<T>) List.of("ADMIN");
            if (sql.contains("from user_tenant")) return List.of();
            throw new AssertionError("Unexpected SQL: " + sql);
        }
    }

    private static class EmptyUserJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (sql.contains("from user_account")) return List.of();
            throw new AssertionError("Unexpected SQL after missing user: " + sql);
        }
    }

    private static class BrokenJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            throw new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        }
    }
}
