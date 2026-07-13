package com.tokensea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.bootstrap.BootstrapController;
import com.tokensea.price.controller.ModelPriceController;
import com.tokensea.price.entity.ModelPrice;
import com.tokensea.price.mapper.ModelPriceMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.project.entity.Project;
import com.tokensea.route.controller.RoutePolicyController;
import com.tokensea.route.mapper.RoutePolicyMapper;
import com.tokensea.route.service.RouteCandidateValidator;
import com.tokensea.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlPlaneContractTests {
    @Test
    void bootstrapRecoveryRequiresDeploymentToken() {
        UserAccountMapper users=mock(UserAccountMapper.class); JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class),eq(Boolean.class))).thenReturn(false);
        when(users.selectCount(any())).thenReturn(1L);
        BootstrapController controller=new BootstrapController(users,mock(PasswordEncoder.class),jdbc,mock(AuditLogMapper.class),"expected-token");
        ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.initAdmin(
                new BootstrapController.InitAdminRequest(null,null,null,null,"existing","wrong-token")));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
    }

    @Test
    void priceRejectsNegativeAndActiveTransitionConflict() {
        ModelPriceMapper mapper=mock(ModelPriceMapper.class);
        ModelPriceController controller=new ModelPriceController(mapper,mock(AuditLogMapper.class),new ObjectMapper(),"CNY",mock(com.tokensea.governance.GovernanceApprovalService.class));
        ModelPriceController.PriceRequest invalid=new ModelPriceController.PriceRequest(null,"pm",null,"CNY",
                new BigDecimal("-1"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,OffsetDateTime.now(),null);
        ResponseStatusException bad=assertThrows(ResponseStatusException.class,()->controller.create(invalid));
        assertEquals(HttpStatus.BAD_REQUEST,bad.getStatusCode());
        ModelPrice active=new ModelPrice();active.setId("p1");active.setStatus("ACTIVE");when(mapper.selectById("p1")).thenReturn(active);
        ResponseStatusException conflict=assertThrows(ResponseStatusException.class,()->controller.activate("p1",null));
        assertEquals(HttpStatus.CONFLICT,conflict.getStatusCode());
    }

    @Test
    void routeRejectsUnknownStrategy() {
        RoutePolicyController controller=new RoutePolicyController(mock(RoutePolicyMapper.class),mock(PlatformModelMapper.class),
                mock(AuditLogMapper.class),new ObjectMapper(),mock(RouteCandidateValidator.class),mock(com.tokensea.governance.GovernanceApprovalService.class));
        ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.create(
                new RoutePolicyController.RouteRequest("route","chat-standard","random",true,"{\"candidates\":[{\"providerInstanceId\":\"p\",\"actualModel\":\"m\"}]}")));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
    }

    @Test
    void genericCrudReturns404ForMissingResource() {
        @SuppressWarnings("unchecked") BaseMapper<Project> mapper=mock(BaseMapper.class);
        when(mapper.selectById("missing")).thenReturn(null);
        BaseCrudController<Project> controller=new BaseCrudController<>() {
            @Override protected BaseMapper<Project> mapper() { return mapper; }
        };
        ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.get("missing"));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
    }
}
