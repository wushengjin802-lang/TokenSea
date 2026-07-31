package com.tokensea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.apikey.controller.ApiKeyController;
import com.tokensea.apikey.entity.ApiKeyEntity;
import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.app.entity.AppEntity;
import com.tokensea.app.mapper.AppEntityMapper;
import com.tokensea.asset.controller.PlatformModelController;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiExceptionHandler;
import com.tokensea.common.OperationException;
import com.tokensea.governance.CapabilityProbeController;
import com.tokensea.governance.CapabilityProbeService;
import com.tokensea.governance.GovernanceApprovalService;
import com.tokensea.governance.ModelLifecycleService;
import com.tokensea.project.entity.Project;
import com.tokensea.project.mapper.ProjectMapper;
import com.tokensea.route.controller.RoutePolicyController;
import com.tokensea.route.entity.RoutePolicy;
import com.tokensea.route.mapper.RoutePolicyMapper;
import com.tokensea.route.service.RouteCandidateValidator;
import com.tokensea.security.JwtService;
import com.tokensea.tenant.entity.Tenant;
import com.tokensea.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminFlowSimplificationTests {
    private static final UsernamePasswordAuthenticationToken ADMIN =
            new UsernamePasswordAuthenticationToken(
                    new JwtService.Identity("admin-user", List.of("ADMIN"), List.of()), null);

    @Test
    void successfulLiveProbeRecordsTechnicalHealthWithoutAutoApproval() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderInstanceMapper instances = mock(ProviderInstanceMapper.class);
        ProviderConnectionService connections = mock(ProviderConnectionService.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        AuditService audits = mock(AuditService.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        Map<String, Object> deployment = new HashMap<>();
        deployment.put("id", "deployment-1");
        deployment.put("provider_instance_id", "channel-1");
        deployment.put("provider_model_name", "model-1");
        deployment.put("review_status", "PENDING");
        Map<String, Object> validation = Map.of("id", "validation-1", "status", "PASSED");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(deployment), List.of(validation));
        ProviderInstance channel = new ProviderInstance();
        channel.setId("channel-1");
        channel.setInstanceName("DeepSeek Production");
        when(instances.selectById("channel-1")).thenReturn(channel);
        when(connections.matchesVerifiedTarget(channel)).thenReturn(true);
        when(connections.probeCapability(channel, "model-1", "CHAT"))
                .thenReturn(new ProviderConnectionService.CapabilityProbeResult(
                        true, 200, null, null, "/v1/chat/completions", 30, false, 128));
        ModelLifecycleService lifecycle = mock(ModelLifecycleService.class);
        CapabilityProbeService service = new CapabilityProbeService(
                jdbc, instances, connections, transactions, new ObjectMapper(), audits, lifecycle);
        CapabilityProbeController controller = new CapabilityProbeController(service);

        Map<String, Object> result = controller.probe(
                "deployment-1", new CapabilityProbeController.ProbeRequest("CHAT"), ADMIN).data();

        assertEquals("PASSED", result.get("status"));
        verify(lifecycle).markProbeResult("deployment-1", true);
        verify(audits, never()).record(eq("MODEL_DEPLOYMENT_AUTO_APPROVE"), anyString(), anyString(), any(), any());
    }

    @Test
    void platformAdminActivatesDraftRouteWithoutApprovalRoundTrip() {
        RoutePolicyMapper routes = mock(RoutePolicyMapper.class);
        PlatformModelMapper models = mock(PlatformModelMapper.class);
        GovernanceApprovalService approvals = mock(GovernanceApprovalService.class);
        RouteCandidateValidator validator = mock(RouteCandidateValidator.class);
        RoutePolicy route = new RoutePolicy();
        route.setId("route-1");
        route.setName("chat-route");
        route.setModelAlias("chat-standard");
        route.setStrategy("priority");
        route.setFallbackEnabled(true);
        route.setConfig("{\"candidates\":[{\"providerInstanceId\":\"channel-1\",\"actualModel\":\"model-1\"}]}");
        route.setStatus("DRAFT");
        PlatformModel model = new PlatformModel();
        model.setPlatformModelName("chat-standard");
        model.setProviderInstanceIds("[\"channel-1\"]");
        model.setActualModels("[\"model-1\"]");
        when(routes.selectById("route-1")).thenReturn(route);
        when(models.selectOne(any())).thenReturn(model);
        CapabilityProbeService probes = mock(CapabilityProbeService.class);
        RoutePolicyController controller = new RoutePolicyController(
                routes, models, mock(AuditLogMapper.class), new ObjectMapper(), validator, approvals, probes);

        RoutePolicy result = controller.activate("route-1", ADMIN).data();

        assertEquals("ACTIVE", result.getStatus());
        verify(approvals, never()).requireApproved(anyString(), anyString(), anyString());
        verify(probes).ensureEligible("channel-1", "model-1", ADMIN);
        verify(validator).validate(eq(model), any(RoutePolicy.class), eq(false));
        verify(routes).updateById(route);
    }

    @Test
    void platformAdminPublishesLowRiskServiceModelWithoutApprovalRoundTrip() {
        PlatformModelMapper models = mock(PlatformModelMapper.class);
        ProviderInstanceMapper instances = mock(ProviderInstanceMapper.class);
        RoutePolicyMapper routes = mock(RoutePolicyMapper.class);
        GovernanceApprovalService approvals = mock(GovernanceApprovalService.class);
        ProviderConnectionService connections = mock(ProviderConnectionService.class);
        RouteCandidateValidator validator = mock(RouteCandidateValidator.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        PlatformModel model = new PlatformModel();
        model.setId("service-model-1");
        model.setPlatformModelName("chat-standard");
        model.setProviderInstanceIds("[\"channel-1\"]");
        model.setActualModels("[\"model-1\"]");
        model.setRoutePolicyId("route-1");
        model.setVisibilityScope("全部租户");
        model.setApprovalRequired(false);
        model.setStatus("草稿");
        RoutePolicy route = new RoutePolicy();
        route.setId("route-1");
        route.setName("chat-route");
        route.setModelAlias("chat-standard");
        route.setStatus("ACTIVE");
        ProviderInstance channel = new ProviderInstance();
        channel.setId("channel-1");
        channel.setInstanceName("DeepSeek Production");
        channel.setStatus("启用");
        channel.setLastConnectionTestStatus("成功");
        channel.setLastConnectionTestAt(OffsetDateTime.now());
        channel.setKeyStatus("已托管");
        when(models.selectById("service-model-1")).thenReturn(model);
        when(routes.selectById("route-1")).thenReturn(route);
        when(instances.selectById("channel-1")).thenReturn(channel);
        when(connections.matchesVerifiedTarget(channel)).thenReturn(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        PlatformModelController controller = new PlatformModelController(
                models, instances, mock(AuditLogMapper.class), routes, connections, validator,
                new ObjectMapper(), transactions, approvals, jdbc);

        PlatformModel result = controller.publish("service-model-1", ADMIN).data();
        PlatformModelController.PublishCheckResult check = controller.publishCheck("service-model-1").data();

        assertEquals("已发布", result.getStatus());
        assertTrue(check.ready());
        assertTrue(check.checks().stream().allMatch(PlatformModelController.PublishCheckItem::passed));
        verify(approvals, never()).requireApproved(anyString(), anyString(), anyString());
        verify(validator, times(2)).validate(model, route, true);
        verify(models).updateById(model);
    }

    @Test
    void platformAdminCreatedKeyIsAutomaticallyAuthorized() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        doAnswer(invocation -> {
            ApiKeyEntity entity = invocation.getArgument(0);
            entity.setId("key-1");
            return 1;
        }).when(keys).insert(any(ApiKeyEntity.class));
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        ApiKeyController controller = new ApiKeyController(keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants,
                mock(ProjectMapper.class), mock(AppEntityMapper.class));
        ApiKeyController.KeyRequest request = new ApiKeyController.KeyRequest(
                "tenant-1", "TENANT", null, null, "test-key", "[\"chat-standard\"]",
                null, null, null, null, null, null);

        ApiKeyController.KeyResponse result = controller.create(request, ADMIN).data();

        assertEquals("APPROVED", result.approvalStatus());
        assertEquals("PENDING", result.status());
        assertEquals("pending", result.keyPrefix());
        verify(keys).insert(argThat((ApiKeyEntity entity) -> "admin-user".equals(entity.getCreatedBy())));
    }

    @Test
    void keyApprovalRecordsApprover() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId("key-approve");
        key.setTenantId("tenant-1");
        key.setName("approval-key");
        key.setKeyPrefix("pending");
        key.setStatus("PENDING");
        key.setApprovalStatus("PENDING");
        key.setModelScope("[\"chat-standard\"]");
        key.setIpWhitelist("[]");
        when(keys.selectById("key-approve")).thenReturn(key);
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setName("Tenant 1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        ApiKeyController controller = new ApiKeyController(
                keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants,
                mock(ProjectMapper.class), mock(AppEntityMapper.class));

        ApiKeyController.KeyResponse result = controller.approve("key-approve", ADMIN).data();

        assertEquals("ACTIVE", result.status());
        assertEquals("APPROVED", result.approvalStatus());
        assertEquals("admin-user", key.getApprovedBy());
        assertNotNull(key.getApprovedAt());
        verify(keys).updateById(key);
    }

    @Test
    void keyCreationDefaultsToApplicationScope() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        doAnswer(invocation -> {
            ApiKeyEntity entity = invocation.getArgument(0);
            entity.setId("key-app");
            return 1;
        }).when(keys).insert(any(ApiKeyEntity.class));
        TenantMapper tenants = mock(TenantMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        AppEntityMapper apps = mock(AppEntityMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        Project project = new Project();
        project.setId("project-1");
        project.setTenantId("tenant-1");
        project.setStatus("ACTIVE");
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setTenantId("tenant-1");
        app.setProjectId("project-1");
        app.setStatus("ACTIVE");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        when(projects.selectById("project-1")).thenReturn(project);
        when(apps.selectById("app-1")).thenReturn(app);
        ApiKeyController controller = new ApiKeyController(
                keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants, projects, apps);
        ApiKeyController.KeyRequest request = new ApiKeyController.KeyRequest(
                "tenant-1", null, "project-1", "app-1", "app-key", "[\"chat-standard\"]",
                null, null, null, null, null, null);

        ApiKeyController.KeyResponse result = controller.create(request, ADMIN).data();

        assertEquals("APPLICATION", result.scopeLevel());
        assertEquals("project-1", result.projectId());
        assertEquals("app-1", result.appId());
    }

    @Test
    void defaultApplicationScopeRejectsMissingProjectAndApp() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        ApiKeyController controller = new ApiKeyController(
                keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants,
                mock(ProjectMapper.class), mock(AppEntityMapper.class));
        ApiKeyController.KeyRequest request = new ApiKeyController.KeyRequest(
                "tenant-1", null, null, null, "unsafe-key", "[\"chat-standard\"]",
                null, null, null, null, null, null);

        OperationException error = assertThrows(OperationException.class, () -> controller.create(request, ADMIN));

        assertEquals("KEY_APPLICATION_SCOPE_REQUIRED", error.code());
        verify(keys, never()).insert(any(ApiKeyEntity.class));
    }

    @Test
    void keyCreationRejectsModelOutsideTenantScope() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        ApiKeyController controller = new ApiKeyController(keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants,
                mock(ProjectMapper.class), mock(AppEntityMapper.class));
        ApiKeyController.KeyRequest request = new ApiKeyController.KeyRequest(
                "tenant-1", "TENANT", null, null, "test-key", "[\"chat-experimental\"]",
                null, null, null, null, null, null);

        OperationException error = assertThrows(OperationException.class, () -> controller.create(request, ADMIN));

        assertEquals("KEY_MODEL_SCOPE_EXCEEDS_TENANT", error.code());
        verify(keys, never()).insert(any(ApiKeyEntity.class));
    }

    @Test
    void platformAdminCanGenerateLegacyPendingKeyWithoutSeparateApproval() throws Exception {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId("key-1");
        key.setTenantId("tenant-1");
        key.setName("legacy-key");
        key.setKeyPrefix("pending");
        key.setStatus("PENDING");
        key.setApprovalStatus("PENDING");
        key.setModelScope("[\"chat-standard\"]");
        key.setIpWhitelist("[]");
        when(keys.selectById("key-1")).thenReturn(key);
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        ApiKeyController controller = new ApiKeyController(
                keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants,
                mock(ProjectMapper.class), mock(AppEntityMapper.class));

        ApiKeyController.GeneratedKey result = controller.generate("key-1", ADMIN).data();

        assertTrue(result.plainTextKey().startsWith("ts_"));
        assertEquals("APPROVED", key.getApprovalStatus());
        assertEquals("ACTIVE", key.getStatus());
        assertEquals("admin-user", key.getApprovedBy());
        verify(keys).updateById(key);
    }

    @Test
    void keyCreationRejectsProjectFromAnotherTenant() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        Project project = new Project();
        project.setId("project-2");
        project.setTenantId("tenant-2");
        project.setStatus("ACTIVE");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        when(projects.selectById("project-2")).thenReturn(project);
        ApiKeyController controller = new ApiKeyController(keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants,
                projects, mock(AppEntityMapper.class));
        ApiKeyController.KeyRequest request = new ApiKeyController.KeyRequest(
                "tenant-1", "PROJECT", "project-2", null, "bad-key", "[\"chat-standard\"]",
                null, null, null, null, null, null);

        OperationException error = assertThrows(OperationException.class, () -> controller.create(request, ADMIN));

        assertEquals("KEY_PROJECT_SCOPE_INVALID", error.code());
        verify(keys, never()).insert(any(ApiKeyEntity.class));
    }

    @Test
    void keyCreationRequiresAppToBelongToSelectedProject() {
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        AppEntityMapper apps = mock(AppEntityMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setStatus("ACTIVE");
        tenant.setModelScope("[\"chat-standard\"]");
        Project project = new Project();
        project.setId("project-1");
        project.setTenantId("tenant-1");
        project.setStatus("ACTIVE");
        AppEntity app = new AppEntity();
        app.setId("app-2");
        app.setTenantId("tenant-1");
        app.setProjectId("project-2");
        app.setStatus("ACTIVE");
        when(tenants.selectById("tenant-1")).thenReturn(tenant);
        when(projects.selectById("project-1")).thenReturn(project);
        when(apps.selectById("app-2")).thenReturn(app);
        ApiKeyController controller = new ApiKeyController(keys, mock(AuditLogMapper.class), new ObjectMapper(), tenants,
                projects, apps);
        ApiKeyController.KeyRequest request = new ApiKeyController.KeyRequest(
                "tenant-1", "APPLICATION", "project-1", "app-2", "bad-app-key", "[\"chat-standard\"]",
                null, null, null, null, null, null);

        OperationException error = assertThrows(OperationException.class, () -> controller.create(request, ADMIN));

        assertEquals("KEY_APP_SCOPE_INVALID", error.code());
        verify(keys, never()).insert(any(ApiKeyEntity.class));
    }

    @Test
    void structuredOperationErrorContainsLocationProblemAndAction() {
        OperationException exception = OperationException.conflict(
                "TEST_ERROR", "企业服务模型 / 发布", "路由未生效", "进入路由页面点击校验并生效");

        var response = new ApiExceptionHandler().handleOperation(exception);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("企业服务模型 / 发布", response.getBody().get("location"));
        assertEquals("路由未生效", response.getBody().get("problem"));
        assertEquals("进入路由页面点击校验并生效", response.getBody().get("action"));
    }
}
