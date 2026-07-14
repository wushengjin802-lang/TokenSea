package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.audit.service.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ProviderPriceCatalogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audits;

    public ProviderPriceCatalogService(JdbcTemplate jdbc, ObjectMapper json, AuditService audits) {
        this.jdbc = jdbc;
        this.json = json;
        this.audits = audits;
    }

    public record MatchResult(boolean matched, boolean created, String priceVersionId,
                              String catalogPriceId, String matchType) {}
    public record RematchSummary(int deployments, int matched, int created, int missing) {}

    @Transactional
    public MatchResult autoFill(ProviderInstance instance, String deploymentId, String providerModelName) {
        String instanceRegion = value(instance.getRegion());
        List<Map<String,Object>> candidates = jdbc.queryForList("""
            select c.*,
              case when lower(c.provider_model_name)=lower(?) then 'EXACT' else 'ALIAS' end match_type
            from provider_model_price_catalog c
            where lower(c.provider_type)=lower(?)
              and c.status='ACTIVE'
              and c.effective_from<=now()
              and (c.effective_to is null or c.effective_to>now())
              and (lower(c.provider_model_name)=lower(?) or exists (
                select 1 from jsonb_array_elements_text(c.aliases) a where lower(a)=lower(?)
              ))
              and (lower(c.region)='global' or lower(c.region)=lower(?))
              and c.request_mode='STANDARD' and c.service_tier='DEFAULT' and c.context_tier='DEFAULT'
            order by case when lower(c.provider_model_name)=lower(?) then 0 else 1 end,
                     case when lower(c.region)=lower(?) then 0 else 1 end,
                     c.effective_from desc,c.revision desc
            limit 1
            """, providerModelName, instance.getProviderType(), providerModelName, providerModelName,
                instanceRegion, providerModelName, instanceRegion);
        if (candidates.isEmpty()) {
            ensureMissingAlert(instance, deploymentId, providerModelName);
            return new MatchResult(false, false, null, null, null);
        }

        Map<String,Object> catalog = candidates.get(0);
        String catalogId = text(catalog.get("id"));
        String matchType = text(catalog.get("match_type"));
        List<Map<String,Object>> current = jdbc.queryForList("""
            select * from price_version
            where deployment_id=? and price_layer='PROVIDER_OFFICIAL' and status='ACTIVE'
            order by version desc limit 1
            """, deploymentId);
        if (!current.isEmpty() && samePrice(current.get(0), catalog, catalogId)) {
            resolveMissingAlert(deploymentId);
            return new MatchResult(true, false, text(current.get(0).get("id")), catalogId, matchType);
        }

        jdbc.update("""
            update price_version set status='RETIRED',effective_to=coalesce(effective_to,now()),updated_at=now()
            where deployment_id=? and price_layer='PROVIDER_OFFICIAL' and status='ACTIVE'
            """, deploymentId);
        Integer version = jdbc.queryForObject(
                "select coalesce(max(version),0)+1 from price_version where deployment_id=? and price_layer='PROVIDER_OFFICIAL'",
                Integer.class, deploymentId);
        String priceId = id();
        jdbc.update("""
            insert into price_version(
              id,price_layer,deployment_id,currency,input_amount_per_1k,output_amount_per_1k,
              source_type,source_ref,source_confidence,version,effective_from,effective_to,status,
              catalog_price_id,auto_generated,match_type,source_updated_at,price_components,evidence_hash,
              region,request_mode,service_tier,context_tier)
            values(?,'PROVIDER_OFFICIAL',?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,true,?,?,cast(? as jsonb),?,?,?,?,?)
            """, priceId, deploymentId, catalog.get("currency"), catalog.get("input_amount_per_1k"),
                catalog.get("output_amount_per_1k"), catalog.get("source_type"), catalog.get("source_ref"),
                catalog.get("source_confidence"), version == null ? 1 : version,
                OffsetDateTime.now(), catalog.get("effective_to"), catalogId, matchType,
                catalog.get("source_updated_at"), componentsJson(catalog), catalog.get("evidence_hash"),
                catalog.get("region"), catalog.get("request_mode"), catalog.get("service_tier"),
                catalog.get("context_tier"));
        resolveMissingAlert(deploymentId);
        Map<String,Object> created = one("select * from price_version where id=?", priceId);
        audits.record("PROVIDER_OFFICIAL_PRICE_AUTO_MATCH", "ChannelModelDeployment", deploymentId, null,
                Map.of("priceVersion", created, "catalogPriceId", catalogId, "matchType", matchType));
        return new MatchResult(true, true, priceId, catalogId, matchType);
    }

    @Transactional
    public RematchSummary rematchCatalog(String catalogId) {
        Map<String,Object> catalog = one("select * from provider_model_price_catalog where id=?", catalogId);
        List<Map<String,Object>> deployments = jdbc.queryForList("""
            select d.id deployment_id,d.provider_model_name,p.*
            from channel_model_deployment d
            join provider_instance p on p.id=d.provider_instance_id
            where lower(p.provider_type)=lower(?)
              and (lower(d.provider_model_name)=lower(?) or exists (
                select 1 from jsonb_array_elements_text(cast(? as jsonb)) a
                where lower(a)=lower(d.provider_model_name)
              ))
            """, catalog.get("provider_type"), catalog.get("provider_model_name"), String.valueOf(catalog.get("aliases")));
        int matched=0,created=0,missing=0;
        for (Map<String,Object> row : deployments) {
            ProviderInstance instance = toInstance(row);
            MatchResult result = autoFill(instance, text(row.get("deployment_id")), text(row.get("provider_model_name")));
            if (result.matched()) matched++; else missing++;
            if (result.created()) created++;
        }
        return new RematchSummary(deployments.size(), matched, created, missing);
    }

    private ProviderInstance toInstance(Map<String,Object> row) {
        ProviderInstance instance = new ProviderInstance();
        instance.setId(text(row.get("id")));
        instance.setProviderType(text(row.get("provider_type")));
        instance.setInstanceName(text(row.get("instance_name")));
        instance.setRegion(text(row.get("region")));
        return instance;
    }

    private boolean samePrice(Map<String,Object> current, Map<String,Object> catalog, String catalogId) {
        return catalogId.equals(text(current.get("catalog_price_id")))
                && Objects.equals(text(current.get("currency")), text(catalog.get("currency")))
                && decimal(current.get("input_amount_per_1k")).compareTo(decimal(catalog.get("input_amount_per_1k")))==0
                && decimal(current.get("output_amount_per_1k")).compareTo(decimal(catalog.get("output_amount_per_1k")))==0;
    }

    private void ensureMissingAlert(ProviderInstance instance, String deploymentId, String modelName) {
        Integer exists = jdbc.queryForObject("""
            select count(*) from alert_event
            where alert_type='MODEL_PRICE_MISSING' and resource_type='MODEL_DEPLOYMENT'
              and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class, deploymentId);
        if (exists != null && exists > 0) return;
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'MODEL_PRICE_MISSING','WARNING','MODEL_DEPLOYMENT',?, ?,cast(? as jsonb))
            """, id(), deploymentId, "模型未匹配供应商官方价格",
                write(Map.of("providerType", value(instance.getProviderType()),
                        "providerInstanceId", value(instance.getId()), "providerModelName", modelName)));
    }

    private void resolveMissingAlert(String deploymentId) {
        jdbc.update("""
            update alert_event set status='RESOLVED',resolved_by='SYSTEM',resolved_at=now(),updated_at=now()
            where alert_type='MODEL_PRICE_MISSING' and resource_type='MODEL_DEPLOYMENT'
              and resource_id=? and status<>'RESOLVED'
            """, deploymentId);
    }

    private Map<String,Object> one(String sql,Object...args){return jdbc.queryForMap(sql,args);}
    private String write(Object value){try{return json.writeValueAsString(value==null?List.of():value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String componentsJson(Map<String,Object> catalog){
        try {
            Map<?,?> normalized=json.readValue(String.valueOf(catalog.getOrDefault("normalized_price","{}")),Map.class);
            Object components=normalized.get("components");
            return json.writeValueAsString(components==null?Map.of():components);
        } catch(Exception e){return "{}";}
    }
    private static BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(value));}
    private static String value(String value){return value==null||value.isBlank()?"global":value;}
    private static String text(Object value){return value==null?"":String.valueOf(value);}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
