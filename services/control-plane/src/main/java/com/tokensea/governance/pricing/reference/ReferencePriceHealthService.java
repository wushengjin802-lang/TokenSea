package com.tokensea.governance.pricing.reference;

import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReferencePriceHealthService {
    private static final String NOTICE = "公开参考价，仅用于模型选型和成本估算，不作为实际结算依据";
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    @Autowired
    public ReferencePriceHealthService(JdbcTemplate jdbc,
                                       @Value("${tokensea.reference-price.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public ReferencePriceHealthService(JdbcTemplate jdbc) {
        this(jdbc, true);
    }

    @Scheduled(fixedDelayString = "${tokensea.reference-price.health-refresh-ms:3600000}")
    public void refreshStaleStatus() {
        jdbc.update("""
            update public_model_price_reference
            set price_status=case when stale_at is not null and stale_at<=now() then 'STALE' else 'CURRENT' end,
                updated_at=now()
            where is_current=true and status='ACTIVE'
              and price_status is distinct from
                  case when stale_at is not null and stale_at<=now() then 'STALE' else 'CURRENT' end
            """);
    }

    public Map<String,Object> overview() {
        long modelCount = number("select count(*) from public_model_reference where status='ACTIVE'");
        long pricedModelCount = number("""
            select count(distinct m.id)
            from public_model_reference m
            join v_current_public_model_price_reference p
              on lower(p.canonical_name)=lower(m.canonical_name)
            where m.status='ACTIVE'
            """);
        long staleCount = number("""
            select count(*) from public_model_price_reference
            where is_current=true and status='ACTIVE'
              and stale_at is not null and stale_at<=now()
            """);
        long sourceCount = number("""
            select count(*) from provider_price_source
            where managed_by='SYSTEM' and source_purpose='REFERENCE' and status<>'DISABLED'
            """);
        Boolean bootstrap = jdbc.queryForObject("""
            select exists(
              select 1 from v_current_public_model_price_reference
              where price_source_id=?
            )
            """, Boolean.class, BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID);
        Object lastSuccessAt = jdbc.queryForObject("""
            select max(last_good_sync_at) from provider_price_source
            where managed_by='SYSTEM' and source_purpose='REFERENCE'
            """, Object.class);
        BigDecimal coverage = modelCount == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(pricedModelCount)
                    .divide(BigDecimal.valueOf(modelCount), 4, RoundingMode.HALF_UP);
        return Map.ofEntries(
                Map.entry("enabled", enabled),
                Map.entry("mode", "AUTO_REFERENCE"),
                Map.entry("modelCount", modelCount),
                Map.entry("pricedModelCount", pricedModelCount),
                Map.entry("coverageRatio", coverage),
                Map.entry("lastSuccessAt", lastSuccessAt == null ? "" : lastSuccessAt),
                Map.entry("staleCount", staleCount),
                Map.entry("sourceCount", sourceCount),
                Map.entry("usingBootstrapSnapshot", Boolean.TRUE.equals(bootstrap)),
                Map.entry("notice", NOTICE));
    }

    public List<Map<String,Object>> sources() {
        return jdbc.queryForList("""
            select s.id,s.name,s.adapter_code as "adapterCode",s.status,
              s.managed_by as "managedBy",s.source_purpose as "sourcePurpose",
              s.schedule_expression as "scheduleExpression",s.next_run_at as "nextRunAt",
              s.last_checked_at as "lastCheckedAt",s.last_good_sync_at as "lastGoodSyncAt",
              s.last_success_at as "lastSuccessAt",s.last_failure_at as "lastFailureAt",
              s.last_error as "lastError",s.bootstrap_version as "bootstrapVersion",
              s.stale_after_hours as "staleAfterHours",
              count(r.id) filter(where r.status='ACTIVE') as "modelCount",
              count(r.id) filter(
                where r.status='ACTIVE' and r.stale_at is not null and r.stale_at<=now()
              ) as "staleCount",
              (select x.status from provider_price_sync_run x where x.price_source_id=s.id
                order by x.created_at desc limit 1) as "lastRunStatus"
            from provider_price_source s
            left join public_model_price_reference r on r.price_source_id=s.id and r.is_current=true
            where s.managed_by='SYSTEM' and s.source_purpose='REFERENCE' and s.status<>'DISABLED'
            group by s.id
            order by s.source_priority desc,s.name
            """);
    }

    public PageResult<Map<String,Object>> models(Integer page,
                                                  Integer size,
                                                  String keyword,
                                                  String providerType,
                                                  String priceStatus,
                                                  String sort,
                                                  String order) {
        PageQuery query = PageQuery.of(page, size, sort, order,
                Map.of(
                        "providerType", "provider_type",
                        "modelName", "provider_model_name",
                        "updatedAt", "observed_at",
                        "inputPrice", "input_unit_price",
                        "outputPrice", "output_unit_price"),
                "updatedAt", "desc");
        String term = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim().toLowerCase() + "%";
        String provider = providerType == null || providerType.isBlank() ? null : providerType.trim();
        String status = priceStatus == null || priceStatus.isBlank() ? null : priceStatus.trim();
        String sql = """
            with filtered as (
              select v.*,
                case when stale_at is not null and stale_at<=now() then 'STALE' else 'CURRENT' end
                  as effective_price_status
              from v_current_public_model_price_reference v
              where (?::text is null or lower(provider_type)=lower(?))
                and (?::text is null or
                  case when stale_at is not null and stale_at<=now() then 'STALE' else 'CURRENT' end=?)
                and (?::text is null or lower(provider_type) like ?
                  or lower(provider_model_name) like ? or lower(display_name) like ?)
            )
            select id,provider_type as "providerType",provider_model_name as "providerModelName",
              display_name as "displayName",currency,billing_basis as "billingBasis",
              billing_quantity as "billingQuantity",region,request_mode as "requestMode",
              service_tier as "serviceTier",context_tier as "contextTier",
              input_unit_price as "inputUnitPrice",cache_read_unit_price as "cacheReadUnitPrice",
              cache_write_unit_price as "cacheWriteUnitPrice",output_unit_price as "outputUnitPrice",
              price_completeness_status as "priceCompletenessStatus",
              effective_price_status as "priceStatus",
              source_name as "sourceName",adapter_code as "adapterCode",source_ref as "sourceRef",
              observed_at as "observedAt",last_seen_at as "lastSeenAt",stale_at as "staleAt",
              bundle_version as "bundleVersion",count(*) over() as "__total"
            from filtered
            order by %s %s,provider_type,provider_model_name
            limit ? offset ?
            """.formatted(query.sortColumn(), query.direction());
        List<Map<String,Object>> rows = jdbc.queryForList(sql,
                provider, provider, status, status, term, term, term, term,
                query.size(), query.offset());
        long total = rows.isEmpty() ? 0L : ((Number) rows.getFirst().get("__total")).longValue();
        if (rows.isEmpty() && query.offset() > 0) {
            total = filteredModelCount(provider, status, term);
        }
        List<Map<String,Object>> items = rows.stream().map(row -> {
            Map<String,Object> item = new LinkedHashMap<>(row);
            item.remove("__total");
            return item;
        }).toList();
        return new PageResult<>(items, total, query.page(), query.size());
    }

    public List<Map<String,Object>> runs(String sourceId) {
        return jdbc.queryForList("""
            select id,price_source_id as "priceSourceId",trigger_type as "triggerType",status,
              scheduled_for as "scheduledFor",started_at as "startedAt",completed_at as "completedAt",
              records_fetched as "recordsFetched",records_normalized as "recordsNormalized",
              records_changed as "recordsChanged",error_code as "errorCode",
              error_message as "errorMessage",created_at as "createdAt"
            from provider_price_sync_run
            where price_source_id=?
            order by created_at desc limit 100
            """, sourceId);
    }

    public boolean isSystemReferenceSource(String sourceId) {
        Integer count = jdbc.queryForObject("""
            select count(*) from provider_price_source
            where id=? and managed_by='SYSTEM' and source_purpose='REFERENCE' and status<>'DISABLED'
            """, Integer.class, sourceId);
        return count != null && count > 0;
    }

    private long filteredModelCount(String provider, String status, String term) {
        Long total = jdbc.queryForObject("""
            select count(*)
            from v_current_public_model_price_reference
            where (?::text is null or lower(provider_type)=lower(?))
              and (?::text is null or
                case when stale_at is not null and stale_at<=now() then 'STALE' else 'CURRENT' end=?)
              and (?::text is null or lower(provider_type) like ?
                or lower(provider_model_name) like ? or lower(display_name) like ?)
            """, Long.class, provider, provider, status, status, term, term, term, term);
        return total == null ? 0L : total;
    }

    private long number(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
