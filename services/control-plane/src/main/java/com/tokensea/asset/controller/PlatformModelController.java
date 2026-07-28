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
import com.tokensea.route.entity.RoutePolicy;
import com.tokensea.route.mapper.RoutePolicyMapper;
import com.tokensea.route.service.RouteCandidateValidator;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.OperationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.tokensea.governance.GovernanceApprovalService;
import com.tokensea.security.JwtService;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform-models")
public class PlatformModelController {
    @Value("${tokensea.provider.connection-test-valid-minutes:10080}")
    private long testValidMinutes = 10080;
    private final PlatformModelMapper mapper;
    private final ProviderInstanceMapper instances;
    private final AuditLogMapper audits;
    private final RoutePolicyMapper routes;
    private final ProviderConnectionService connections;
    private final RouteCandidateValidator candidateValidator;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final GovernanceApprovalService approvals;
    private final JdbcTemplate jdbc;

    public PlatformModelController(PlatformModelMapper mapper, ProviderInstanceMapper instances,
                                   AuditLogMapper audits, RoutePolicyMapper routes,
                                   ProviderConnectionService connections,
                                   RouteCandidateValidator candidateValidator,
                                   ObjectMapper json, TransactionTemplate transactions,
                                   GovernanceApprovalService approvals, JdbcTemplate jdbc) {
        this.mapper = mapper; this.instances = instances; this.audits = audits;
        this.routes = routes; this.connections = connections;
        this.candidateValidator = candidateValidator;
        this.json = json; this.transactions = transactions;this.approvals=approvals;this.jdbc=jdbc;
    }

    public record ModelRequest(String platformModelName, String displayName, String modelTemplateIds,
                               String providerInstanceIds, String actualModels, String routePolicyId,
                               String routePolicy, String pricePolicyId, String pricePolicy,
                               String visibilityScope, Boolean approvalRequired) {}
    public record VisibilityRequest(String visibilityScope) {}
    public record RouteRequest(String routePolicyId, String routePolicy) {}
    public record PublishCheckItem(String item, boolean passed, String location, String problem, String action) {}
    public record PublishCheckResult(boolean ready, List<PublishCheckItem> checks) {}
    private record ModelMapping(List<String> instanceIds, List<String> actualModels) {}

    @GetMapping public ApiResponse<List<PlatformModel>> list() {
        List<PlatformModel> values = mapper.selectList(new QueryWrapper<PlatformModel>().orderByDesc("created_at"));
        values.forEach(this::attachRouteStatus);
        return ApiResponse.ok(values);
    }
    @GetMapping("/published") public ApiResponse<List<PlatformModel>> published() {
        List<PlatformModel> values = mapper.selectList(new QueryWrapper<PlatformModel>()
                .eq("status", "已发布").orderByAsc("display_name").orderByAsc("platform_model_name"));
        values.forEach(this::attachRouteStatus);
        return ApiResponse.ok(values);
    }
    @GetMapping("/{id}") public ApiResponse<PlatformModel> get(@PathVariable String id) {
        PlatformModel value = require(id);
        attachRouteStatus(value);
        return ApiResponse.ok(value);
    }
    @GetMapping("/{id}/publish-check")
    public ApiResponse<PublishCheckResult> publishCheck(@PathVariable String id) {
        return ApiResponse.ok(checkPublishability(require(id)));
    }

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
    public ApiResponse<PlatformModel> publish(@PathVariable String id, Authentication authentication) {
        return ApiResponse.ok(transactions.execute(tx -> {
            PlatformModel value = require(id), before = require(id);
            if (Boolean.TRUE.equals(value.getApprovalRequired()) || !isPlatformAdmin(authentication)) {
                approvals.requireApproved("PLATFORM_MODEL", id, actor(authentication));
            }
            PublishCheckResult check = checkPublishability(value);
            if (!check.ready()) throwPublishCheckFailed(check);
            value.setStatus("已发布");
            mapper.updateById(value);
            audit("PUBLISH", value, before);
            return value;
        }));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<java.util.Map<String,Object>> submit(@PathVariable String id, Authentication authentication) {
        PlatformModel value = require(id);
        if (isPlatformAdmin(authentication) && !Boolean.TRUE.equals(value.getApprovalRequired())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_APPROVAL_NOT_REQUIRED",
                    "企业服务模型 / 提交审批",
                    "该企业服务模型已配置为无需审批",
                    "返回列表直接点击“发布”；发布时仍会校验路由、部署、能力验证、价格和渠道连接状态");
        }
        return ApiResponse.ok(approvals.submit("PLATFORM_MODEL", id, "平台模型发布审批", actor(authentication)));
    }

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
        if (value == null) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_CHANNEL_MISSING",
                    "企业服务模型 / 发布 / 供应商渠道",
                    "关联的供应商渠道不存在或已被移除",
                    "编辑企业服务模型，重新选择有效的供应商渠道和部署模型");
        }
        if (!"启用".equals(value.getStatus())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_CHANNEL_DISABLED",
                    "企业服务模型 / 发布 / 供应商渠道",
                    "渠道“" + value.getInstanceName() + "”当前状态为 " + value.getStatus(),
                    "进入“供应商渠道”启用该渠道并完成连接测试后重新发布");
        }
        if (!"成功".equals(value.getLastConnectionTestStatus()) || value.getLastConnectionTestAt() == null
                || value.getLastConnectionTestAt().isBefore(OffsetDateTime.now().minusMinutes(testValidMinutes))) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_CONNECTION_TEST_REQUIRED",
                    "企业服务模型 / 发布 / 供应商渠道",
                    "渠道“" + value.getInstanceName() + "”没有最近 " + testValidMinutes + " 分钟内成功的连接测试",
                    "仅在首次接入、API 地址或凭据变更、连续调用失败时重新执行连接测试；测试成功后重新发布");
        }
        if (!"无需 Key".equals(value.getKeyStatus()) && !"已托管".equals(value.getKeyStatus())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_CHANNEL_KEY_MISSING",
                    "企业服务模型 / 发布 / 供应商密钥",
                    "渠道“" + value.getInstanceName() + "”的供应商密钥尚未托管",
                    "进入“供应商渠道”执行“托管或轮换密钥”，然后重新连接测试和发布");
        }
        if (!connections.matchesVerifiedTarget(value)) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_DNS_CHANGED",
                    "企业服务模型 / 发布 / 渠道网络",
                    "渠道 API 地址的主机或端口与最近连接测试不一致",
                    "仅当 API 地址的域名或端口发生变化时重新执行连接测试；公网 DNS 地址正常轮换无需重新发布");
        }
    }
    private PublishCheckResult checkPublishability(PlatformModel model) {
        List<PublishCheckItem> checks = new ArrayList<>();
        ModelMapping mapping = check(checks, "服务模型映射", () -> validateMapping(model));
        RoutePolicy route = check(checks, "路由策略", () -> validateRoute(model));
        check(checks, "模型可见范围", () -> { validateVisibility(model); return null; });
        if (route != null && mapping != null) {
            check(checks, "路由候选、生产准入与价格", () -> { validateCandidates(model, route); return null; });
        } else {
            checks.add(new PublishCheckItem("路由候选、生产准入与价格", false,
                    "企业服务模型 / 发布 / 路由候选", "前置的服务模型映射或路由策略校验未通过，无法继续检查候选部署",
                    "先修复映射和路由策略，再执行发布检查"));
        }
        if (mapping != null) {
            for (String instanceId : mapping.instanceIds()) {
                check(checks, "供应商渠道", () -> { validatePublishableChannel(instanceId); return null; });
            }
        }
        return new PublishCheckResult(checks.stream().allMatch(PublishCheckItem::passed), checks);
    }
    private ModelMapping validateMapping(PlatformModel value) {
        List<String> instanceIds = list(value.getProviderInstanceIds(), "真实渠道");
        List<String> actualModels = list(value.getActualModels(), "实际模型");
        if (instanceIds.isEmpty() || actualModels.isEmpty() || (instanceIds.size() != 1 && instanceIds.size() != actualModels.size())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_MAPPING_INCOMPLETE",
                    "企业服务模型 / 发布",
                    "供应商渠道与实际模型的映射不完整或数量不一致",
                    "编辑企业服务模型，重新选择渠道和已审核部署模型；单渠道可对应多个模型，多渠道时渠道数必须与模型数一致");
        }
        return new ModelMapping(instanceIds, actualModels);
    }
    private void validateVisibility(PlatformModel value) {
        if (blank(value.getVisibilityScope())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_VISIBILITY_MISSING",
                    "企业服务模型 / 发布",
                    "尚未配置模型可见范围",
                    "编辑企业服务模型，选择“全部租户”、租户类型或指定租户后重新发布");
        }
    }
    private void validateCandidates(PlatformModel value, RoutePolicy route) {
        try {
            candidateValidator.validate(value, route, true);
        } catch (ResponseStatusException exception) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_CANDIDATE_INVALID",
                    "企业服务模型 / 发布",
                    exception.getReason() == null ? "路由候选校验未通过" : exception.getReason(),
                    "检查候选部署是否已审核并通过 LIVE_PROBE，同时确认已选择生效价格版本，然后重试发布");
        }
    }
    private <T> T check(List<PublishCheckItem> checks, String item, CheckedSupplier<T> validator) {
        try {
            T result = validator.get();
            checks.add(new PublishCheckItem(item, true, null, null, null));
            return result;
        } catch (OperationException exception) {
            checks.add(new PublishCheckItem(item, false, exception.location(), exception.problem(), exception.action()));
        } catch (ResponseStatusException exception) {
            checks.add(new PublishCheckItem(item, false, "企业服务模型 / 发布 / " + item,
                    exception.getReason() == null ? "校验未通过" : exception.getReason(), "修复对应配置后重新执行发布检查"));
        }
        return null;
    }
    private void throwPublishCheckFailed(PublishCheckResult result) {
        List<PublishCheckItem> failures = result.checks().stream().filter(item -> !item.passed()).toList();
        PublishCheckItem first = failures.getFirst();
        String problem = failures.stream().map(item -> item.item() + "：" + item.problem()).reduce((a, b) -> a + "；" + b).orElse(first.problem());
        throw OperationException.conflict("PLATFORM_MODEL_PUBLISH_CHECK_FAILED", first.location(), problem,
                "先处理发布检查中的异常项后重试发布；可点击“发布检查”查看逐项处理方式");
    }
    @FunctionalInterface
    private interface CheckedSupplier<T> { T get(); }
    private RoutePolicy validateRoute(PlatformModel model) {
        if (blank(model.getRoutePolicyId())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_ROUTE_MISSING",
                    "企业服务模型 / 发布",
                    "尚未绑定路由策略",
                    "先到“路由策略”创建并执行“校验并生效”，再回到企业服务模型选择该路由");
        }
        RoutePolicy route = routes.selectById(model.getRoutePolicyId());
        if (route == null) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_ROUTE_NOT_FOUND",
                    "企业服务模型 / 发布 / 路由策略",
                    "关联路由不存在",
                    "编辑企业服务模型重新选择路由；没有可用路由时先到“路由策略”新建并生效");
        }
        if (!model.getPlatformModelName().equals(route.getModelAlias())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_ROUTE_MISMATCH",
                    "企业服务模型 / 发布 / 路由策略",
                    "路由“" + route.getName() + "”属于模型 " + route.getModelAlias()
                            + "，与当前模型 " + model.getPlatformModelName() + " 不一致",
                    "编辑企业服务模型选择属于当前模型的路由，或重新创建正确的路由策略");
        }
        if (!"ACTIVE".equals(route.getStatus())) {
            throw OperationException.conflict(
                    "PLATFORM_MODEL_ROUTE_INACTIVE",
                    "企业服务模型 / 发布 / 路由策略",
                    "路由“" + route.getName() + "”当前状态为 " + route.getStatus() + "，尚未生效",
                    "进入“路由策略”，找到该路由并点击“校验并生效”，成功后返回重新发布");
        }
        return route;
    }
    private void attachRouteStatus(PlatformModel model) {
        if (blank(model.getRoutePolicyId())) {
            model.setRouteStatus(null);
            return;
        }
        RoutePolicy route = routes.selectById(model.getRoutePolicyId());
        model.setRouteStatus(route == null ? "MISSING" : route.getStatus());
    }
    private void validateBase(ModelRequest req) {
        if (req == null || blank(req.platformModelName()) || blank(req.displayName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务模型名和展示名称不能为空");
        }
        if (!req.platformModelName().matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "服务模型别名仅支持字母、数字、点、下划线和连字符，且不能包含空格");
        }
        List<String> instanceIds = list(req.providerInstanceIds(), "真实渠道");
        List<String> actualModels = list(req.actualModels(), "实际模型");
        if (instanceIds.isEmpty() || actualModels.isEmpty() || (instanceIds.size() != 1 && instanceIds.size() != actualModels.size())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "供应商渠道与实际模型的映射不完整或数量不一致");
        }
        for (int index = 0; index < actualModels.size(); index++) {
            String instanceId = instanceIds.size() == 1 ? instanceIds.getFirst() : instanceIds.get(index);
            Integer count = jdbc.queryForObject("""
                    select count(*) from channel_model_deployment
                    where provider_instance_id=? and provider_model_name=? and discovery_status<>'MISSING_CONFIRMED'
                    """, Integer.class, instanceId, actualModels.get(index));
            if (count == null || count == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "实际模型“" + actualModels.get(index) + "”不属于已选供应商渠道，请重新选择渠道或模型");
            }
        }
        if (!blank(req.pricePolicyId())) {
            Integer exists = jdbc.queryForObject("select count(*) from model_price where id=?", Integer.class, req.pricePolicyId());
            if (exists == null || exists == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "pricePolicyId 仅支持旧版模型售价；供应商生效价格请在路由策略中选择价格版本");
            }
        }
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
    private static boolean isPlatformAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof JwtService.Identity identity
                && identity.roles().contains("ADMIN");
    }
    private static String actor(Authentication a){return a!=null&&a.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM";}
}
