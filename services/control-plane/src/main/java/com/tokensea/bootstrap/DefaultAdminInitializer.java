package com.tokensea.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokensea.user.entity.UserAccount;
import com.tokensea.user.mapper.UserAccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the documented administrator only for a brand-new installation. */
@Component
public class DefaultAdminInitializer implements ApplicationRunner {
    private final UserAccountMapper users;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String displayName;

    public DefaultAdminInitializer(UserAccountMapper users, PasswordEncoder passwords, JdbcTemplate jdbc,
                                   @Value("${tokensea.default-admin.enabled:true}") boolean enabled,
                                   @Value("${tokensea.default-admin.username:admin}") String username,
                                   @Value("${tokensea.default-admin.password:TokenSea@local2026}") String password,
                                   @Value("${tokensea.default-admin.display-name:系统管理员}") String displayName) {
        this.users = users;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        Boolean initialized = jdbc.queryForObject(
                "select initialized from platform_bootstrap_state where singleton=true for update", Boolean.class);
        if (Boolean.TRUE.equals(initialized) || users.selectCount(new QueryWrapper<>()) > 0) return;

        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName);
        user.setStatus("ACTIVE");
        users.insert(user);

        jdbc.update("insert into role(id,code,name) values (?,'ADMIN','平台管理员') on conflict(code) do nothing", "role_admin");
        jdbc.update("insert into user_role(user_id,role_id) select ?,id from role where code='ADMIN' on conflict do nothing", user.getId());
        jdbc.update("update platform_bootstrap_state set initialized=true,initialized_by=?,initialized_at=now(),updated_at=now() where singleton=true", user.getId());
    }
}
