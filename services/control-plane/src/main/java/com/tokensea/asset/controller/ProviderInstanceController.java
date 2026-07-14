package com.tokensea.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/provider-instances")
public class ProviderInstanceController {
    private static final Set<String> STATUSES = Set.of("启用", "暂停", "停用");
    private static final long TEST_VALID_MINUTES = 30;
    private final ProviderInstanceMapper mapper;
    private final ProviderConnectionService connections;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ProviderInstanceController(ProviderInstanceMapper mapper, ProviderConnectionService connections,
                                      AuditLogMapper audits, ObjectMapper json, TransactionTemplate transactions) {
        this.mapper = mapper; this.connections = connections; this.audits = audits;
        this.json = json; this.transactions = transactions;
    }

    public record CreateRequest(String providerTemplateId, String instanceName, String providerType,
                                String apiStyle, String apiBase, String region, String environment,
                                String owner, Integer rateLimitRpm, Integer rateLimitTpm) {}
    public record UpdateRequest(String instanceName, String apiStyle, String apiBase, String region,
                                String environment, String owner, Integer rateLimitRpm, Integer rateLimitTpm) {}
    public record StatusRequest(String status) {}

    @GetMapping public ApiResponse<List<ProviderInstance>> list() { return ApiResponse.ok(mapper.selectList(null)); }
    @GetMapping("/{id}") public ApiResponse<ProviderInstance> get(@PathVariable("id") String id) { return ApiResponse.ok(require(id)); }

    @PostMapping
    public ApiResponse<ProviderInstance> create(@RequestBody CreateRequest req) {
        if (blank(req.instanceName()) || blank(req.providerType()) || blank(req.apiStyle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实例名称、来源模板和协议不能为空");
        }
        if (mapper.selectCount(new QueryWrapper<ProviderInstance>().eq("instance_name", req.instanceName())) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "供应商渠道名称已存在");
        }
        return ApiResponse.ok(transactions.execute(status -> {
            ProviderInstance value = new ProviderInstance();
            value.setProviderTemplateId(req.providerTemplateId()); value.setInstanceName(req.instanceName());
            value.setProviderType(req.providerType()); value.setApiStyle(req.apiStyle()); value.setApiBase(req.apiBase());
            value.setRegion(req.region()); value.setEnvironment(blank(req.environment()) ? "生产" : req.environment());
            value.setOwner(req.owner()); value.setRateLimitRpm(req.rateLimitRpm()); value.setRateLimitTpm(req.rateLimitTpm());
            value.setCredentialRef(null); value.setKeyStatus("未配置"); value.setHealthStatus("观察");
            value.setEnabledModels("[]"); value.setStatus("暂停");
            mapper.insert(value); audit("CREATE", value, null); return value;
        }));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProviderInstance> update(@PathVariable("id") String id, @RequestBody UpdateRequest req) {
        ProviderInstance current = require(id);
        if (blank(req.instanceName()) || blank(req.apiStyle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实例名称和协议不能为空");
        }
        if (mapper.selectCount(new QueryWrapper<ProviderInstance>().eq("instance_name", req.instanceName()).ne("id", id)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "供应商渠道名称已存在");
        }
        return ApiResponse.ok(transactions.execute(status -> {
            ProviderInstance before = require(id);
            current.setInstanceName(req.instanceName()); current.setApiStyle(req.apiStyle()); current.setApiBase(req.apiBase());
            current.setRegion(req.region()); current.setEnvironment(req.environment()); current.setOwner(req.owner());
            current.setRateLimitRpm(req.rateLimitRpm()); current.setRateLimitTpm(req.rateLimitTpm());
            clearConnectionResult(current); current.setStatus("暂停");
            mapper.updateById(current); audit("UPDATE_CONFIGURATION", current, before); return current;
        }));
    }

    @PostMapping("/{id}/test-connection")
    public ApiResponse<ProviderInstance> testConnection(@PathVariable("id") String id) {
        ProviderInstance snapshot = require(id);
        ProviderConnectionService.TestResult result = connections.test(snapshot); // no DB transaction during network I/O
        ProviderInstance saved = transactions.execute(status -> {
            ProviderInstance current = require(id);
            ProviderInstance before = require(id);
            current.setLastConnectionTestAt(OffsetDateTime.now());
            current.setLastConnectionTestStatus(result.success() ? "成功" : "失败");
            current.setLastConnectionTestError(result.error());
            current.setLastConnectionTestHost(result.targetHost());
            current.setLastConnectionTestAddresses(result.targetHost());
            current.setLastConnectionTestPort(result.targetPort());
            current.setHealthStatus(result.success() ? "健康" : "异常");
            mapper.updateById(current); audit("TEST_CONNECTION", current, before); return current;
        });
        if (!result.success()) return ApiResponse.fail(result.errorCode()+": "+result.error());
        return ApiResponse.ok(saved);
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<ProviderInstance> status(@PathVariable("id") String id, @RequestBody StatusRequest req) {
        if (req == null || !STATUSES.contains(req.status())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道状态无效");
        return ApiResponse.ok(transactions.execute(tx -> {
            ProviderInstance value = require(id); ProviderInstance before = require(id);
            if ("启用".equals(req.status()) && !recentlyVerified(value)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道必须在最近 30 分钟内通过连接测试后才能启用");
            }
            value.setStatus(req.status()); mapper.updateById(value); audit("STATE_CHANGE", value, before); return value;
        }));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public void delete(@PathVariable("id") String id) { throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "供应商渠道禁止物理删除，请停用"); }

    private boolean recentlyVerified(ProviderInstance value) {
        return "成功".equals(value.getLastConnectionTestStatus()) && value.getLastConnectionTestAt() != null
                && value.getLastConnectionTestAt().isAfter(OffsetDateTime.now().minusMinutes(TEST_VALID_MINUTES))
                && ("无需 Key".equals(value.getKeyStatus()) || "已托管".equals(value.getKeyStatus()) || "已配置".equals(value.getKeyStatus()));
    }
    private static void clearConnectionResult(ProviderInstance value) {
        value.setHealthStatus("观察"); value.setLastConnectionTestAt(null);
        value.setLastConnectionTestStatus(null); value.setLastConnectionTestError(null);
        value.setLastConnectionTestHost(null); value.setLastConnectionTestAddresses(null);
        value.setLastConnectionTestPort(null);
    }
    private ProviderInstance require(String id) {
        ProviderInstance value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商渠道不存在");
        return value;
    }
    private void audit(String action, ProviderInstance after, ProviderInstance before) {
        try {
            AuditLog log = new AuditLog(); log.setId(UUID.randomUUID().toString().replace("-", ""));
            log.setAction(action); log.setObjectType("ProviderInstance"); log.setObjectId(after.getId());
            log.setBeforeValue(before == null ? null : json.writeValueAsString(before)); log.setAfterValue(json.writeValueAsString(after));
            audits.insert(log);
        } catch (Exception e) { throw new IllegalStateException("关键操作审计写入失败", e); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
