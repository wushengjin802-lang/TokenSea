package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryController.class);
    private final ProviderInstanceMapper instances;
    private final ProviderConnectionService connections;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final AuditService audits;
    private final ProviderPriceCatalogService prices;
    private final ModelDiscoveryAutoProbeService autoProbes;
    private final ModelLifecycleService lifecycle;

    public ModelDiscoveryController(ProviderInstanceMapper instances, ProviderConnectionService connections,
                                    JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate transactions,
                                    AuditService audits, ProviderPriceCatalogService prices,
                                    ModelDiscoveryAutoProbeService autoProbes, ModelLifecycleService lifecycle) {
        this.instances=instances;this.connections=connections;this.jdbc=jdbc;this.json=json;this.transactions=transactions;this.audits=audits;this.prices=prices;this.autoProbes=autoProbes;this.lifecycle=lifecycle;
    }

    public record DiscoverySummary(String snapshotId,int discovered,int deploymentsCreated,int diffsCreated,
                                   int missingCount,int pricesMatched,int pricesCreated,int pricesMissing,int probesQueued) {}
    private record DiscoveryOutcome(DiscoverySummary summary,List<String> probeDeploymentIds) {}

    @PostMapping("/{id}/discover-models")
    public ApiResponse<DiscoverySummary> discover(@PathVariable("id") String id) {
        ProviderInstance instance=instances.selectById(id);
        if(instance==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"供应商渠道不存在");
        ProviderConnectionService.DiscoveryResult result=connections.discoverModels(instance);
        if(!result.success()) return ApiResponse.fail(result.errorCode()+": "+result.error());
        List<Map<String,Object>> models=parseModels(result.rawPayload());
        if(models.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"供应商 /models 未返回可识别模型");
        DiscoveryOutcome outcome=transactions.execute(status->persist(instance,result,models));
        for (String deploymentId : outcome.probeDeploymentIds()) {
            try {
                autoProbes.probeChat(deploymentId);
            } catch (RuntimeException exception) {
                log.warn("模型自动探测未能排队，deploymentId={}", deploymentId, exception);
            }
        }
        return ApiResponse.ok(outcome.summary());
    }

    @GetMapping("/{id}/model-snapshots")
    public ApiResponse<List<Map<String,Object>>> snapshots(@PathVariable("id") String id){
        return ApiResponse.ok(jdbc.queryForList("select id,provider_instance_id,source_endpoint,http_status,checksum,discovered_at,created_at from provider_model_snapshot where provider_instance_id=? order by discovered_at desc",id));
    }

    @GetMapping("/{id}/deployments")
    public ApiResponse<List<Map<String,Object>>> deployments(@PathVariable("id") String id){
        return ApiResponse.ok(jdbc.queryForList("select * from channel_model_deployment where provider_instance_id=? order by provider_model_name",id));
    }

    private DiscoveryOutcome persist(ProviderInstance instance,ProviderConnectionService.DiscoveryResult result,List<Map<String,Object>> models){
        String snapshotId=id(),raw=result.rawPayload(),checksum=sha256(raw);
        jdbc.update("insert into provider_model_snapshot(id,provider_instance_id,source_endpoint,http_status,checksum,raw_payload) values(?,?,?,?,?,cast(? as jsonb))",
                snapshotId,instance.getId(),result.sourceEndpoint(),result.httpStatus(),checksum,raw);
        Set<String> seen=new HashSet<>(),probeDeploymentIds=new LinkedHashSet<>();int created=0,diffs=0,pricesMatched=0,pricesCreated=0,pricesMissing=0;
        for(Map<String,Object> model:models){
            String name=modelName(model);seen.add(name);String rawModel=write(model);String deploymentId;
            List<Map<String,Object>> existing=jdbc.queryForList("select * from channel_model_deployment where provider_instance_id=? and provider_model_name=?",instance.getId(),name);
            if(existing.isEmpty()){
                deploymentId=id();Map<String,Object> sources=new LinkedHashMap<>();model.keySet().forEach(k->sources.put(k,Map.of("source",result.sourceEndpoint(),"snapshotId",snapshotId,"confidence",1)));
                jdbc.update("insert into channel_model_deployment(id,provider_instance_id,provider_model_name,display_name,raw_model,field_sources,source_snapshot_id) values(?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)",
                        deploymentId,instance.getId(),name,String.valueOf(model.getOrDefault("display_name",name)),rawModel,write(sources),snapshotId);
                lifecycle.markNewDeployment(deploymentId);created++;probeDeploymentIds.add(deploymentId);
            }else{
                Map<String,Object> current=existing.get(0);deploymentId=String.valueOf(current.get("id"));Map<String,Object> old=readMap(current.get("raw_model"));
                for(String field:union(old.keySet(),model.keySet())) if(!Objects.equals(old.get(field),model.get(field))){
                    jdbc.update("insert into model_discovery_diff(id,deployment_id,snapshot_id,field_name,old_value,new_value,source,confidence) values(?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?)",
                            id(),deploymentId,snapshotId,field,writeValue(old.get(field)),writeValue(model.get(field)),result.sourceEndpoint(),1);diffs++;
                }
                jdbc.update("update channel_model_deployment set raw_model=cast(? as jsonb),source_snapshot_id=?,updated_at=now() where id=?",
                        rawModel,snapshotId,deploymentId);
                ModelLifecycleService.SeenDecision seenDecision=lifecycle.markSeen(deploymentId,snapshotId);
                if(seenDecision.probeRequired()||(!hasSuccessfulLiveProbe(deploymentId)
                        && !"APPROVED".equals(String.valueOf(current.get("production_status")))))probeDeploymentIds.add(deploymentId);
            }
            verifyOfficialCandidates(instance,name);
            ProviderPriceCatalogService.MatchResult price=prices.autoFill(instance,deploymentId,name);
            if(price.matched())pricesMatched++;else pricesMissing++;
            if(price.created())pricesCreated++;
        }
        List<Map<String,Object>> known=jdbc.queryForList("""
            select id,provider_model_name from channel_model_deployment
            where provider_instance_id=? and discovery_status<>'MISSING_CONFIRMED'
            """,instance.getId());
        int missing=0;for(Map<String,Object> knownDeployment:known){
            String name=String.valueOf(knownDeployment.get("provider_model_name"));
            if(seen.contains(name))continue;
            String deploymentId=String.valueOf(knownDeployment.get("id"));
            ModelLifecycleService.MissingDecision decision=lifecycle.recordMissingObservation(
                    deploymentId,ModelLifecycleService.DEFAULT_MISSING_CONFIRMATIONS);
            if(decision.probeRequired())probeDeploymentIds.add(deploymentId);
            missing++;
        }
        DiscoverySummary summary=new DiscoverySummary(snapshotId,models.size(),created,diffs,missing,pricesMatched,pricesCreated,pricesMissing,probeDeploymentIds.size());
        jdbc.update("""
            insert into governance_event_outbox(id,event_type,aggregate_type,aggregate_id,payload)
            values(?,'MODEL_DISCOVERY_COMPLETED','ProviderInstance',?,cast(? as jsonb))
            """,id(),instance.getId(),write(Map.of("providerInstanceId",instance.getId(),"snapshotId",snapshotId)));
        audits.record("PROVIDER_MODEL_DISCOVERY","ProviderInstance",instance.getId(),null,summary);
        return new DiscoveryOutcome(summary,List.copyOf(probeDeploymentIds));
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
    private void verifyOfficialCandidates(ProviderInstance instance,String modelName){
        jdbc.update("""
            update model_discovery_candidate set channel_verified_count=(
              select count(*) from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
              where lower(p.provider_type)=lower(model_discovery_candidate.provider_type)
                and lower(d.provider_model_name)=lower(model_discovery_candidate.candidate_model_name)
                and (lower(model_discovery_candidate.region)='global' or lower(p.region)=lower(model_discovery_candidate.region))
            ),status='CHANNEL_VERIFIED',verified_at=coalesce(verified_at,now()),last_seen_at=now(),updated_at=now()
            where lower(provider_type)=lower(?) and lower(candidate_model_name)=lower(?)
              and (lower(region)='global' or lower(region)=lower(?))
            """,instance.getProviderType(),modelName,value(instance.getRegion(),"global"));
    }
    private boolean hasSuccessfulLiveProbe(String deploymentId){
        List<String> statuses=jdbc.queryForList("select status from capability_validation where deployment_id=? and test_type='LIVE_PROBE' order by validated_at desc limit 1",String.class,deploymentId);
        return !statuses.isEmpty()&&"PASSED".equals(statuses.getFirst());
    }
    private static Set<String> union(Set<String>a,Set<String>b){Set<String> result=new LinkedHashSet<>(a);result.addAll(b);return result;}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static String value(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
