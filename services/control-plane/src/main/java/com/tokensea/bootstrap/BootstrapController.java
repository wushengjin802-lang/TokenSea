package com.tokensea.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.user.entity.UserAccount;
import com.tokensea.user.mapper.UserAccountMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {
    private final UserAccountMapper users;
    private final PasswordEncoder encoder;
    public BootstrapController(UserAccountMapper users, PasswordEncoder encoder) { this.users = users; this.encoder = encoder; }
    public record InitAdminRequest(@NotBlank String username, @NotBlank String password, String displayName, String email) {}
    @PostMapping("/admin")
    public ApiResponse<String> initAdmin(@RequestBody InitAdminRequest req) {
        Long count = users.selectCount(new QueryWrapper<>());
        if (count != null && count > 0) return ApiResponse.fail("系统已初始化管理员");
        UserAccount u = new UserAccount();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setDisplayName(req.displayName() == null ? req.username() : req.displayName());
        u.setEmail(req.email());
        u.setStatus("ACTIVE");
        users.insert(u);
        return ApiResponse.ok("管理员已初始化，请登录");
    }
}
