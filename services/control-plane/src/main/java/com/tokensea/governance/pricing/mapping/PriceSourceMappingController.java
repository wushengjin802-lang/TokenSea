package com.tokensea.governance.pricing.mapping;

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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class PriceSourceMappingController {
    private static final Set<String> COMPONENTS = Set.of(
            "INPUT_TOKEN","OUTPUT_TOKEN","CACHE_READ_TOKEN","CACHE_WRITE_TOKEN","REASONING_TOKEN",
            "IMAGE_INPUT","IMAGE_OUTPUT","AUDIO_SECOND","VIDEO_SECOND","REQUEST");
    private static final Set<String> BASES = Set.of(
            "TOKEN","REQUEST","IMAGE","SECOND","MINUTE","CHARACTER","AUDIO_MINUTE","TOKEN_SECOND");
    private static final Set<String> STATUSES = Set.of("ACTIVE","PAUSED","DISABLED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PriceSourceMappingService mappings;
    private final AuditService audits;

    public PriceSourceMappingController(JdbcTemplate jdbc,
                                        ObjectMapper json,
                                        PriceSourceMappingService mappings,
                                        AuditService audits) {
        this.jdbc = jdbc;
        this.json = json;
        this.mappings = mappings;
        this.audits = audits;
    }

    public record MappingRequest(
            String priceSourceId,
            String mappingProfile,
            String ruleName,
            String externalServicePattern,
            String externalProductPattern,
            String externalSkuPattern,
            String externalMeterPattern,
            String externalModelPattern,
            String targetProviderType,
            String targetModelName,
            String targetComponentType,
            String targetRequestMode,
            String targetServiceTier,
            String targetContextTier,
            String targetRegion,
            String billingBasis,
            Long billingQuantity,
            Map<String,Object> transformConfig,
            Integer priority,
            String status
    ) {}

    public record MappingTestRequest(List<Map<String,Object>> records) {}
    public record UnmappedDecisionRequest(String reason) {}

    @GetMapping("/provider-price-mappings")
    public ApiResponse<PageResult<Map<String,Object>>> mappings(
            @RequestParam(required=false) String sourceId,
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "ruleName", "m.rule_name", "mappingProfile", "m.mapping_profile",
                "targetModelName", "m.target_model_name", "targetComponentType", "m.target_component_type",
                "priority", "m.priority", "status", "m.status", "createdAt", "m.created_at",
                "updatedAt", "m.updated_at"), "priority", "asc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String state = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or m.price_source_id=?)
              and (?::text is null or lower(m.rule_name) like ? or lower(m.target_model_name) like ?)
              and (?::text is null or m.status=?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select m.*,s.name "priceSourceName",s.adapter_code "adapterCode"
            from price_source_mapping_rule m join provider_price_source s on s.id=m.price_source_id
            """ + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                + ",m.id " + paging.direction() + " limit ? offset ?",
                sourceId, sourceId, q, q, q, state, state, paging.size(), paging.offset());
        Long total = jdbc.queryForObject("""
            select count(*) from price_source_mapping_rule m
            join provider_price_source s on s.id=m.price_source_id
            """ + filter, Long.class, sourceId, sourceId, q, q, q, state, state);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/provider-price-sources/{sourceId}/mappings")
    public ApiResponse<List<Map<String,Object>>> sourceMappings(@PathVariable String sourceId) {
        requireSource(sourceId);
        return ApiResponse.ok(jdbc.queryForList("""
            select * from price_source_mapping_rule where price_source_id=? order by priority,id
            """, sourceId));
    }

    @PostMapping("/provider-price-mappings")
    @Transactional
    public ApiResponse<Map<String,Object>> create(@RequestBody MappingRequest request,
                                                   Authentication authentication) {
        MappingRequest value = normalize(request, null, request == null ? null : request.priceSourceId());
        validate(value);
        requireSource(value.priceSourceId());
        String id = id();
        jdbc.update("""
            insert into price_source_mapping_rule(
              id,price_source_id,mapping_profile,rule_name,external_service_pattern,external_product_pattern,
              external_sku_pattern,external_meter_pattern,external_model_pattern,target_provider_type,
              target_model_name,target_component_type,target_request_mode,target_service_tier,target_context_tier,
              target_region,billing_basis,billing_quantity,transform_config,priority,status,created_by,updated_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?)
            """, id, value.priceSourceId(), value.mappingProfile(), value.ruleName(),
                value.externalServicePattern(), value.externalProductPattern(), value.externalSkuPattern(),
                value.externalMeterPattern(), value.externalModelPattern(), value.targetProviderType(),
                value.targetModelName(), value.targetComponentType(), value.targetRequestMode(),
                value.targetServiceTier(), value.targetContextTier(), value.targetRegion(), value.billingBasis(),
                value.billingQuantity(), write(value.transformConfig()), value.priority(), value.status(),
                actor(authentication), actor(authentication));
        Map<String,Object> created = requireMapping(id);
        audits.record("PRICE_SOURCE_MAPPING_CREATE", "PriceSourceMappingRule", id, null, created);
        return ApiResponse.ok(created);
    }

    @PostMapping("/provider-price-sources/{sourceId}/mappings")
    public ApiResponse<Map<String,Object>> createForSource(@PathVariable String sourceId,
                                                            @RequestBody MappingRequest request,
                                                            Authentication authentication) {
        MappingRequest value = normalize(request, null, sourceId);
        return create(value, authentication);
    }

    @PatchMapping("/provider-price-mappings/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> update(@PathVariable String id,
                                                   @RequestBody MappingRequest request,
                                                   Authentication authentication) {
        Map<String,Object> before = requireMapping(id);
        MappingRequest value = normalize(request, before, text(before.get("price_source_id")));
        validate(value);
        jdbc.update("""
            update price_source_mapping_rule set mapping_profile=?,rule_name=?,external_service_pattern=?,
              external_product_pattern=?,external_sku_pattern=?,external_meter_pattern=?,external_model_pattern=?,
              target_provider_type=?,target_model_name=?,target_component_type=?,target_request_mode=?,
              target_service_tier=?,target_context_tier=?,target_region=?,billing_basis=?,billing_quantity=?,
              transform_config=cast(? as jsonb),priority=?,status=?,updated_by=?,updated_at=now() where id=?
            """, value.mappingProfile(), value.ruleName(), value.externalServicePattern(),
                value.externalProductPattern(), value.externalSkuPattern(), value.externalMeterPattern(),
                value.externalModelPattern(), value.targetProviderType(), value.targetModelName(),
                value.targetComponentType(), value.targetRequestMode(), value.targetServiceTier(),
                value.targetContextTier(), value.targetRegion(), value.billingBasis(), value.billingQuantity(),
                write(value.transformConfig()), value.priority(), value.status(), actor(authentication), id);
        Map<String,Object> after = requireMapping(id);
        audits.record("PRICE_SOURCE_MAPPING_UPDATE", "PriceSourceMappingRule", id, before, after);
        return ApiResponse.ok(after);
    }

    @PutMapping("/provider-price-sources/{sourceId}/mappings/{id}")
    public ApiResponse<Map<String,Object>> updateForSource(@PathVariable String sourceId,
                                                            @PathVariable String id,
                                                            @RequestBody MappingRequest request,
                                                            Authentication authentication) {
        Map<String,Object> before = requireMapping(id);
        if (!sourceId.equals(text(before.get("price_source_id")))) notFound("价格映射规则不存在");
        return update(id, request, authentication);
    }

    @DeleteMapping("/provider-price-mappings/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> disable(@PathVariable String id, Authentication authentication) {
        Map<String,Object> before = requireMapping(id);
        jdbc.update("update price_source_mapping_rule set status='DISABLED',updated_by=?,updated_at=now() where id=?",
                actor(authentication), id);
        Map<String,Object> after = requireMapping(id);
        audits.record("PRICE_SOURCE_MAPPING_DISABLE", "PriceSourceMappingRule", id, before, after);
        return ApiResponse.ok(after);
    }

    @DeleteMapping("/provider-price-sources/{sourceId}/mappings/{id}")
    public ApiResponse<Map<String,Object>> disableForSource(@PathVariable String sourceId,
                                                             @PathVariable String id,
                                                             Authentication authentication) {
        Map<String,Object> before = requireMapping(id);
        if (!sourceId.equals(text(before.get("price_source_id")))) notFound("价格映射规则不存在");
        return disable(id, authentication);
    }

    @PostMapping("/provider-price-sources/{sourceId}/mappings/test")
    public ApiResponse<Map<String,Object>> test(@PathVariable String sourceId,
                                                 @RequestBody(required=false) MappingTestRequest request) {
        requireSource(sourceId);
        return ApiResponse.ok(mappings.previewCoverage(sourceId,
                request == null || request.records() == null ? List.of() : request.records()));
    }

    @GetMapping("/provider-price-unmapped-records")
    public ApiResponse<PageResult<Map<String,Object>>> unmapped(
            @RequestParam(required=false) String sourceId,
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "lastSeenAt", "u.last_seen_at", "externalModel", "u.external_model",
                "externalSku", "u.external_sku", "reasonCode", "u.reason_code",
                "status", "u.status", "occurrenceCount", "u.occurrence_count"), "lastSeenAt", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String state = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or u.price_source_id=?)
              and (?::text is null or lower(coalesce(u.external_model,'')) like ?
                   or lower(coalesce(u.external_sku,'')) like ? or lower(coalesce(u.external_meter,'')) like ?)
              and (?::text is null or u.status=?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select u.*,s.name "priceSourceName",s.adapter_code "adapterCode"
            from price_source_unmapped_record u join provider_price_source s on s.id=u.price_source_id
            """ + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                + ",u.id " + paging.direction() + " limit ? offset ?",
                sourceId, sourceId, q, q, q, q, state, state, paging.size(), paging.offset());
        Long total = jdbc.queryForObject("""
            select count(*) from price_source_unmapped_record u
            join provider_price_source s on s.id=u.price_source_id
            """ + filter, Long.class, sourceId, sourceId, q, q, q, q, state, state);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/provider-price-sources/{sourceId}/unmapped-records")
    public ApiResponse<PageResult<Map<String,Object>>> sourceUnmapped(
            @PathVariable String sourceId,
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        requireSource(sourceId);
        return unmapped(sourceId, keyword, status, page, size, sort, order);
    }

    @PostMapping("/provider-price-unmapped-records/{id}/ignore")
    @Transactional
    public ApiResponse<Map<String,Object>> ignore(@PathVariable String id,
                                                   @RequestBody(required=false) UnmappedDecisionRequest request,
                                                   Authentication authentication) {
        Map<String,Object> before = requireUnmapped(id);
        jdbc.update("update price_source_unmapped_record set status='IGNORED',reason_message=coalesce(?,reason_message),updated_at=now() where id=?",
                request == null ? null : request.reason(), id);
        Map<String,Object> after = requireUnmapped(id);
        audits.record("PRICE_SOURCE_UNMAPPED_IGNORE", "PriceSourceUnmappedRecord", id, before,
                Map.of("record", after, "actor", actor(authentication)));
        return ApiResponse.ok(after);
    }

    private MappingRequest normalize(MappingRequest request, Map<String,Object> before, String sourceId) {
        if (request == null) request = new MappingRequest(null,null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,null,null);
        return new MappingRequest(
                value(sourceId, choose(request.priceSourceId(), before, "price_source_id", "")),
                choose(request.mappingProfile(), before, "mapping_profile", "DEFAULT"),
                choose(request.ruleName(), before, "rule_name", ""),
                chooseNullable(request.externalServicePattern(), before, "external_service_pattern"),
                chooseNullable(request.externalProductPattern(), before, "external_product_pattern"),
                chooseNullable(request.externalSkuPattern(), before, "external_sku_pattern"),
                chooseNullable(request.externalMeterPattern(), before, "external_meter_pattern"),
                chooseNullable(request.externalModelPattern(), before, "external_model_pattern"),
                chooseNullable(request.targetProviderType(), before, "target_provider_type"),
                choose(request.targetModelName(), before, "target_model_name", ""),
                choose(request.targetComponentType(), before, "target_component_type", "INPUT_TOKEN").toUpperCase(Locale.ROOT),
                choose(request.targetRequestMode(), before, "target_request_mode", "STANDARD").toUpperCase(Locale.ROOT),
                choose(request.targetServiceTier(), before, "target_service_tier", "DEFAULT").toUpperCase(Locale.ROOT),
                choose(request.targetContextTier(), before, "target_context_tier", "DEFAULT").toUpperCase(Locale.ROOT),
                chooseNullable(request.targetRegion(), before, "target_region"),
                choose(request.billingBasis(), before, "billing_basis", "TOKEN").toUpperCase(Locale.ROOT),
                request.billingQuantity() != null ? request.billingQuantity()
                        : number(before, "billing_quantity", 1_000_000L),
                request.transformConfig() != null ? request.transformConfig()
                        : before == null ? Map.of() : map(before.get("transform_config")),
                request.priority() != null ? request.priority() : (int) number(before, "priority", 100L),
                choose(request.status(), before, "status", "ACTIVE").toUpperCase(Locale.ROOT));
    }

    private void validate(MappingRequest request) {
        if (blank(request.priceSourceId()) || blank(request.ruleName()) || blank(request.targetModelName()))
            bad("价格源、规则名称和目标模型不能为空");
        if (!COMPONENTS.contains(request.targetComponentType())) bad("目标计费组件无效");
        if (!BASES.contains(request.billingBasis())) bad("计费基准无效");
        if (!STATUSES.contains(request.status())) bad("映射规则状态无效");
        if (request.billingQuantity() == null || request.billingQuantity() <= 0) bad("计费数量必须大于 0");
        if (request.priority() == null || request.priority() < 1 || request.priority() > 10000)
            bad("映射优先级必须在 1 到 10000 之间");
        boolean hasPattern = java.util.stream.Stream.of(
                        request.externalServicePattern(), request.externalProductPattern(),
                        request.externalSkuPattern(), request.externalMeterPattern(), request.externalModelPattern())
                .anyMatch(value -> !blank(value));
        if (!hasPattern) bad("映射规则至少配置一个外部字段匹配表达式");
        for (String expression : new String[]{
                request.externalServicePattern(), request.externalProductPattern(), request.externalSkuPattern(),
                request.externalMeterPattern(), request.externalModelPattern()}) {
            if (blank(expression)) continue;
            try {
                Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            } catch (Exception exception) {
                bad("映射规则包含无效正则表达式");
            }
        }
    }

    private Map<String,Object> requireSource(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from provider_price_source where id=?", id);
        if (rows.isEmpty()) notFound("价格源不存在");
        return rows.getFirst();
    }

    private Map<String,Object> requireMapping(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from price_source_mapping_rule where id=?", id);
        if (rows.isEmpty()) notFound("价格映射规则不存在");
        return rows.getFirst();
    }

    private Map<String,Object> requireUnmapped(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from price_source_unmapped_record where id=?", id);
        if (rows.isEmpty()) notFound("未映射价格记录不存在");
        return rows.getFirst();
    }

    private String choose(String supplied, Map<String,Object> before, String key, String fallback) {
        if (!blank(supplied)) return supplied.trim();
        return before != null && before.get(key) != null ? text(before.get(key)) : fallback;
    }

    private String chooseNullable(String supplied, Map<String,Object> before, String key) {
        if (supplied != null) return blank(supplied) ? null : supplied.trim();
        return before != null && before.get(key) != null ? text(before.get(key)) : null;
    }

    private long number(Map<String,Object> before, String key, long fallback) {
        if (before == null || before.get(key) == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(before.get(key)));
        } catch (Exception exception) {
            return fallback;
        }
    }

    private Map<String,Object> map(Object value) {
        if (value instanceof Map<?,?> source) {
            Map<String,Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return json.convertValue(value, json.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("价格映射 JSON 序列化失败", exception);
        }
    }

    private String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String value(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void notFound(String message) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
