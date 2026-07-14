package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/provider-instances")
public class ModelDiscoveryController {
    private final ProviderInstanceMapper instances;
    private final ProviderConnectionService connections;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final AuditService audits;
    private final ProviderPriceCatalogService prices;

    public ModelDiscoveryController(ProviderInstanceMapper instances, ProviderConnectionService connections,
                                    JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate transactions,
                                    AuditService audits, ProviderPriceCatalogService prices) {
        this.instances=instances;this.connections=connections;this.jdbc=jdbc;this.json=json;this.transactions=transactions;this.audits=audits;this.prices=prices;
    }

    public record DiscoverySummary(String snapshotId,int discovered,int deploymentsCreated,int diffsCreated,
                                   int missingCount,int pricesMatched,int pricesCreated,int pricesMissing) {}

    @PostMapping("/{id}/discover-models")
    public ApiResponse<DiscoverySummary> discover(@PathVariable("id") String id) {
        ProviderInstance instance=instances.selectById(id);
        if(instance==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"供应商渠道不存在");
        ProviderConnectionService.DiscoveryResult result=connections.discoverModels(instance);
        if(!result.success()) return ApiResponse.fail(result.errorCode()+": "+result.error());
        List<Map<String,Object>> models=parseModels(result.rawPayload());
        if(models.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"供应商 /models 未返回可识别模型");
        return ApiResponse.ok(transactions.execute(status->persist(instance,result,models)));
    }

    @GetMapping("/{id}/model-snapshots")
    public ApiResponse<List<Map<String,Object>>> snapshots(@PathVariable("id") String id){
        return ApiResponse.ok(jdbc.queryForList("select id,provider_instance_id,source_endpoint,http_status,checksum,discovered_at,created_at from provider_model_snapshot where provider_instance_id=? order by discovered_at desc",id));
    }

    @GetMapping("/{id}/deployments")
    public ApiResponse<List<Map<String,Object>>> deployments(@PathVariable("id") String id){
        return ApiResponse.ok(jdbc.queryForList("select * from channel_model_deployment where provider_instance_id=? order by provider_model_name",id));
    }

    private DiscoverySummary persist(ProviderInstance instance,ProviderConnectionService.DiscoveryResult result,List<Map<String,Object>> models){
        String snapshotId=id(),raw=result.rawPayload(),checksum=sha256(raw);
        jdbc.update("insert into provider_model_snapshot(id,provider_instance_id,source_endpoint,http_status,checksum,raw_payload) values(?,?,?,?,?,cast(? as jsonb))",
                snapshotId,instance.getId(),result.sourceEndpoint(),result.httpStatus(),checksum,raw);
        Set<String> seen=new HashSet<>();int created=0,diffs=0,pricesMatched=0,pricesCreated=0,pricesMissing=0;
        for(Map<String,Object> model:models){
            String name=modelName(model);seen.add(name);String rawModel=write(model);String deploymentId;
            List<Map<String,Object>> existing=jdbc.queryForList("select id,raw_model from channel_model_deployment where provider_instance_id=? and provider_model_name=?",instance.getId(),name);
            if(existing.isEmpty()){
                deploymentId=id();Map<String,Object> sources=new LinkedHashMap<>();model.keySet().forEach(k->sources.put(k,Map.of("source",result.sourceEndpoint(),"snapshotId",snapshotId,"confidence",1)));
                jdbc.update("insert into channel_model_deployment(id,provider_instance_id,provider_model_name,display_name,raw_model,field_sources,source_snapshot_id) values(?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)",
                        deploymentId,instance.getId(),name,String.valueOf(model.getOrDefault("display_name",name)),rawModel,write(sources),snapshotId);created++;
            }else{
                deploymentId=String.valueOf(existing.get(0).get("id"));Map<String,Object> old=readMap(existing.get(0).get("raw_model"));
                for(String field:union(old.keySet(),model.keySet())) if(!Objects.equals(old.get(field),model.get(field))){
                    jdbc.update("insert into model_discovery_diff(id,deployment_id,snapshot_id,field_name,old_value,new_value,source,confidence) values(?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?)",
                            id(),deploymentId,snapshotId,field,writeValue(old.get(field)),writeValue(model.get(field)),result.sourceEndpoint(),1);diffs++;
                }
                jdbc.update("update channel_model_deployment set last_seen_at=now(),missing_at=null,review_status=case when review_status='MISSING' then 'PENDING_REVIEW' else review_status end,source_snapshot_id=?,updated_at=now() where id=?",snapshotId,deploymentId);
            }
            ProviderPriceCatalogService.MatchResult price=prices.autoFill(instance,deploymentId,name);
            if(price.matched())pricesMatched++;else pricesMissing++;
            if(price.created())pricesCreated++;
        }
        List<String> known=jdbc.queryForList("select provider_model_name from channel_model_deployment where provider_instance_id=? and missing_at is null",String.class,instance.getId());
        int missing=0;for(String name:known)if(!seen.contains(name)){
            String deploymentId=jdbc.queryForObject("select id from channel_model_deployment where provider_instance_id=? and provider_model_name=?",String.class,instance.getId(),name);
            jdbc.update("update channel_model_deployment set missing_at=now(),review_status='MISSING',routing_status='SUSPENDED',updated_at=now() where id=?",deploymentId);
            jdbc.update("insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail) values(?,?,?,?,?,?,cast(? as jsonb))",id(),"MODEL_DISAPPEARED","HIGH","MODEL_DEPLOYMENT",deploymentId,"供应商模型已从发现列表消失",write(Map.of("providerInstanceId",instance.getId(),"providerModelName",name,"snapshotId",snapshotId)));missing++;
        }
        DiscoverySummary summary=new DiscoverySummary(snapshotId,models.size(),created,diffs,missing,pricesMatched,pricesCreated,pricesMissing);
        audits.record("PROVIDER_MODEL_DISCOVERY","ProviderInstance",instance.getId(),null,summary);
        return summary;
    }

    private List<Map<String,Object>> parseModels(String raw){
        try{JsonNode root=json.readTree(raw),array=root.isArray()?root:root.path("data").isArray()?root.path("data"):root.path("models");
            if(!array.isArray())return List.of();List<Map<String,Object>> values=new ArrayList<>();
            for(JsonNode node:array)if(node.isObject()){Map<String,Object> value=json.convertValue(node,new TypeReference<>(){});if(!modelName(value).isBlank())values.add(value);}return values;
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"供应商模型响应不是有效 JSON");}
    }
    private static String modelName(Map<String,Object> model){Object value=model.get("id");if(value==null)value=model.get("name");if(value==null)value=model.get("model");return value==null?"":String.valueOf(value).trim();}
    private Map<String,Object> readMap(Object value){try{return json.readValue(String.valueOf(value),new TypeReference<>(){});}catch(Exception e){return Map.of();}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String writeValue(Object value){return value==null?"null":write(value);}
    private static Set<String> union(Set<String>a,Set<String>b){Set<String> result=new LinkedHashSet<>(a);result.addAll(b);return result;}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
