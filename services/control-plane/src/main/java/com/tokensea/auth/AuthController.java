package com.tokensea.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import com.tokensea.user.entity.UserAccount;
import com.tokensea.user.mapper.UserAccountMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserAccountMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    public AuthController(UserAccountMapper users, PasswordEncoder encoder, JwtService jwt, JdbcTemplate jdbc) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.jdbc = jdbc;
    }
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, String userId, String username, String displayName) {}
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req) {
        UserAccount u = users.selectOne(new QueryWrapper<UserAccount>().eq("username", req.username()));
        if (u == null || !"ACTIVE".equalsIgnoreCase(u.getStatus()) || !encoder.matches(req.password(), u.getPasswordHash())) {
            return ApiResponse.fail("用户名或密码错误");
        }
        List<String> roles = jdbc.queryForList("select r.code from role r join user_role ur on ur.role_id=r.id where ur.user_id=?", String.class, u.getId());
        List<String> tenantIds = jdbc.queryForList("select tenant_id from user_tenant where user_id=? and status='ACTIVE'", String.class, u.getId());
        return ApiResponse.ok(new LoginResponse(jwt.issue(u.getId(), u.getUsername(), roles, tenantIds), u.getId(), u.getUsername(), u.getDisplayName()));
    }
}
