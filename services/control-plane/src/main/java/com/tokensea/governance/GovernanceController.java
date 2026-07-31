package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class GovernanceController {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final AuditService audits; private final GovernanceApprovalService governance; private final PricingComponentService pricingComponents;
    public GovernanceController(JdbcTemplate jdbc,ObjectMapper json,AuditService audits,GovernanceApprovalService governance,PricingComponentService pricingComponents){this.jdbc=jdbc;this.json=json;this.audits=audits;this.governance=governance;this.pricingComponents=pricingComponents;}

    public record DataSourceRequest(String name,String sourceType,String endpoint,String providerInstanceId,String authRef,String syncMode,String scheduleExpression,Map<String,Object> config){}
    public record ReferenceRequest(String canonicalName,String displayName,String vendor,String family,List<String> capabilityClaims,Integer contextLength,String sourceType,String sourceRef,BigDecimal sourceConfidence){}
    public record ReviewRequest(String decision,String reason){}
    public record ValidationRequest(String capabilityCode,String testType,String status,Map<String,Object> requestSummary,Map<String,Object> responseSummary,String evidenceRef,Integer latencyMs){}
    public record DiffDecisionRequest(String decision,String reason){}
    public record PriceRequest(String priceLayer,String publicModelReferenceId,String deploymentId,String platformModelId,
                               String currency,String billingBasis,Long billingQuantity,
                               BigDecimal inputUnitPrice,BigDecimal cacheReadUnitPrice,String cacheReadMode,
                               BigDecimal cacheWriteUnitPrice,String cacheWriteMode,BigDecimal outputUnitPrice,
                               List<PricingComponentService.ComponentInput> priceComponents,String sourceType,String sourceRef,
                               BigDecimal sourceConfidence,Integer version,OffsetDateTime effectiveFrom,OffsetDateTime effectiveTo,
                               String contractId,String contractName,String providerInstanceId,String contractReference){}
    public record BudgetRequest(String scopeType,String scopeId,String currency,BigDecimal amountLimit,BigDecimal warningThresholdPercent,String overLimitAction,String degradeModelAlias,OffsetDateTime effectiveFrom,OffsetDateTime effectiveTo){}
    public record ApprovalDecisionRequest(String decisionReason){}
    public record ReconciliationRequest(String providerInstanceId,java.time.LocalDate periodStart,java.time.LocalDate periodEnd,String currency,BigDecimal providerAmount,Long providerTokens,BigDecimal providerExchangeRateAdjustment,BigDecimal providerTaxAmount,String sourceRef,String notes){}
    public record SensitiveAccessRequest(String objectType,String objectId,String reason,List<String> fieldsViewed){}

    @GetMapping("/data-sources") public ApiResponse<List<Map<String,Object>>> dataSources(){return ok("select * from data_source order by created_at desc");}
    @PostMapping("/data-sources") @Transactional public ApiResponse<Map<String,Object>> createDataSource(@RequestBody DataSourceRequest r){
        if(r==null||blank(r.name())||!Set.of("PROVIDER_API","PUBLIC_REFERENCE","FILE_IMPORT").contains(r.sourceType()))bad("数据源名称或类型无效");String id=id();
        String syncMode=value(r.syncMode(),"MANUAL");if("SCHEDULED".equals(syncMode)&&blank(r.scheduleExpression()))bad("定时数据源必须设置 ISO-8601 同步周期");
        jdbc.update("insert into data_source(id,name,source_type,endpoint,provider_instance_id,auth_ref,sync_mode,schedule_expression,next_run_at,config) values(?,?,?,?,?,?,?,?,?,cast(? as jsonb))",id,r.name(),r.sourceType(),r.endpoint(),r.providerInstanceId(),r.authRef(),syncMode,r.scheduleExpression(),"SCHEDULED".equals(syncMode)?nextRun(r.scheduleExpression()):null,write(r.config()==null?Map.of():r.config()));
        Map<String,Object> result=one("select * from data_source where id=?",id);audits.record("DATA_SOURCE_CREATE","DataSource",id,null,result);return ApiResponse.ok(result);
    }
    @PostMapping("/data-sources/{id}/sync") @Transactional public ApiResponse<Map<String,Object>> startSync(@PathVariable String id){require("data_source",id);String job=id();jdbc.update("insert into sync_job(id,data_source_id,job_type,status) values(?,?,'FULL_SYNC','PENDING')",job,id);Map<String,Object> result=one("select * from sync_job where id=?",job);audits.record("SYNC_JOB_CREATE","SyncJob",job,null,result);return ApiResponse.ok(result);}
    @GetMapping("/sync-jobs") public ApiResponse<List<Map<String,Object>>> syncJobs(){return ok("select * from sync_job order by created_at desc");}

    @GetMapping("/model-references") public ApiResponse<List<Map<String,Object>>> references(){return ok("select * from public_model_reference order by canonical_name");}
    @PostMapping("/model-references") @Transactional public ApiResponse<Map<String,Object>> createReference(@RequestBody ReferenceRequest r){
        if(r==null||blank(r.canonicalName())||blank(r.displayName())||blank(r.sourceType()))bad("公共模型名称和来源不能为空");confidence(r.sourceConfidence());String id=id();
        jdbc.update("insert into public_model_reference(id,canonical_name,display_name,vendor,family,capability_claims,context_length,source_type,source_ref,source_confidence) values(?,?,?,?,?,cast(? as jsonb),?,?,?,?)",id,r.canonicalName(),r.displayName(),r.vendor(),r.family(),write(r.capabilityClaims()==null?List.of():r.capabilityClaims()),r.contextLength(),r.sourceType(),r.sourceRef(),r.sourceConfidence());Map<String,Object> result=one("select * from public_model_reference where id=?",id);audits.record("MODEL_REFERENCE_CREATE","PublicModelReference",id,null,result);return ApiResponse.ok(result);
    }

    @GetMapping("/channel-model-deployments") public ApiResponse<List<Map<String,Object>>> deployments(@RequestParam(required=false)String providerInstanceId,@RequestParam(required=false)String status,@RequestParam(required=false)String productionStatus){
        return ApiResponse.ok(jdbc.queryForList("select d.*,p.instance_name,r.canonical_name,case when pv.id is null then 'MISSING' else 'MATCHED' end price_status,pv.match_type price_match_type,pv.id price_version_id from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id left join public_model_reference r on r.id=d.public_model_reference_id left join lateral (select x.id,x.match_type from price_version x where x.deployment_id=d.id and x.price_layer in ('PROVIDER_OFFICIAL','CHANNEL_ACTUAL') and x.status='ACTIVE' and x.effective_from<=now() and (x.effective_to is null or x.effective_to>now()) order by case when x.price_layer='PROVIDER_OFFICIAL' then 0 else 1 end,x.version desc limit 1) pv on true where (?::text is null or d.provider_instance_id=?) and (?::text is null or d.review_status=?) and (?::text is null or d.production_status=?) order by d.updated_at desc",providerInstanceId,providerInstanceId,status,status,productionStatus,productionStatus));
    }
    @PatchMapping("/channel-model-deployments/{id}/review") @Transactional public ApiResponse<Map<String,Object>> reviewDeployment(@PathVariable String id,@RequestBody ReviewRequest r,Authentication a){
        if(r==null||!Set.of("APPROVE","REJECT").contains(r.decision()))bad("审核决定无效");Map<String,Object> before=require("channel_model_deployment",id);String state="APPROVE".equals(r.decision())?"APPROVED":"REJECTED";
        jdbc.update("update channel_model_deployment set review_status=?,routing_status=case when ?='APPROVED' and exists(select 1 from capability_validation where deployment_id=? and test_type='LIVE_PROBE' and status='PASSED') then 'ELIGIBLE' else 'INELIGIBLE' end,updated_at=now() where id=?",state,state,id,id);Map<String,Object> after=one("select * from channel_model_deployment where id=?",id);audits.record("MODEL_DEPLOYMENT_REVIEW","ChannelModelDeployment",id,before,Map.of("value",after,"reason",value(r.reason(),""),"actor",actor(a)));return ApiResponse.ok(after);
    }
    @GetMapping("/channel-model-deployments/{id}/capability-validations") public ApiResponse<List<Map<String,Object>>> validations(@PathVariable String id){return ApiResponse.ok(jdbc.queryForList("select * from capability_validation where deployment_id=? order by validated_at desc",id));}
    @PostMapping("/channel-model-deployments/{id}/capability-validations") public ApiResponse<Map<String,Object>> validateCapability(@PathVariable String id,@RequestBody ValidationRequest r,Authentication a){
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED,"能力资格必须使用服务端主动探测接口");
    }

    @GetMapping("/discovery-diffs") public ApiResponse<List<Map<String,Object>>> diffs(@RequestParam(required=false)String status){return ApiResponse.ok(jdbc.queryForList("select d.*,m.provider_model_name,m.provider_instance_id from model_discovery_diff d join channel_model_deployment m on m.id=d.deployment_id where (?::text is null or d.decision=?) order by d.created_at desc",status,status));}
    @PatchMapping("/discovery-diffs/{id}/decision") @Transactional public ApiResponse<Map<String,Object>> decideDiff(@PathVariable String id,@RequestBody DiffDecisionRequest r,Authentication a){
        if(r==null||!Set.of("ACCEPTED","IGNORED","PINNED").contains(r.decision()))bad("差异决定无效");Map<String,Object> before=require("model_discovery_diff",id);
        if("ACCEPTED".equals(r.decision()))applyDiff(before);jdbc.update("update model_discovery_diff set decision=?,decision_reason=?,decided_by=?,decided_at=now() where id=?",r.decision(),r.reason(),actor(a),id);Map<String,Object> after=one("select * from model_discovery_diff where id=?",id);audits.record("DISCOVERY_DIFF_DECISION","ModelDiscoveryDiff",id,before,after);return ApiResponse.ok(after);
    }
    @PostMapping("/discovery-diffs/{id}/rollback") @Transactional public ApiResponse<Map<String,Object>> rollbackDiff(@PathVariable String id,Authentication a){Map<String,Object> diff=require("model_discovery_diff",id);if(!"ACCEPTED".equals(diff.get("decision")))conflict("仅已接受差异可回滚");applyValue(String.valueOf(diff.get("deployment_id")),String.valueOf(diff.get("field_name")),diff.get("old_value"));jdbc.update("update model_discovery_diff set decision='ROLLED_BACK',decided_by=?,decided_at=now() where id=?",actor(a),id);Map<String,Object> after=one("select * from model_discovery_diff where id=?",id);audits.record("DISCOVERY_DIFF_ROLLBACK","ModelDiscoveryDiff",id,diff,after);return ApiResponse.ok(after);}

    @GetMapping("/price-versions")
    public ApiResponse<List<Map<String,Object>>> prices(@RequestParam(required=false) String layer,
                                                        @RequestParam(required=false) String status) {
        return ApiResponse.ok(jdbc.queryForList("""
            select p.id,
              p.price_layer "priceLayer",
              p.public_model_reference_id "publicModelReferenceId",
              p.deployment_id "deploymentId",
              p.platform_model_id "platformModelId",
              p.currency,
              p.billing_basis "billingBasis",
              p.billing_quantity "billingQuantity",
              p.input_unit_price "inputUnitPrice",
              p.input_unit_price "inputUncachedUnitPrice",
              p.cache_read_unit_price "cacheReadUnitPrice",
              p.cache_read_mode "cacheReadMode",
              p.cache_write_unit_price "cacheWriteUnitPrice",
              p.cache_write_mode "cacheWriteMode",
              p.output_unit_price "outputUnitPrice",
              p.source_type "sourceType",
              p.source_ref "sourceRef",
              p.source_confidence "sourceConfidence",
              p.version,
              p.effective_from "effectiveFrom",
              p.effective_to "effectiveTo",
              p.status,
              p.created_at "createdAt",
              p.updated_at "updatedAt",
              p.activated_by "activatedBy",
              p.activated_at "activatedAt",
              p.catalog_price_id "catalogPriceId",
              p.auto_generated "autoGenerated",
              p.match_type "matchType",
              p.source_updated_at "sourceUpdatedAt",
              p.price_components "priceComponents",
              p.component_schema_version "componentSchemaVersion",
              p.price_completeness_status "priceCompletenessStatus",
              (select count(*) from jsonb_array_elements(p.price_components) component
               where component->>'componentType'='CACHE_WRITE_TOKEN') "cacheWriteVariantCount",
              p.evidence_hash "evidenceHash",
              p.region,
              p.request_mode "requestMode",
              p.service_tier "serviceTier",
              p.context_tier "contextTier",
              d.provider_model_name "providerModelName",
              d.display_name "modelDisplayName",
              i.id "providerInstanceId",
              i.provider_type "providerType",
              i.instance_name "instanceName",
              c.display_name "catalogDisplayName"
            from price_version p
            left join channel_model_deployment d on d.id=p.deployment_id
            left join provider_instance i on i.id=d.provider_instance_id
            left join provider_model_price_catalog c on c.id=p.catalog_price_id
            where (?::text is null or p.price_layer=?)
              and (?::text is null or p.status=?)
            order by p.created_at desc
            """, layer, layer, status, status));
    }
    @GetMapping("/price-versions/{id}/components") public ApiResponse<List<Map<String,Object>>> priceComponents(@PathVariable String id){Map<String,Object> row=require("price_version",id);return ApiResponse.ok(pricingComponents.readComponents(row.get("price_components")));}
    @PostMapping("/price-versions") @Transactional public ApiResponse<Map<String,Object>> createPrice(@RequestBody PriceRequest r){
        validatePrice(r);
        List<Map<String,Object>> components=pricingComponents.normalize(r.inputUnitPrice(),r.cacheReadUnitPrice(),r.cacheWriteUnitPrice(),r.outputUnitPrice(),r.cacheReadMode(),r.cacheWriteMode(),r.billingBasis(),r.billingQuantity(),r.priceComponents(),r.sourceRef());
        PricingComponentService.Summary summary=pricingComponents.summarize(components,r.inputUnitPrice(),r.outputUnitPrice());
        if("PARTIAL".equals(summary.priceCompletenessStatus()))bad("价格组件不完整，不能创建生效候选价格");
        String id=id();
        jdbc.update("""
            insert into price_version(id,price_layer,public_model_reference_id,deployment_id,platform_model_id,currency,
              billing_basis,billing_quantity,input_unit_price,cache_read_unit_price,cache_read_mode,
              cache_write_unit_price,cache_write_mode,output_unit_price,price_components,component_schema_version,
              price_completeness_status,source_type,source_ref,source_confidence,version,effective_from,effective_to,
              contract_id,contract_name,provider_instance_id,contract_reference)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),2,?,?,?,?,?,?,?,?,?,?,?)
            """,id,r.priceLayer(),r.publicModelReferenceId(),r.deploymentId(),r.platformModelId(),r.currency(),
                r.billingBasis(),r.billingQuantity(),summary.inputUncachedUnitPrice(),summary.cacheReadUnitPrice(),
                summary.cacheReadMode(),summary.cacheWriteUnitPrice(),summary.cacheWriteMode(),summary.outputUnitPrice(),
                pricingComponents.writeComponents(components),summary.priceCompletenessStatus(),r.sourceType(),r.sourceRef(),
                r.sourceConfidence(),r.version()==null?1:r.version(),r.effectiveFrom(),r.effectiveTo(),
                r.contractId(),r.contractName(),r.providerInstanceId(),r.contractReference());
        Map<String,Object> result=one("select * from price_version where id=?",id);audits.record("PRICE_VERSION_CREATE","PriceVersion",id,null,result);return ApiResponse.ok(result);
    }
    @PostMapping("/price-versions/{id}/submit") @Transactional public ApiResponse<Map<String,Object>> submitPrice(@PathVariable String id,Authentication a){Map<String,Object> price=require("price_version",id);if(!"DRAFT".equals(price.get("status")))conflict("仅草稿价格可提交");String version=createVersion("PRICE_VERSION",id,price,actor(a)),approval=id();jdbc.update("insert into approval_request(id,resource_type,resource_id,version_id,risk_level,reason,requested_by) values(?,'PRICE_VERSION',?,?, 'HIGH','价格生效审批',?)",approval,id,version,actor(a));jdbc.update("update price_version set status='PENDING_APPROVAL',updated_at=now() where id=?",id);return ApiResponse.ok(one("select * from approval_request where id=?",approval));}
    @PostMapping("/price-versions/{id}/activate") @Transactional public ApiResponse<Map<String,Object>> activatePrice(@PathVariable String id){
        Map<String,Object> before=require("price_version",id);
        Integer approved=jdbc.queryForObject("select count(*) from approval_request where resource_type='PRICE_VERSION' and resource_id=? and status='APPROVED'",Integer.class,id);
        if(approved==null||approved==0)conflict("价格尚未审批通过");
        String layer=String.valueOf(before.get("price_layer"));
        Object deploymentId=before.get("deployment_id");
        if(deploymentId!=null&&Set.of("CONTRACT_PRICE","CHANNEL_ACTUAL","PROVIDER_OFFICIAL").contains(layer)){
            jdbc.update("""
                update price_version set status='RETIRED',effective_to=coalesce(effective_to,now()),updated_at=now()
                where deployment_id=? and price_layer=? and status='ACTIVE' and id<>?
                """,deploymentId,layer,id);
        }
        jdbc.update("update price_version set status='ACTIVE',updated_at=now() where id=?",id);
        jdbc.update("update approval_request set status='EXECUTED' where resource_type='PRICE_VERSION' and resource_id=? and status='APPROVED'",id);
        if(deploymentId!=null){
            String priceStatus=switch(layer){case"CONTRACT_PRICE"->"MATCHED_CONTRACT";case"CHANNEL_ACTUAL"->"MATCHED_CHANNEL";case"PROVIDER_OFFICIAL"->"MATCHED_OFFICIAL";default->null;};
            if(priceStatus!=null)jdbc.update("""
                update channel_model_deployment set price_status=?,
                  production_status=case when production_status in ('APPROVED','SUSPENDED','REJECTED') then production_status
                    when health_status='HEALTHY' then 'READY_FOR_REVIEW' else 'CANDIDATE' end,
                  updated_at=now() where id=?
                """,priceStatus,deploymentId);
        }
        Map<String,Object> after=one("select * from price_version where id=?",id);
        audits.record("PRICE_VERSION_ACTIVATE","PriceVersion",id,before,after);
        return ApiResponse.ok(after);
    }

    @GetMapping("/budget-rules") public ApiResponse<List<Map<String,Object>>> budgets(){return ok("select * from budget_rule order by created_at desc");}
    @PostMapping("/budget-rules") @Transactional public ApiResponse<Map<String,Object>> createBudget(@RequestBody BudgetRequest r){if(r==null||!Set.of("TENANT","PROJECT","APP","API_KEY").contains(r.scopeType())||!Set.of("BLOCK","ALERT_ONLY","DEGRADE").contains(r.overLimitAction())||r.amountLimit()==null||r.amountLimit().signum()<0||r.currency()!=null&&!"CNY".equalsIgnoreCase(r.currency()))bad("预算规则无效，预算币种统一为 CNY");String id=id();Integer version=jdbc.queryForObject("select coalesce(max(version),0)+1 from budget_rule where scope_type=? and scope_id=?",Integer.class,r.scopeType(),r.scopeId());jdbc.update("insert into budget_rule(id,scope_type,scope_id,currency,amount_limit,warning_threshold_percent,over_limit_action,degrade_model_alias,status,approval_status,version,effective_from,effective_to) values(?,?,?,?,?,?,?,?,'DRAFT','DRAFT',?,?,?)",id,r.scopeType(),r.scopeId(),"CNY",r.amountLimit(),r.warningThresholdPercent()==null?new BigDecimal("80"):r.warningThresholdPercent(),r.overLimitAction(),r.degradeModelAlias(),version,r.effectiveFrom()==null?OffsetDateTime.now():r.effectiveFrom(),r.effectiveTo());Map<String,Object> result=one("select * from budget_rule where id=?",id);audits.record("BUDGET_RULE_CREATE","BudgetRule",id,null,result);return ApiResponse.ok(result);}
    @PostMapping("/budget-rules/{id}/submit") @Transactional public ApiResponse<Map<String,Object>> submitBudget(@PathVariable String id,@RequestBody(required=false)ApprovalDecisionRequest r,Authentication a){Map<String,Object> before=require("budget_rule",id);if(!"DRAFT".equals(before.get("approval_status")))conflict("仅草稿预算规则可提交");Map<String,Object> approval=governance.submit("BUDGET_RULE",id,r==null||blank(r.decisionReason())?"预算规则生效审批":r.decisionReason(),actor(a));jdbc.update("update budget_rule set approval_status='PENDING_APPROVAL',updated_at=now() where id=?",id);return ApiResponse.ok(approval);}
    @PostMapping("/budget-rules/{id}/activate") @Transactional public ApiResponse<Map<String,Object>> activateBudget(@PathVariable String id,Authentication a){Map<String,Object> before=require("budget_rule",id);governance.requireApproved("BUDGET_RULE",id,actor(a));jdbc.update("update budget_rule set status='ACTIVE',approval_status='APPROVED',approved_by=?,approved_at=now(),updated_at=now() where id=?",actor(a),id);Map<String,Object> after=one("select * from budget_rule where id=?",id);audits.record("BUDGET_RULE_ACTIVATE","BudgetRule",id,before,after);return ApiResponse.ok(after);}
    @PostMapping("/budget-rules/{id}/retire") @Transactional public ApiResponse<Map<String,Object>> retireBudget(@PathVariable String id,Authentication a){Map<String,Object> before=require("budget_rule",id);if(!"ACTIVE".equals(before.get("status")))conflict("仅生效预算规则可退役");jdbc.update("update budget_rule set status='RETIRED',retired_by=?,retired_at=now(),updated_at=now() where id=?",actor(a),id);Map<String,Object> after=one("select * from budget_rule where id=?",id);audits.record("BUDGET_RULE_RETIRE","BudgetRule",id,before,after);return ApiResponse.ok(after);}
    @GetMapping("/budget-rules/{id}/versions") public ApiResponse<List<Map<String,Object>>> budgetVersions(@PathVariable String id){return ApiResponse.ok(jdbc.queryForList("select gv.id,gv.version,case gv.source_action when 'SUBMIT' then '提交审批' else gv.source_action end source_action,coalesce(nullif(u.display_name,''),u.username,'系统任务') created_by,gv.created_at from governance_version gv left join user_account u on u.id=gv.created_by where gv.resource_type='BUDGET_RULE' and gv.resource_id=? order by gv.version desc",id));}
    @PostMapping("/budget-rules/versions/{versionId}/rollback") public ApiResponse<Map<String,Object>> rollbackBudget(@PathVariable String versionId,Authentication a){return ApiResponse.ok(governance.rollback(versionId,actor(a)));}

    @GetMapping("/governance/approvals") public ApiResponse<List<Map<String,Object>>> approvals(@RequestParam(required=false)String status){return ApiResponse.ok(jdbc.queryForList("select * from approval_request where (?::text is null or status=?) order by requested_at desc",status,status));}
    @PostMapping("/governance/approvals/{id}/approve") @Transactional public ApiResponse<Map<String,Object>> approve(@PathVariable String id,@RequestBody(required=false)ApprovalDecisionRequest r,Authentication a){return decision(id,"APPROVED",r==null?null:r.decisionReason(),a);}
    @PostMapping("/governance/approvals/{id}/reject") @Transactional public ApiResponse<Map<String,Object>> reject(@PathVariable String id,@RequestBody(required=false)ApprovalDecisionRequest r,Authentication a){return decision(id,"REJECTED",r==null?null:r.decisionReason(),a);}
    @GetMapping("/governance/versions") public ApiResponse<List<Map<String,Object>>> versions(@RequestParam(required=false)String resourceType,@RequestParam(required=false)String resourceId){return ApiResponse.ok(jdbc.queryForList("select * from governance_version where (?::text is null or resource_type=?) and (?::text is null or resource_id=?) order by created_at desc",resourceType,resourceType,resourceId,resourceId));}
    @RequestMapping(value="/governance/versions/{id}/rollback",method={RequestMethod.POST,RequestMethod.PATCH}) public ApiResponse<Map<String,Object>> rollback(@PathVariable String id,Authentication a){return ApiResponse.ok(governance.rollback(id,actor(a)));}

    @GetMapping("/calls/{requestId}") public ApiResponse<Map<String,Object>> call(@PathVariable String requestId){List<Map<String,Object>> usage=jdbc.queryForList("select * from usage_record where request_id=?",requestId);if(usage.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"调用记录不存在");return ApiResponse.ok(Map.of("usage",usage.get(0),"attempts",jdbc.queryForList("select * from request_attempt where request_id=? order by attempt_no",requestId),"costSnapshot",jdbc.queryForList("select * from usage_cost_snapshot where request_id=?",requestId)));}
    @GetMapping("/cost-statements") public ApiResponse<List<Map<String,Object>>> costStatements(@RequestParam OffsetDateTime from,@RequestParam OffsetDateTime to,@RequestParam(required=false)String tenantId){return ApiResponse.ok(jdbc.queryForList("select tenant_id,project_id,app_id,api_key_id,model_alias,provider_id,'CNY' currency,count(*) request_count,sum(total_tokens) total_tokens,coalesce(sum(coalesce(tokensea_fx_amount(cost_amount,currency,created_at,'CNY'),0)),0) actual_cost,count(*) filter(where currency<>'CNY' and tokensea_fx_rate(created_at,currency,'CNY') is null) fx_missing_count from usage_record where created_at>=? and created_at<? and status='SUCCESS' and (?::text is null or tenant_id=?) group by tenant_id,project_id,app_id,api_key_id,model_alias,provider_id order by actual_cost desc",from,to,tenantId,tenantId));}
    @GetMapping("/provider-reconciliations") public ApiResponse<List<Map<String,Object>>> reconciliations(){return ok("select * from provider_reconciliation order by period_start desc");}
    @PostMapping("/provider-reconciliations") @Transactional public ApiResponse<Map<String,Object>> reconcile(@RequestBody ReconciliationRequest r){if(r==null||r.periodStart()==null||r.periodEnd()==null||r.providerAmount()==null||r.providerTokens()==null||r.providerTokens()<0||r.providerExchangeRateAdjustment()==null||r.providerTaxAmount()==null||blank(r.sourceRef()))bad("对账账单必须包含金额、Token、汇率调整、税费和来源");OffsetDateTime from=r.periodStart().atStartOfDay().atOffset(java.time.ZoneOffset.UTC),to=r.periodEnd().plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);Map<String,Object> actual=one("select coalesce(sum(cost_amount),0) internal_cost,coalesce(sum(total_tokens),0) internal_tokens from usage_record where provider_id=? and created_at>=? and created_at<? and status='SUCCESS' and currency=?",r.providerInstanceId(),from,to,r.currency());BigDecimal internal=new BigDecimal(String.valueOf(actual.get("internal_cost"))),exchange=r.providerExchangeRateAdjustment(),tax=r.providerTaxAmount(),diff=r.providerAmount().subtract(internal);long internalTokens=((Number)actual.get("internal_tokens")).longValue(),tokenDiff=r.providerTokens()-internalTokens;BigDecimal priceDiff=diff.subtract(exchange).subtract(tax);Map<String,Object> classification=Map.of("token",Map.of("provider",r.providerTokens(),"internal",internalTokens,"difference",tokenDiff),"price",priceDiff,"exchangeRate",exchange,"tax",tax);String id=id();jdbc.update("insert into provider_reconciliation(id,provider_instance_id,period_start,period_end,currency,internal_cost,provider_amount,difference_amount,status,source_ref,notes,token_difference,price_difference,exchange_rate_difference,tax_difference,difference_classification) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))",id,r.providerInstanceId(),r.periodStart(),r.periodEnd(),r.currency(),internal,r.providerAmount(),diff,diff.signum()==0&&tokenDiff==0?"MATCHED":"OPEN",r.sourceRef(),r.notes(),tokenDiff,priceDiff,exchange,tax,write(classification));Map<String,Object> result=one("select * from provider_reconciliation where id=?",id);audits.record("PROVIDER_RECONCILIATION_CREATE","ProviderReconciliation",id,null,result);return ApiResponse.ok(result);}

    @GetMapping("/alerts") public ApiResponse<List<Map<String,Object>>> alerts(@RequestParam(required=false)String status,@RequestParam(required=false)String scope){
        String condition = switch (scope == null ? "" : scope.toLowerCase(Locale.ROOT)) {
            case "", "all" -> "true";
            case "current" -> "status<>'RESOLVED'";
            case "history" -> "status='RESOLVED'";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"告警范围仅支持 current 或 history");
        };
        return ApiResponse.ok(jdbc.queryForList("select * from alert_event where severity<>'INFO' and (?::text is null or status=?) and " + condition + " order by created_at desc",status,status));
    }
    @PatchMapping("/alerts/{id}/{action}") @Transactional public ApiResponse<Map<String,Object>> alertAction(@PathVariable String id,@PathVariable String action,Authentication a){require("alert_event",id);String actor=actor(a);if("acknowledge".equals(action))jdbc.update("update alert_event set status='ACKNOWLEDGED',acknowledged_by=?,acknowledged_at=now(),updated_at=now() where id=?",actor,id);else if("resolve".equals(action))jdbc.update("update alert_event set status='RESOLVED',resolved_by=?,resolved_at=now(),updated_at=now() where id=?",actor,id);else bad("告警动作无效");return ApiResponse.ok(one("select * from alert_event where id=?",id));}
    @PostMapping("/audit/sensitive-access") @Transactional public ApiResponse<Map<String,Object>> sensitive(@RequestBody SensitiveAccessRequest r,Authentication a){if(r==null||blank(r.objectType())||blank(r.objectId())||blank(r.reason()))bad("敏感查看必须填写对象和理由");String id=id();jdbc.update("insert into sensitive_access_log(id,actor_id,object_type,object_id,reason,fields_viewed) values(?,?,?,?,?,cast(? as jsonb))",id,actor(a),r.objectType(),r.objectId(),r.reason(),write(r.fieldsViewed()==null?List.of():r.fieldsViewed()));Map<String,Object> result=one("select * from sensitive_access_log where id=?",id);audits.record("SENSITIVE_DATA_VIEW",r.objectType(),r.objectId(),null,Map.of("reason",r.reason(),"fields",r.fieldsViewed()==null?List.of():r.fieldsViewed()));return ApiResponse.ok(result);}

    private ApiResponse<Map<String,Object>> decision(String id,String state,String reason,Authentication a){Map<String,Object> before=require("approval_request",id);if(!"PENDING".equals(before.get("status")))conflict("审批已处理");jdbc.update("update approval_request set status=?,decision_reason=?,decided_by=?,decided_at=now() where id=?",state,reason,actor(a),id);if("BUDGET_RULE".equals(before.get("resource_type")))jdbc.update("update budget_rule set approval_status=?,updated_at=now() where id=?","APPROVED".equals(state)?"APPROVED":"REJECTED",before.get("resource_id"));Map<String,Object> after=one("select * from approval_request where id=?",id);audits.record("GOVERNANCE_APPROVAL_DECISION","ApprovalRequest",id,before,after);return ApiResponse.ok(after);}
    private void applyDiff(Map<String,Object> diff){applyValue(String.valueOf(diff.get("deployment_id")),String.valueOf(diff.get("field_name")),diff.get("new_value"));}
    private void applyValue(String deployment,String field,Object raw){Map<String,Object> row=one("select raw_model from channel_model_deployment where id=?",deployment);Map<String,Object> model=read(row.get("raw_model"));Object parsed=readValue(raw);if(parsed==null)model.remove(field);else model.put(field,parsed);jdbc.update("update channel_model_deployment set raw_model=cast(? as jsonb),version=version+1,updated_at=now() where id=?",write(model),deployment);}
    private String createVersion(String type,String resource,Object snapshot,String actor){Integer version=jdbc.queryForObject("select coalesce(max(version),0)+1 from governance_version where resource_type=? and resource_id=?",Integer.class,type,resource);String id=id();jdbc.update("insert into governance_version(id,resource_type,resource_id,version,snapshot,source_action,created_by) values(?,?,?,?,cast(? as jsonb),'SUBMIT',?)",id,type,resource,version,write(snapshot),actor);return id;}
    private void validatePrice(PriceRequest r){
        if(r==null||!Set.of("PUBLIC_REFERENCE","CHANNEL_ACTUAL","CONTRACT_PRICE","INTERNAL_ACCOUNTING").contains(r.priceLayer())
                ||blank(r.sourceType())||blank(r.sourceRef())
                ||!Set.of("TOKEN","REQUEST","IMAGE","SECOND","MINUTE","CHARACTER","AUDIO_MINUTE").contains(r.billingBasis())
                ||r.billingQuantity()==null||r.billingQuantity()<=0||r.inputUnitPrice()==null||r.outputUnitPrice()==null
                ||r.inputUnitPrice().signum()<0||r.outputUnitPrice().signum()<0||r.effectiveFrom()==null)bad("价格版本无效");
        boolean owner=switch(r.priceLayer()){
            case"PUBLIC_REFERENCE"->!blank(r.publicModelReferenceId())&&blank(r.deploymentId())&&blank(r.platformModelId())
                    &&Set.of("OFFICIAL_REFERENCE","MANUAL_VERIFIED").contains(r.sourceType());
            case"CHANNEL_ACTUAL"->blank(r.publicModelReferenceId())&&!blank(r.deploymentId())&&blank(r.platformModelId())
                    &&Set.of("PROVIDER_BILL","PROVIDER_API","MANUAL_VERIFIED").contains(r.sourceType());
            case"CONTRACT_PRICE"->blank(r.publicModelReferenceId())&&!blank(r.deploymentId())&&blank(r.platformModelId())
                    &&Set.of("CONTRACT","MANUAL_VERIFIED").contains(r.sourceType())
                    &&(!blank(r.contractId())||!blank(r.contractReference()));
            case"INTERNAL_ACCOUNTING"->blank(r.publicModelReferenceId())&&blank(r.deploymentId())&&!blank(r.platformModelId())
                    &&Set.of("INTERNAL_POLICY","MANUAL_VERIFIED").contains(r.sourceType());
            default->false;
        };
        if(!owner)bad("价格层级、归属对象与来源类型不匹配");
        if("CONTRACT_PRICE".equals(r.priceLayer())&&!blank(r.providerInstanceId())){
            Integer deploymentMatch=jdbc.queryForObject("""
                select count(*) from channel_model_deployment where id=? and provider_instance_id=?
                """,Integer.class,r.deploymentId(),r.providerInstanceId());
            if(deploymentMatch==null||deploymentMatch==0)bad("合同价绑定的供应商渠道与模型部署不一致");
        }
        confidence(r.sourceConfidence());
    }
    private OffsetDateTime nextRun(String expression){try{return OffsetDateTime.now().plus(java.time.Duration.parse(expression));}catch(Exception e){bad("同步周期必须是 ISO-8601 Duration");return null;}}
    private void confidence(BigDecimal value){if(value!=null&&(value.signum()<0||value.compareTo(BigDecimal.ONE)>0))bad("可信度必须在0到1之间");}
    private ApiResponse<List<Map<String,Object>>> ok(String sql){return ApiResponse.ok(jdbc.queryForList(sql));}
    private Map<String,Object> require(String table,String id){List<Map<String,Object>> rows=jdbc.queryForList("select * from "+table+" where id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在");return rows.get(0);}
    private Map<String,Object> one(String sql,Object...args){return jdbc.queryForMap(sql,args);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> read(Object value){try{return json.readValue(String.valueOf(value),new TypeReference<>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private Object readValue(Object value){if(value==null)return null;try{return json.readValue(String.valueOf(value),Object.class);}catch(Exception e){return value;}}
    private static String actor(Authentication a){if(a!=null&&a.getPrincipal() instanceof JwtService.Identity i)return i.userId();return "SYSTEM";}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}private static boolean blank(String v){return v==null||v.isBlank();}private static String value(String v,String d){return blank(v)?d:v;}
    private static void bad(String m){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}private static void conflict(String m){throw new ResponseStatusException(HttpStatus.CONFLICT,m);}
}
