package com.tokensea.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.user.entity.UserAccount;
import com.tokensea.user.mapper.UserAccountMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {
    private final UserAccountMapper users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final AuditLogMapper audits;
    private final String recoveryToken;
    public BootstrapController(UserAccountMapper users, PasswordEncoder encoder, JdbcTemplate jdbc, AuditLogMapper audits,
                               @Value("${tokensea.bootstrap-token:}") String recoveryToken) {
        this.users = users; this.encoder = encoder; this.jdbc = jdbc; this.audits = audits; this.recoveryToken = recoveryToken;
    }
    public record InitAdminRequest(String username, String password, String displayName, String email,
                                   String existingUsername, String bootstrapToken) {}
    @PostMapping("/admin")
    @Transactional
    public ApiResponse<String> initAdmin(@RequestBody InitAdminRequest req) {
        Boolean initialized = jdbc.queryForObject(
                "select initialized from platform_bootstrap_state where singleton=true for update", Boolean.class);
        if (Boolean.TRUE.equals(initialized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统已完成初始化");
        }
        Long count = users.selectCount(new QueryWrapper<>());
        if (count != null && count > 0) {
            if (recoveryToken.isBlank() || req.existingUsername() == null || req.existingUsername().isBlank()
                    || !constantTimeEquals(recoveryToken, req.bootstrapToken())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "已有用户恢复需要部署端一次性令牌和明确用户名");
            }
            UserAccount existing = users.selectOne(new QueryWrapper<UserAccount>().eq("username", req.existingUsername()));
            if (existing == null || !"ACTIVE".equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指定的现有用户不存在或未启用");
            }
            grantAdmin(existing);
            AuditLog log = new AuditLog(); log.setId(UUID.randomUUID().toString().replace("-", ""));
            log.setAction("ADMIN_RECOVERY"); log.setObjectType("UserAccount"); log.setObjectId(existing.getId());
            log.setAfterValue("username=" + existing.getUsername()); audits.insert(log);
            return ApiResponse.ok("管理员恢复完成，一次性恢复入口已永久关闭");
        }
        if (req.username() == null || req.username().isBlank() || req.password() == null || req.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "首次初始化需要用户名和密码");
        }
        UserAccount u = new UserAccount();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setDisplayName(req.displayName() == null ? req.username() : req.displayName());
        u.setEmail(req.email());
        u.setStatus("ACTIVE");
        users.insert(u);
        grantAdmin(u);
        return ApiResponse.ok("管理员已初始化，请登录");
    }

    private void grantAdmin(UserAccount user) {
        jdbc.update("insert into role(id,code,name) values (?,'ADMIN','平台管理员') on conflict(code) do nothing", "role_admin");
        jdbc.update("insert into user_role(user_id,role_id) select ?,id from role where code='ADMIN' on conflict do nothing", user.getId());
        jdbc.update("update platform_bootstrap_state set initialized=true,initialized_by=?,initialized_at=now(),updated_at=now() where singleton=true", user.getId());
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
