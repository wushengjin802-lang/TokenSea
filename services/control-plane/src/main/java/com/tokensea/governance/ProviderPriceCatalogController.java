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
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/provider-price-catalog")
public class ProviderPriceCatalogController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audits;
    private final ProviderPriceCatalogService matcher;

    public ProviderPriceCatalogController(JdbcTemplate jdbc, ObjectMapper json, AuditService audits,
                                          ProviderPriceCatalogService matcher) {
        this.jdbc = jdbc;
        this.json = json;
        this.audits = audits;
        this.matcher = matcher;
    }

    public record CatalogRequest(String providerType, String providerModelName, String displayName,
                                 List<String> aliases, String currency, String billingUnit,
                                 BigDecimal inputAmountPer1k, BigDecimal outputAmountPer1k,
                                 String sourceType, String sourceRef, BigDecimal sourceConfidence,
                                 OffsetDateTime sourceUpdatedAt, OffsetDateTime effectiveFrom,
                                 OffsetDateTime effectiveTo, String status) {}

    @GetMapping
    public ApiResponse<List<Map<String,Object>>> list(@RequestParam(required=false) String providerType,
                                                       @RequestParam(required=false) String status) {
        return ApiResponse.ok(jdbc.queryForList("""
            select c.*,
              (select count(*) from channel_model_deployment d
               join provider_instance p on p.id=d.provider_instance_id
               where lower(p.provider_type)=lower(c.provider_type)
                 and (lower(d.provider_model_name)=lower(c.provider_model_name) or exists (
                   select 1 from jsonb_array_elements_text(c.aliases) a
                   where lower(a)=lower(d.provider_model_name)))) matched_deployments
            from provider_model_price_catalog c
            where (?::text is null or lower(c.provider_type)=lower(?))
              and (?::text is null or c.status=?)
            order by c.provider_type,c.provider_model_name,c.revision desc
            """, providerType, providerType, status, status));
    }

    @PostMapping
    @Transactional
    public ApiResponse<Map<String,Object>> create(@RequestBody CatalogRequest request, Authentication authentication) {
        validate(request);
        String id = id();
        String actor = actor(authentication);
        String state = value(request.status(), "ACTIVE");
        if ("ACTIVE".equals(state)) retireActive(request.providerType(), request.providerModelName(), actor);
        Integer revision = jdbc.queryForObject("""
            select coalesce(max(revision),0)+1 from provider_model_price_catalog
            where lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?)
            """, Integer.class, request.providerType(), request.providerModelName());
        jdbc.update("""
            insert into provider_model_price_catalog(
              id,provider_type,provider_model_name,display_name,aliases,currency,billing_unit,
              input_amount_per_1k,output_amount_per_1k,source_type,source_ref,source_confidence,
              source_updated_at,effective_from,effective_to,revision,status,created_by,updated_by)
            values(?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, request.providerType().trim(), request.providerModelName().trim(), request.displayName(),
                write(normalizeAliases(request.aliases())), request.currency().toUpperCase(Locale.ROOT),
                value(request.billingUnit(), "PER_1K_TOKENS"), request.inputAmountPer1k(), request.outputAmountPer1k(),
                request.sourceType(), request.sourceRef(), request.sourceConfidence(), request.sourceUpdatedAt(),
                request.effectiveFrom()==null?OffsetDateTime.now():request.effectiveFrom(), request.effectiveTo(),
                revision==null?1:revision, state, actor, actor);
        Map<String,Object> created = one(id);
        audits.record("PROVIDER_PRICE_CATALOG_CREATE", "ProviderModelPriceCatalog", id, null, created);
        ProviderPriceCatalogService.RematchSummary summary = matcher.rematchCatalog(id);
        Map<String,Object> response = new LinkedHashMap<>(created);
        response.put("rematchSummary", summary);
        return ApiResponse.ok(response);
    }

    @PatchMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> revise(@PathVariable("id") String id, @RequestBody CatalogRequest request,
                                                   Authentication authentication) {
        Map<String,Object> before = one(id);
        CatalogRequest merged = merge(before, request);
        validate(merged);
        String actor = actor(authentication);
        jdbc.update("update provider_model_price_catalog set status='INACTIVE',updated_by=?,updated_at=now() where id=?", actor, id);
        ApiResponse<Map<String,Object>> created = create(merged, authentication);
        audits.record("PROVIDER_PRICE_CATALOG_REVISE", "ProviderModelPriceCatalog", id, before, created.data());
        return created;
    }

    @PostMapping("/{id}/rematch")
    public ApiResponse<ProviderPriceCatalogService.RematchSummary> rematch(@PathVariable("id") String id) {
        require(id);
        return ApiResponse.ok(matcher.rematchCatalog(id));
    }

    private CatalogRequest merge(Map<String,Object> before, CatalogRequest request) {
        return new CatalogRequest(
                choose(request.providerType(), before.get("provider_type")),
                choose(request.providerModelName(), before.get("provider_model_name")),
                choose(request.displayName(), before.get("display_name")),
                request.aliases()==null?readAliases(before.get("aliases")):request.aliases(),
                choose(request.currency(), before.get("currency")),
                choose(request.billingUnit(), before.get("billing_unit")),
                request.inputAmountPer1k()==null?decimal(before.get("input_amount_per_1k")):request.inputAmountPer1k(),
                request.outputAmountPer1k()==null?decimal(before.get("output_amount_per_1k")):request.outputAmountPer1k(),
                choose(request.sourceType(), before.get("source_type")),
                choose(request.sourceRef(), before.get("source_ref")),
                request.sourceConfidence()==null?decimalNullable(before.get("source_confidence")):request.sourceConfidence(),
                request.sourceUpdatedAt()==null?time(before.get("source_updated_at")):request.sourceUpdatedAt(),
                request.effectiveFrom()==null?OffsetDateTime.now():request.effectiveFrom(),
                request.effectiveTo()==null?time(before.get("effective_to")):request.effectiveTo(),
                choose(request.status(), before.get("status")));
    }

    private void validate(CatalogRequest request) {
        if (request==null || blank(request.providerType()) || blank(request.providerModelName())
                || blank(request.currency()) || request.currency().length()!=3
                || request.inputAmountPer1k()==null || request.outputAmountPer1k()==null
                || request.inputAmountPer1k().signum()<0 || request.outputAmountPer1k().signum()<0
                || !Set.of("OFFICIAL_REFERENCE","PROVIDER_API","MANUAL_VERIFIED").contains(request.sourceType())
                || blank(request.sourceRef())
                || !Set.of("ACTIVE","INACTIVE").contains(value(request.status(),"ACTIVE"))) {
            bad("供应商、模型、币种、非负价格和来源依据不能为空");
        }
        if (request.sourceConfidence()!=null && (request.sourceConfidence().signum()<0
                || request.sourceConfidence().compareTo(BigDecimal.ONE)>0)) bad("来源可信度必须在0到1之间");
        if (request.effectiveTo()!=null && request.effectiveFrom()!=null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) bad("失效时间必须晚于生效时间");
    }

    private void retireActive(String providerType,String modelName,String actor) {
        jdbc.update("""
            update provider_model_price_catalog set status='INACTIVE',updated_by=?,updated_at=now()
            where lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?) and status='ACTIVE'
            """, actor, providerType, modelName);
    }

    private Map<String,Object> one(String id) {
        List<Map<String,Object>> rows=jdbc.queryForList("select * from provider_model_price_catalog where id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"官方价格目录记录不存在");
        return rows.get(0);
    }
    private void require(String id){one(id);}
    private List<String> readAliases(Object value){try{return json.readValue(String.valueOf(value),json.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){return List.of();}}
    private List<String> normalizeAliases(List<String> aliases){if(aliases==null)return List.of();return aliases.stream().filter(Objects::nonNull).map(String::trim).filter(v->!v.isBlank()).distinct().toList();}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static OffsetDateTime time(Object value){if(value==null)return null;if(value instanceof OffsetDateTime t)return t;return OffsetDateTime.parse(String.valueOf(value));}
    private static BigDecimal decimal(Object value){return new BigDecimal(String.valueOf(value));}
    private static BigDecimal decimalNullable(Object value){return value==null?null:decimal(value);}
    private static String choose(String supplied,Object fallback){return supplied==null?fallback==null?null:String.valueOf(fallback):supplied;}
    private static String value(String supplied,String fallback){return blank(supplied)?fallback:supplied;}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String actor(Authentication authentication){return authentication!=null&&authentication.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM";}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
