package com.tokensea;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
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
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ensureTenantUserCanBeAuthenticated() {
        jdbc.update("""
            insert into user_account(id,username,password_hash,display_name,status)
            values('tenant-user','tenant-user','unused','租户测试用户','ACTIVE')
            on conflict(id) do update set status='ACTIVE'
            """);
        jdbc.update("""
            insert into user_role(user_id,role_id)
            select 'tenant-user',id from role where code='TENANT_USER'
            on conflict do nothing
            """);
    }

    @Test void contextLoads() {}

    @Test void nonAdminCannotReadLegacyManagementApi() throws Exception {
        String token = jwt.issue("tenant-user", "tenant-user", List.of("TENANT_USER"), List.of("tenant-a"));
        mvc.perform(get("/api/dashboard/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
