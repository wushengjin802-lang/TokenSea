package com.tokensea.price.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.price.entity.ModelPrice;
import com.tokensea.price.mapper.ModelPriceMapper;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.tokensea.governance.GovernanceApprovalService;import com.tokensea.security.JwtService;import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/model-prices")
public class ModelPriceController {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final ModelPriceMapper mapper;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final String budgetCurrency;
    private final GovernanceApprovalService approvals;

    public ModelPriceController(ModelPriceMapper mapper, AuditLogMapper audits, ObjectMapper json,
                                @Value("${tokensea.budget-currency:CNY}") String budgetCurrency,GovernanceApprovalService approvals) {
        this.mapper = mapper; this.audits = audits; this.json = json;
        this.budgetCurrency = budgetCurrency.toUpperCase(Locale.ROOT);this.approvals=approvals;
    }

    public record PriceRequest(String modelId, String platformModelId, String providerInstanceId,
                               String currency, BigDecimal inputCostPer1k, BigDecimal outputCostPer1k,
                               BigDecimal inputPricePer1k, BigDecimal outputPricePer1k,
                               OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {}

    @GetMapping public ApiResponse<List<ModelPrice>> list() { return ApiResponse.ok(mapper.selectList(null)); }
    @GetMapping("/{id}") public ApiResponse<ModelPrice> get(@PathVariable String id) { return ApiResponse.ok(require(id)); }

    @PostMapping
    @Transactional
    public ApiResponse<ModelPrice> create(@RequestBody PriceRequest request) {
        validate(request);
        ModelPrice value = new ModelPrice(); apply(value, request); value.setStatus("DRAFT");
        mapper.insert(value); audit("PRICE_CREATE", value, null); return ApiResponse.ok(value);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<ModelPrice> update(@PathVariable String id, @RequestBody PriceRequest request) {
        validate(request); ModelPrice value = require(id), before = require(id);
        if (!"DRAFT".equals(value.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅草稿价格可修改");
        apply(value, request); mapper.updateById(value); audit("PRICE_UPDATE", value, before); return ApiResponse.ok(value);
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public ApiResponse<ModelPrice> activate(@PathVariable String id,Authentication authentication) {
        approvals.requireApproved("MODEL_PRICE",id,actor(authentication));
        ModelPrice value = require(id), before = require(id);
        if (!"DRAFT".equals(value.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅草稿价格可生效");
        if (value.getEffectiveFrom().isAfter(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "价格尚未到生效时间");
        }
        if (!budgetCurrency.equals(value.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "尚未实现汇率换算，价格币种必须与平台预算币种一致");
        }
        value.setStatus("ACTIVE"); mapper.updateById(value); audit("PRICE_ACTIVATE", value, before); return ApiResponse.ok(value);
    }
    @PostMapping("/{id}/submit") public ApiResponse<java.util.Map<String,Object>> submit(@PathVariable String id,Authentication authentication){require(id);return ApiResponse.ok(approvals.submit("MODEL_PRICE",id,"旧价格兼容版本生效审批",actor(authentication)));}

    @PostMapping("/{id}/retire")
    @Transactional
    public ApiResponse<ModelPrice> retire(@PathVariable String id) {
        ModelPrice value = require(id), before = require(id);
        if (!"ACTIVE".equals(value.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅生效价格可终止");
        value.setStatus("RETIRED");
        if (value.getEffectiveTo() == null || value.getEffectiveTo().isAfter(OffsetDateTime.now())) value.setEffectiveTo(OffsetDateTime.now());
        mapper.updateById(value); audit("PRICE_RETIRE", value, before); return ApiResponse.ok(value);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) { throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "价格版本禁止物理删除"); }

    private void validate(PriceRequest r) {
        if (r == null || (blank(r.modelId()) == blank(r.platformModelId())) || (!blank(r.modelId()) && !blank(r.providerInstanceId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格必须且只能归属旧模型或平台模型；渠道价格必须归属平台模型");
        }
        if (r.currency() == null || !r.currency().toUpperCase(Locale.ROOT).matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位大写代码");
        }
        if (r.effectiveFrom() == null || (r.effectiveTo() != null && !r.effectiveTo().isAfter(r.effectiveFrom()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格有效期无效");
        }
        for (BigDecimal amount : List.of(amount(r.inputCostPer1k()), amount(r.outputCostPer1k()), amount(r.inputPricePer1k()), amount(r.outputPricePer1k()))) {
            if (amount.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格不能为负数");
        }
    }
    private void apply(ModelPrice v, PriceRequest r) {
        v.setModelId(blank(r.modelId()) ? null : r.modelId()); v.setPlatformModelId(blank(r.platformModelId()) ? null : r.platformModelId());
        v.setProviderInstanceId(blank(r.providerInstanceId()) ? null : r.providerInstanceId());
        v.setCurrency(r.currency().toUpperCase(Locale.ROOT)); v.setInputCostPer1k(amount(r.inputCostPer1k()));
        v.setOutputCostPer1k(amount(r.outputCostPer1k())); v.setInputPricePer1k(amount(r.inputPricePer1k()));
        v.setOutputPricePer1k(amount(r.outputPricePer1k())); v.setEffectiveFrom(r.effectiveFrom()); v.setEffectiveTo(r.effectiveTo());
    }
    private ModelPrice require(String id) {
        ModelPrice value = mapper.selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "价格版本不存在");
        return value;
    }
    private void audit(String action, ModelPrice after, ModelPrice before) {
        try {
            AuditLog log = new AuditLog(); log.setId(UUID.randomUUID().toString().replace("-", ""));
            log.setAction(action); log.setObjectType("ModelPrice"); log.setObjectId(after.getId());
            log.setBeforeValue(before == null ? null : json.writeValueAsString(before)); log.setAfterValue(json.writeValueAsString(after)); audits.insert(log);
        } catch (Exception e) { throw new IllegalStateException("关键操作审计写入失败", e); }
    }
    private static BigDecimal amount(BigDecimal value) { return value == null ? ZERO : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String actor(Authentication a){return a!=null&&a.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM";}
}
