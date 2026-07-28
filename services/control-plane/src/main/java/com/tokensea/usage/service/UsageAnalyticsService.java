package com.tokensea.usage.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UsageAnalyticsService {
    private static final String JOINS = """
        from usage_record u
        left join tenant t on t.id=u.tenant_id
        left join project p on p.id=u.project_id
        left join app a on a.id=u.app_id
        left join api_key k on k.id=u.api_key_id
        left join provider_instance pi on pi.id=u.provider_id
        left join provider legacy_provider on legacy_provider.id=u.provider_id
        left join usage_cost_snapshot cs on cs.request_id=u.request_id
        left join lateral (
          select identity_source,source_ip,session_id_hash
          from identity_record identity_row
          where identity_row.usage_record_id=u.id
          order by identity_row.created_at desc
          limit 1
        ) identity_row on true
        """;

    private static final Map<String,String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("createdAt", "u.created_at"),
            Map.entry("tenantName", "coalesce(t.name,u.tenant_id,'')"),
            Map.entry("projectName", "coalesce(p.name,u.project_id,'')"),
            Map.entry("appName", "coalesce(a.name,u.app_id,'')"),
            Map.entry("apiKeyName", "coalesce(k.name,k.key_prefix,u.api_key_id,'')"),
            Map.entry("providerName", "coalesce(pi.instance_name,legacy_provider.name,u.provider_id,'')"),
            Map.entry("modelAlias", "coalesce(u.model_alias,'')"),
            Map.entry("promptTokens", "u.prompt_tokens"),
            Map.entry("completionTokens", "u.completion_tokens"),
            Map.entry("totalTokens", "u.total_tokens"),
            Map.entry("costAmount", "u.cost_amount"),
            Map.entry("latencyMs", "u.latency_ms"),
            Map.entry("status", "u.status")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public UsageAnalyticsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String,Object> details(UsageQuery query) {
        UsageQuery normalized = normalize(query);
        FilterSql filter = filter(normalized, true);
        String sortColumn = SORT_COLUMNS.getOrDefault(normalized.sort(), "u.created_at");
        String order = "asc".equalsIgnoreCase(normalized.order()) ? "asc" : "desc";
        int page = Math.max(normalized.page(), 1);
        int size = Math.min(Math.max(normalized.size(), 1), 200);
        int offset = (page - 1) * size;

        MapSqlParameterSource params = copy(filter.params())
                .addValue("limit", size)
                .addValue("offset", offset);

        List<Map<String,Object>> items = jdbc.queryForList("""
            select u.id,u.request_id,
              u.tenant_id,coalesce(t.name,u.tenant_id,'未归属租户') tenant_name,
              u.project_id,coalesce(p.name,nullif(u.project_id,''),'未归属项目') project_name,
              u.app_id,coalesce(a.name,nullif(u.app_id,''),'未归属应用') app_name,
              u.api_key_id,coalesce(k.name,k.key_prefix,u.api_key_id,'未识别 Key') api_key_name,
              k.key_prefix api_key_prefix,
              coalesce(pi.instance_name,legacy_provider.name,u.provider_id,'未识别供应商') provider_name,
              coalesce(pi.provider_type,legacy_provider.provider_type,'') provider_type,
              u.model_alias,u.runtime_model_name,
              u.prompt_tokens,u.completion_tokens,u.total_tokens,
              coalesce(cs.input_uncached_tokens,u.prompt_tokens) input_uncached_tokens,
              coalesce(cs.cache_read_tokens,0) cache_read_tokens,
              coalesce(cs.cache_write_tokens,0) cache_write_tokens,
              coalesce(cs.input_tokens_total,u.prompt_tokens) input_tokens_total,
              coalesce(cs.output_tokens,u.completion_tokens) output_tokens,
              coalesce(cs.reasoning_tokens,0) reasoning_tokens,
              coalesce(cs.cache_hit_rate,0) cache_hit_rate,
              coalesce(cs.cache_net_savings,0) cache_net_savings,
              coalesce(cs.cost_status,case when u.status='SUCCESS' then 'INCOMPLETE_PRICE' else 'COMPLETE' end) cost_status,
              u.cost_amount,u.currency,u.status,u.error_code,u.latency_ms,
              coalesce(nullif(identity_row.identity_source,''),nullif(identity_row.session_id_hash,''),'—') user_identifier,
              identity_row.source_ip,u.created_at
            """ + JOINS + filter.where() + " order by " + sortColumn + " " + order + ",u.request_id asc limit :limit offset :offset", params);

        Long total = jdbc.queryForObject("select count(*) " + JOINS + filter.where(), filter.params(), Long.class);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0L : total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    public Map<String,Object> dashboard(UsageQuery query) {
        UsageQuery normalized = normalize(query);
        FilterSql filter = filter(normalized, false);
        Map<String,Object> summary = jdbc.queryForMap("""
            select count(*) requests,
              coalesce(sum(u.prompt_tokens),0) prompt_tokens,
              coalesce(sum(u.completion_tokens),0) completion_tokens,
              coalesce(sum(u.total_tokens),0) total_tokens,
              coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)),0) cost_amount,
              coalesce(avg(u.latency_ms),0) avg_latency_ms,
              count(distinct nullif(u.tenant_id,'')) active_tenants,
              coalesce(round(100.0*count(*) filter(where u.status='SUCCESS')/nullif(count(*),0),2),0) success_rate,
              count(*) filter(where u.status<>'SUCCESS') failed_requests,
              count(*) filter(where u.currency<>'CNY' and tokensea_fx_rate(u.created_at,u.currency,'CNY') is null) fx_missing_count,
              coalesce(sum(cs.input_uncached_tokens),0) input_uncached_tokens,
              coalesce(sum(cs.cache_read_tokens),0) cache_read_tokens,
              coalesce(sum(cs.cache_write_tokens),0) cache_write_tokens,
              coalesce(sum(cs.output_tokens),0) output_tokens,
              coalesce(sum(cs.reasoning_tokens),0) reasoning_tokens,
              coalesce(round(
                sum(cs.cache_read_tokens)::numeric /
                nullif(sum(cs.input_uncached_tokens + cs.cache_read_tokens),0),6
              ),0) cache_hit_rate,
              coalesce(sum(coalesce(tokensea_fx_amount(cs.cache_gross_savings,cs.currency,cs.created_at,'CNY'),0)),0) cache_gross_savings,
              coalesce(sum(coalesce(tokensea_fx_amount(cs.cache_write_premium,cs.currency,cs.created_at,'CNY'),0)),0) cache_write_premium,
              coalesce(sum(coalesce(tokensea_fx_amount(cs.cache_storage_cost,cs.currency,cs.created_at,'CNY'),0)),0) cache_storage_cost,
              coalesce(sum(coalesce(tokensea_fx_amount(cs.cache_net_savings,cs.currency,cs.created_at,'CNY'),0)),0) cache_net_savings,
              count(*) filter(where u.status='SUCCESS' and (cs.id is null or cs.cost_status<>'COMPLETE')) cache_cost_anomaly_count,
              'CNY' currency
            """ + JOINS + filter.where(), filter.params());

        List<Map<String,Object>> trend = jdbc.queryForList("""
            with days as (
              select generate_series(date_trunc('day',cast(:startAt as timestamptz)),
                                     date_trunc('day',cast(:endAt as timestamptz)),interval '1 day') as bucket_day
            ), aggregate_data as (
              select date_trunc('day',u.created_at) as bucket_day,count(*) requests,
                coalesce(sum(u.total_tokens),0) tokens,
                coalesce(sum(cs.cache_read_tokens),0) cache_read_tokens,
                coalesce(sum(cs.cache_write_tokens),0) cache_write_tokens,
                coalesce(sum(coalesce(tokensea_fx_amount(cs.cache_net_savings,cs.currency,cs.created_at,'CNY'),0)),0) cache_net_savings,
                coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)),0) cost,
                coalesce(avg(u.latency_ms),0) avg_latency_ms
              """ + JOINS + filter.where() + " group by 1) " + """
            select to_char(days.bucket_day,'YYYY-MM-DD') date,
              coalesce(aggregate_data.requests,0) requests,
              coalesce(aggregate_data.tokens,0) tokens,
              coalesce(aggregate_data.cache_read_tokens,0) cache_read_tokens,
              coalesce(aggregate_data.cache_write_tokens,0) cache_write_tokens,
              coalesce(aggregate_data.cache_net_savings,0) cache_net_savings,
              coalesce(aggregate_data.cost,0) cost,
              coalesce(aggregate_data.avg_latency_ms,0) avg_latency_ms
            from days left join aggregate_data on aggregate_data.bucket_day=days.bucket_day
            order by days.bucket_day
            """, filter.params());

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("trend", trend);
        result.put("providerCost", grouped(filter, """
            select coalesce(pi.instance_name,legacy_provider.name,u.provider_id,'未识别供应商') name,
              count(*) requests,coalesce(sum(u.total_tokens),0) tokens,
              coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)),0) cost
            """, "group by 1 order by cost desc,requests desc limit 6"));
        result.put("modelUsage", grouped(filter, """
            select coalesce(nullif(u.model_alias,''),nullif(u.runtime_model_name,''),'未识别模型') name,
              count(*) requests,coalesce(sum(u.total_tokens),0) tokens,
              coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)),0) cost
            """, "group by 1 order by tokens desc,requests desc limit 10"));
        result.put("tenantCost", grouped(filter, """
            select coalesce(t.name,nullif(u.tenant_id,''),'未归属租户') name,
              count(*) requests,coalesce(sum(u.total_tokens),0) tokens,
              coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)),0) cost
            """, "group by 1 order by cost desc,requests desc limit 10"));
        result.put("projectUsage", grouped(filter, """
            select coalesce(p.name,nullif(u.project_id,''),'未归属项目') name,
              count(*) requests,coalesce(sum(u.total_tokens),0) tokens,
              coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)),0) cost
            """, "group by 1 order by tokens desc,requests desc limit 6"));
        result.put("keyRanking", grouped(filter, """
            select coalesce(k.name,k.key_prefix,nullif(u.api_key_id,''),'未识别 Key') name,
              coalesce(k.key_prefix,'') key_prefix,count(*) requests,
              coalesce(sum(u.total_tokens),0) tokens,
              coalesce(sum(coalesce(tokensea_fx_amount(u.cost_amount,u.currency,u.created_at,'CNY'),0)),0) cost
            """, "group by 1,2 order by tokens desc,requests desc limit 10"));
        result.put("appTrend", appTrend(filter));
        result.put("fxMissingCurrencies", jdbc.queryForList("""
            select u.currency,count(*) records
            """ + JOINS + filter.where()
                + " and u.currency<>'CNY' and tokensea_fx_rate(u.created_at,u.currency,'CNY') is null"
                + " group by u.currency order by records desc", filter.params()));
        result.put("range", Map.of("startAt", normalized.startAt(), "endAt", normalized.endAt()));
        return result;
    }

    public Map<String,Object> options() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("tenants", optionQuery("u.tenant_id", "coalesce(t.name,u.tenant_id)"));
        result.put("projects", optionQuery("u.project_id", "coalesce(p.name,u.project_id)"));
        result.put("apps", optionQuery("u.app_id", "coalesce(a.name,u.app_id)"));
        result.put("apiKeys", optionQuery("u.api_key_id", "coalesce(k.name,k.key_prefix,u.api_key_id)"));
        result.put("providers", optionQuery("u.provider_id", "coalesce(pi.instance_name,legacy_provider.name,u.provider_id)"));
        result.put("models", jdbc.queryForList("""
            select distinct u.model_alias value,coalesce(nullif(u.model_alias,''),'未识别模型') label
            from usage_record u where nullif(u.model_alias,'') is not null order by label
            """, Map.of()));
        return result;
    }

    private List<Map<String,Object>> grouped(FilterSql filter, String select, String suffix) {
        return jdbc.queryForList(select + JOINS + filter.where() + " " + suffix, filter.params());
    }

    private List<Map<String,Object>> appTrend(FilterSql filter) {
        return jdbc.queryForList("""
            with top_apps as (
              select coalesce(a.name,nullif(u.app_id,''),'未归属应用') name,count(*) requests
              """ + JOINS + filter.where() + " group by 1 order by requests desc limit 3), app_daily as (" + """
              select to_char(date_trunc('day',u.created_at),'YYYY-MM-DD') date,
                coalesce(a.name,nullif(u.app_id,''),'未归属应用') name,count(*) requests
              """ + JOINS + filter.where() + " and coalesce(a.name,nullif(u.app_id,''),'未归属应用') in (select name from top_apps) group by 1,2) " + """
            select date,name,requests from app_daily order by date,name
            """, filter.params());
    }

    private List<Map<String,Object>> optionQuery(String valueExpression, String labelExpression) {
        return jdbc.queryForList("select distinct " + valueExpression + " value," + labelExpression + " label " + JOINS
                + " where nullif(" + valueExpression + ",'') is not null order by label", Map.of());
    }

    private FilterSql filter(UsageQuery query, boolean includeKeyword) {
        StringBuilder where = new StringBuilder(" where u.created_at>=:startAt and u.created_at<=:endAt");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startAt", OffsetDateTime.ofInstant(query.startAt(), ZoneOffset.UTC))
                .addValue("endAt", OffsetDateTime.ofInstant(query.endAt(), ZoneOffset.UTC));
        add(where, params, "tenantId", query.tenantId(), "u.tenant_id=:tenantId");
        add(where, params, "projectId", query.projectId(), "u.project_id=:projectId");
        add(where, params, "appId", query.appId(), "u.app_id=:appId");
        add(where, params, "apiKeyId", query.apiKeyId(), "u.api_key_id=:apiKeyId");
        add(where, params, "providerId", query.providerId(), "u.provider_id=:providerId");
        add(where, params, "modelAlias", query.modelAlias(), "u.model_alias=:modelAlias");
        add(where, params, "status", query.status(), "u.status=:status");
        if (includeKeyword && !blank(query.keyword())) {
            params.addValue("keyword", "%" + query.keyword().trim().toLowerCase(Locale.ROOT) + "%");
            where.append(" and (lower(coalesce(t.name,'')) like :keyword")
                    .append(" or lower(coalesce(p.name,'')) like :keyword")
                    .append(" or lower(coalesce(a.name,'')) like :keyword")
                    .append(" or lower(coalesce(k.name,k.key_prefix,'')) like :keyword")
                    .append(" or lower(coalesce(pi.instance_name,legacy_provider.name,'')) like :keyword")
                    .append(" or lower(coalesce(u.model_alias,'')) like :keyword")
                    .append(" or lower(coalesce(u.runtime_model_name,'')) like :keyword)");
        }
        return new FilterSql(where.toString(), params);
    }

    private UsageQuery normalize(UsageQuery query) {
        Instant now = Instant.now();
        Instant end = query.endAt() == null ? now : query.endAt();
        Instant start = query.startAt() == null ? end.minus(29, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS) : query.startAt();
        if (start.isAfter(end)) {
            Instant swap = start;
            start = end;
            end = swap;
        }
        return new UsageQuery(start, end, query.tenantId(), query.projectId(), query.appId(), query.apiKeyId(),
                query.providerId(), query.modelAlias(), query.status(), query.keyword(), query.page(), query.size(),
                query.sort(), query.order());
    }

    private static void add(StringBuilder where, MapSqlParameterSource params, String name, String value, String clause) {
        if (blank(value)) return;
        params.addValue(name, value.trim());
        where.append(" and ").append(clause);
    }

    private static MapSqlParameterSource copy(MapSqlParameterSource source) {
        MapSqlParameterSource copy = new MapSqlParameterSource();
        for (String name : source.getParameterNames()) copy.addValue(name, source.getValue(name));
        return copy;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record UsageQuery(Instant startAt, Instant endAt, String tenantId, String projectId, String appId,
                             String apiKeyId, String providerId, String modelAlias, String status, String keyword,
                             int page, int size, String sort, String order) {
        public static UsageQuery of(String startAt, String endAt, String tenantId, String projectId, String appId,
                                    String apiKeyId, String providerId, String modelAlias, String status, String keyword,
                                    int page, int size, String sort, String order) {
            return new UsageQuery(parse(startAt), parse(endAt), tenantId, projectId, appId, apiKeyId, providerId,
                    modelAlias, status, keyword, page, size, sort, order);
        }

        private static Instant parse(String value) {
            if (blank(value)) return null;
            try { return Instant.parse(value); }
            catch (Exception ignored) {
                try { return OffsetDateTime.parse(value).toInstant(); }
                catch (Exception second) {
                    return OffsetDateTime.parse(value + "Z").withOffsetSameInstant(ZoneOffset.UTC).toInstant();
                }
            }
        }
    }

    private record FilterSql(String where, MapSqlParameterSource params) {}
}
