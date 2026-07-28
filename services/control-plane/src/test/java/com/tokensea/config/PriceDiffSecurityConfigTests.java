package com.tokensea.config;

import com.tokensea.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = PriceDiffSecurityConfigTests.TestController.class,
        properties = "tokensea.cors.allowed-origins=http://localhost:39210")
@ContextConfiguration(classes = {SecurityConfig.class, PriceDiffSecurityConfigTests.TestController.class})
class PriceDiffSecurityConfigTests {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void authenticatedRequestCanReachPriceDiffApprovalControllerForLiveRoleCheck() throws Exception {
        mvc.perform(post("/api/provider-price-diffs/diff-1/approve")
                        .with(user("tenant-user").roles("TENANT_USER")))
                .andExpect(status().isNoContent());
    }

    @Test
    void authenticatedRequestCanReachPriceDiffRejectControllerForLiveRoleCheck() throws Exception {
        mvc.perform(post("/api/provider-price-diffs/diff-1/reject")
                        .with(user("tenant-user").roles("TENANT_USER")))
                .andExpect(status().isNoContent());
    }

    @Test
    void priceDiffReadApisRemainAdminOnly() throws Exception {
        mvc.perform(get("/api/provider-price-diffs")
                        .with(user("tenant-user").roles("TENANT_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherManagementApisRemainAdminOnly() throws Exception {
        mvc.perform(get("/api/dashboard/stats")
                        .with(user("tenant-user").roles("TENANT_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountAndRoleManagementRemainAdminOnly() throws Exception {
        mvc.perform(get("/api/users")
                        .with(user("tenant-user").roles("TENANT_USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/roles")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
    }

    @RestController
    @RequestMapping("/api")
    static class TestController {
        @PostMapping("/provider-price-diffs/{id}/approve")
        ResponseEntity<Void> approve(@PathVariable String id) {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/provider-price-diffs/{id}/reject")
        ResponseEntity<Void> reject(@PathVariable String id) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/provider-price-diffs")
        ResponseEntity<Void> diffs() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/dashboard/stats")
        ResponseEntity<Void> dashboard() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/users")
        ResponseEntity<Void> users() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/roles")
        ResponseEntity<Void> roles() {
            return ResponseEntity.noContent().build();
        }
    }
}
