package com.tokensea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.asset.controller.ProviderTemplateController;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.dashboard.DashboardController;
import com.tokensea.price.entity.ModelPrice;
import com.tokensea.price.mapper.ModelPriceMapper;
import com.tokensea.provider.mapper.ProviderMapper;
import com.tokensea.provider.mapper.ProviderSecretMapper;
import com.tokensea.provider.service.CryptoService;
import com.tokensea.route.entity.RoutePolicy;
import com.tokensea.route.service.RouteCandidateValidator;
import com.tokensea.security.JwtService;
import com.tokensea.tenant.controller.TenantWorkspaceController;
import com.tokensea.tenant.mapper.TenantMapper;
import com.tokensea.usage.mapper.UsageRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Phase1GContractTests {
    @Test
    void revokedMembershipImmediatelyRemovesTenantAccess() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("tenant-a"), List.of());
        TenantWorkspaceController controller = new TenantWorkspaceController(jdbc);
        var auth = new UsernamePasswordAuthenticationToken(
                new JwtService.Identity("user-a", List.of("TENANT_USER"), List.of("tenant-a", "tenant-b")), null);

        assertEquals(List.of("tenant-a"), controller.context(auth).data().get("tenantIds"));
        assertThrows(AccessDeniedException.class, () -> controller.context(auth));
        verify(jdbc, times(2)).queryForList(contains("user_tenant"), any(MapSqlParameterSource.class), eq(String.class));
    }

    @Test
    void tenantModelsUseTheSameVisibilityScopesAsGateway() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(contains("user_tenant"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("tenant-a"));
        when(jdbc.queryForList(contains("distinct type from tenant"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("INTERNAL"));
        when(jdbc.queryForList(contains("from platform_model"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(
                        model("all-cn", "全部租户"),
                        model("all-en", "ALL"),
                        model("wildcard", "*"),
                        model("internal", "内部租户"),
                        model("single-id", "tenant-a"),
                        model("json-id", "[\"tenant-a\"]"),
                        model("json-type", "[\"INTERNAL\"]"),
                        model("other", "[\"tenant-b\"]"),
                        model("invalid", "[invalid]")));
        TenantWorkspaceController controller = new TenantWorkspaceController(jdbc);
        var auth = new UsernamePasswordAuthenticationToken(
                new JwtService.Identity("user-a", List.of("TENANT_USER"), List.of("tenant-a", "tenant-b")), null);

        List<String> ids = controller.models(auth).data().stream().map(row -> String.valueOf(row.get("id"))).toList();

        assertEquals(List.of("all-cn", "all-en", "wildcard", "internal", "single-id", "json-id", "json-type"), ids);
        verify(jdbc).queryForList(contains("status='ACTIVE'"), any(MapSqlParameterSource.class), eq(String.class));
    }

    private static Map<String,Object> model(String id, String visibilityScope) {
        return Map.of("id", id, "visibility_scope", visibilityScope);
    }

    @Test
    void routeCandidateWithoutApplicablePriceIsRejected() {
        ModelPriceMapper prices = mock(ModelPriceMapper.class);
        RouteCandidateValidator validator = new RouteCandidateValidator(prices, new ObjectMapper(), "CNY", mock(org.springframework.jdbc.core.JdbcTemplate.class));
        PlatformModel model = new PlatformModel();
        model.setId("pm-1"); model.setPlatformModelName("chat");
        model.setProviderInstanceIds("[\"pi-1\"]"); model.setActualModels("[\"model-a\"]");
        RoutePolicy route = new RoutePolicy();
        route.setModelAlias("chat"); route.setStatus("ACTIVE");
        route.setConfig("{\"candidates\":[{\"providerInstanceId\":\"pi-1\",\"actualModel\":\"model-a\",\"priceVersionId\":\"price-1\"}]}");
        ModelPrice wrong = new ModelPrice();
        wrong.setPlatformModelId("other-model"); wrong.setProviderInstanceId("pi-1");
        wrong.setCurrency("CNY"); wrong.setStatus("ACTIVE"); wrong.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        when(prices.selectById("price-1")).thenReturn(wrong);

        assertThrows(ResponseStatusException.class, () -> validator.validate(model, route, true));
    }

    @Test
    void proxyDenialHasStableBusinessError() throws Exception {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        proxy.start();
        try {
            ProviderConnectionService service = new ProviderConnectionService(
                    mock(ProviderSecretMapper.class), mock(CryptoService.class),
                    "provider.example", "80", "127.0.0.1", proxy.getAddress().getPort());
            ProviderInstance instance = new ProviderInstance();
            instance.setApiBase("http://provider.example"); instance.setKeyStatus("无需 Key");
            var result = service.test(instance);
            assertFalse(result.success());
            assertEquals("PROVIDER_EGRESS_DENIED", result.errorCode());
        } finally { proxy.stop(0); }
    }

    @Test
    void dashboardCountsProviderInstancesAndPlatformModels() {
        TenantMapper tenants = mock(TenantMapper.class);
        PlatformModelMapper models = mock(PlatformModelMapper.class);
        ApiKeyEntityMapper keys = mock(ApiKeyEntityMapper.class);
        UsageRecordMapper usage = mock(UsageRecordMapper.class);
        ProviderInstanceMapper instances = mock(ProviderInstanceMapper.class);
        when(tenants.selectCount(null)).thenReturn(1L);
        when(models.selectCount(null)).thenReturn(2L);
        when(keys.selectCount(null)).thenReturn(3L);
        when(usage.selectCount(null)).thenReturn(4L);
        when(usage.aggregateStats()).thenReturn(Map.of());
        when(instances.selectCount(null)).thenReturn(5L);
        when(instances.selectCount(notNull())).thenReturn(1L);

        Map<String,Object> stats = new DashboardController(tenants, models, keys, usage, instances).stats().data();
        assertEquals(5L, stats.get("providers"));
        assertEquals(2L, stats.get("models"));
        verify(instances).selectCount(null);
        verify(models).selectCount(null);
    }

    @Test
    void providerTemplateControllerHasNoLegacyProviderDependency() {
        for (var constructor : ProviderTemplateController.class.getConstructors()) {
            assertFalse(List.of(constructor.getParameterTypes()).contains(ProviderMapper.class));
        }
    }
}
