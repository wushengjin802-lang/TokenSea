package com.tokensea.route.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.route.entity.RoutePolicy;
import com.tokensea.route.mapper.RoutePolicyMapper;
import com.tokensea.route.service.RouteCandidateValidator;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.tokensea.governance.GovernanceApprovalService;import com.tokensea.security.JwtService;import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/routes")
public class RoutePolicyController {
    private static final Set<String> STRATEGIES = Set.of("priority", "weighted");
    private final RoutePolicyMapper mapper;
    private final PlatformModelMapper models;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final RouteCandidateValidator candidateValidator;
    private final GovernanceApprovalService approvals;

    public RoutePolicyController(RoutePolicyMapper mapper, PlatformModelMapper models, AuditLogMapper audits, ObjectMapper json,RouteCandidateValidator candidateValidator,GovernanceApprovalService approvals) {
        this.mapper = mapper; this.models = models; this.audits = audits; this.json = json;this.candidateValidator=candidateValidator;this.approvals=approvals;
    }
    public record RouteRequest(String name, String modelAlias, String strategy, Boolean fallbackEnabled, String config) {}

    @GetMapping public ApiResponse<List<RoutePolicy>> list() { return ApiResponse.ok(mapper.selectList(null)); }
    @GetMapping("/{id}") public ApiResponse<RoutePolicy> get(@PathVariable String id) { return ApiResponse.ok(require(id)); }

    @PostMapping @Transactional
    public ApiResponse<RoutePolicy> create(@RequestBody RouteRequest request) {
        validate(request, false); RoutePolicy value = new RoutePolicy(); apply(value, request); value.setStatus("DRAFT");
        mapper.insert(value); audit("ROUTE_CREATE", value, null); return ApiResponse.ok(value);
    }
    @PutMapping("/{id}") @Transactional
    public ApiResponse<RoutePolicy> update(@PathVariable String id, @RequestBody RouteRequest request) {
        validate(request, false); RoutePolicy value=require(id), before=require(id);
        if (!"DRAFT".equals(value.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅草稿路由可修改");
        apply(value, request); mapper.updateById(value); audit("ROUTE_UPDATE", value, before); return ApiResponse.ok(value);
    }
    @PostMapping("/{id}/activate") @Transactional
    public ApiResponse<RoutePolicy> activate(@PathVariable String id,Authentication authentication) {
        approvals.requireApproved("ROUTE_POLICY",id,actor(authentication));
        RoutePolicy value=require(id), before=require(id);
        if (!"DRAFT".equals(value.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅草稿路由可生效");
        validate(new RouteRequest(value.getName(),value.getModelAlias(),value.getStrategy(),value.getFallbackEnabled(),value.getConfig()), true);
        value.setStatus("ACTIVE"); mapper.updateById(value); audit("ROUTE_ACTIVATE", value, before); return ApiResponse.ok(value);
    }
    @PostMapping("/{id}/submit") public ApiResponse<Map<String,Object>> submit(@PathVariable String id,Authentication authentication){require(id);return ApiResponse.ok(approvals.submit("ROUTE_POLICY",id,"路由策略生效审批",actor(authentication)));}
    @PostMapping("/{id}/retire") @Transactional
    public ApiResponse<RoutePolicy> retire(@PathVariable String id) {
        RoutePolicy value=require(id), before=require(id);
        if (!"ACTIVE".equals(value.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅生效路由可退役");
        value.setStatus("RETIRED"); mapper.updateById(value); audit("ROUTE_RETIRE", value, before); return ApiResponse.ok(value);
    }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "路由策略禁止物理删除");
    }

    private void validate(RouteRequest r, boolean activating) {
        if (r==null || blank(r.name()) || blank(r.modelAlias()) || !r.modelAlias().matches("^[a-z0-9][a-z0-9._-]{0,159}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路由名称或服务模型别名无效");
        }
        if (!STRATEGIES.contains(r.strategy())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路由策略仅支持 priority 或 weighted");
        Map<String,Object> config;
        try { config=json.readValue(r.config(),new TypeReference<>(){}); }
        catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"路由配置必须是 JSON 对象"); }
        Object raw=config.get("candidates");
        if (!(raw instanceof List<?> candidates) || candidates.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"路由候选不能为空");
        Set<String> pairs=new HashSet<>();
        for(Object item:candidates){
            if(!(item instanceof Map<?,?> candidate)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"路由候选格式无效");
            String provider=String.valueOf(candidate.get("providerInstanceId")), model=String.valueOf(candidate.get("actualModel"));
            if(blank(provider)||blank(model)||"null".equals(provider)||"null".equals(model)||!pairs.add(provider+"\u0000"+model))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"路由候选缺字段或重复");
            bounded(candidate.get("priority"),1,10000,"priority"); bounded(candidate.get("weight"),1,10000,"weight");
            bounded(candidate.get("timeoutSeconds"),1,300,"timeoutSeconds"); bounded(candidate.get("maxRetries"),0,3,"maxRetries");
        }
        if(activating){
            PlatformModel model=models.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PlatformModel>()
                    .eq("platform_model_name",r.modelAlias()).last("limit 1"));
            if(model==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"路由关联的服务模型不存在");
            Set<String> allowed=mappings(model);
            if(!allowed.containsAll(pairs)) throw new ResponseStatusException(HttpStatus.CONFLICT,"路由候选不属于服务模型映射");
            RoutePolicy pending=new RoutePolicy();apply(pending,r);pending.setStatus("DRAFT");candidateValidator.validate(model,pending,false);
        }
    }
    private Set<String> mappings(PlatformModel model){
        try{
            List<String> providers=json.readValue(model.getProviderInstanceIds(),new TypeReference<>(){});
            List<String> actual=json.readValue(model.getActualModels(),new TypeReference<>(){});
            Set<String> result=new HashSet<>();
            for(int i=0;i<actual.size();i++) result.add((providers.size()==1?providers.get(0):providers.get(i))+"\u0000"+actual.get(i));
            return result;
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"服务模型映射无效");}
    }
    private static void bounded(Object raw,int min,int max,String field){
        if(raw==null)return; try{int value=Integer.parseInt(String.valueOf(raw));if(value<min||value>max)throw new Exception();}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,field+" 超出允许范围");}
    }
    private void apply(RoutePolicy v,RouteRequest r){v.setName(r.name());v.setModelAlias(r.modelAlias());v.setStrategy(r.strategy());v.setFallbackEnabled(Boolean.TRUE.equals(r.fallbackEnabled()));v.setConfig(r.config());}
    private RoutePolicy require(String id){RoutePolicy v=mapper.selectById(id);if(v==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"路由策略不存在");return v;}
    private void audit(String action,RoutePolicy after,RoutePolicy before){try{AuditLog log=new AuditLog();log.setId(UUID.randomUUID().toString().replace("-",""));log.setAction(action);log.setObjectType("RoutePolicy");log.setObjectId(after.getId());log.setBeforeValue(before==null?null:json.writeValueAsString(before));log.setAfterValue(json.writeValueAsString(after));audits.insert(log);}catch(Exception e){throw new IllegalStateException("关键操作审计写入失败",e);}}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String actor(Authentication a){return a!=null&&a.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM";}
}
