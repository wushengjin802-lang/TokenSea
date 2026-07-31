package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import com.tokensea.governance.pricing.connector.PriceSourceConnectorDefinition;
import com.tokensea.governance.pricing.connector.PriceSourceConnectorRegistry;
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
    private final PriceSourceConnectorRegistry connectors;

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderPriceSyncController(JdbcTemplate jdbc, ObjectMapper json,
                                       ProviderPriceSyncService sync, AuditService audits,
                                       PriceSourceConnectorRegistry connectors) {
        this.jdbc = jdbc;
        this.json = json;
        this.sync = sync;
        this.audits = audits;
        this.connectors = connectors;
    }

    public ProviderPriceSyncController(JdbcTemplate jdbc, ObjectMapper json,
                                       ProviderPriceSyncService sync, AuditService audits) {
        this.jdbc = jdbc;
        this.json = json;
        this.sync = sync;
        this.audits = audits;
        this.connectors = null;
    }

    public record PriceSourceRequest(
            String name,
            String sourceClass,
            String adapterCode,
            String providerType,
            String providerInstanceId,
            String credentialRef,
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
            String parserVersion,
            String fetchMode,
            Integer sourcePriority,
            String priceNature,
            String connectorCode,
            String dataScope,
            String trustLevel,
            String publishPolicy,
            String schemaVersion,
            String credentialPurpose,
            String mappingProfile,
            String documentType,
            String extractionMode,
            BigDecimal minimumConfidence,
            Boolean requireManualReview,
            Integer maxDocumentPages,
            Integer maxDocumentBytes,
            String llmModel
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
    public ApiResponse<List<Map<String,Object>>> sources(@RequestParam(required=false) String status) {
        return ApiResponse.ok(jdbc.queryForList("""
            select s.*,
              (select count(*) from provider_price_sync_run r where r.price_source_id=s.id) sync_run_count,
              (select count(*) from provider_price_diff d where d.price_source_id=s.id and d.status='PENDING') pending_diff_count,
              (select r.parse_status from provider_price_sync_run r where r.price_source_id=s.id
                order by r.created_at desc limit 1) parse_status,
              (select r.diagnostic_snapshot from provider_price_sync_run r where r.price_source_id=s.id
                order by r.created_at desc limit 1) source_evidence
            from provider_price_source s
            where (?::text is null and s.status<>'DISABLED')
               or (?::text is not null and s.status=?)
            order by s.created_at desc
            """, status, status, status));
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
              id,name,source_class,adapter_code,provider_type,provider_instance_id,credential_ref,auth_mode,endpoint,official_hosts,
              region,default_currency,schedule_expression,auto_publish,max_auto_change_ratio,confirmation_runs,config,
              status,next_run_at,parser_version,fetch_mode,source_priority,price_nature,
              connector_code,data_scope,trust_level,publish_policy,schema_version,credential_purpose,mapping_profile,
              document_type,extraction_mode,minimum_confidence,require_manual_review,max_document_pages,
              max_document_bytes,llm_model,created_by,updated_by)
            values(?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,cast(? as jsonb),
              ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, value.name(), value.sourceClass(), value.adapterCode(), value.providerType(),
                value.providerInstanceId(), value.credentialRef(), value.authMode(), value.endpoint(), write(value.officialHosts()),
                value.region(), value.defaultCurrency(), value.scheduleExpression(),
                value.autoPublish(), value.maxAutoChangeRatio(), value.confirmationRuns(), write(value.config()),
                value.status(), nextRun, value.parserVersion(), value.fetchMode(), value.sourcePriority(),
                value.priceNature(), value.connectorCode(), value.dataScope(), value.trustLevel(),
                value.publishPolicy(), value.schemaVersion(), value.credentialPurpose(), value.mappingProfile(),
                value.documentType(), value.extractionMode(), value.minimumConfidence(), value.requireManualReview(),
                value.maxDocumentPages(), value.maxDocumentBytes(), value.llmModel(), actor, actor);
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
        requireUserManaged(before);
        PriceSourceRequest value = normalize(request, before);
        validate(value);
        String actor = actor(authentication);
        OffsetDateTime nextRun = "ACTIVE".equals(value.status())
                ? before.get("next_run_at") == null ? OffsetDateTime.now() : time(before.get("next_run_at"))
                : null;
        jdbc.update("""
            update provider_price_source set name=?,source_class=?,adapter_code=?,provider_type=?,
              provider_instance_id=?,credential_ref=?,auth_mode=?,endpoint=?,official_hosts=cast(? as jsonb),region=?,default_currency=?,
              schedule_expression=?,auto_publish=?,max_auto_change_ratio=?,confirmation_runs=?,config=cast(? as jsonb),
              status=?,next_run_at=?,parser_version=?,fetch_mode=?,source_priority=?,price_nature=?,
              connector_code=?,data_scope=?,trust_level=?,publish_policy=?,schema_version=?,
              credential_purpose=?,mapping_profile=?,document_type=?,extraction_mode=?,minimum_confidence=?,
              require_manual_review=?,max_document_pages=?,max_document_bytes=?,llm_model=?,
              updated_by=?,updated_at=now() where id=?
            """, value.name(), value.sourceClass(), value.adapterCode(), value.providerType(),
                value.providerInstanceId(), value.credentialRef(), value.authMode(), value.endpoint(), write(value.officialHosts()),
                value.region(), value.defaultCurrency(), value.scheduleExpression(),
                value.autoPublish(), value.maxAutoChangeRatio(), value.confirmationRuns(), write(value.config()),
                value.status(), nextRun, value.parserVersion(), value.fetchMode(), value.sourcePriority(),
                value.priceNature(), value.connectorCode(), value.dataScope(), value.trustLevel(),
                value.publishPolicy(), value.schemaVersion(), value.credentialPurpose(), value.mappingProfile(),
                value.documentType(), value.extractionMode(), value.minimumConfidence(), value.requireManualReview(),
                value.maxDocumentPages(), value.maxDocumentBytes(), value.llmModel(), actor, id);
        Map<String,Object> after = one("provider_price_source", id, "价格来源不存在");
        audits.record("PROVIDER_PRICE_SOURCE_UPDATE", "ProviderPriceSource", id, before, after);
        return ApiResponse.ok(after);
    }

    @PostMapping("/provider-price-sources/{id}/test")
    public ApiResponse<ProviderPriceSyncService.FetchPreview> testSource(@PathVariable("id") String id) {
        return ApiResponse.ok(sync.preview(id));
    }

    @PostMapping("/provider-price-sources/{id}/test-parse")
    public ApiResponse<ProviderPriceSyncService.FetchPreview> testParse(@PathVariable("id") String id) {
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
        requireUserManaged(before);
        requirePricingCredential(before);
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
        requireUserManaged(before);
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
    public ApiResponse<PageResult<Map<String,Object>>> diffs(@RequestParam(required=false) String status,
                                                               @RequestParam(required=false) String riskLevel,
                                                               @RequestParam(required=false) String sourceId,
                                                               @RequestParam(required=false) Integer page,
                                                               @RequestParam(required=false) Integer size,
                                                               @RequestParam(required=false) String keyword,
                                                               @RequestParam(required=false) String sort,
                                                               @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.ofEntries(
                Map.entry("id", "d.id"),
                Map.entry("sourceName", "s.name"),
                Map.entry("providerType", "d.provider_type"),
                Map.entry("providerModelName", "d.provider_model_name"),
                Map.entry("region", "d.region"),
                Map.entry("requestMode", "d.request_mode"),
                Map.entry("serviceTier", "d.service_tier"),
                Map.entry("contextTier", "d.context_tier"),
                Map.entry("diffType", "d.diff_type"),
                Map.entry("changeRatio", "d.change_ratio"),
                Map.entry("riskLevel", "d.risk_level"),
                Map.entry("confirmationProgress", "d.confirmation_count"),
                Map.entry("status", "d.status"),
                Map.entry("decidedByName", "decided_by_name"),
                Map.entry("decidedAt", "d.decided_at"),
                Map.entry("createdAt", "d.created_at")
        ), "createdAt", "desc");
        String normalizedStatus = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String normalizedRisk = blank(riskLevel) ? null : riskLevel.trim().toUpperCase(Locale.ROOT);
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String filter = """
            where (?::text is null or d.status=?) and (?::text is null or d.risk_level=?)
              and (?::text is null or d.price_source_id=?)
              and (?::text is null or lower(concat_ws(' ',s.name,d.provider_type,d.provider_model_name,
                    d.region,d.request_mode,d.service_tier,d.context_tier,d.diff_type,d.risk_level,d.status)) like ?)
            """;
        Object[] filters = {normalizedStatus, normalizedStatus, normalizedRisk, normalizedRisk,
                sourceId, sourceId, q, q};
        String projection = """
            select d.*,s.name source_name,s.adapter_code,s.confirmation_runs confirmation_required,
              concat(d.confirmation_count,'/',s.confirmation_runs) confirmation_progress,
              case
                when d.decided_by is null then null
                when d.decided_by='SYSTEM' then chr(31995)||chr(32479)
                when u.display_name is null
                  or btrim(u.display_name)=''
                  or translate(btrim(u.display_name),chr(63)||chr(65311)||chr(65533),'')=''
                  then coalesce(u.username,d.decided_by)
                else btrim(u.display_name)
              end decided_by_name
            from provider_price_diff d
            join provider_price_source s on s.id=d.price_source_id
            left join user_account u on u.id=d.decided_by
            """;
        List<Map<String,Object>> rows = jdbc.queryForList(
                projection + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                        + ("d.id".equals(paging.sortColumn()) ? "" : ", d.id " + paging.direction())
                        + " limit ? offset ?",
                append(filters, paging.size(), paging.offset()));
        Long total = jdbc.queryForObject("""
            select count(*) from provider_price_diff d
            join provider_price_source s on s.id=d.price_source_id
            left join user_account u on u.id=d.decided_by
            """ + filter, Long.class, filters);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/provider-price-diffs/{id}")
    public ApiResponse<Map<String,Object>> diff(@PathVariable("id") String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select d.*,s.name source_name,s.adapter_code,s.confirmation_runs confirmation_required,
              concat(d.confirmation_count,'/',s.confirmation_runs) confirmation_progress,
              case
                when d.decided_by is null then null
                when d.decided_by='SYSTEM' then chr(31995)||chr(32479)
                when u.display_name is null
                  or btrim(u.display_name)=''
                  or translate(btrim(u.display_name),chr(63)||chr(65311)||chr(65533),'')=''
                  then coalesce(u.username,d.decided_by)
                else btrim(u.display_name)
              end decided_by_name
            from provider_price_diff d
            join provider_price_source s on s.id=d.price_source_id
            left join user_account u on u.id=d.decided_by
            where d.id=?
            """, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "价格差异不存在");
        return ApiResponse.ok(rows.get(0));
    }

    @PostMapping("/provider-price-diffs/{id}/approve")
    public ApiResponse<Map<String,Object>> approveDiff(@PathVariable("id") String id,
                                                        @RequestBody(required=false) DiffDecisionRequest request,
                                                        Authentication authentication) {
        requirePlatformAdmin(authentication);
        return ApiResponse.ok(sync.approveDiff(id, actor(authentication), request == null ? null : request.reason()));
    }

    @PostMapping("/provider-price-diffs/{id}/reject")
    public ApiResponse<Map<String,Object>> rejectDiff(@PathVariable("id") String id,
                                                       @RequestBody(required=false) DiffDecisionRequest request,
                                                       Authentication authentication) {
        requirePlatformAdmin(authentication);
        return ApiResponse.ok(sync.rejectDiff(id, actor(authentication), request == null ? null : request.reason()));
    }

    @PostMapping("/provider-price-diffs/{id}/revoke")
    public ApiResponse<Map<String,Object>> revokeDiff(@PathVariable("id") String id,
                                                       @RequestBody(required=false) DiffDecisionRequest request,
                                                       Authentication authentication) {
        requirePlatformAdmin(authentication);
        return ApiResponse.ok(sync.revokeDiff(id, actor(authentication), request == null ? null : request.reason()));
    }

    private PriceSourceRequest normalize(PriceSourceRequest request, Map<String,Object> before) {
        if (request == null) request = new PriceSourceRequest(
                null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        String sourceClass = choose(request.sourceClass(), before, "source_class", "PUBLIC_REFERENCE");
        String adapter = choose(request.adapterCode(), before, "adapter_code", "LITELLM_COST_MAP");
        String endpoint = choose(request.endpoint(), before, "endpoint", "");
        String connectorCode = choose(request.connectorCode(), before, "connector_code", connectorFor(adapter));
        PriceSourceConnectorDefinition connector = connectors == null ? null : connectors.find(connectorCode).orElse(null);
        List<String> hosts = request.officialHosts() != null ? normalizeHosts(request.officialHosts())
                : before == null ? endpointHost(endpoint) : readStrings(before.get("official_hosts"));
        return new PriceSourceRequest(
                choose(request.name(), before, "name", ""), sourceClass, adapter,
                chooseNullable(request.providerType(), before, "provider_type"),
                chooseNullable(request.providerInstanceId(), before, "provider_instance_id"),
                chooseNullable(request.credentialRef(), before, "credential_ref"),
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
                choose(request.parserVersion(), before, "parser_version", "1.0.0"),
                choose(request.fetchMode(), before, "fetch_mode", "AUTO"),
                request.sourcePriority() != null ? request.sourcePriority()
                        : before == null ? 100 : ((Number) before.get("source_priority")).intValue(),
                choose(request.priceNature(), before, "price_nature", "ORIGINAL"),
                connectorCode,
                choose(request.dataScope(), before, "data_scope", connector == null ? defaultDataScope(sourceClass) : connector.dataScope()),
                choose(request.trustLevel(), before, "trust_level", connector == null ? defaultTrustLevel(sourceClass) : connector.trustLevel()),
                choose(request.publishPolicy(), before, "publish_policy",
                        sourceClass.equals("PUBLIC_REFERENCE") ? "MANUAL_ONLY" : Boolean.TRUE.equals(request.autoPublish()) ? "AUTO_LOW_RISK" : "MANUAL_ONLY"),
                choose(request.schemaVersion(), before, "schema_version", "price-record-v1"),
                choose(request.credentialPurpose(), before, "credential_purpose",
                        "PROVIDER_INSTANCE".equals(choose(request.authMode(), before, "auth_mode", "NONE")) ? "PRICING_READ" : "NONE"),
                chooseNullable(request.mappingProfile(), before, "mapping_profile"),
                choose(request.documentType(), before, "document_type", "AUTO").toUpperCase(Locale.ROOT),
                choose(request.extractionMode(), before, "extraction_mode",
                        "GENERIC_DOCUMENT".equals(adapter) && Boolean.TRUE.equals(readBoolean(request.config(), before, "llmEnabled"))
                                ? "DETERMINISTIC_LLM" : "GENERIC_DOCUMENT".equals(adapter) ? "DETERMINISTIC" : "SPECIALIZED"),
                request.minimumConfidence() != null ? request.minimumConfidence()
                        : before == null ? new BigDecimal("0.85000") : decimal(before.get("minimum_confidence")),
                request.requireManualReview() != null ? request.requireManualReview()
                        : before != null && Boolean.TRUE.equals(before.get("require_manual_review")),
                request.maxDocumentPages() != null ? request.maxDocumentPages()
                        : before == null ? 200 : ((Number) before.get("max_document_pages")).intValue(),
                request.maxDocumentBytes() != null ? request.maxDocumentBytes()
                        : before == null ? 20_000_000 : ((Number) before.get("max_document_bytes")).intValue(),
                chooseNullable(request.llmModel(), before, "llm_model"));
    }

    private void validate(PriceSourceRequest request) {
        if (blank(request.name()) || blank(request.endpoint()) || request.officialHosts().isEmpty()) bad("价格源名称、地址和官方域名不能为空");
        if (!Set.of("PUBLIC_REFERENCE","OFFICIAL").contains(request.sourceClass())) bad("价格来源类别无效");
        if (connectors != null) {
            PriceSourceConnectorDefinition connector;
            try {
                connector = connectors.require(request.connectorCode());
            } catch (IllegalArgumentException exception) {
                bad(exception.getMessage());
                return;
            }
            if (!connector.supportedAdapterCodes().contains(request.adapterCode())) {
                bad("所选连接器不支持当前价格适配器");
            }
        }
        if (!Set.of("PUBLIC_CATALOG","ACCOUNT_PRICING","REFERENCE_DATASET","DOCUMENT").contains(request.dataScope()))
            bad("价格数据范围无效");
        if (!Set.of("OFFICIAL_PUBLIC","OFFICIAL_ACCOUNT","COMMUNITY_REFERENCE").contains(request.trustLevel()))
            bad("价格来源可信等级无效");
        if (!Set.of("AUTO_LOW_RISK","MANUAL_ONLY").contains(request.publishPolicy())) bad("价格发布策略无效");
        if (!Set.of("NONE","PRICING_READ").contains(request.credentialPurpose())) bad("价格凭据用途无效");
        if (blank(request.schemaVersion())) bad("价格 Schema 版本不能为空");
        if (!Set.of("AUTO","HTML","JSON","CSV","PDF","TEXT","BINARY").contains(request.documentType()))
            bad("价格文档类型无效");
        if (!Set.of("DETERMINISTIC","DETERMINISTIC_LLM","SPECIALIZED").contains(request.extractionMode()))
            bad("价格文档提取模式无效");
        if (request.minimumConfidence().signum() < 0 || request.minimumConfidence().compareTo(BigDecimal.ONE) > 0)
            bad("最低置信度必须在 0 到 1 之间");
        if (request.maxDocumentPages() < 1 || request.maxDocumentPages() > 500)
            bad("价格文档最大页数必须在 1 到 500 之间");
        if (request.maxDocumentBytes() < 100_000 || request.maxDocumentBytes() > 50_000_000)
            bad("价格文档最大字节数必须在 100000 到 50000000 之间");
        if ("PUBLIC_REFERENCE".equals(request.sourceClass())
                && !("REFERENCE_DATASET".equals(request.dataScope())
                && "COMMUNITY_REFERENCE".equals(request.trustLevel())
                && "MANUAL_ONLY".equals(request.publishPolicy())
                && "NONE".equals(request.credentialPurpose()))) {
            bad("公共参考源必须使用参考数据范围、社区参考可信等级、人工发布策略且不使用凭据");
        }
        if ("OFFICIAL".equals(request.sourceClass()) && "COMMUNITY_REFERENCE".equals(request.trustLevel()))
            bad("供应商官方价格不能标记为社区参考来源");
        if ("MANUAL_ONLY".equals(request.publishPolicy()) && request.autoPublish())
            bad("人工发布策略不能同时开启低风险自动发布");
        if (!Set.of("LITELLM_COST_MAP","MODELS_DEV","AZURE_RETAIL_PRICES","AWS_PRICE_LIST_BULK",
                "GOOGLE_CLOUD_CATALOG","GENERIC_DOCUMENT","DEEPSEEK_OFFICIAL_PAGE","QWEN_OFFICIAL_PAGE",
                "KIMI_OFFICIAL_PAGE","XIAOMI_MIMO_OFFICIAL_PAGE","ZHIPU_OFFICIAL_PAGE",
                "OFFICIAL_JSON","OFFICIAL_CSV").contains(request.adapterCode())) bad("价格适配器无效");
        if (Set.of("LITELLM_COST_MAP","MODELS_DEV").contains(request.adapterCode()) && !"PUBLIC_REFERENCE".equals(request.sourceClass()))
            bad("LiteLLM 与 models.dev 只能作为公共参考来源");
        if (Set.of("AZURE_RETAIL_PRICES","AWS_PRICE_LIST_BULK","GOOGLE_CLOUD_CATALOG","GENERIC_DOCUMENT",
                "DEEPSEEK_OFFICIAL_PAGE","QWEN_OFFICIAL_PAGE","KIMI_OFFICIAL_PAGE",
                "XIAOMI_MIMO_OFFICIAL_PAGE","ZHIPU_OFFICIAL_PAGE","OFFICIAL_JSON","OFFICIAL_CSV")
                .contains(request.adapterCode()) && !"OFFICIAL".equals(request.sourceClass()))
            bad("云价格目录、通用文档或供应商专用适配器必须用于供应商官方价格来源");
        if ("DEEPSEEK_OFFICIAL_PAGE".equals(request.adapterCode()) && !"deepseek".equalsIgnoreCase(request.providerType()))
            bad("DeepSeek 官方价格页适配器只能绑定 DeepSeek 供应商");
        if ("QWEN_OFFICIAL_PAGE".equals(request.adapterCode()) && !"qwen".equalsIgnoreCase(request.providerType()))
            bad("千问官方价格页适配器只能绑定 Qwen 供应商");
        if ("KIMI_OFFICIAL_PAGE".equals(request.adapterCode()) && !"moonshot".equalsIgnoreCase(request.providerType()))
            bad("Kimi 官方价格页适配器只能绑定 Moonshot 供应商");
        if ("XIAOMI_MIMO_OFFICIAL_PAGE".equals(request.adapterCode()) && !"xiaomi_mimo".equalsIgnoreCase(request.providerType()))
            bad("Xiaomi MiMo 官方价格页适配器只能绑定 Xiaomi MiMo 供应商");
        if ("ZHIPU_OFFICIAL_PAGE".equals(request.adapterCode()) && !"zhipu".equalsIgnoreCase(request.providerType()))
            bad("智谱官方价格页适配器只能绑定 Zhipu 供应商");
        if (blank(request.providerType())) bad("价格源必须指定供应商类型");
        if (!Set.of("NONE","PROVIDER_INSTANCE").contains(request.authMode())) bad("价格源认证方式无效");
        if ("NONE".equals(request.authMode()) && (!blank(request.providerInstanceId()) || !blank(request.credentialRef())))
            bad("无认证价格源不能绑定供应商渠道或价格凭据");
        if ("PROVIDER_INSTANCE".equals(request.authMode()) && blank(request.providerInstanceId())) bad("渠道凭据认证必须绑定供应商渠道");
        if ("PUBLIC_REFERENCE".equals(request.sourceClass()) && !"NONE".equals(request.authMode())) bad("公共参考价格源不能使用供应商渠道凭据");
        if ("PROVIDER_INSTANCE".equals(request.authMode()) && !"PRICING_READ".equals(request.credentialPurpose()))
            bad("渠道凭据认证的价格源必须标记为 PRICING_READ");
        if ("NONE".equals(request.authMode()) && !"NONE".equals(request.credentialPurpose()))
            bad("无需认证的价格源不能声明凭据用途");
        Object authHeader = request.config().get("authHeader");
        if (authHeader != null && !Set.of("x-goog-api-key", "api-key", "x-api-key")
                .contains(String.valueOf(authHeader).toLowerCase(Locale.ROOT))) {
            bad("价格源自定义认证头仅允许 x-goog-api-key、api-key 或 x-api-key");
        }
        if (!blank(request.providerInstanceId())) {
            if (blank(request.providerType())) bad("绑定供应商渠道前必须指定供应商类型");
            List<Map<String,Object>> channels = jdbc.queryForList("select provider_type from provider_instance where id=?", request.providerInstanceId());
            if (channels.isEmpty()) bad("绑定的供应商渠道不存在");
            if (!request.providerType().equalsIgnoreCase(String.valueOf(channels.get(0).get("provider_type")))) bad("价格源供应商与绑定渠道不一致");
            if (!blank(request.credentialRef())) {
                List<Map<String,Object>> secrets = jdbc.queryForList("""
                    select id from provider_secret
                    where id=? and provider_instance_id=? and secret_purpose='PRICING_READ' and status='ACTIVE'
                    """, stripSecretPrefix(request.credentialRef()), request.providerInstanceId());
                if (secrets.isEmpty()) bad("所选价格凭据不存在、未启用、用途不正确或不属于当前供应商渠道");
            }
            if ("ACTIVE".equals(request.status()) && blank(request.credentialRef()))
                bad("启用认证价格源前必须选择独立的 PRICING_READ 凭据");
        }
        if ("PUBLIC_REFERENCE".equals(request.sourceClass()) && request.autoPublish()) bad("公共参考价格不能自动发布为生产价格");
        if (!request.defaultCurrency().matches("^[A-Z]{3}$")) bad("币种必须是三位大写代码");
        if (request.maxAutoChangeRatio().signum() < 0 || request.maxAutoChangeRatio().compareTo(new BigDecimal("10")) > 0)
            bad("自动发布价格变化比例必须在0到10之间");
        if (request.confirmationRuns() < 1 || request.confirmationRuns() > 10) bad("连续确认次数必须在1到10之间");
        if (!Set.of("AUTO","STRUCTURED_HTTP","STATIC_HTML","HEADLESS").contains(request.fetchMode())) bad("价格获取模式无效");
        if (request.sourcePriority() < 1 || request.sourcePriority() > 10000) bad("来源优先级必须在1到10000之间");
        if (!Set.of("ORIGINAL","PROMOTIONAL","FREE_QUOTA").contains(request.priceNature())) bad("价格性质无效");
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
        if (Set.of("AZURE_RETAIL_PRICES","AWS_PRICE_LIST_BULK","GOOGLE_CLOUD_CATALOG")
                .contains(request.adapterCode())
                && !request.config().containsKey("modelPattern")
                && !(request.config().get("modelMappings") instanceof Map<?,?> mappings && !mappings.isEmpty())) {
            bad("云价格目录适配器必须配置 modelPattern 或 modelMappings，避免把非模型 SKU 误识别为价格");
        }
        if ("GENERIC_DOCUMENT".equals(request.adapterCode())) {
            boolean llmEnabled = Boolean.TRUE.equals(request.config().get("llmEnabled"));
            boolean deterministic = request.config().containsKey("modelField")
                    && (request.config().containsKey("inputField") || request.config().containsKey("outputField")
                    || request.config().containsKey("cacheReadField") || request.config().containsKey("cacheWriteField"))
                    || request.config().containsKey("linePattern");
            if (!llmEnabled && !deterministic) {
                bad("通用文档适配器必须配置字段映射、linePattern，或显式启用 llmEnabled");
            }
            if ("DETERMINISTIC_LLM".equals(request.extractionMode()) && !llmEnabled)
                bad("确定性 + LLM 提取模式必须显式启用 llmEnabled");
            if (llmEnabled && !("MANUAL_ONLY".equals(request.publishPolicy())
                    && !request.autoPublish() && request.requireManualReview())) {
                bad("启用 LLM Schema 映射时必须使用人工发布策略、关闭自动发布并强制人工审核");
            }
        }
    }

    private void requireUserManaged(Map<String,Object> source) {
        if ("SYSTEM".equals(String.valueOf(source.get("managed_by")))) {
            conflict("系统内置参考价格源由平台自动维护，仅允许查看状态或手工重试");
        }
    }

    private void requirePricingCredential(Map<String,Object> source) {
        if (!"PROVIDER_INSTANCE".equals(String.valueOf(source.get("auth_mode")))) return;
        String reference = source.get("credential_ref") == null ? "" : String.valueOf(source.get("credential_ref"));
        if (blank(reference)) conflict("启用认证价格源前必须配置独立的 PRICING_READ 凭据");
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select id from provider_secret
            where id=? and provider_instance_id=? and secret_purpose='PRICING_READ' and status='ACTIVE'
            """, stripSecretPrefix(reference), source.get("provider_instance_id"));
        if (rows.isEmpty()) conflict("价格凭据不存在、未启用、用途不正确或不属于当前供应商渠道");
    }

    private Map<String,Object> one(String table, String id, String message) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from " + table + " where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return rows.get(0);
    }

    private Boolean readBoolean(Map<String,Object> suppliedConfig,
                                Map<String,Object> before,
                                String key) {
        if (suppliedConfig != null && suppliedConfig.containsKey(key)) {
            Object value = suppliedConfig.get(key);
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
        }
        if (before != null) {
            Object value = readMap(before.get("config")).get(key);
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
        }
        return false;
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

    private static String stripSecretPrefix(String reference) {
        return reference != null && reference.startsWith("secret:") ? reference.substring(7) : reference;
    }

    private static String chooseNullable(String supplied, Map<String,Object> before, String key) {
        if (supplied != null) return supplied.isBlank() ? null : supplied;
        return before != null && before.get(key) != null ? String.valueOf(before.get(key)) : null;
    }

    private String connectorFor(String adapterCode) {
        return connectors == null ? fallbackConnector(adapterCode) : connectors.connectorForAdapter(adapterCode);
    }

    private static String fallbackConnector(String adapterCode) {
        return Set.of("AZURE_RETAIL_PRICES","AWS_PRICE_LIST_BULK","GOOGLE_CLOUD_CATALOG",
                        "LITELLM_COST_MAP","MODELS_DEV").contains(adapterCode)
                ? adapterCode : "HTTP_DOCUMENT";
    }

    private static String defaultDataScope(String sourceClass) {
        return "PUBLIC_REFERENCE".equals(sourceClass) ? "REFERENCE_DATASET" : "DOCUMENT";
    }

    private static String defaultTrustLevel(String sourceClass) {
        return "PUBLIC_REFERENCE".equals(sourceClass) ? "COMMUNITY_REFERENCE" : "OFFICIAL_PUBLIC";
    }

    private static OffsetDateTime time(Object value) {
        if (value instanceof OffsetDateTime time) return time;
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) { return new BigDecimal(String.valueOf(value)); }
    private static Object[] append(Object[] values, Object... extra) {
        Object[] result = new Object[values.length + extra.length];
        System.arraycopy(values, 0, result, 0, values.length);
        System.arraycopy(extra, 0, result, values.length, extra.length);
        return result;
    }
    private void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private void requirePlatformAdmin(Authentication authentication) {
        if (!(authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前登录会话无效");
        }
        Boolean admin = jdbc.queryForObject("""
            select exists(
              select 1 from user_role ur join role r on r.id=ur.role_id
              where ur.user_id=? and r.code='ADMIN'
            )
            """, Boolean.class, identity.userId());
        if (!Boolean.TRUE.equals(admin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不是平台管理员，不能审核价格差异");
        }
    }
    private static String actor(Authentication authentication) { return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity ? identity.userId() : "SYSTEM"; }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
