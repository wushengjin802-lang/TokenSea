package com.tokensea.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.access.AccessControlController;
import com.tokensea.apikey.controller.ApiKeyController;
import com.tokensea.apikey.entity.ApiKeyEntity;
import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.app.entity.AppEntity;
import com.tokensea.app.mapper.AppEntityMapper;
import com.tokensea.audit.controller.AuditLogController;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.governance.ProviderPriceSyncController;
import com.tokensea.governance.ProviderPriceSyncService;
import com.tokensea.project.entity.Project;
import com.tokensea.project.mapper.ProjectMapper;
import com.tokensea.tenant.controller.TenantController;
import com.tokensea.tenant.entity.Tenant;
import com.tokensea.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagementPaginationTests {

    @Test
    void pageQueryUsesSafeDefaultsAndCapsOptionQueries() {
        PageQuery defaults = PageQuery.of(null, null, null, null,
                Map.of("createdAt", "created_at"), "createdAt", "desc");
        assertEquals(1, defaults.page());
        assertEquals(20, defaults.size());
        assertEquals("created_at", defaults.sortColumn());
        assertEquals("desc", defaults.direction());

        PageQuery capped = PageQuery.of(2, 5_000, "unknown", "asc",
                Map.of("createdAt", "created_at"), "createdAt", "desc");
        assertEquals(500, capped.size());
        assertEquals(500L, capped.offset());
        assertEquals("created_at", capped.sortColumn());
        assertEquals("asc", capped.direction());
    }

    @Test
    void userListPaginatesAndBatchLoadsAssociations() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AccessControlController controller = new AccessControlController(
                jdbc, mock(PasswordEncoder.class), mock(AuditService.class));

        Map<String,Object> userA = row("id", "user-a", "username", "alice", "status", "ACTIVE");
        Map<String,Object> userB = row("id", "user-b", "username", "bob", "status", "ACTIVE");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from user_account")) return new ArrayList<>(List.of(userA, userB));
            if (sql.contains("from user_role")) return List.of(
                    row("ownerId", "user-a", "id", "role-admin", "code", "ADMIN", "name", "平台管理员"),
                    row("ownerId", "user-b", "id", "role-user", "code", "USER", "name", "普通用户"));
            if (sql.contains("from user_tenant")) return List.of(
                    row("ownerId", "user-a", "id", "tenant-a", "name", "租户甲"),
                    row("ownerId", "user-b", "id", "tenant-b", "name", "租户乙"));
            return List.of();
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        PageResult<Map<String,Object>> result = controller.users(null, null, 1, 20, null, null).data();

        assertEquals(2L, result.total());
        assertEquals(List.of("ADMIN"), result.items().get(0).get("roleCodes"));
        assertEquals(List.of("租户乙"), result.items().get(1).get("tenantNames"));
        verify(jdbc, times(3)).queryForList(anyString(), any(Object[].class));
        verify(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void roleListPaginatesAndBatchLoadsPermissions() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AccessControlController controller = new AccessControlController(
                jdbc, mock(PasswordEncoder.class), mock(AuditService.class));

        Map<String,Object> roleA = row("id", "role-a", "code", "OPS", "name", "运维", "status", "ACTIVE");
        Map<String,Object> roleB = row("id", "role-b", "code", "AUDIT", "name", "审计", "status", "ACTIVE");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from role r") && sql.contains("left join user_role")) {
                return new ArrayList<>(List.of(roleA, roleB));
            }
            if (sql.contains("from role_permission")) return List.of(
                    row("ownerId", "role-a", "id", "perm-a", "code", "OPS_READ", "name", "运维查看"),
                    row("ownerId", "role-b", "id", "perm-b", "code", "AUDIT_READ", "name", "审计查看"));
            return List.of();
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        PageResult<Map<String,Object>> result = controller.roles(null, null, 1, 20, null, null).data();

        assertEquals(2L, result.total());
        assertEquals(List.of("OPS_READ"), result.items().get(0).get("permissionCodes"));
        assertEquals(List.of("审计查看"), result.items().get(1).get("permissionNames"));
        verify(jdbc, times(2)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void tenantListReturnsStablePageContract() {
        TenantMapper mapper = mock(TenantMapper.class);
        TenantController controller = new TenantController(mapper, mock(AuditService.class), mock(JdbcTemplate.class));
        Tenant tenant = new Tenant(); tenant.setId("tenant-a"); tenant.setName("租户甲");
        when(mapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(tenant));

        PageResult<Tenant> result = controller.list(1, 20, "租户", "ACTIVE", "name", "asc").data();

        assertEquals(1L, result.total());
        assertEquals("tenant-a", result.items().getFirst().getId());
        assertEquals(1, result.page());
        assertEquals(20, result.size());
    }

    @Test
    void auditListReturnsPagedProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditLogController controller = new AuditLogController(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("id", "audit-a", "action", "TENANT_CREATE")));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        PageResult<Map<String,Object>> result = controller.list(1, 20, "tenant", "createdAt", "desc").data();

        assertEquals(1L, result.total());
        assertEquals("audit-a", result.items().getFirst().get("id"));
    }

    @Test
    void priceDiffListReturnsPagedProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderPriceSyncController controller = new ProviderPriceSyncController(
                jdbc, new ObjectMapper(), mock(ProviderPriceSyncService.class), mock(AuditService.class));
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("id", "diff-a", "status", "PENDING")));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        PageResult<Map<String,Object>> result = controller.diffs(
                "PENDING", null, null, 1, 20, "deepseek", "createdAt", "desc").data();

        assertEquals(1L, result.total());
        assertEquals("diff-a", result.items().getFirst().get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keyListUsesBatchLookupsInsteadOfPerRowQueries() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        AppEntityMapper apps = mock(AppEntityMapper.class);
        ApiKeyController controller = new ApiKeyController(
                keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants, projects, apps);

        ApiKeyEntity first = key("key-a", "tenant-a", "project-a", "app-a", "研发 Key");
        ApiKeyEntity second = key("key-b", "tenant-a", "project-a", "app-b", "测试 Key");
        when(keys.selectCount(any(QueryWrapper.class))).thenReturn(2L);
        when(keys.selectList(any(QueryWrapper.class))).thenReturn(List.of(first, second));

        Tenant tenant = new Tenant(); tenant.setId("tenant-a"); tenant.setName("租户甲");
        Project project = new Project(); project.setId("project-a"); project.setName("项目甲");
        AppEntity appA = new AppEntity(); appA.setId("app-a"); appA.setName("研发应用");
        AppEntity appB = new AppEntity(); appB.setId("app-b"); appB.setName("测试应用");
        when(tenants.selectBatchIds(anyCollection())).thenReturn(List.of(tenant));
        when(projects.selectBatchIds(anyCollection())).thenReturn(List.of(project));
        when(apps.selectBatchIds(anyCollection())).thenReturn(List.of(appA, appB));

        PageResult<ApiKeyController.KeyResponse> result = controller.list(1, 20, null, null, null, null).data();

        assertEquals(2L, result.total());
        assertEquals("租户甲", result.items().get(0).tenantName());
        assertEquals("测试应用", result.items().get(1).appName());
        verify(tenants).selectBatchIds(anyCollection());
        verify(projects).selectBatchIds(anyCollection());
        verify(apps).selectBatchIds(anyCollection());
        verify(tenants, times(0)).selectById(any());
        verify(projects, times(0)).selectById(any());
        verify(apps, times(0)).selectById(any());
    }

    private static ApiKeyEntity key(String id, String tenantId, String projectId, String appId, String name) {
        ApiKeyEntity value = new ApiKeyEntity();
        value.setId(id);
        value.setTenantId(tenantId);
        value.setProjectId(projectId);
        value.setAppId(appId);
        value.setName(name);
        value.setKeyPrefix("pending");
        value.setStatus("PENDING");
        value.setApprovalStatus("APPROVED");
        value.setModelScope("[\"chat-standard\"]");
        value.setIpWhitelist("[]");
        return value;
    }

    private static Map<String,Object> row(Object... values) {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
        return row;
    }
}
