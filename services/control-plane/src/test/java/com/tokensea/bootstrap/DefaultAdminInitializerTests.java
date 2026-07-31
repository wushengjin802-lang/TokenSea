package com.tokensea.bootstrap;

import com.tokensea.user.entity.UserAccount;
import com.tokensea.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAdminInitializerTests {
    @Test
    void createsDocumentedAdminOnlyForAnUninitializedEmptyInstallation() throws Exception {
        UserAccountMapper users = mock(UserAccountMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class))).thenReturn(false);
        when(users.selectCount(any())).thenReturn(0L);
        when(passwords.encode("TokenSea@local2026")).thenReturn("encoded-password");

        new DefaultAdminInitializer(users, passwords, jdbc, true, "admin", "TokenSea@local2026", "系统管理员")
                .run(new DefaultApplicationArguments());

        ArgumentCaptor<UserAccount> user = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).insert(user.capture());
        assertEquals("admin", user.getValue().getUsername());
        assertEquals("encoded-password", user.getValue().getPasswordHash());
        assertEquals("系统管理员", user.getValue().getDisplayName());
        assertEquals("ACTIVE", user.getValue().getStatus());
        verify(jdbc).update("update platform_bootstrap_state set initialized=true,initialized_by=?,initialized_at=now(),updated_at=now() where singleton=true", user.getValue().getId());
    }

    @Test
    void preservesExistingInstallations() throws Exception {
        UserAccountMapper users = mock(UserAccountMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class))).thenReturn(true);

        new DefaultAdminInitializer(users, passwords, jdbc, true, "admin", "TokenSea@local2026", "系统管理员")
                .run(new DefaultApplicationArguments());

        verify(users, never()).insert(any(UserAccount.class));
        verify(passwords, never()).encode(any());
    }
}
