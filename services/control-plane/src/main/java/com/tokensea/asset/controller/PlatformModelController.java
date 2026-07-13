package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.price.entity.ModelPrice;
import com.tokensea.price.mapper.ModelPriceMapper;
import com.tokensea.route.entity.RoutePolicy;
import com.tokensea.route.mapper.RoutePolicyMapper;
import com.tokensea.route.service.RouteCandidateValidator;
import com.tokensea.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import com.tokensea.governance.GovernanceApprovalService;
import com.tokensea.security.JwtService;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform-models")
public class PlatformModelController {
    private static final long TEST_VALID_MINUTES = 30;
    private final PlatformModelMapper mapper;
    private final ProviderInstanceMapper instances;
    private final AuditLogMapper audits;
    private final ModelPriceMapper prices;
    private final RoutePolicyMapper routes;
    private final ProviderConnectionService connections;
    private final String budgetCurrency;
    private final RouteCandidateValidator candidateValidator;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final GovernanceApprovalService approvals;

    public PlatformModelController(PlatformModelMapper mapper, ProviderInstanceMapper instances,
                                   AuditLogMapper audits, ModelPriceMapper prices, RoutePolicyMapper routes,
                                   ProviderConnectionService connections,
                                   RouteCandidateValidator candidateValidator,
                                   ObjectMapper json, TransactionTemplate transactions,
                                   @Value("${tokensea.budget-currency:CNY}") String budgetCurrency,GovernanceApprovalService approvals) {
        this.mapper = mapper; this.instances = instances; this.audits = audits;
        this.prices = prices; this.routes = routes;
        this.connections = connections;
        this.candidateValidator = candidateValidator;
        this.budgetCurrency = budgetCurrency.toUpperCase(java.util.Locale.ROOT);
        this.json = json; this.transactions = transactions;this.approvals=approvals;
    }

    public record ModelRequest(String platformModelName, String displayName, String modelTemplateIds,
                               String providerInstanceIds, String actualModels, String routePolicyId,
                               String routePolicy, String pricePolicyId, String pricePolicy,
                               String visibilityScope, Boolean approvalRequired) {}
    public record VisibilityRequest(String visibilityScope) {}
    public record RouteRequest(String routePolicyId, String routePolicy) {}

    @GetMapping public ApiResponse<List<PlatformModel>> list() { return ApiResponse.ok(mapper.selectList(null)); }
    @GetMapping("/{id}") public ApiResponse<PlatformModel> get(@PathVariable String id) { return ApiResponse.ok(require(id)); }

    @PostMapping
    public ApiResponse<PlatformModel> create(@RequestBody ModelRequest req) {
        validateBase(req);
        if (mapper.selectCount(new QueryWrapper<PlatformModel>().eq("platform_model_name", req.platformModelName())) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "服务模型名已存在");
        }
        return ApiResponse.ok(transactions.execute(tx -> {
            PlatformModel value = new PlatformModel(); apply(value, req);
            value.setStatus("草稿"); mapper.insert(value); audit("CREATE", value, null); return value;
        }));
    }

    @PutMapping("/{id}")
    public ApiResponse<PlatformModel> update(@PathVariable String id, @RequestBody ModelRequest req) {
        validateBase(req);
        if (mapper.selectCount(new QueryWrapper<PlatformModel>().eq("platform_model_name", req.platformModelName()).ne("id", id)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "服务模型名已存在");
        }
        return ApiResponse.ok(transactions.execute(tx -> {
            PlatformModel value = require(id), before = require(id); apply(value, req);
            value.setStatus("草稿"); mapper.updateById(value); audit("UPDATE_CONFIGURATION", value, before); return value;
        }));
    }

    @PatchMapping("/{id}/publish")
    public ApiResponse<PlatformModel> publish(@PathVariable String id,Authentication authentication) {
        return ApiResponse.ok(transactions.execute(tx -> {
            approvals.requireApproved("PLATFORM_MODEL",id,actor(authentication));
            PlatformModel value = require(id), before = require(id);
            List<String> instanceIds = list(value.getProviderInstanceIds(), "真实渠道");
            List<String> actualModels = list(value.getActualModels(), "实际模型");
            if (instanceIds.isEmpty() || actualModels.isEmpty() || (instanceIds.size() != 1 && instanceIds.size() != actualModels.size())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "真实渠道与实际模型映射不完整");
            }
            if (blank(value.getPricePolicyId()) || blank(value.getRoutePolicyId()) || blank(value.getVisibilityScope())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "价格版本、路由策略和可见范围未配置");
            }
            RoutePolicy activeRoute=validateRoute(value);
            candidateValidator.validate(value,activeRoute,true);
            for (String instanceId : instanceIds) validatePublishableChannel(instanceId);
            value.setStatus("已发布"); mapper.updateById(value); audit("PUBLISH", value, before); return value;
        }));
    }

    @PostMapping("/{id}/submit") public ApiResponse<java.util.Map<String,Object>> submit(@PathVariable String id,Authentication authentication){require(id);return ApiResponse.ok(approvals.submit("PLATFORM_MODEL",id,"平台模型发布审批",actor(authentication)));}

    @PatchMapping("/{id}/visibility")
    public ApiResponse<PlatformModel> visibility(@PathVariable String id, @RequestBody VisibilityRequest req) {
        if (req == null || blank(req.visibilityScope())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "可见范围不能为空");
        return ApiResponse.ok(transactions.execute(tx -> {
            PlatformModel value = require(id), before = require(id); value.setVisibilityScope(req.visibilityScope());
            value.setStatus("草稿"); mapper.updateById(value); audit("VISIBILITY_CHANGE", value, before); return value;
        }));
    }

    @PatchMapping("/{id}/route-policy")
    public ApiResponse<PlatformModel> routePolicy(@PathVariable String id, @RequestBody RouteRequest req) {
        if (req == null || blank(req.routePolicy())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路由策略不能为空");
        return ApiResponse.ok(transactions.execute(tx -> {
            PlatformModel value = require(id), before = require(id);
            value.setRoutePolicyId(req.routePolicyId()); value.setRoutePolicy(req.routePolicy()); value.setStatus("草稿");
            mapper.updateById(value); audit("ROUTE_CHANGE", value, before); return value;
        }));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) { throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "服务模型禁止物理删除，请下架"); }

    private void validatePublishableChannel(String id) {
        ProviderInstance value = instances.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "关联的供应商渠道不存在");
        if (!"启用".equals(value.getStatus()) || !"成功".equals(value.getLastConnectionTestStatus())
                || value.getLastConnectionTestAt() == null
                || value.getLastConnectionTestAt().isBefore(OffsetDateTime.now().minusMinutes(TEST_VALID_MINUTES))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "关联渠道未启用或连接测试已过期");
        }
        if (!"无需 Key".equals(value.getKeyStatus()) && !"已托管".equals(value.getKeyStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "关联渠道密钥未托管");
        }
        if (!connections.matchesVerifiedTarget(value)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道目标主机或 DNS 解析结果已变化，请重新测试连接");
        }
    }
    private RoutePolicy validateRoute(PlatformModel model) {
        RoutePolicy route = routes.selectById(model.getRoutePolicyId());
        if (route == null || !"ACTIVE".equals(route.getStatus()) || !model.getPlatformModelName().equals(route.getModelAlias())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "路由策略不存在、未生效或不属于当前服务模型");
        }
        return route;
    }
    private void validateBase(ModelRequest req) {
        if (req == null || blank(req.platformModelName()) || blank(req.displayName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务模型名和展示名称不能为空");
        }
        list(req.providerInstanceIds(), "真实渠道"); list(req.actualModels(), "实际模型");
    }
    private void apply(PlatformModel value, ModelRequest req) {
        value.setPlatformModelName(req.platformModelName()); value.setDisplayName(req.displayName());
        value.setModelTemplateIds(defaultList(req.modelTemplateIds())); value.setProviderInstanceIds(defaultList(req.providerInstanceIds()));
        value.setActualModels(defaultList(req.actualModels())); value.setRoutePolicyId(req.routePolicyId()); value.setRoutePolicy(req.routePolicy());
        value.setPricePolicyId(req.pricePolicyId()); value.setPricePolicy(req.pricePolicy());
        value.setVisibilityScope(req.visibilityScope()); value.setApprovalRequired(Boolean.TRUE.equals(req.approvalRequired()));
    }
    private List<String> list(String raw, String field) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> values = json.readValue(raw, new TypeReference<>() {});
            if (values.stream().anyMatch(v -> v == null || v.isBlank())) throw new IllegalArgumentException();
            return values;
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "必须是有效列表"); }
    }
    private PlatformModel require(String id) {
        PlatformModel value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "服务模型不存在");
        return value;
    }
    private void audit(String action, PlatformModel after, PlatformModel before) {
        try {
            AuditLog log = new AuditLog(); log.setId(UUID.randomUUID().toString().replace("-", ""));
            log.setAction(action); log.setObjectType("PlatformModel"); log.setObjectId(after.getId());
            log.setBeforeValue(before == null ? null : json.writeValueAsString(before)); log.setAfterValue(json.writeValueAsString(after)); audits.insert(log);
        } catch (Exception e) { throw new IllegalStateException("关键操作审计写入失败", e); }
    }
    private static String defaultList(String value) { return value == null || value.isBlank() ? "[]" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank() || "[]".equals(value) || "{}".equals(value); }
    private static String actor(Authentication a){return a!=null&&a.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM";}
}
