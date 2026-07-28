package com.tokensea.access;

import com.tokensea.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AccessControlControllerTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuditService audits = mock(AuditService.class);
    private final AccessControlController controller = new AccessControlController(jdbc, passwords, audits);

    @Test
    void rejectsWeakInitialPasswordBeforeDatabaseWrite() {
        var request = new AccessControlController.UserCreateRequest(
                "tenant.user", "password", "租户用户", "user@example.com",
                List.of("role_tenant_user"), List.of("tenant-a"), "ACTIVE");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.createUser(request, null));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(jdbc, passwords, audits);
    }

    @Test
    void rejectsInvalidRoleCodeBeforeDatabaseWrite() {
        var request = new AccessControlController.RoleRequest(
                "invalid role", "无效角色", null, List.of(), "ACTIVE");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.createRole(request, null));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(jdbc, passwords, audits);
    }

    @Test
    void userAccountsCannotBePhysicallyDeleted() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.deleteUser("user-a"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, error.getStatusCode());
        verifyNoInteractions(jdbc, passwords, audits);
    }
}
