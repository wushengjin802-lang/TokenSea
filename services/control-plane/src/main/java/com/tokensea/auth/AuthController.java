package com.tokensea.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import com.tokensea.user.entity.UserAccount;
import com.tokensea.user.mapper.UserAccountMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserAccountMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    public AuthController(UserAccountMapper users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users; this.encoder = encoder; this.jwt = jwt;
    }
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, String userId, String username, String displayName) {}
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req) {
        UserAccount u = users.selectOne(new QueryWrapper<UserAccount>().eq("username", req.username()));
        if (u == null || !"ACTIVE".equalsIgnoreCase(u.getStatus()) || !encoder.matches(req.password(), u.getPasswordHash())) {
            return ApiResponse.fail("用户名或密码错误");
        }
        return ApiResponse.ok(new LoginResponse(jwt.issue(u.getId(), u.getUsername()), u.getId(), u.getUsername(), u.getDisplayName()));
    }
}
