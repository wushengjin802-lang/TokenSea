package com.tokensea.asset.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.governance.GovernanceApprovalService;
import com.tokensea.route.entity.RoutePolicy;
import com.tokensea.route.mapper.RoutePolicyMapper;
import com.tokensea.route.service.RouteCandidateValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformModelRouteWorkflowTests {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void creatingServiceModelAllowsMultipleModelsPerSelectedChannel() throws Exception {
        PlatformModelMapper models = mock(PlatformModelMapper.class);
        RoutePolicyMapper routes = mock(RoutePolicyMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(models.selectCount(any())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenAnswer(invocation -> {
            String instanceId = invocation.getArgument(2);
            String actualModel = invocation.getArgument(3);
            return switch (actualModel) {
                case "model-a", "model-a2" -> "channel-a".equals(instanceId) ? 1 : 0;
                case "model-b" -> "channel-b".equals(instanceId) ? 1 : 0;
                default -> 0;
            };
        });
        doAnswer(invocation -> {
            PlatformModel model = invocation.getArgument(0);
            model.setId("service-model-1");
            return 1;
        }).when(models).insert(any(PlatformModel.class));
        doAnswer(invocation -> {
            RoutePolicy route = invocation.getArgument(0);
            route.setId("route-draft-1");
            return 1;
        }).when(routes).insert(any(RoutePolicy.class));
        PlatformModelController controller = controller(models, routes, jdbc);

        PlatformModelController.ModelRequest request = new PlatformModelController.ModelRequest(
                "chat-standard", "标准对话模型", null,
                "[\"channel-a\",\"channel-b\"]",
                "[\"model-b\",\"model-a\",\"model-a2\"]",
                null, null, null, null, "全部租户", false);

        PlatformModel result = controller.create(request).data();

        assertThat(result.getStatus()).isEqualTo("草稿");
        assertThat(result.getRoutePolicyId()).isEqualTo("route-draft-1");
        assertThat(result.getRoutePolicy()).isEqualTo("标准对话模型 默认路由");
        verify(models).updateById(result);
        verify(routes).insert(argThat(argThatRoute(route -> {
            Map<String,Object> config = json.readValue(route.getConfig(), new TypeReference<>() {});
            List<Map<String,Object>> candidates = (List<Map<String,Object>>) config.get("candidates");
            assertThat(config.get("managedBy")).isEqualTo("PLATFORM_MODEL_DRAFT");
            assertThat(candidates).containsExactly(
                    Map.of("providerInstanceId", "channel-b", "actualModel", "model-b", "priority", 1),
                    Map.of("providerInstanceId", "channel-a", "actualModel", "model-a", "priority", 2),
                    Map.of("providerInstanceId", "channel-a", "actualModel", "model-a2", "priority", 3));
            return "DRAFT".equals(route.getStatus())
                    && "priority".equals(route.getStrategy())
                    && Boolean.TRUE.equals(route.getFallbackEnabled());
        })));
    }

    @Test
    void editingServiceModelRemapsMultipleModelsToTheirOwningChannels() throws Exception {
        PlatformModelMapper models = mock(PlatformModelMapper.class);
        RoutePolicyMapper routes = mock(RoutePolicyMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(models.selectCount(any())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenAnswer(invocation -> {
            String instanceId = invocation.getArgument(2);
            String actualModel = invocation.getArgument(3);
            return switch (actualModel) {
                case "glm-5.2" -> "glm-channel".equals(instanceId) ? 1 : 0;
                case "doubao-mini", "doubao-lite" -> "doubao-channel".equals(instanceId) ? 1 : 0;
                default -> 0;
            };
        });
        PlatformModel existing = new PlatformModel();
        existing.setId("service-model-1");
        existing.setPlatformModelName("doubao-seed-2-0-min");
        existing.setDisplayName("doubao-seed-2-0-min");
        existing.setProviderInstanceIds("[\"doubao-channel\"]");
        existing.setActualModels("[\"doubao-mini\"]");
        existing.setRoutePolicyId("route-draft-1");
        existing.setRoutePolicy("旧默认路由");
        existing.setVisibilityScope("全部租户");
        existing.setStatus("草稿");
        RoutePolicy route = new RoutePolicy();
        route.setId("route-draft-1");
        route.setName("旧默认路由");
        route.setModelAlias("doubao-seed-2-0-min");
        route.setStrategy("priority");
        route.setFallbackEnabled(true);
        route.setStatus("DRAFT");
        route.setConfig("{\"managedBy\":\"PLATFORM_MODEL_DRAFT\",\"candidates\":[]}");
        when(models.selectById("service-model-1")).thenReturn(existing, copy(existing));
        when(routes.selectById("route-draft-1")).thenReturn(route);
        PlatformModelController controller = controller(models, routes, jdbc);
        PlatformModelController.ModelRequest request = new PlatformModelController.ModelRequest(
                "doubao-seed-2-0-min", "doubao-seed-2-0-min", null,
                "[\"doubao-channel\",\"glm-channel\"]",
                "[\"glm-5.2\",\"doubao-mini\",\"doubao-lite\"]",
                null, null, null, null, "全部租户", false);

        PlatformModel result = controller.update("service-model-1", request).data();

        assertThat(result.getRoutePolicyId()).isEqualTo("route-draft-1");
        verify(routes).updateById(argThat(argThatRoute(updated -> {
            Map<String,Object> config = json.readValue(updated.getConfig(), new TypeReference<>() {});
            List<Map<String,Object>> candidates = (List<Map<String,Object>>) config.get("candidates");
            assertThat(candidates).containsExactly(
                    Map.of("providerInstanceId", "glm-channel", "actualModel", "glm-5.2", "priority", 1),
                    Map.of("providerInstanceId", "doubao-channel", "actualModel", "doubao-mini", "priority", 2),
                    Map.of("providerInstanceId", "doubao-channel", "actualModel", "doubao-lite", "priority", 3));
            return true;
        })));
    }

    @Test
    void configuringPublishedModelReusesItsBoundRouteInsteadOfCreatingAnotherPolicy() {
        PlatformModelMapper models = mock(PlatformModelMapper.class);
        RoutePolicyMapper routes = mock(RoutePolicyMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        PlatformModel model = new PlatformModel();
        model.setId("service-model-1");
        model.setPlatformModelName("chat-standard");
        model.setDisplayName("标准对话模型");
        model.setProviderInstanceIds("[\"channel-a\"]");
        model.setActualModels("[\"model-a\"]");
        model.setRoutePolicyId("route-active-1");
        model.setRoutePolicy("生产路由");
        model.setStatus("已发布");
        RoutePolicy active = new RoutePolicy();
        active.setId("route-active-1");
        active.setName("生产路由");
        active.setModelAlias("chat-standard");
        active.setStrategy("weighted");
        active.setFallbackEnabled(false);
        active.setStatus("ACTIVE");
        when(models.selectById("service-model-1")).thenReturn(model);
        when(routes.selectById("route-active-1")).thenReturn(active);
        PlatformModelController controller = controller(models, routes, jdbc);

        RoutePolicy result = controller.routeDraft("service-model-1").data();

        assertThat(result.getId()).isEqualTo("route-active-1");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getStrategy()).isEqualTo("weighted");
        assertThat(result.getFallbackEnabled()).isFalse();
        assertThat(model.getRoutePolicyId()).isEqualTo("route-active-1");
        assertThat(model.getStatus()).isEqualTo("已发布");
        verify(routes, never()).insert(any(RoutePolicy.class));
        verify(routes, never()).updateById(active);
        verify(models, never()).updateById(model);
    }

    @Test
    void editingServiceModelWithoutRouteFieldPreservesExistingManualDraftRoute() {
        PlatformModelMapper models = mock(PlatformModelMapper.class);
        RoutePolicyMapper routes = mock(RoutePolicyMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        when(models.selectCount(any())).thenReturn(0L);
        PlatformModel existing = new PlatformModel();
        existing.setId("service-model-1");
        existing.setPlatformModelName("chat-standard");
        existing.setDisplayName("标准对话模型");
        existing.setProviderInstanceIds("[\"channel-a\"]");
        existing.setActualModels("[\"model-a\"]");
        existing.setRoutePolicyId("route-manual-1");
        existing.setRoutePolicy("手工路由");
        existing.setStatus("草稿");
        RoutePolicy manual = new RoutePolicy();
        manual.setId("route-manual-1");
        manual.setName("手工路由");
        manual.setModelAlias("chat-standard");
        manual.setStrategy("priority");
        manual.setFallbackEnabled(false);
        manual.setStatus("DRAFT");
        manual.setConfig("{\"candidates\":[{\"providerInstanceId\":\"channel-a\",\"actualModel\":\"model-a\",\"priority\":1}]}");
        when(models.selectById("service-model-1")).thenReturn(existing, copy(existing));
        when(routes.selectById("route-manual-1")).thenReturn(manual);
        PlatformModelController controller = controller(models, routes, jdbc);
        PlatformModelController.ModelRequest request = new PlatformModelController.ModelRequest(
                "chat-standard", "标准对话模型 V2", null,
                "[\"channel-a\"]", "[\"model-a\"]",
                null, null, null, null, "全部租户", false);

        PlatformModel result = controller.update("service-model-1", request).data();

        assertThat(result.getRoutePolicyId()).isEqualTo("route-manual-1");
        assertThat(result.getRoutePolicy()).isEqualTo("手工路由");
        verify(routes, never()).insert(any(RoutePolicy.class));
        verify(routes, never()).updateById(any(RoutePolicy.class));
    }

    private PlatformModelController controller(PlatformModelMapper models, RoutePolicyMapper routes, JdbcTemplate jdbc) {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return new PlatformModelController(
                models,
                mock(ProviderInstanceMapper.class),
                mock(AuditLogMapper.class),
                routes,
                mock(ProviderConnectionService.class),
                mock(RouteCandidateValidator.class),
                json,
                transactions,
                mock(GovernanceApprovalService.class),
                jdbc);
    }

    private static PlatformModel copy(PlatformModel source) {
        PlatformModel copy = new PlatformModel();
        copy.setId(source.getId());
        copy.setPlatformModelName(source.getPlatformModelName());
        copy.setDisplayName(source.getDisplayName());
        copy.setProviderInstanceIds(source.getProviderInstanceIds());
        copy.setActualModels(source.getActualModels());
        copy.setRoutePolicyId(source.getRoutePolicyId());
        copy.setRoutePolicy(source.getRoutePolicy());
        copy.setVisibilityScope(source.getVisibilityScope());
        copy.setApprovalRequired(source.getApprovalRequired());
        copy.setStatus(source.getStatus());
        return copy;
    }

    private static org.mockito.ArgumentMatcher<RoutePolicy> argThatRoute(
            ThrowingRouteMatcher matcher) {
        return route -> {
            try {
                return matcher.matches(route);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRouteMatcher {
        boolean matches(RoutePolicy route) throws Exception;
    }
}
