package com.tokensea;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import com.tokensea.security.JwtService;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "tokensea.crypto-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "tokensea.jwt-secret=phase4-test-jwt-secret-with-at-least-32-bytes",
        "tokensea.egress.proxy-host=127.0.0.1",
        "tokensea.egress.proxy-port=9",
        "tokensea.egress.allowed-hosts=example.invalid",
        "tokensea.egress.allowed-ports=443",
        "tokensea.runtime.engine-key=phase4-test-runtime-key"
})
@AutoConfigureMockMvc
class TokenseaApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Test void contextLoads() {}

    @Test void nonAdminCannotReadLegacyManagementApi() throws Exception {
        String token = jwt.issue("tenant-user", "tenant-user", List.of("TENANT_USER"), List.of("tenant-a"));
        mvc.perform(get("/api/dashboard/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
