package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.audit.service.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final PricingComponentService pricingComponents;

    public ProviderPriceCatalogService(JdbcTemplate jdbc, ObjectMapper json, AuditService audits,
                                       PricingComponentService pricingComponents) {
        this.jdbc = jdbc;
        this.json = json;
        this.audits = audits;
        this.pricingComponents = pricingComponents;
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
              ) or exists (
                select 1 from provider_model_alias a
                where lower(a.provider_type)=lower(c.provider_type)
                  and lower(a.provider_model_name)=lower(?)
                  and lower(a.target_provider_model_name)=lower(c.provider_model_name)
                  and a.review_status in ('APPROVED','MIGRATED_APPROVED')
                  and a.effective_from<=now() and (a.effective_to is null or a.effective_to>now())
                  and (lower(a.region)='global' or lower(a.region)=lower(?))
              ))
              and (lower(c.region)='global' or lower(c.region)=lower(?))
              and c.request_mode='STANDARD' and c.service_tier='DEFAULT'
            order by case when lower(c.provider_model_name)=lower(?) then 0 else 1 end,
                     case when lower(c.region)=lower(?) then 0 else 1 end,
                     case when upper(c.context_tier)='DEFAULT' then 0 else 1 end,
                     c.effective_from desc,c.revision desc
            limit 1
            """, providerModelName, instance.getProviderType(), providerModelName, providerModelName,
                providerModelName, instanceRegion, instanceRegion, providerModelName, instanceRegion);
        if (candidates.isEmpty()) {
            ensureMissingAlert(instance, deploymentId, providerModelName);
            refreshDeploymentPriceStatus(deploymentId);
            return new MatchResult(false, false, null, null, null);
        }

        Map<String,Object> catalog = candidates.get(0);
        String completeness = text(catalog.get("price_completeness_status"));
        if (Set.of("PARTIAL", "UNKNOWN_CACHE_PRICE").contains(completeness)) {
            ensureCachePriceAlert(instance, deploymentId, providerModelName, catalog);
            refreshDeploymentPriceStatus(deploymentId);
            return new MatchResult(false, false, null, text(catalog.get("id")), text(catalog.get("match_type")));
        }
        resolveCachePriceAlert(deploymentId);
        String catalogId = text(catalog.get("id"));
        String matchType = text(catalog.get("match_type"));
        List<Map<String,Object>> current = jdbc.queryForList("""
            select * from price_version
            where deployment_id=? and price_layer='PROVIDER_OFFICIAL' and status='ACTIVE'
            order by version desc limit 1
            """, deploymentId);
        if (!current.isEmpty() && samePrice(current.get(0), catalog, catalogId)) {
            resolveMissingAlert(deploymentId);
            refreshProviderCoverageAlert(instance);
            refreshDeploymentPriceStatus(deploymentId);
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
              id,price_layer,deployment_id,currency,billing_basis,billing_quantity,
              input_unit_price,cache_read_unit_price,cache_read_mode,cache_write_unit_price,cache_write_mode,
              output_unit_price,source_type,source_ref,source_confidence,version,effective_from,effective_to,status,
              catalog_price_id,auto_generated,match_type,source_updated_at,price_components,component_schema_version,
              price_completeness_status,evidence_hash,region,request_mode,service_tier,context_tier,
              price_nature,pricing_conditions,source_priority,source_evidence_path,source_published_at,provider_instance_id)
            values(
              ?,'PROVIDER_OFFICIAL',?,?,?, ?,
              ?,?,?,?, ?,
              ?,?,?,?,?,?,?,'ACTIVE',
              ?,true,?,?,cast(? as jsonb),2,
              ?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?
            )
            """, priceId, deploymentId, catalog.get("currency"), catalog.get("billing_basis"),
                catalog.get("billing_quantity"), catalog.get("input_unit_price"), catalog.get("cache_read_unit_price"),
                catalog.get("cache_read_mode"), catalog.get("cache_write_unit_price"), catalog.get("cache_write_mode"),
                catalog.get("output_unit_price"), catalog.get("source_type"), catalog.get("source_ref"),
                catalog.get("source_confidence"), version == null ? 1 : version,
                OffsetDateTime.now(), catalog.get("effective_to"), catalogId, matchType,
                catalog.get("source_updated_at"), componentsJson(catalog), catalog.get("price_completeness_status"),
                catalog.get("evidence_hash"), catalog.get("region"), catalog.get("request_mode"),
                catalog.get("service_tier"), "DEFAULT", catalog.get("price_nature"),
                write(catalog.get("pricing_conditions")), catalog.get("source_priority"),
                catalog.get("source_evidence_path"), catalog.get("source_published_at"), instance.getId());
        resolveMissingAlert(deploymentId);
        refreshProviderCoverageAlert(instance);
        refreshDeploymentPriceStatus(deploymentId);
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
              ) or exists (
                select 1 from provider_model_alias a
                where lower(a.provider_type)=lower(p.provider_type)
                  and lower(a.provider_model_name)=lower(d.provider_model_name)
                  and lower(a.target_provider_model_name)=lower(?)
                  and a.review_status in ('APPROVED','MIGRATED_APPROVED')
                  and a.effective_from<=now() and (a.effective_to is null or a.effective_to>now())
                  and (lower(a.region)='global' or lower(a.region)=lower(p.region))
              ))
            """, catalog.get("provider_type"), catalog.get("provider_model_name"),
                String.valueOf(catalog.get("aliases")), catalog.get("provider_model_name"));
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
                && Objects.equals(text(current.get("billing_basis")), text(catalog.get("billing_basis")))
                && Objects.equals(text(current.get("billing_quantity")), text(catalog.get("billing_quantity")))
                && decimal(current.get("input_unit_price")).compareTo(decimal(catalog.get("input_unit_price")))==0
                && decimalNullable(current.get("cache_read_unit_price"), catalog.get("cache_read_unit_price"))
                && decimalNullable(current.get("cache_write_unit_price"), catalog.get("cache_write_unit_price"))
                && Objects.equals(text(current.get("cache_read_mode")), text(catalog.get("cache_read_mode")))
                && Objects.equals(text(current.get("cache_write_mode")), text(catalog.get("cache_write_mode")))
                && decimal(current.get("output_unit_price")).compareTo(decimal(catalog.get("output_unit_price")))==0
                && Objects.equals(text(current.get("price_completeness_status")), text(catalog.get("price_completeness_status")))
                && Objects.equals(componentsJson(current), componentsJson(catalog));
    }

    public void refreshDeploymentPriceStatus(String deploymentId) {
        String priceStatus = jdbc.queryForObject("""
            select case
              when exists(select 1 from price_version p where p.deployment_id=? and p.price_layer='CONTRACT_PRICE'
                and p.status='ACTIVE' and p.effective_from<=clock_timestamp() and (p.effective_to is null or p.effective_to>clock_timestamp()))
                then 'MATCHED_CONTRACT'
              when exists(select 1 from price_version p where p.deployment_id=? and p.price_layer='CHANNEL_ACTUAL'
                and p.status='ACTIVE' and p.effective_from<=clock_timestamp() and (p.effective_to is null or p.effective_to>clock_timestamp()))
                then 'MATCHED_CHANNEL'
              when exists(select 1 from price_version p where p.deployment_id=? and p.price_layer='PROVIDER_OFFICIAL'
                and p.status='ACTIVE' and p.effective_from<=clock_timestamp() and (p.effective_to is null or p.effective_to>clock_timestamp()))
                then 'MATCHED_OFFICIAL'
              else 'MISSING' end
            """, String.class, deploymentId, deploymentId, deploymentId);
        jdbc.update("""
            update channel_model_deployment set price_status=?,
              production_status=case
                when ?='MISSING' and production_status='APPROVED' then 'SUSPENDED'
                when production_status in ('APPROVED','SUSPENDED','REJECTED') then production_status
                when health_status='HEALTHY' and ?<>'MISSING' then 'READY_FOR_REVIEW'
                else 'CANDIDATE' end,
              routing_status=case
                when ?='MISSING' and routing_status='ELIGIBLE' then 'SUSPENDED'
                else routing_status end,
              updated_at=now() where id=?
            """, priceStatus, priceStatus, priceStatus, priceStatus, deploymentId);
    }

    @Scheduled(initialDelayString = "${tokensea.price-alert-reconcile.initial-delay-ms:10000}",
            fixedDelayString = "${tokensea.price-alert-reconcile.fixed-delay-ms:300000}")
    public void reconcileCandidatePriceAlerts() {
        List<Map<String,Object>> providers = jdbc.queryForList("""
            select p.id,p.provider_type,p.instance_name,p.region
            from provider_instance p
            where exists(
              select 1 from channel_model_deployment d
              where d.provider_instance_id=p.id and d.production_status='CANDIDATE' and d.price_status='MISSING'
            ) or exists(
              select 1 from alert_event a
              where a.alert_type='PROVIDER_PRICE_COVERAGE_GAP' and a.resource_type='PROVIDER_INSTANCE'
                and a.resource_id=p.id and a.status in ('OPEN','ACKNOWLEDGED')
            )
            """);
        for (Map<String,Object> row : providers) refreshProviderCoverageAlert(toInstance(row));
    }

    private void refreshProviderCoverageAlert(ProviderInstance instance) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select d.provider_model_name,d.health_status
            from channel_model_deployment d
            where d.provider_instance_id=? and d.production_status='CANDIDATE'
              and d.price_status='MISSING' and d.discovery_status<>'MISSING_CONFIRMED'
            order by d.provider_model_name
            """, instance.getId());
        resolveCandidateDeploymentAlerts(instance.getId());
        if (rows.isEmpty()) {
            resolveCoverageAlert(instance.getId());
            return;
        }
        long healthy = rows.stream().filter(row -> "HEALTHY".equals(text(row.get("health_status")))).count();
        long pending = rows.stream().filter(row -> "PROBE_PENDING".equals(text(row.get("health_status")))).count();
        long unavailable = rows.stream().filter(row -> "UNAVAILABLE".equals(text(row.get("health_status")))).count();
        List<String> samples = rows.stream().map(row -> text(row.get("provider_model_name"))).limit(10).toList();
        Map<String,Object> detail = new LinkedHashMap<>();
        detail.put("providerType", value(instance.getProviderType()));
        detail.put("providerInstanceId", instance.getId());
        detail.put("candidateCount", rows.size());
        detail.put("healthyCandidateCount", healthy);
        detail.put("pendingProbeCount", pending);
        detail.put("unavailableCount", unavailable);
        detail.put("sampleModels", samples);
        String title = value(instance.getInstanceName()) + "：" + rows.size() + " 个候选模型未匹配官方价格";
        Integer exists = jdbc.queryForObject("""
            select count(*) from alert_event
            where alert_type='PROVIDER_PRICE_COVERAGE_GAP' and resource_type='PROVIDER_INSTANCE'
              and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class, instance.getId());
        if (exists != null && exists > 0) {
            jdbc.update("""
                update alert_event set severity='INFO',title=?,detail=cast(? as jsonb),updated_at=now()
                where alert_type='PROVIDER_PRICE_COVERAGE_GAP' and resource_type='PROVIDER_INSTANCE'
                  and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
                """, title, write(detail), instance.getId());
            return;
        }
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'PROVIDER_PRICE_COVERAGE_GAP','INFO','PROVIDER_INSTANCE',?,?,cast(? as jsonb))
            """, id(), instance.getId(), title, write(detail));
    }

    private void ensureMissingAlert(ProviderInstance instance, String deploymentId, String modelName) {
        String productionStatus = jdbc.queryForObject(
                "select production_status from channel_model_deployment where id=?", String.class, deploymentId);
        if ("CANDIDATE".equals(productionStatus)) {
            resolveMissingAlert(deploymentId);
            refreshProviderCoverageAlert(instance);
            return;
        }
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

    private void resolveCandidateDeploymentAlerts(String providerInstanceId) {
        jdbc.update("""
            update alert_event set status='RESOLVED',resolved_by='SYSTEM',resolved_at=now(),updated_at=now()
            where alert_type='MODEL_PRICE_MISSING' and resource_type='MODEL_DEPLOYMENT'
              and resource_id in (
                select id from channel_model_deployment
                where provider_instance_id=? and production_status='CANDIDATE'
              ) and status<>'RESOLVED'
            """, providerInstanceId);
    }

    private void resolveCoverageAlert(String providerInstanceId) {
        jdbc.update("""
            update alert_event set status='RESOLVED',resolved_by='SYSTEM',resolved_at=now(),updated_at=now()
            where alert_type='PROVIDER_PRICE_COVERAGE_GAP' and resource_type='PROVIDER_INSTANCE'
              and resource_id=? and status<>'RESOLVED'
            """, providerInstanceId);
    }

    private void resolveMissingAlert(String deploymentId) {
        jdbc.update("""
            update alert_event set status='RESOLVED',resolved_by='SYSTEM',resolved_at=now(),updated_at=now()
            where alert_type='MODEL_PRICE_MISSING' and resource_type='MODEL_DEPLOYMENT'
              and resource_id=? and status<>'RESOLVED'
            """, deploymentId);
    }

    private void ensureCachePriceAlert(ProviderInstance instance, String deploymentId, String modelName,
                                       Map<String,Object> catalog) {
        Integer exists = jdbc.queryForObject("""
            select count(*) from alert_event
            where alert_type='CACHE_PRICE_MISSING' and resource_type='MODEL_DEPLOYMENT'
              and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class, deploymentId);
        if (exists != null && exists > 0) return;
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'CACHE_PRICE_MISSING','HIGH','MODEL_DEPLOYMENT',?, ?,cast(? as jsonb))
            """, id(), deploymentId, "模型缓存价格尚未完整确认",
                write(Map.of("providerType", value(instance.getProviderType()),
                        "providerInstanceId", value(instance.getId()),
                        "providerModelName", modelName,
                        "catalogPriceId", text(catalog.get("id")),
                        "priceCompletenessStatus", text(catalog.get("price_completeness_status")),
                        "cacheReadMode", text(catalog.get("cache_read_mode")),
                        "cacheWriteMode", text(catalog.get("cache_write_mode")))));
    }

    private void resolveCachePriceAlert(String deploymentId) {
        jdbc.update("""
            update alert_event set status='RESOLVED',resolved_by='SYSTEM',resolved_at=now(),updated_at=now()
            where alert_type='CACHE_PRICE_MISSING' and resource_type='MODEL_DEPLOYMENT'
              and resource_id=? and status<>'RESOLVED'
            """, deploymentId);
    }

    private Map<String,Object> one(String sql,Object...args){return jdbc.queryForMap(sql,args);}
    private String write(Object value){try{return json.writeValueAsString(value==null?List.of():value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String componentsJson(Map<String,Object> row){
        return pricingComponents.writeComponents(pricingComponents.readComponents(row.get("price_components")));
    }
    private static BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(value));}
    private static boolean decimalNullable(Object left,Object right){
        if(left==null||right==null)return left==null&&right==null;
        return decimal(left).compareTo(decimal(right))==0;
    }
    private static String value(String value){return value==null||value.isBlank()?"global":value;}
    private static String text(Object value){return value==null?"":String.valueOf(value);}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
