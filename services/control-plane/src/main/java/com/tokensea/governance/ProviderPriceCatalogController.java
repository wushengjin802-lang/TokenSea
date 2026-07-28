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
    private final PricingComponentService pricingComponents;

    public ProviderPriceCatalogController(JdbcTemplate jdbc, ObjectMapper json, AuditService audits,
                                          ProviderPriceCatalogService matcher,
                                          PricingComponentService pricingComponents) {
        this.jdbc = jdbc;
        this.json = json;
        this.audits = audits;
        this.matcher = matcher;
        this.pricingComponents = pricingComponents;
    }

    public record CatalogRequest(String providerType, String providerModelName, String displayName,
                                 List<String> aliases, String currency, String billingBasis,
                                 Long billingQuantity, BigDecimal inputUnitPrice,
                                 BigDecimal cacheReadUnitPrice, String cacheReadMode,
                                 BigDecimal cacheWriteUnitPrice, String cacheWriteMode,
                                 BigDecimal outputUnitPrice,
                                 List<PricingComponentService.ComponentInput> priceComponents,
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
                   where lower(a)=lower(d.provider_model_name)))) matched_deployments,
              (select count(*) from provider_price_component pc
               where pc.catalog_price_id=c.id and pc.component_type='CACHE_WRITE_TOKEN') cache_write_variant_count
            from provider_model_price_catalog c
            where (?::text is null or lower(c.provider_type)=lower(?))
              and (?::text is null or c.status=?)
            order by c.provider_type,c.provider_model_name,c.revision desc
            """, providerType, providerType, status, status));
    }

    @GetMapping("/{id}/components")
    public ApiResponse<List<Map<String,Object>>> components(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(jdbc.queryForList("""
            select id,catalog_price_id "catalogPriceId",component_type "componentType",variant,
              unit_price "unitPrice",unit_basis "unitBasis",unit_quantity "unitQuantity",
              component_mode "mode",priority,scope,source_ref "sourceRef",metadata,created_at "createdAt"
            from provider_price_component
            where catalog_price_id=?
            order by priority,component_type,variant,scope_hash
            """, id));
    }

    @PostMapping
    @Transactional
    public ApiResponse<Map<String,Object>> create(@RequestBody CatalogRequest request, Authentication authentication) {
        validateIdentity(request);
        List<Map<String,Object>> components = pricingComponents.normalize(
                request.inputUnitPrice(), request.cacheReadUnitPrice(), request.cacheWriteUnitPrice(),
                request.outputUnitPrice(), request.cacheReadMode(), request.cacheWriteMode(),
                request.billingBasis(), request.billingQuantity(), request.priceComponents(), request.sourceRef());
        PricingComponentService.Summary summary = pricingComponents.summarize(
                components, request.inputUnitPrice(), request.outputUnitPrice());
        validateActivation(request, summary);

        String id = id();
        String actor = actor(authentication);
        String state = value(request.status(), "ACTIVE");
        List<String> aliases = normalizeAliases(request.aliases());
        if ("ACTIVE".equals(state)) retireActive(request.providerType(), request.providerModelName(), actor);
        Integer revision = jdbc.queryForObject("""
            select coalesce(max(revision),0)+1 from provider_model_price_catalog
            where lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?)
            """, Integer.class, request.providerType(), request.providerModelName());
        String componentJson = pricingComponents.writeComponents(components);
        String normalizedJson = write(normalizedPrice(request, summary, components));
        jdbc.update("""
            insert into provider_model_price_catalog(
              id,provider_type,provider_model_name,display_name,aliases,currency,billing_basis,billing_quantity,
              input_unit_price,cache_read_unit_price,cache_read_mode,cache_write_unit_price,cache_write_mode,
              output_unit_price,price_components,component_schema_version,price_completeness_status,cache_pricing_status,
              source_type,source_ref,source_confidence,source_updated_at,effective_from,effective_to,revision,status,
              created_by,updated_by,normalized_price)
            values(?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?,?,?,cast(? as jsonb),2,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
            """, id, request.providerType().trim(), request.providerModelName().trim(), displayName(request, aliases),
                write(aliases), request.currency().toUpperCase(Locale.ROOT),
                value(request.billingBasis(), "TOKEN"), request.billingQuantity()==null?1_000_000L:request.billingQuantity(),
                summary.inputUncachedUnitPrice(), summary.cacheReadUnitPrice(), summary.cacheReadMode(),
                summary.cacheWriteUnitPrice(), summary.cacheWriteMode(), summary.outputUnitPrice(), componentJson,
                summary.priceCompletenessStatus(), summary.cachePricingStatus(), request.sourceType(), request.sourceRef(),
                request.sourceConfidence(), request.sourceUpdatedAt(),
                request.effectiveFrom()==null?OffsetDateTime.now():request.effectiveFrom(), request.effectiveTo(),
                revision==null?1:revision, state, actor, actor, normalizedJson);
        saveComponents(id, components);
        Map<String,Object> created = one(id);
        audits.record("PROVIDER_PRICE_CATALOG_CREATE", "ProviderModelPriceCatalog", id, null, created);
        ProviderPriceCatalogService.RematchSummary rematch = matcher.rematchCatalog(id);
        Map<String,Object> response = new LinkedHashMap<>(created);
        response.put("rematchSummary", rematch);
        response.put("priceComponents", components);
        return ApiResponse.ok(response);
    }

    @PatchMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String,Object>> revise(@PathVariable String id, @RequestBody CatalogRequest request,
                                                   Authentication authentication) {
        Map<String,Object> before = one(id);
        CatalogRequest merged = merge(before, request);
        String actor = actor(authentication);
        jdbc.update("update provider_model_price_catalog set status='INACTIVE',updated_by=?,updated_at=now() where id=?", actor, id);
        ApiResponse<Map<String,Object>> created = create(merged, authentication);
        audits.record("PROVIDER_PRICE_CATALOG_REVISE", "ProviderModelPriceCatalog", id, before, created.data());
        return created;
    }

    @PostMapping("/{id}/rematch")
    public ApiResponse<ProviderPriceCatalogService.RematchSummary> rematch(@PathVariable String id) {
        require(id);
        return ApiResponse.ok(matcher.rematchCatalog(id));
    }

    private CatalogRequest merge(Map<String,Object> before, CatalogRequest request) {
        List<String> aliases = request.aliases()==null ? readAliases(before.get("aliases")) : request.aliases();
        List<PricingComponentService.ComponentInput> advanced = request.priceComponents()==null
                ? advancedInputs(pricingComponents.readComponents(before.get("price_components")))
                : request.priceComponents();
        return new CatalogRequest(
                choose(request.providerType(), before.get("provider_type")),
                choose(request.providerModelName(), before.get("provider_model_name")),
                request.aliases()==null ? choose(request.displayName(), before.get("display_name"))
                        : displayName(request, normalizeAliases(aliases)),
                aliases,
                choose(request.currency(), before.get("currency")),
                choose(request.billingBasis(), before.get("billing_basis")),
                request.billingQuantity()==null?longValue(before.get("billing_quantity")):request.billingQuantity(),
                request.inputUnitPrice()==null?decimal(before.get("input_unit_price")):request.inputUnitPrice(),
                request.cacheReadUnitPrice()==null?decimalNullable(before.get("cache_read_unit_price")):request.cacheReadUnitPrice(),
                choose(request.cacheReadMode(), before.get("cache_read_mode")),
                request.cacheWriteUnitPrice()==null?decimalNullable(before.get("cache_write_unit_price")):request.cacheWriteUnitPrice(),
                choose(request.cacheWriteMode(), before.get("cache_write_mode")),
                request.outputUnitPrice()==null?decimal(before.get("output_unit_price")):request.outputUnitPrice(),
                advanced,
                choose(request.sourceType(), before.get("source_type")),
                choose(request.sourceRef(), before.get("source_ref")),
                request.sourceConfidence()==null?decimalNullable(before.get("source_confidence")):request.sourceConfidence(),
                request.sourceUpdatedAt()==null?time(before.get("source_updated_at")):request.sourceUpdatedAt(),
                request.effectiveFrom()==null?OffsetDateTime.now():request.effectiveFrom(),
                request.effectiveTo()==null?time(before.get("effective_to")):request.effectiveTo(),
                choose(request.status(), before.get("status")));
    }

    private List<PricingComponentService.ComponentInput> advancedInputs(List<Map<String,Object>> components) {
        List<PricingComponentService.ComponentInput> result = new ArrayList<>();
        for (Map<String,Object> item : components) {
            String type = String.valueOf(item.get("componentType"));
            String variant = value(String.valueOf(item.getOrDefault("variant", "DEFAULT")), "DEFAULT");
            Map<String,Object> scope = map(item.get("scope"));
            if (Set.of("INPUT_TOKEN","CACHE_READ_TOKEN","CACHE_WRITE_TOKEN","OUTPUT_TOKEN").contains(type)
                    && "DEFAULT".equals(variant) && scope.isEmpty()) continue;
            result.add(new PricingComponentService.ComponentInput(
                    type, variant, decimalNullable(item.get("unitPrice")), String.valueOf(item.get("unitBasis")),
                    longValue(item.get("unitQuantity")), String.valueOf(item.get("mode")), scope,
                    intValue(item.get("priority"),100), nullable(item.get("sourceRef")), map(item.get("metadata"))));
        }
        return result;
    }

    private void validateIdentity(CatalogRequest request) {
        if (request==null || blank(request.providerType()) || blank(request.providerModelName())
                || blank(request.currency()) || request.currency().length()!=3
                || !Set.of("TOKEN","REQUEST","IMAGE","SECOND","MINUTE","CHARACTER","AUDIO_MINUTE")
                    .contains(value(request.billingBasis(), "TOKEN"))
                || request.billingQuantity()==null || request.billingQuantity()<=0
                || request.inputUnitPrice()==null || request.outputUnitPrice()==null
                || request.inputUnitPrice().signum()<0 || request.outputUnitPrice().signum()<0
                || !Set.of("OFFICIAL_REFERENCE","PROVIDER_API","MANUAL_VERIFIED").contains(request.sourceType())
                || blank(request.sourceRef())
                || !Set.of("ACTIVE","INACTIVE").contains(value(request.status(),"ACTIVE"))) {
            bad("供应商、模型、币种、缓存未命中输入价、输出价和来源依据不能为空");
        }
        if (request.sourceConfidence()!=null && (request.sourceConfidence().signum()<0
                || request.sourceConfidence().compareTo(BigDecimal.ONE)>0)) bad("来源可信度必须在0到1之间");
        if (request.effectiveTo()!=null && request.effectiveFrom()!=null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) bad("失效时间必须晚于生效时间");
    }

    private void validateActivation(CatalogRequest request, PricingComponentService.Summary summary) {
        if (!"ACTIVE".equals(value(request.status(), "ACTIVE"))) return;
        if ("PARTIAL".equals(summary.priceCompletenessStatus())) {
            bad("价格组件不完整，不能激活；请补充缓存价格模式或将缓存明确标记为不适用");
        }
    }

    private void saveComponents(String catalogId, List<Map<String,Object>> components) {
        for (Map<String,Object> component : components) {
            Map<String,Object> scope = map(component.get("scope"));
            jdbc.update("""
                insert into provider_price_component(
                  id,catalog_price_id,component_type,variant,unit_price,unit_basis,unit_quantity,component_mode,
                  priority,scope,scope_hash,source_ref,metadata)
                values(?,?,?,?,?,?,?, ?,?,cast(? as jsonb),?,?,cast(? as jsonb))
                """, id(), catalogId, component.get("componentType"), component.get("variant"),
                    component.get("unitPrice"), component.get("unitBasis"), component.get("unitQuantity"),
                    component.get("mode"), component.get("priority"), write(scope), pricingComponents.scopeHash(scope),
                    component.get("sourceRef"), write(map(component.get("metadata"))));
        }
    }

    private Map<String,Object> normalizedPrice(CatalogRequest request,
                                               PricingComponentService.Summary summary,
                                               List<Map<String,Object>> components) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("providerType", request.providerType());
        result.put("providerModelName", request.providerModelName());
        result.put("displayName", request.displayName());
        result.put("currency", request.currency());
        result.put("billingBasis", value(request.billingBasis(), "TOKEN"));
        result.put("billingQuantity", request.billingQuantity());
        result.put("inputUnitPrice", summary.inputUncachedUnitPrice());
        result.put("cacheReadUnitPrice", summary.cacheReadUnitPrice());
        result.put("cacheReadMode", summary.cacheReadMode());
        result.put("cacheWriteUnitPrice", summary.cacheWriteUnitPrice());
        result.put("cacheWriteMode", summary.cacheWriteMode());
        result.put("outputUnitPrice", summary.outputUnitPrice());
        result.put("componentSchemaVersion", PricingComponentService.SCHEMA_VERSION);
        result.put("priceCompletenessStatus", summary.priceCompletenessStatus());
        result.put("cachePricingStatus", summary.cachePricingStatus());
        result.put("components", components);
        result.put("sourceRef", request.sourceRef());
        result.put("effectiveFrom", request.effectiveFrom());
        result.put("effectiveTo", request.effectiveTo());
        return result;
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

    private String displayName(CatalogRequest request, List<String> aliases) {
        if (!blank(request.displayName())) return request.displayName().trim();
        if (!aliases.isEmpty()) return aliases.getFirst();
        return request.providerModelName().trim();
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> map(Object value){if(value instanceof Map<?,?> m){Map<String,Object> result=new LinkedHashMap<>();m.forEach((k,v)->result.put(String.valueOf(k),v));return result;}return Map.of();}
    private static OffsetDateTime time(Object value){if(value==null)return null;if(value instanceof OffsetDateTime t)return t;return OffsetDateTime.parse(String.valueOf(value));}
    private static BigDecimal decimal(Object value){return new BigDecimal(String.valueOf(value));}
    private static BigDecimal decimalNullable(Object value){return value==null||String.valueOf(value).isBlank()?null:decimal(value);}
    private static long longValue(Object value){return value==null?1_000_000L:Long.parseLong(String.valueOf(value));}
    private static int intValue(Object value,int fallback){return value==null?fallback:Integer.parseInt(String.valueOf(value));}
    private static String choose(String supplied,Object fallback){return supplied==null?fallback==null?null:String.valueOf(fallback):supplied;}
    private static String nullable(Object value){return value==null?null:String.valueOf(value);}
    private static String value(String supplied,String fallback){return blank(supplied)?fallback:supplied;}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String actor(Authentication authentication){return authentication!=null&&authentication.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM";}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
