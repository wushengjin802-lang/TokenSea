package com.tokensea.access;

import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class AccessControlController {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@-]{3,100}");
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,99}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> ROLE_STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audits;

    public AccessControlController(JdbcTemplate jdbc, PasswordEncoder passwords, AuditService audits) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audits = audits;
    }

    public record UserCreateRequest(String username, String initialPassword, String displayName, String email,
                                    List<String> roleIds, List<String> tenantIds, String status) {}
    public record UserUpdateRequest(String displayName, String email, List<String> roleIds,
                                    List<String> tenantIds, String status) {}
    public record PasswordResetRequest(String newPassword) {}
    public record StatusRequest(String status) {}
    public record RoleRequest(String code, String name, String description,
                              List<String> permissionIds, String status) {}

    @GetMapping("/users")
    public ApiResponse<PageResult<Map<String,Object>>> users(@RequestParam(required=false) String keyword,
                                                              @RequestParam(required=false) String status,
                                                              @RequestParam(required=false) Integer page,
                                                              @RequestParam(required=false) Integer size,
                                                              @RequestParam(required=false) String sort,
                                                              @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "id", "id",
                "username", "username",
                "displayName", "display_name",
                "email", "email",
                "status", "status",
                "passwordChangedAt", "password_changed_at",
                "lastLoginAt", "last_login_at",
                "createdAt", "created_at",
                "updatedAt", "updated_at"
        ), "createdAt", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String normalizedStatus = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or lower(username) like ? or lower(coalesce(display_name,'')) like ?
                   or lower(coalesce(email,'')) like ?)
              and (?::text is null or status=?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select id,username,display_name "displayName",email,status,
              password_changed_at "passwordChangedAt",last_login_at "lastLoginAt",
              created_at "createdAt",updated_at "updatedAt"
            from user_account
            """ + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                + ("id".equals(paging.sortColumn()) ? "" : ", id " + paging.direction())
                + " limit ? offset ?", q, q, q, q, normalizedStatus, normalizedStatus,
                paging.size(), paging.offset());
        Long total = jdbc.queryForObject("select count(*) from user_account " + filter, Long.class,
                q, q, q, q, normalizedStatus, normalizedStatus);
        enrichUsers(rows);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/users/{id}")
    public ApiResponse<Map<String,Object>> user(@PathVariable String id) {
        return ApiResponse.ok(userView(id));
    }

    @PostMapping("/users")
    @Transactional
    public ApiResponse<Map<String,Object>> createUser(@RequestBody UserCreateRequest request,
                                                       Authentication authentication) {
        if (request == null) bad("账户信息不能为空");
        String username = normalizeUsername(request.username());
        String status = value(request.status(), "ACTIVE").toUpperCase(Locale.ROOT);
        validatePassword(request.initialPassword());
        validateEmail(request.email());
        validateUserStatus(status);
        List<String> roleIds = normalizeIds(request.roleIds());
        List<String> tenantIds = normalizeIds(request.tenantIds());
        if (roleIds.isEmpty()) bad("账户至少需要分配一个角色");
        ensureActiveRoles(roleIds);
        ensureTenants(tenantIds);
        Integer duplicate = jdbc.queryForObject("select count(*) from user_account where lower(username)=lower(?)",
                Integer.class, username);
        if (duplicate != null && duplicate > 0) conflict("账号已存在");

        String id = id();
        jdbc.update("""
            insert into user_account(id,username,password_hash,display_name,email,status,password_changed_at)
            values(?,?,?,?,?,?,now())
            """, id, username, passwords.encode(request.initialPassword()),
                blank(request.displayName()) ? username : request.displayName().trim(), nullable(request.email()), status);
        replaceUserRoles(id, roleIds);
        replaceUserTenants(id, tenantIds);
        Map<String,Object> created = userView(id);
        audits.record("USER_ACCOUNT_CREATE", "UserAccount", id, null,
                auditValue(created, "createdBy", actor(authentication)));
        return ApiResponse.ok(created);
    }

    @PutMapping("/users/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> updateUser(@PathVariable String id,
                                                       @RequestBody UserUpdateRequest request,
                                                       Authentication authentication) {
        if (request == null) bad("账户信息不能为空");
        Map<String,Object> before = userView(id);
        String nextStatus = value(request.status(), String.valueOf(before.get("status"))).toUpperCase(Locale.ROOT);
        validateEmail(request.email());
        validateUserStatus(nextStatus);
        List<String> roleIds = request.roleIds() == null
                ? currentRoleIds(id) : normalizeIds(request.roleIds());
        List<String> tenantIds = request.tenantIds() == null
                ? currentTenantIds(id) : normalizeIds(request.tenantIds());
        if (roleIds.isEmpty()) bad("账户至少需要分配一个角色");
        ensureActiveRoles(roleIds);
        ensureTenants(tenantIds);
        ensureAdminContinuity(id, nextStatus, roleIds, actor(authentication));

        jdbc.update("""
            update user_account set display_name=?,email=?,status=?,updated_at=now() where id=?
            """, blank(request.displayName()) ? before.get("username") : request.displayName().trim(),
                nullable(request.email()), nextStatus, id);
        replaceUserRoles(id, roleIds);
        replaceUserTenants(id, tenantIds);
        Map<String,Object> after = userView(id);
        audits.record("USER_ACCOUNT_UPDATE", "UserAccount", id, before,
                auditValue(after, "updatedBy", actor(authentication)));
        return ApiResponse.ok(after);
    }

    @PatchMapping("/users/{id}/status")
    @Transactional
    public ApiResponse<Map<String,Object>> changeUserStatus(@PathVariable String id,
                                                             @RequestBody StatusRequest request,
                                                             Authentication authentication) {
        Map<String,Object> before = userView(id);
        String nextStatus = request == null ? null : upper(request.status());
        validateUserStatus(nextStatus);
        List<String> roles = currentRoleIds(id);
        ensureAdminContinuity(id, nextStatus, roles, actor(authentication));
        jdbc.update("update user_account set status=?,updated_at=now() where id=?", nextStatus, id);
        Map<String,Object> after = userView(id);
        audits.record("USER_ACCOUNT_STATUS_CHANGE", "UserAccount", id, before,
                auditValue(after, "changedBy", actor(authentication)));
        return ApiResponse.ok(after);
    }

    @PostMapping("/users/{id}/reset-password")
    @Transactional
    public ApiResponse<Map<String,Object>> resetPassword(@PathVariable String id,
                                                          @RequestBody PasswordResetRequest request,
                                                          Authentication authentication) {
        Map<String,Object> before = userView(id);
        validatePassword(request == null ? null : request.newPassword());
        jdbc.update("""
            update user_account set password_hash=?,password_changed_at=now(),updated_at=now() where id=?
            """, passwords.encode(request.newPassword()), id);
        Map<String,Object> after = userView(id);
        audits.record("USER_ACCOUNT_PASSWORD_RESET", "UserAccount", id,
                Map.of("username", before.get("username")),
                Map.of("username", after.get("username"), "resetBy", actor(authentication)));
        return ApiResponse.ok(after);
    }

    @DeleteMapping("/users/{id}")
    public void deleteUser(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "账户禁止物理删除，请停用账户");
    }

    @GetMapping("/roles")
    public ApiResponse<PageResult<Map<String,Object>>> roles(@RequestParam(required=false) String keyword,
                                                              @RequestParam(required=false) String status,
                                                              @RequestParam(required=false) Integer page,
                                                              @RequestParam(required=false) Integer size,
                                                              @RequestParam(required=false) String sort,
                                                              @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "id", "r.id",
                "code", "r.code",
                "name", "r.name",
                "description", "r.description",
                "status", "r.status",
                "systemBuiltin", "r.system_builtin",
                "userCount", "\"userCount\"", 
                "createdAt", "r.created_at",
                "updatedAt", "r.updated_at"
        ), "systemBuiltin", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String normalizedStatus = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or lower(r.code) like ? or lower(r.name) like ?
                   or lower(coalesce(r.description,'')) like ?)
              and (?::text is null or r.status=?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select r.id,r.code,r.name,r.description,r.status,r.system_builtin "systemBuiltin",
              r.created_at "createdAt",r.updated_at "updatedAt",count(ur.user_id) "userCount"
            from role r
            left join user_role ur on ur.role_id=r.id
            """ + filter + " group by r.id,r.code,r.name,r.description,r.status,r.system_builtin,r.created_at,r.updated_at"
                + " order by " + paging.sortColumn() + " " + paging.direction()
                + ("r.id".equals(paging.sortColumn()) ? "" : ", r.id " + paging.direction())
                + " limit ? offset ?", q, q, q, q, normalizedStatus, normalizedStatus,
                paging.size(), paging.offset());
        Long total = jdbc.queryForObject("select count(*) from role r " + filter, Long.class,
                q, q, q, q, normalizedStatus, normalizedStatus);
        enrichRoles(rows);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/roles/{id}")
    public ApiResponse<Map<String,Object>> role(@PathVariable String id) {
        return ApiResponse.ok(roleView(id));
    }

    @PostMapping("/roles")
    @Transactional
    public ApiResponse<Map<String,Object>> createRole(@RequestBody RoleRequest request,
                                                       Authentication authentication) {
        if (request == null) bad("角色信息不能为空");
        String code = normalizeRoleCode(request.code());
        String status = value(request.status(), "ACTIVE").toUpperCase(Locale.ROOT);
        validateRole(request.name(), status);
        List<String> permissionIds = normalizeIds(request.permissionIds());
        ensurePermissions(permissionIds);
        Integer duplicate = jdbc.queryForObject("select count(*) from role where code=?", Integer.class, code);
        if (duplicate != null && duplicate > 0) conflict("角色编码已存在");
        String id = id();
        jdbc.update("""
            insert into role(id,code,name,description,status,system_builtin)
            values(?,?,?,?,?,false)
            """, id, code, request.name().trim(), nullable(request.description()), status);
        replaceRolePermissions(id, permissionIds);
        Map<String,Object> created = roleView(id);
        audits.record("ROLE_CREATE", "Role", id, null,
                auditValue(created, "createdBy", actor(authentication)));
        return ApiResponse.ok(created);
    }

    @PutMapping("/roles/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> updateRole(@PathVariable String id,
                                                       @RequestBody RoleRequest request,
                                                       Authentication authentication) {
        if (request == null) bad("角色信息不能为空");
        Map<String,Object> before = roleView(id);
        String code = String.valueOf(before.get("code"));
        if (!blank(request.code()) && !code.equalsIgnoreCase(request.code().trim())) {
            conflict("角色编码创建后不可修改");
        }
        String status = value(request.status(), String.valueOf(before.get("status"))).toUpperCase(Locale.ROOT);
        validateRole(request.name(), status);
        if (Boolean.TRUE.equals(before.get("systemBuiltin")) && "ADMIN".equals(code) && !"ACTIVE".equals(status)) {
            conflict("平台管理员角色不能停用");
        }
        List<String> permissionIds = request.permissionIds() == null
                ? currentPermissionIds(id) : normalizeIds(request.permissionIds());
        ensurePermissions(permissionIds);
        if ("ADMIN".equals(code)) permissionIds = allPermissionIds();
        jdbc.update("""
            update role set name=?,description=?,status=?,updated_at=now() where id=?
            """, request.name().trim(), nullable(request.description()), status, id);
        replaceRolePermissions(id, permissionIds);
        Map<String,Object> after = roleView(id);
        audits.record("ROLE_UPDATE", "Role", id, before,
                auditValue(after, "updatedBy", actor(authentication)));
        return ApiResponse.ok(after);
    }

    @PatchMapping("/roles/{id}/status")
    @Transactional
    public ApiResponse<Map<String,Object>> changeRoleStatus(@PathVariable String id,
                                                             @RequestBody StatusRequest request,
                                                             Authentication authentication) {
        Map<String,Object> before = roleView(id);
        String nextStatus = request == null ? null : upper(request.status());
        if (!ROLE_STATUSES.contains(nextStatus)) bad("角色状态无效");
        if (Boolean.TRUE.equals(before.get("systemBuiltin")) && "ADMIN".equals(before.get("code"))
                && !"ACTIVE".equals(nextStatus)) conflict("平台管理员角色不能停用");
        jdbc.update("update role set status=?,updated_at=now() where id=?", nextStatus, id);
        Map<String,Object> after = roleView(id);
        audits.record("ROLE_STATUS_CHANGE", "Role", id, before,
                auditValue(after, "changedBy", actor(authentication)));
        return ApiResponse.ok(after);
    }

    @DeleteMapping("/roles/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> deleteRole(@PathVariable String id, Authentication authentication) {
        Map<String,Object> before = roleView(id);
        if (Boolean.TRUE.equals(before.get("systemBuiltin"))) conflict("系统内置角色不能删除");
        Number users = (Number) before.getOrDefault("userCount", 0);
        if (users.longValue() > 0) conflict("角色仍有关联账户，不能删除");
        jdbc.update("delete from role_permission where role_id=?", id);
        jdbc.update("delete from role where id=?", id);
        audits.record("ROLE_DELETE", "Role", id, before, Map.of("deletedBy", actor(authentication)));
        return ApiResponse.ok(Map.of("id", id, "deleted", true));
    }

    @GetMapping("/permissions")
    public ApiResponse<List<Map<String,Object>>> permissions() {
        return ApiResponse.ok(jdbc.queryForList("""
            select id,code,name,type,created_at "createdAt",updated_at "updatedAt"
            from permission order by type,code
            """));
    }

    private Map<String,Object> userView(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select id,username,display_name "displayName",email,status,
              password_changed_at "passwordChangedAt",last_login_at "lastLoginAt",
              created_at "createdAt",updated_at "updatedAt"
            from user_account where id=?
            """, id);
        if (rows.isEmpty()) notFound("账户不存在");
        Map<String,Object> row = rows.get(0);
        enrichUser(row);
        return row;
    }

    private void enrichUser(Map<String,Object> row) {
        enrichUsers(List.of(row));
    }

    private void enrichUsers(List<Map<String,Object>> rows) {
        if (rows.isEmpty()) return;
        Map<String,Map<String,Object>> users = initializeAssociations(rows,
                "roleIds", "roleCodes", "roleNames", "tenantIds", "tenantNames");
        String placeholders = String.join(",", Collections.nCopies(users.size(), "?"));
        List<Map<String,Object>> roleRows = jdbc.queryForList("""
            select ur.user_id "ownerId",r.id,r.code,r.name
            from user_role ur join role r on r.id=ur.role_id
            where ur.user_id in (%s)
            order by ur.user_id,r.code
            """.formatted(placeholders), users.keySet().toArray());
        for (Map<String,Object> association : roleRows) {
            Map<String,Object> user = users.get(String.valueOf(association.get("ownerId")));
            if (user == null) continue;
            append(user, "roleIds", association.get("id"));
            append(user, "roleCodes", association.get("code"));
            append(user, "roleNames", association.get("name"));
        }
        List<Map<String,Object>> tenantRows = jdbc.queryForList("""
            select ut.user_id "ownerId",t.id,t.name
            from user_tenant ut join tenant t on t.id=ut.tenant_id
            where ut.status='ACTIVE' and ut.user_id in (%s)
            order by ut.user_id,t.name
            """.formatted(placeholders), users.keySet().toArray());
        for (Map<String,Object> association : tenantRows) {
            Map<String,Object> user = users.get(String.valueOf(association.get("ownerId")));
            if (user == null) continue;
            append(user, "tenantIds", association.get("id"));
            append(user, "tenantNames", association.get("name"));
        }
    }

    private Map<String,Object> roleView(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select r.id,r.code,r.name,r.description,r.status,r.system_builtin "systemBuiltin",
              r.created_at "createdAt",r.updated_at "updatedAt",
              (select count(*) from user_role ur where ur.role_id=r.id) "userCount"
            from role r where r.id=?
            """, id);
        if (rows.isEmpty()) notFound("角色不存在");
        Map<String,Object> row = rows.get(0);
        enrichRole(row);
        return row;
    }

    private void enrichRole(Map<String,Object> row) {
        enrichRoles(List.of(row));
    }

    private void enrichRoles(List<Map<String,Object>> rows) {
        if (rows.isEmpty()) return;
        Map<String,Map<String,Object>> roles = initializeAssociations(rows,
                "permissionIds", "permissionCodes", "permissionNames");
        String placeholders = String.join(",", Collections.nCopies(roles.size(), "?"));
        List<Map<String,Object>> permissionRows = jdbc.queryForList("""
            select rp.role_id "ownerId",p.id,p.code,p.name
            from role_permission rp join permission p on p.id=rp.permission_id
            where rp.role_id in (%s)
            order by rp.role_id,p.code
            """.formatted(placeholders), roles.keySet().toArray());
        for (Map<String,Object> association : permissionRows) {
            Map<String,Object> role = roles.get(String.valueOf(association.get("ownerId")));
            if (role == null) continue;
            append(role, "permissionIds", association.get("id"));
            append(role, "permissionCodes", association.get("code"));
            append(role, "permissionNames", association.get("name"));
        }
    }

    private Map<String,Map<String,Object>> initializeAssociations(List<Map<String,Object>> rows, String... fields) {
        Map<String,Map<String,Object>> indexed = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            for (String field : fields) row.put(field, new ArrayList<>());
            indexed.put(String.valueOf(row.get("id")), row);
        }
        return indexed;
    }

    @SuppressWarnings("unchecked")
    private void append(Map<String,Object> row, String field, Object value) {
        ((List<Object>) row.get(field)).add(value);
    }

    private void replaceUserRoles(String userId, List<String> roleIds) {
        jdbc.update("delete from user_role where user_id=?", userId);
        roleIds.forEach(roleId -> jdbc.update("""
            insert into user_role(user_id,role_id) values(?,?) on conflict do nothing
            """, userId, roleId));
    }

    private void replaceUserTenants(String userId, List<String> tenantIds) {
        jdbc.update("delete from user_tenant where user_id=?", userId);
        tenantIds.forEach(tenantId -> jdbc.update("""
            insert into user_tenant(user_id,tenant_id,status) values(?,?,'ACTIVE') on conflict do nothing
            """, userId, tenantId));
    }

    private void replaceRolePermissions(String roleId, List<String> permissionIds) {
        jdbc.update("delete from role_permission where role_id=?", roleId);
        permissionIds.forEach(permissionId -> jdbc.update("""
            insert into role_permission(role_id,permission_id) values(?,?) on conflict do nothing
            """, roleId, permissionId));
    }

    private void ensureAdminContinuity(String userId, String nextStatus, List<String> roleIds, String actorId) {
        Map<String,Object> current = userView(userId);
        boolean currentlyAdmin = ((List<?>) current.get("roleCodes")).contains("ADMIN")
                && "ACTIVE".equals(current.get("status"));
        String adminRoleId = roleId("ADMIN");
        boolean remainsAdmin = "ACTIVE".equals(nextStatus) && roleIds.contains(adminRoleId);
        if (userId.equals(actorId) && !remainsAdmin) conflict("不能停用当前登录账户或移除自身平台管理员角色");
        if (currentlyAdmin && !remainsAdmin) {
            Integer others = jdbc.queryForObject("""
                select count(distinct u.id) from user_account u
                join user_role ur on ur.user_id=u.id
                join role r on r.id=ur.role_id
                where u.status='ACTIVE' and r.status='ACTIVE' and r.code='ADMIN' and u.id<>?
                """, Integer.class, userId);
            if (others == null || others == 0) conflict("系统至少需要保留一个启用的平台管理员账户");
        }
    }

    private void ensureActiveRoles(List<String> ids) {
        for (String id : ids) {
            Integer count = jdbc.queryForObject("select count(*) from role where status='ACTIVE' and id=?",
                    Integer.class, id);
            if (count == null || count == 0) bad("包含不存在或已停用的角色");
        }
    }

    private void ensureTenants(List<String> ids) {
        for (String id : ids) {
            Integer count = jdbc.queryForObject("select count(*) from tenant where id=?", Integer.class, id);
            if (count == null || count == 0) bad("包含不存在的租户");
        }
    }

    private void ensurePermissions(List<String> ids) {
        for (String id : ids) {
            Integer count = jdbc.queryForObject("select count(*) from permission where id=?", Integer.class, id);
            if (count == null || count == 0) bad("包含不存在的权限");
        }
    }

    private List<String> currentRoleIds(String userId) {
        return jdbc.queryForList("select role_id from user_role where user_id=? order by role_id", String.class, userId);
    }

    private List<String> currentTenantIds(String userId) {
        return jdbc.queryForList("select tenant_id from user_tenant where user_id=? and status='ACTIVE' order by tenant_id",
                String.class, userId);
    }

    private List<String> currentPermissionIds(String roleId) {
        return jdbc.queryForList("select permission_id from role_permission where role_id=? order by permission_id",
                String.class, roleId);
    }

    private List<String> allPermissionIds() {
        return jdbc.queryForList("select id from permission order by id", String.class);
    }

    private String roleId(String code) {
        List<String> rows = jdbc.queryForList("select id from role where code=?", String.class, code);
        if (rows.isEmpty()) throw new IllegalStateException("系统角色不存在: " + code);
        return rows.get(0);
    }

    private static String normalizeUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (!USERNAME.matcher(username).matches()) bad("账号需为 3-100 位字母、数字或 . _ @ -");
        return username;
    }

    private static String normalizeRoleCode(String value) {
        String code = upper(value);
        if (code == null || !ROLE_CODE.matcher(code).matches()) bad("角色编码需为 3-100 位大写字母、数字或下划线");
        return code;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128
                || password.chars().noneMatch(Character::isLetter)
                || password.chars().noneMatch(Character::isDigit)) {
            bad("密码需为 8-128 位，并同时包含字母和数字");
        }
    }

    private static void validateEmail(String email) {
        if (!blank(email) && !EMAIL.matcher(email.trim()).matches()) bad("邮箱格式无效");
    }

    private static void validateUserStatus(String status) {
        if (!USER_STATUSES.contains(status)) bad("账户状态无效");
    }

    private static void validateRole(String name, String status) {
        if (blank(name) || name.trim().length() > 100) bad("角色名称不能为空且不能超过 100 个字符");
        if (!ROLE_STATUSES.contains(status)) bad("角色状态无效");
    }

    private static Map<String,Object> auditValue(Map<String,Object> source, String key, Object value) {
        Map<String,Object> result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }

    private static List<String> normalizeIds(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
    }

    private static String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }

    private static String nullable(String value) { return blank(value) ? null : value.trim(); }
    private static String upper(String value) { return blank(value) ? null : value.trim().toUpperCase(Locale.ROOT); }
    private static String value(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static void notFound(String message) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
