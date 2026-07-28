package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ProviderBillingController {
    private static final Set<String> ADAPTERS = Set.of("OPENAI_COSTS_API", "GENERIC_BILLING_JSON");
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "PAUSED", "DEGRADED", "DISABLED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProviderBillingSyncService sync;
    private final AuditService audits;

    public ProviderBillingController(JdbcTemplate jdbc,
                                     ObjectMapper json,
                                     ProviderBillingSyncService sync,
                                     AuditService audits) {
        this.jdbc = jdbc;
        this.json = json;
        this.sync = sync;
        this.audits = audits;
    }

    public record BillingSourceRequest(
            String name,
            String providerInstanceId,
            String adapterCode,
            String endpoint,
            List<String> officialHosts,
            String defaultCurrency,
            String scheduleExpression,
            Map<String,Object> config,
            String status
    ) {}

    public record PeriodRequest(OffsetDateTime from, OffsetDateTime to) {}

    @GetMapping("/provider-billing-sources")
    public ApiResponse<PageResult<Map<String,Object>>> sources(
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "name", "s.name",
                "adapterCode", "s.adapter_code",
                "providerInstanceId", "s.provider_instance_id",
                "status", "s.status",
                "lastSuccessAt", "s.last_success_at",
                "lastFailureAt", "s.last_failure_at",
                "createdAt", "s.created_at",
                "updatedAt", "s.updated_at"
        ), "createdAt", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String normalizedStatus = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or lower(s.name) like ? or lower(p.instance_name) like ?
                   or lower(s.adapter_code) like ?)
              and (?::text is null or s.status=?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select s.*,p.instance_name "providerInstanceName",p.provider_type "providerType",
              (select count(*) from provider_billing_sync_run r where r.billing_source_id=s.id) "syncRunCount",
              (select count(*) from provider_billing_record b where b.billing_source_id=s.id) "billingRecordCount"
            from provider_billing_source s join provider_instance p on p.id=s.provider_instance_id
            """ + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                + ",s.id " + paging.direction() + " limit ? offset ?",
                q, q, q, q, normalizedStatus, normalizedStatus, paging.size(), paging.offset());
        Long total = jdbc.queryForObject("""
            select count(*) from provider_billing_source s
            join provider_instance p on p.id=s.provider_instance_id
            """ + filter, Long.class, q, q, q, q, normalizedStatus, normalizedStatus);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/provider-billing-sources/{id}")
    public ApiResponse<Map<String,Object>> source(@PathVariable String id) {
        return ApiResponse.ok(requireSource(id));
    }

    @PostMapping("/provider-billing-sources")
    @Transactional
    public ApiResponse<Map<String,Object>> create(@RequestBody BillingSourceRequest request,
                                                   Authentication authentication) {
        BillingSourceRequest normalized = normalize(request, null);
        validate(normalized);
        Integer duplicate = jdbc.queryForObject("select count(*) from provider_billing_source where lower(name)=lower(?)",
                Integer.class, normalized.name());
        if (duplicate != null && duplicate > 0) conflict("供应商账单来源名称已存在");
        String id = id();
        jdbc.update("""
            insert into provider_billing_source(
              id,name,provider_instance_id,adapter_code,endpoint,official_hosts,default_currency,
              schedule_expression,config,status,next_run_at,created_by,updated_by)
            values(?,?,?,?,?,cast(? as jsonb),?,?,cast(? as jsonb),?,
              case when ?='ACTIVE' then now() else null end,?,?)
            """, id, normalized.name(), normalized.providerInstanceId(), normalized.adapterCode(),
                normalized.endpoint(), write(normalized.officialHosts()), normalized.defaultCurrency(),
                normalized.scheduleExpression(), write(normalized.config()), normalized.status(), normalized.status(),
                actor(authentication), actor(authentication));
        Map<String,Object> created = requireSource(id);
        audits.record("PROVIDER_BILLING_SOURCE_CREATE", "ProviderBillingSource", id, null, created);
        return ApiResponse.ok(created);
    }

    @PatchMapping("/provider-billing-sources/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> update(@PathVariable String id,
                                                   @RequestBody BillingSourceRequest request,
                                                   Authentication authentication) {
        Map<String,Object> before = requireSource(id);
        BillingSourceRequest normalized = normalize(request, before);
        validate(normalized);
        Integer duplicate = jdbc.queryForObject("""
            select count(*) from provider_billing_source where lower(name)=lower(?) and id<>?
            """, Integer.class, normalized.name(), id);
        if (duplicate != null && duplicate > 0) conflict("供应商账单来源名称已存在");
        jdbc.update("""
            update provider_billing_source set name=?,provider_instance_id=?,adapter_code=?,endpoint=?,
              official_hosts=cast(? as jsonb),default_currency=?,schedule_expression=?,config=cast(? as jsonb),
              status=?,next_run_at=case when ?='ACTIVE' then coalesce(next_run_at,now()) else next_run_at end,
              updated_by=?,updated_at=now() where id=?
            """, normalized.name(), normalized.providerInstanceId(), normalized.adapterCode(), normalized.endpoint(),
                write(normalized.officialHosts()), normalized.defaultCurrency(), normalized.scheduleExpression(),
                write(normalized.config()), normalized.status(), normalized.status(), actor(authentication), id);
        Map<String,Object> after = requireSource(id);
        audits.record("PROVIDER_BILLING_SOURCE_UPDATE", "ProviderBillingSource", id, before, after);
        return ApiResponse.ok(after);
    }

    @PostMapping("/provider-billing-sources/{id}/test")
    public ApiResponse<ProviderBillingSyncService.BillingPreview> test(
            @PathVariable String id,
            @RequestBody(required=false) PeriodRequest request) {
        return ApiResponse.ok(sync.test(id, request == null ? null : request.from(), request == null ? null : request.to()));
    }

    @PostMapping("/provider-billing-sources/{id}/sync")
    public ApiResponse<ProviderBillingSyncService.BillingSyncSummary> sync(
            @PathVariable String id,
            @RequestBody(required=false) PeriodRequest request) {
        return ApiResponse.ok(sync.sync(id, request == null ? null : request.from(), request == null ? null : request.to(), "MANUAL"));
    }

    @PostMapping("/provider-billing-sources/{id}/enable")
    @Transactional
    public ApiResponse<Map<String,Object>> enable(@PathVariable String id, Authentication authentication) {
        return state(id, "ACTIVE", "PROVIDER_BILLING_SOURCE_ENABLE", authentication);
    }

    @PostMapping("/provider-billing-sources/{id}/pause")
    @Transactional
    public ApiResponse<Map<String,Object>> pause(@PathVariable String id, Authentication authentication) {
        return state(id, "PAUSED", "PROVIDER_BILLING_SOURCE_PAUSE", authentication);
    }

    @GetMapping("/provider-billing-sync-runs")
    public ApiResponse<PageResult<Map<String,Object>>> runs(
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        return page("provider_billing_sync_run r join provider_billing_source s on s.id=r.billing_source_id",
                "select r.*,s.name \"sourceName\" from ",
                keyword, status, page, size, sort, order,
                Map.of("createdAt", "r.created_at", "periodStart", "r.period_start", "periodEnd", "r.period_end",
                        "status", "r.status", "amountFetched", "r.amount_fetched", "recordsFetched", "r.records_fetched"));
    }

    @GetMapping("/provider-billing-records")
    public ApiResponse<PageResult<Map<String,Object>>> records(
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "periodStart", "b.period_start", "periodEnd", "b.period_end", "amount", "b.amount",
                "currency", "b.currency", "lineItem", "b.line_item", "createdAt", "b.created_at"
        ), "periodStart", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String filter = """
            where (?::text is null or lower(coalesce(b.line_item,'')) like ?
                or lower(coalesce(b.provider_model_name,'')) like ? or lower(s.name) like ?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select b.*,s.name "sourceName",p.instance_name "providerInstanceName"
            from provider_billing_record b
            join provider_billing_source s on s.id=b.billing_source_id
            join provider_instance p on p.id=b.provider_instance_id
            """ + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                + ",b.id " + paging.direction() + " limit ? offset ?",
                q, q, q, q, paging.size(), paging.offset());
        Long total = jdbc.queryForObject("""
            select count(*) from provider_billing_record b
            join provider_billing_source s on s.id=b.billing_source_id
            """ + filter, Long.class, q, q, q, q);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @DeleteMapping("/provider-billing-sources/{id}")
    public void delete(@PathVariable String id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "供应商账单来源禁止物理删除，请暂停或停用");
    }

    private ApiResponse<Map<String,Object>> state(String id,
                                                   String status,
                                                   String action,
                                                   Authentication authentication) {
        Map<String,Object> before = requireSource(id);
        jdbc.update("""
            update provider_billing_source set status=?,next_run_at=case when ?='ACTIVE' then now() else next_run_at end,
              updated_by=?,updated_at=now() where id=?
            """, status, status, actor(authentication), id);
        Map<String,Object> after = requireSource(id);
        audits.record(action, "ProviderBillingSource", id, before, after);
        return ApiResponse.ok(after);
    }

    private ApiResponse<PageResult<Map<String,Object>>> page(String table,
                                                              String selectPrefix,
                                                              String keyword,
                                                              String status,
                                                              Integer page,
                                                              Integer size,
                                                              String sort,
                                                              String order,
                                                              Map<String,String> sorts) {
        PageQuery paging = PageQuery.of(page, size, sort, order, sorts, "createdAt", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String normalizedStatus = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or lower(s.name) like ?)
              and (?::text is null or r.status=?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList(selectPrefix + table + filter
                        + " order by " + paging.sortColumn() + " " + paging.direction()
                        + ",r.id " + paging.direction() + " limit ? offset ?",
                q, q, normalizedStatus, normalizedStatus, paging.size(), paging.offset());
        Long total = jdbc.queryForObject("select count(*) from " + table + filter, Long.class,
                q, q, normalizedStatus, normalizedStatus);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    private BillingSourceRequest normalize(BillingSourceRequest request, Map<String,Object> before) {
        if (request == null) request = new BillingSourceRequest(null,null,null,null,null,null,null,null,null);
        String endpoint = choose(request.endpoint(), before, "endpoint", "");
        List<String> hosts = request.officialHosts() == null
                ? before == null ? endpointHost(endpoint) : strings(before.get("official_hosts"))
                : request.officialHosts().stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
        return new BillingSourceRequest(
                choose(request.name(), before, "name", ""),
                choose(request.providerInstanceId(), before, "provider_instance_id", ""),
                choose(request.adapterCode(), before, "adapter_code", "OPENAI_COSTS_API"),
                endpoint,
                hosts,
                choose(request.defaultCurrency(), before, "default_currency", "USD").toUpperCase(Locale.ROOT),
                choose(request.scheduleExpression(), before, "schedule_expression", "P1D"),
                request.config() != null ? request.config() : before == null ? Map.of() : map(before.get("config")),
                choose(request.status(), before, "status", "DRAFT").toUpperCase(Locale.ROOT));
    }

    private void validate(BillingSourceRequest request) {
        if (blank(request.name()) || blank(request.providerInstanceId()) || blank(request.endpoint())
                || request.officialHosts().isEmpty()) bad("账单来源名称、供应商渠道、地址和官方域名不能为空");
        if (!ADAPTERS.contains(request.adapterCode())) bad("供应商账单适配器无效");
        if (!STATUSES.contains(request.status())) bad("供应商账单来源状态无效");
        if (!request.defaultCurrency().matches("^[A-Z]{3}$")) bad("账单币种必须是三位大写代码");
        try {
            Duration.parse(request.scheduleExpression());
        } catch (Exception exception) {
            bad("账单同步周期必须是 ISO-8601 Duration");
        }
        URI uri;
        try {
            uri = URI.create(request.endpoint());
        } catch (Exception exception) {
            bad("供应商账单地址无效");
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) bad("供应商账单接口必须使用 HTTPS");
        if (!request.officialHosts().contains(uri.getHost().toLowerCase(Locale.ROOT))) bad("账单地址主机必须列入官方域名");
        List<Map<String,Object>> channel = jdbc.queryForList("select id,status from provider_instance where id=?", request.providerInstanceId());
        if (channel.isEmpty()) bad("绑定的供应商渠道不存在");
        if ("OPENAI_COSTS_API".equals(request.adapterCode())
                && !uri.getPath().endsWith("/v1/organization/costs")) {
            bad("OpenAI Costs API 地址必须以 /v1/organization/costs 结尾");
        }
        if ("GENERIC_BILLING_JSON".equals(request.adapterCode())) {
            if (!request.config().containsKey("recordsPath") || !request.config().containsKey("amountField")
                    || !request.config().containsKey("periodStartField") || !request.config().containsKey("periodEndField")) {
                bad("通用账单 JSON 必须配置 recordsPath、amountField、periodStartField 和 periodEndField");
            }
        }
    }

    private Map<String,Object> requireSource(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from provider_billing_source where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商账单来源不存在");
        return rows.getFirst();
    }

    private String choose(String supplied, Map<String,Object> before, String key, String fallback) {
        if (!blank(supplied)) return supplied;
        return before != null && before.get(key) != null ? String.valueOf(before.get(key)) : fallback;
    }

    private List<String> endpointHost(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() == null ? List.of() : List.of(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> strings(Object value) {
        if (value instanceof Collection<?> collection) return collection.stream().map(String::valueOf).toList();
        try {
            return json.readValue(String.valueOf(value), json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String,Object> map(Object value) {
        if (value instanceof Map<?,?> source) {
            Map<String,Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return json.readValue(String.valueOf(value), json.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    private String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
