package com.tokensea.governance;

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
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ProviderPriceSyncController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProviderPriceSyncService sync;
    private final AuditService audits;

    public ProviderPriceSyncController(JdbcTemplate jdbc, ObjectMapper json,
                                       ProviderPriceSyncService sync, AuditService audits) {
        this.jdbc = jdbc;
        this.json = json;
        this.sync = sync;
        this.audits = audits;
    }

    public record PriceSourceRequest(
            String name,
            String sourceClass,
            String adapterCode,
            String providerType,
            String providerInstanceId,
            String authMode,
            String endpoint,
            List<String> officialHosts,
            String region,
            String defaultCurrency,
            String scheduleExpression,
            Boolean autoPublish,
            BigDecimal maxAutoChangeRatio,
            Integer confirmationRuns,
            Map<String,Object> config,
            String status,
            String parserVersion
    ) {}

    public record DiffDecisionRequest(String reason) {}

    @GetMapping("/public-price-references")
    public ApiResponse<List<Map<String,Object>>> publicPriceReferences(
            @RequestParam(required=false) String providerType,
            @RequestParam(required=false) String sourceId) {
        return ApiResponse.ok(jdbc.queryForList("""
            select r.*,s.name source_name,s.adapter_code
            from public_model_price_reference r join provider_price_source s on s.id=r.price_source_id
            where (?::text is null or lower(r.provider_type)=lower(?))
              and (?::text is null or r.price_source_id=?)
            order by r.provider_type,r.provider_model_name,s.name
            """, providerType, providerType, sourceId, sourceId));
    }

    @GetMapping("/public-price-references/{id}")
    public ApiResponse<Map<String,Object>> publicPriceReference(@PathVariable("id") String id) {
        return ApiResponse.ok(one("public_model_price_reference", id, "公共价格参考不存在"));
    }

    @GetMapping("/provider-price-sources")
    public ApiResponse<List<Map<String,Object>>> sources() {
        return ApiResponse.ok(jdbc.queryForList("""
            select s.*,
              (select count(*) from provider_price_sync_run r where r.price_source_id=s.id) sync_run_count,
              (select count(*) from provider_price_diff d where d.price_source_id=s.id and d.status='PENDING') pending_diff_count
            from provider_price_source s order by s.created_at desc
            """));
    }

    @PostMapping("/provider-price-sources")
    @Transactional
    public ApiResponse<Map<String,Object>> createSource(@RequestBody PriceSourceRequest request,
                                                         Authentication authentication) {
        PriceSourceRequest value = normalize(request, null);
        validate(value);
        String id = id();
        String actor = actor(authentication);
        OffsetDateTime nextRun = "ACTIVE".equals(value.status()) ? OffsetDateTime.now() : null;
        jdbc.update("""
            insert into provider_price_source(
              id,name,source_class,adapter_code,provider_type,provider_instance_id,auth_mode,endpoint,official_hosts,
              region,default_currency,schedule_expression,auto_publish,max_auto_change_ratio,confirmation_runs,config,
              status,next_run_at,parser_version,created_by,updated_by)
            values(?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?)
            """, id, value.name(), value.sourceClass(), value.adapterCode(), value.providerType(),
                value.providerInstanceId(), value.authMode(), value.endpoint(), write(value.officialHosts()),
                value.region(), value.defaultCurrency(), value.scheduleExpression(),
                value.autoPublish(), value.maxAutoChangeRatio(), value.confirmationRuns(), write(value.config()),
                value.status(), nextRun, value.parserVersion(), actor, actor);
        Map<String,Object> created = one("provider_price_source", id, "价格来源不存在");
        audits.record("PROVIDER_PRICE_SOURCE_CREATE", "ProviderPriceSource", id, null, created);
        return ApiResponse.ok(created);
    }

    @PatchMapping("/provider-price-sources/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> updateSource(@PathVariable("id") String id,
                                                         @RequestBody PriceSourceRequest request,
                                                         Authentication authentication) {
        Map<String,Object> before = one("provider_price_source", id, "价格来源不存在");
        PriceSourceRequest value = normalize(request, before);
        validate(value);
        String actor = actor(authentication);
        OffsetDateTime nextRun = "ACTIVE".equals(value.status())
                ? before.get("next_run_at") == null ? OffsetDateTime.now() : time(before.get("next_run_at"))
                : null;
        jdbc.update("""
            update provider_price_source set name=?,source_class=?,adapter_code=?,provider_type=?,
              provider_instance_id=?,auth_mode=?,endpoint=?,official_hosts=cast(? as jsonb),region=?,default_currency=?,
              schedule_expression=?,auto_publish=?,max_auto_change_ratio=?,confirmation_runs=?,config=cast(? as jsonb),
              status=?,next_run_at=?,parser_version=?,updated_by=?,updated_at=now() where id=?
            """, value.name(), value.sourceClass(), value.adapterCode(), value.providerType(),
                value.providerInstanceId(), value.authMode(), value.endpoint(), write(value.officialHosts()),
                value.region(), value.defaultCurrency(), value.scheduleExpression(),
                value.autoPublish(), value.maxAutoChangeRatio(), value.confirmationRuns(), write(value.config()),
                value.status(), nextRun, value.parserVersion(), actor, id);
        Map<String,Object> after = one("provider_price_source", id, "价格来源不存在");
        audits.record("PROVIDER_PRICE_SOURCE_UPDATE", "ProviderPriceSource", id, before, after);
        return ApiResponse.ok(after);
    }

    @PostMapping("/provider-price-sources/{id}/test")
    public ApiResponse<ProviderPriceSyncService.FetchPreview> testSource(@PathVariable("id") String id) {
        return ApiResponse.ok(sync.preview(id));
    }

    @PostMapping("/provider-price-sources/{id}/sync")
    @Transactional
    public ApiResponse<Map<String,Object>> startSync(@PathVariable("id") String id,
                                                      Authentication authentication) {
        one("provider_price_source", id, "价格来源不存在");
        String runId = sync.enqueue(id, "MANUAL");
        Map<String,Object> result = one("provider_price_sync_run", runId, "价格同步任务不存在");
        audits.record("PROVIDER_PRICE_SYNC_CREATE", "ProviderPriceSyncRun", runId, null,
                Map.of("sourceId", id, "actor", actor(authentication)));
        return ApiResponse.ok(result);
    }

    @PostMapping("/provider-price-sources/{id}/enable")
    @Transactional
    public ApiResponse<Map<String,Object>> enableSource(@PathVariable("id") String id,
                                                         Authentication authentication) {
        Map<String,Object> before = one("provider_price_source", id, "价格来源不存在");
        jdbc.update("update provider_price_source set status='ACTIVE',next_run_at=now(),last_error=null,updated_by=?,updated_at=now() where id=?",
                actor(authentication), id);
        Map<String,Object> after = one("provider_price_source", id, "价格来源不存在");
        audits.record("PROVIDER_PRICE_SOURCE_ENABLE", "ProviderPriceSource", id, before, after);
        return ApiResponse.ok(after);
    }

    @PostMapping("/provider-price-sources/{id}/pause")
    @Transactional
    public ApiResponse<Map<String,Object>> pauseSource(@PathVariable("id") String id,
                                                        Authentication authentication) {
        Map<String,Object> before = one("provider_price_source", id, "价格来源不存在");
        jdbc.update("update provider_price_source set status='PAUSED',next_run_at=null,updated_by=?,updated_at=now() where id=?",
                actor(authentication), id);
        Map<String,Object> after = one("provider_price_source", id, "价格来源不存在");
        audits.record("PROVIDER_PRICE_SOURCE_PAUSE", "ProviderPriceSource", id, before, after);
        return ApiResponse.ok(after);
    }

    @GetMapping("/provider-price-sync-runs")
    public ApiResponse<List<Map<String,Object>>> runs(@RequestParam(required=false) String sourceId,
                                                       @RequestParam(required=false) String status) {
        return ApiResponse.ok(jdbc.queryForList("""
            select r.*,s.name source_name,s.adapter_code,s.source_class
            from provider_price_sync_run r join provider_price_source s on s.id=r.price_source_id
            where (?::text is null or r.price_source_id=?) and (?::text is null or r.status=?)
            order by r.created_at desc
            """, sourceId, sourceId, status, status));
    }

    @GetMapping("/provider-price-sync-runs/{id}")
    public ApiResponse<Map<String,Object>> run(@PathVariable("id") String id) {
        Map<String,Object> value = new LinkedHashMap<>(one("provider_price_sync_run", id, "价格同步任务不存在"));
        value.put("snapshots", jdbc.queryForList("""
            select id,price_source_id,sync_run_id,source_endpoint,final_endpoint,http_status,content_type,etag,
              last_modified,checksum,response_bytes,parser_version,fetched_at,created_at
            from provider_price_raw_snapshot where sync_run_id=? order by fetched_at desc
            """, id));
        value.put("diffs", jdbc.queryForList("select * from provider_price_diff where sync_run_id=? order by created_at", id));
        return ApiResponse.ok(value);
    }

    @GetMapping("/provider-price-snapshots")
    public ApiResponse<List<Map<String,Object>>> snapshots(@RequestParam(required=false) String sourceId) {
        return ApiResponse.ok(jdbc.queryForList("""
            select p.id,p.price_source_id,p.sync_run_id,s.name source_name,p.source_endpoint,p.final_endpoint,
              p.http_status,p.content_type,p.etag,p.last_modified,p.checksum,p.response_bytes,p.parser_version,
              p.fetched_at,p.created_at
            from provider_price_raw_snapshot p join provider_price_source s on s.id=p.price_source_id
            where (?::text is null or p.price_source_id=?) order by p.fetched_at desc
            """, sourceId, sourceId));
    }

    @GetMapping("/provider-price-snapshots/{id}")
    public ApiResponse<Map<String,Object>> snapshot(@PathVariable("id") String id) {
        return ApiResponse.ok(one("provider_price_raw_snapshot", id, "价格原始快照不存在"));
    }

    @GetMapping("/provider-price-diffs")
    public ApiResponse<List<Map<String,Object>>> diffs(@RequestParam(required=false) String status,
                                                        @RequestParam(required=false) String riskLevel,
                                                        @RequestParam(required=false) String sourceId) {
        return ApiResponse.ok(jdbc.queryForList("""
            select d.*,s.name source_name,s.adapter_code
            from provider_price_diff d join provider_price_source s on s.id=d.price_source_id
            where (?::text is null or d.status=?) and (?::text is null or d.risk_level=?)
              and (?::text is null or d.price_source_id=?)
            order by case d.risk_level when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
              d.created_at desc
            """, status, status, riskLevel, riskLevel, sourceId, sourceId));
    }

    @GetMapping("/provider-price-diffs/{id}")
    public ApiResponse<Map<String,Object>> diff(@PathVariable("id") String id) {
        return ApiResponse.ok(one("provider_price_diff", id, "价格差异不存在"));
    }

    @PostMapping("/provider-price-diffs/{id}/approve")
    public ApiResponse<Map<String,Object>> approveDiff(@PathVariable("id") String id,
                                                        @RequestBody(required=false) DiffDecisionRequest request,
                                                        Authentication authentication) {
        return ApiResponse.ok(sync.approveDiff(id, actor(authentication), request == null ? null : request.reason()));
    }

    @PostMapping("/provider-price-diffs/{id}/reject")
    public ApiResponse<Map<String,Object>> rejectDiff(@PathVariable("id") String id,
                                                       @RequestBody(required=false) DiffDecisionRequest request,
                                                       Authentication authentication) {
        return ApiResponse.ok(sync.rejectDiff(id, actor(authentication), request == null ? null : request.reason()));
    }

    private PriceSourceRequest normalize(PriceSourceRequest request, Map<String,Object> before) {
        if (request == null) request = new PriceSourceRequest(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        String sourceClass = choose(request.sourceClass(), before, "source_class", "PUBLIC_REFERENCE");
        String adapter = choose(request.adapterCode(), before, "adapter_code", "LITELLM_COST_MAP");
        String endpoint = choose(request.endpoint(), before, "endpoint", "");
        List<String> hosts = request.officialHosts() != null ? normalizeHosts(request.officialHosts())
                : before == null ? endpointHost(endpoint) : readStrings(before.get("official_hosts"));
        return new PriceSourceRequest(
                choose(request.name(), before, "name", ""), sourceClass, adapter,
                chooseNullable(request.providerType(), before, "provider_type"),
                chooseNullable(request.providerInstanceId(), before, "provider_instance_id"),
                choose(request.authMode(), before, "auth_mode", "NONE"), endpoint, hosts,
                choose(request.region(), before, "region", "global"),
                choose(request.defaultCurrency(), before, "default_currency", "USD").toUpperCase(Locale.ROOT),
                choose(request.scheduleExpression(), before, "schedule_expression", "P1D"),
                request.autoPublish() != null ? request.autoPublish() : before != null && Boolean.TRUE.equals(before.get("auto_publish")),
                request.maxAutoChangeRatio() != null ? request.maxAutoChangeRatio()
                        : before == null ? new BigDecimal("0.3000") : decimal(before.get("max_auto_change_ratio")),
                request.confirmationRuns() != null ? request.confirmationRuns()
                        : before == null ? 1 : ((Number) before.get("confirmation_runs")).intValue(),
                request.config() != null ? request.config() : before == null ? Map.of() : readMap(before.get("config")),
                choose(request.status(), before, "status", "DRAFT"),
                choose(request.parserVersion(), before, "parser_version", "1.0.0"));
    }

    private void validate(PriceSourceRequest request) {
        if (blank(request.name()) || blank(request.endpoint()) || request.officialHosts().isEmpty()) bad("价格源名称、地址和官方域名不能为空");
        if (!Set.of("PUBLIC_REFERENCE","OFFICIAL").contains(request.sourceClass())) bad("价格来源类别无效");
        if (!Set.of("LITELLM_COST_MAP","MODELS_DEV","DEEPSEEK_OFFICIAL_PAGE","OFFICIAL_JSON","OFFICIAL_CSV").contains(request.adapterCode())) bad("价格适配器无效");
        if (Set.of("LITELLM_COST_MAP","MODELS_DEV").contains(request.adapterCode()) && !"PUBLIC_REFERENCE".equals(request.sourceClass()))
            bad("LiteLLM 与 models.dev 只能作为公共参考来源");
        if (Set.of("DEEPSEEK_OFFICIAL_PAGE","OFFICIAL_JSON","OFFICIAL_CSV").contains(request.adapterCode()) && !"OFFICIAL".equals(request.sourceClass()))
            bad("供应商专用或官方结构化适配器必须用于供应商官方价格来源");
        if ("DEEPSEEK_OFFICIAL_PAGE".equals(request.adapterCode()) && !"deepseek".equalsIgnoreCase(request.providerType()))
            bad("DeepSeek 官方价格页适配器只能绑定 DeepSeek 供应商");
        if ("OFFICIAL".equals(request.sourceClass()) && blank(request.providerType())) bad("供应商官方价格来源必须指定供应商类型");
        if (!Set.of("NONE","PROVIDER_INSTANCE").contains(request.authMode())) bad("价格源认证方式无效");
        if ("NONE".equals(request.authMode()) && !blank(request.providerInstanceId())) bad("无认证价格源不能绑定供应商渠道");
        if ("PROVIDER_INSTANCE".equals(request.authMode()) && blank(request.providerInstanceId())) bad("渠道凭据认证必须绑定供应商渠道");
        if ("PUBLIC_REFERENCE".equals(request.sourceClass()) && !"NONE".equals(request.authMode())) bad("公共参考价格源不能使用供应商渠道凭据");
        if (!blank(request.providerInstanceId())) {
            if (blank(request.providerType())) bad("绑定供应商渠道前必须指定供应商类型");
            List<Map<String,Object>> channels = jdbc.queryForList("select provider_type from provider_instance where id=?", request.providerInstanceId());
            if (channels.isEmpty()) bad("绑定的供应商渠道不存在");
            if (!request.providerType().equalsIgnoreCase(String.valueOf(channels.get(0).get("provider_type")))) bad("价格源供应商与绑定渠道不一致");
        }
        if ("PUBLIC_REFERENCE".equals(request.sourceClass()) && request.autoPublish()) bad("公共参考价格不能自动发布为生产价格");
        if (!request.defaultCurrency().matches("^[A-Z]{3}$")) bad("币种必须是三位大写代码");
        if (request.maxAutoChangeRatio().signum() < 0 || request.maxAutoChangeRatio().compareTo(new BigDecimal("10")) > 0)
            bad("自动发布价格变化比例必须在0到10之间");
        if (request.confirmationRuns() < 1 || request.confirmationRuns() > 10) bad("连续确认次数必须在1到10之间");
        if (!Set.of("DRAFT","ACTIVE","PAUSED","DEGRADED","DISABLED").contains(request.status())) bad("价格源状态无效");
        try { Duration.parse(request.scheduleExpression()); } catch (Exception e) { bad("同步周期必须是 ISO-8601 Duration"); }
        URI uri;
        try { uri = URI.create(request.endpoint()); } catch (Exception e) { bad("价格源地址无效"); return; }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) bad("价格源必须使用 HTTPS");
        if (!request.officialHosts().contains(uri.getHost().toLowerCase(Locale.ROOT))) bad("价格源地址主机必须列入官方域名");
        if ("OFFICIAL_JSON".equals(request.adapterCode())) {
            if (!request.config().containsKey("modelField") && !Boolean.TRUE.equals(request.config().get("modelFromKey")))
                bad("官方 JSON 适配器必须配置 modelField 或 modelFromKey");
            if (!request.config().containsKey("inputField") && !request.config().containsKey("outputField"))
                bad("官方 JSON 适配器至少配置 inputField 或 outputField");
        }
        if ("OFFICIAL_CSV".equals(request.adapterCode()) && !request.config().containsKey("modelField"))
            bad("官方 CSV 适配器必须配置 modelField");
    }

    private Map<String,Object> one(String table, String id, String message) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from " + table + " where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return rows.get(0);
    }

    private Map<String,Object> readMap(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try { return json.readValue(String.valueOf(value), json.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private List<String> readStrings(Object value) {
        if (value instanceof Collection<?> collection) return normalizeHosts(collection.stream().map(String::valueOf).toList());
        try { return normalizeHosts(json.readValue(String.valueOf(value), json.getTypeFactory().constructCollectionType(List.class, String.class))); }
        catch (Exception e) { return List.of(); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }

    private static List<String> endpointHost(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() == null ? List.of() : List.of(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (Exception e) { return List.of(); }
    }

    private static List<String> normalizeHosts(List<String> hosts) {
        return hosts.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static String choose(String supplied, Map<String,Object> before, String key, String fallback) {
        if (!blank(supplied)) return supplied;
        return before != null && before.get(key) != null ? String.valueOf(before.get(key)) : fallback;
    }

    private static String chooseNullable(String supplied, Map<String,Object> before, String key) {
        if (supplied != null) return supplied.isBlank() ? null : supplied;
        return before != null && before.get(key) != null ? String.valueOf(before.get(key)) : null;
    }

    private static OffsetDateTime time(Object value) {
        if (value instanceof OffsetDateTime time) return time;
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) { return new BigDecimal(String.valueOf(value)); }
    private static String actor(Authentication authentication) { return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity ? identity.userId() : "SYSTEM"; }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
