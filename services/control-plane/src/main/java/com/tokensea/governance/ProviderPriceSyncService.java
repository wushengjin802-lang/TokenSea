package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import com.tokensea.governance.pricing.adapter.KimiOfficialPriceAdapter;
import com.tokensea.governance.pricing.adapter.PriceSourceAdapterContext;
import com.tokensea.governance.pricing.adapter.PriceSourceDocument;
import com.tokensea.governance.pricing.adapter.PriceSourceParseResult;
import com.tokensea.governance.pricing.mapping.PriceSourceMappingService;
import com.tokensea.governance.pricing.extractor.PriceDocumentExtractionService;
import com.tokensea.governance.pricing.reference.ReferenceModelMatcher;
import com.tokensea.provider.service.ManagedPurposeCredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

@Service
public class ProviderPriceSyncService {
    private static final int MAX_RESPONSE_BYTES = 5_000_000;
    private static final int MAX_CONFIGURED_RESPONSE_BYTES = 50_000_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PriceSourceParser parser;
    private final ProviderPriceCatalogService matcher;
    private final AuditService audits;
    private final ProviderConnectionService providerConnections;
    private final PricingComponentService pricingComponents;
    private final ManagedPurposeCredentialService purposeCredentials;
    private final PriceSourceMappingService mappings;
    private final PriceDocumentExtractionService extractions;
    private final TransactionTemplate transactions;
    private final HttpClient http;
    private final HttpClient internalHttp;
    private final Set<String> globalAllowedHosts;
    private final boolean proxyConfigured;
    private final URI headlessFetcherUri;
    private final String headlessFetcherToken;
    private final String owner = UUID.randomUUID().toString();

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderPriceSyncService(JdbcTemplate jdbc, ObjectMapper json, PriceSourceParser parser,
                                    ProviderPriceCatalogService matcher, AuditService audits,
                                    ProviderConnectionService providerConnections,
                                    PricingComponentService pricingComponents,
                                    ManagedPurposeCredentialService purposeCredentials,
                                    PriceSourceMappingService mappings,
                                    PriceDocumentExtractionService extractions,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${tokensea.egress.proxy-host:}") String proxyHost,
                                    @Value("${tokensea.egress.proxy-port:18080}") int proxyPort,
                                    @Value("${tokensea.egress.allowed-hosts:}") String allowedHosts,
                                    @Value("${tokensea.headless-fetcher.url:}") String headlessFetcherUrl,
                                    @Value("${tokensea.headless-fetcher.token:}") String headlessFetcherToken) {
        this.jdbc = jdbc;
        this.json = json;
        this.parser = parser;
        this.matcher = matcher;
        this.audits = audits;
        this.providerConnections = providerConnections;
        this.pricingComponents = pricingComponents;
        this.purposeCredentials = purposeCredentials;
        this.mappings = mappings;
        this.extractions = extractions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.globalAllowedHosts = parseHosts(allowedHosts);
        this.proxyConfigured = proxyHost != null && !proxyHost.isBlank();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxyConfigured) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.http = builder.build();
        this.internalHttp = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.headlessFetcherUri = blank(headlessFetcherUrl)
                ? null
                : URI.create(headlessFetcherUrl.replaceAll("/+$", "") + "/render");
        this.headlessFetcherToken = value(headlessFetcherToken, "");
    }

    ProviderPriceSyncService(JdbcTemplate jdbc, ObjectMapper json, PriceSourceParser parser,
                             ProviderPriceCatalogService matcher, AuditService audits,
                             ProviderConnectionService providerConnections,
                             PricingComponentService pricingComponents,
                             PlatformTransactionManager transactionManager,
                             String proxyHost, int proxyPort, String allowedHosts) {
        this(jdbc, json, parser, matcher, audits, providerConnections, pricingComponents,
                null, null, null, transactionManager, proxyHost, proxyPort, allowedHosts, "", "");
    }

    public record FetchPreview(int httpStatus, String contentType, String checksum, int responseBytes,
                               int recordsNormalized, String parseStatus, int tableCount,
                               int matchedTableCount, int skippedTableCount,
                               String structureFingerprint, Map<String,Object> sourceEvidence,
                               List<String> warnings, boolean headlessRecommended,
                               List<PriceSourceParseResult.OfficialSubPage> discoveredPricePages,
                               List<Map<String,Object>> sample) {}
    public record SyncSummary(String runId, String status, int fetched, int normalized, int changed,
                              int autoPublished, int reviewRequired, String snapshotId) {}
    private record ParseDiagnostics(String status, int tableCount, int matchedTableCount,
                                    int generatedPriceCount, Map<String,Object> snapshot) {
        private static ParseDiagnostics empty() {
            return new ParseDiagnostics("NOT_PARSED", 0, 0, 0, Map.of());
        }
    }

    @Scheduled(fixedDelayString = "${tokensea.price-sync.poll-ms:15000}")
    public void poll() {
        enqueueScheduled();
        claimAndExecute();
    }

    public String enqueue(String sourceId, String triggerType) {
        requireSource(sourceId);
        String runId = id();
        int inserted = jdbc.update("""
            insert into provider_price_sync_run(id,price_source_id,trigger_type,status,scheduled_for)
            select ?,?,?,'PENDING',now()
            where not exists(select 1 from provider_price_sync_run where price_source_id=? and status in ('PENDING','RUNNING'))
            on conflict do nothing
            """, runId, sourceId, triggerType == null ? "MANUAL" : triggerType, sourceId);
        if (inserted == 1) return runId;
        return jdbc.queryForObject("""
            select id from provider_price_sync_run where price_source_id=? and status in ('PENDING','RUNNING')
            order by created_at limit 1
            """, String.class, sourceId);
    }

    public String enqueueScheduledNow(String sourceId) {
        Map<String,Object> source = requireSource(sourceId);
        String runId = enqueue(sourceId, "SCHEDULED");
        jdbc.update("update provider_price_source set next_run_at=?,updated_at=now() where id=?",
                nextRun(text(source.get("schedule_expression")), isSystemReference(source)), sourceId);
        return runId;
    }

    public FetchPreview preview(String sourceId) {
        Map<String,Object> source = requireSource(sourceId);
        try {
            FetchResult fetched = fetch(source, false);
            if (fetched.statusCode() == 304) return new FetchPreview(304, fetched.contentType(),
                    text(source.get("last_content_hash")), 0, 0, "NOT_PARSED_UNCHANGED", 0, 0, 0,
                    text(source.get("structure_fingerprint")), Map.of(), List.of(), false, List.of(), List.of());
            PriceSourceParseResult parsed = parseDetailed(source, fetched);
            if (shouldFallbackToHeadless(source, parsed)) {
                fetched = fetchHeadless(source);
                parsed = parseDetailed(source, fetched);
            }
            List<Map<String,Object>> sample = parsed.prices().stream().limit(10).map(this::normalizedMap).toList();
            return new FetchPreview(fetched.statusCode(), fetched.contentType(), fetched.checksum(),
                    fetched.bytes(), parsed.prices().size(), parsed.parseStatus(), parsed.tableCount(),
                    parsed.matchedTableCount(), parsed.skippedTableCount(), parsed.structureFingerprint(),
                    parsed.sourceEvidence(), parsed.warnings(), parsed.headlessRecommended(),
                    parsed.discoveredPricePages(), sample);
        } catch (Exception e) {
            String message = safe(e.getMessage());
            if (message.contains("Tunnel failed, got: 403")) {
                message = "出口代理拒绝目标域名，请确认官方域名已保存并属于当前价格源";
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        }
    }

    @Transactional
    public Map<String,Object> submitExtractionRun(String extractionRunId, String actor, String reason) {
        if (extractions == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "价格文档抽取服务未启用");
        Map<String,Object> extraction = extractions.requireRun(extractionRunId);
        if (!"REVIEW_REQUIRED".equals(text(extraction.get("status")))) {
            conflict("仅待审核的价格文档抽取运行可以提交");
        }
        int pending = extractions.pendingCount(extractionRunId);
        if (pending > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "仍有 " + pending + " 条抽取记录待审核，全部处理后才能提交");
        }
        List<PriceSourceParser.NormalizedPrice> accepted = extractions.reviewedPrices(extractionRunId);
        if (accepted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有已接受或已修正的抽取记录可提交");
        }
        String sourceId = text(extraction.get("source_id"));
        String syncRunId = text(extraction.get("sync_run_id"));
        String snapshotId = text(extraction.get("raw_snapshot_id"));
        Map<String,Object> source = requireSource(sourceId);
        boolean publicReference = "PUBLIC_REFERENCE".equals(text(source.get("source_class")));
        ProcessResult processed = publicReference
                ? processReferences(source, syncRunId, snapshotId, text(extraction.get("snapshot_checksum")), accepted)
                : processOfficial(source, syncRunId, snapshotId, text(extraction.get("snapshot_checksum")), accepted,
                        false, text(source.get("structure_fingerprint")));
        if (publicReference) refreshReferencePriceBindings();
        extractions.linkDiffEvidence(syncRunId, extractionRunId);
        extractions.markSubmitted(extractionRunId);
        String state = processed.reviewRequired() > 0 ? "REVIEW_REQUIRED" : "SUCCEEDED";
        jdbc.update("""
            update provider_price_sync_run set status=?,records_changed=records_changed+?,
              records_auto_published=records_auto_published+?,records_review_required=?,updated_at=now()
            where id=?
            """, state, processed.changed(), processed.autoPublished(), processed.reviewRequired(), syncRunId);
        audits.record("PRICE_DOCUMENT_EXTRACTION_SUBMITTED", "PriceDocumentExtractionRun", extractionRunId,
                extraction, Map.of("actor", actor, "reason", value(reason, ""), "syncRunId", syncRunId,
                        "accepted", accepted.size(), "changed", processed.changed(),
                        "autoPublished", processed.autoPublished(), "reviewRequired", processed.reviewRequired()));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("extractionRunId", extractionRunId);
        result.put("syncRunId", syncRunId);
        result.put("accepted", accepted.size());
        result.put("changed", processed.changed());
        result.put("autoPublished", processed.autoPublished());
        result.put("reviewRequired", processed.reviewRequired());
        result.put("status", state);
        return result;
    }

    @Transactional
    public Map<String,Object> approveDiff(String diffId, String actor, String reason) {
        Map<String,Object> diff = require("provider_price_diff", diffId, "价格差异不存在");
        if (!"PENDING".equals(text(diff.get("status")))) conflict("仅待审核价格差异可批准");
        String diffType = text(diff.get("diff_type"));
        if (Set.of("MODEL_REMOVED","SOURCE_STRUCTURE_CHANGED","SOURCE_CONFLICT").contains(diffType)) {
            jdbc.update("update provider_price_diff set status='APPROVED',decision_reason=?,decided_by=?,decided_at=now(),updated_at=now() where id=?",
                    reason, actor, diffId);
            audits.record("PROVIDER_PRICE_GOVERNANCE_ACKNOWLEDGED", "ProviderPriceDiff", diffId, diff,
                    Map.of("reason", value(reason, ""), "actor", actor, "diffType", diffType));
            return require("provider_price_diff", diffId, "价格差异不存在");
        }
        PriceSourceParser.NormalizedPrice price = priceFromJson(diff.get("new_value"));
        String catalogId = publish(diff, price, "MANUAL", actor);
        jdbc.update("""
            update provider_price_diff set status='APPROVED',decision_reason=?,decided_by=?,decided_at=now(),
              published_catalog_id=?,updated_at=now()
            where status='PENDING' and price_source_id=? and provider_type=? and provider_model_name=?
              and region=? and request_mode=? and service_tier=? and context_tier=?
              and new_value=cast(? as jsonb)
            """, reason, actor, catalogId, diff.get("price_source_id"), diff.get("provider_type"),
                diff.get("provider_model_name"), diff.get("region"), diff.get("request_mode"),
                diff.get("service_tier"), diff.get("context_tier"), String.valueOf(diff.get("new_value")));
        Map<String,Object> after = require("provider_price_diff", diffId, "价格差异不存在");
        audits.record("PROVIDER_PRICE_DIFF_APPROVE", "ProviderPriceDiff", diffId, diff, after);
        return after;
    }

    @Transactional
    public Map<String,Object> rejectDiff(String diffId, String actor, String reason) {
        Map<String,Object> before = require("provider_price_diff", diffId, "价格差异不存在");
        if (!"PENDING".equals(text(before.get("status")))) conflict("仅待审核价格差异可驳回");
        jdbc.update("update provider_price_diff set status='REJECTED',decision_reason=?,decided_by=?,decided_at=now(),updated_at=now() where id=?",
                reason, actor, diffId);
        Map<String,Object> after = require("provider_price_diff", diffId, "价格差异不存在");
        audits.record("PROVIDER_PRICE_DIFF_REJECT", "ProviderPriceDiff", diffId, before, after);
        return after;
    }

    @Transactional
    public Map<String,Object> revokeDiff(String diffId, String actor, String reason) {
        Map<String,Object> before = require("provider_price_diff", diffId, "价格差异不存在");
        String currentStatus = text(before.get("status"));
        if (!Set.of("APPROVED", "AUTO_PUBLISHED").contains(currentStatus)) {
            conflict("仅已批准或已自动发布的价格差异可撤销");
        }
        String catalogId = text(before.get("published_catalog_id"));
        if (blank(catalogId)) conflict("该价格差异未关联已发布官方价格目录，无法撤销");
        Map<String,Object> catalogBefore = require("provider_model_price_catalog", catalogId, "已发布官方价格目录不存在");
        List<String> deploymentIds = jdbc.queryForList("""
            select distinct deployment_id from price_version
            where catalog_price_id=? and deployment_id is not null
            """, String.class, catalogId);
        jdbc.update("""
            update price_version set status='RETIRED',effective_to=coalesce(effective_to,now()),updated_at=now()
            where catalog_price_id=? and status='ACTIVE'
            """, catalogId);
        jdbc.update("""
            update provider_model_price_catalog set status='INACTIVE',effective_to=coalesce(effective_to,now()),
              updated_by=?,updated_at=now() where id=? and status='ACTIVE'
            """, actor, catalogId);
        jdbc.update("""
            update provider_price_diff set status='REVOKED',decision_reason=?,decided_by=?,decided_at=now(),updated_at=now()
            where id=?
            """, value(reason, "管理员撤销误发布价格"), actor, diffId);
        for (String deploymentId : deploymentIds) matcher.refreshDeploymentPriceStatus(deploymentId);
        Map<String,Object> after = require("provider_price_diff", diffId, "价格差异不存在");
        Map<String,Object> catalogAfter = require("provider_model_price_catalog", catalogId, "已发布官方价格目录不存在");
        audits.record("PROVIDER_PRICE_DIFF_REVOKE", "ProviderPriceDiff", diffId, before,
                Map.of("diff", after, "catalogBefore", catalogBefore, "catalogAfter", catalogAfter,
                        "affectedDeployments", deploymentIds));
        return after;
    }

    public SyncSummary executeNow(String runId) {
        return execute(runId);
    }

    private void enqueueScheduled() {
        List<Map<String,Object>> due = jdbc.queryForList("""
            select id,schedule_expression from provider_price_source s
            where status in ('ACTIVE','DEGRADED') and next_run_at is not null and next_run_at<=now()
              and not exists(select 1 from provider_price_sync_run r where r.price_source_id=s.id and r.status in ('PENDING','RUNNING'))
            order by next_run_at limit 20
            """);
        for (Map<String,Object> source : due) {
            enqueueScheduledNow(text(source.get("id")));
        }
    }

    private void claimAndExecute() {
        List<Map<String,Object>> pending = jdbc.queryForList("""
            select id from provider_price_sync_run where status='PENDING' and scheduled_for<=now()
            order by created_at limit 1
            """);
        if (pending.isEmpty()) return;
        String runId = text(pending.get(0).get("id"));
        int claimed = jdbc.update("""
            update provider_price_sync_run set status='RUNNING',started_at=now(),lock_owner=?,heartbeat_at=now(),updated_at=now()
            where id=? and status='PENDING'
            """, owner, runId);
        if (claimed == 1) execute(runId);
    }

    private SyncSummary execute(String runId) {
        Map<String,Object> run = require("provider_price_sync_run", runId, "价格同步任务不存在");
        if ("PENDING".equals(text(run.get("status")))) {
            int claimed = jdbc.update("update provider_price_sync_run set status='RUNNING',started_at=now(),lock_owner=?,heartbeat_at=now(),updated_at=now() where id=? and status='PENDING'",
                    owner, runId);
            if (claimed != 1) throw new IllegalStateException("价格同步任务无法认领");
        } else if (!"RUNNING".equals(text(run.get("status")))) {
            throw new IllegalStateException("价格同步任务状态不允许执行: " + run.get("status"));
        }
        Map<String,Object> source = requireSource(text(run.get("price_source_id")));
        List<Map<String,Object>> logs = new ArrayList<>();
        ParseDiagnostics parseDiagnostics = ParseDiagnostics.empty();
        logs.add(log("STARTED", Map.of("adapter", source.get("adapter_code"), "endpoint", source.get("endpoint"))));
        try {
            FetchResult fetched = fetch(source, true);
            boolean unchanged = fetched.statusCode() == 304
                    || (!fetched.checksum().isBlank() && fetched.checksum().equals(text(source.get("last_content_hash"))));
            String snapshotId;
            String sourceContent;
            String sourceChecksum;
            if (unchanged) {
                if (!needsConfirmation(source)) {
                    finishNoChange(runId, source, fetched, logs);
                    return new SyncSummary(runId, "NO_CHANGE", 0, 0, 0, 0, 0, null);
                }
                Map<String,Object> snapshot = latestSnapshot(text(source.get("id")));
                snapshotId = text(snapshot.get("id"));
                sourceContent = text(snapshot.get("raw_content"));
                sourceChecksum = text(snapshot.get("checksum"));
                logs.add(log("CONFIRMATION_REPLAY", Map.of("snapshotId", snapshotId)));
            } else {
                snapshotId = saveSnapshot(runId, source, fetched);
                sourceContent = fetched.content();
                sourceChecksum = fetched.checksum();
            }
            PriceSourceParseResult parsed = parseDetailed(source, sourceContent,
                    unchanged ? text(latestSnapshot(text(source.get("id"))).get("content_type")) : fetched.contentType(),
                    sourceChecksum, unchanged ? text(source.get("endpoint")) : fetched.finalEndpoint());
            parseDiagnostics = parseDiagnostics(parsed);
            logs.add(log("PARSE_DIAGNOSTICS", Map.of(
                    "parseStatus", parseDiagnostics.status(),
                    "tableCount", parseDiagnostics.tableCount(),
                    "matchedTableCount", parseDiagnostics.matchedTableCount(),
                    "generatedPriceCount", parseDiagnostics.generatedPriceCount(),
                    "diagnosticSnapshot", parseDiagnostics.snapshot()
            )));
            PriceDocumentExtractionService.PersistenceResult extraction = null;
            List<PriceSourceParser.NormalizedPrice> prices = parsed.prices();
            if (extractions != null && "GENERIC_DOCUMENT".equals(text(source.get("adapter_code")))) {
                extraction = extractions.persist(source, runId, snapshotId, parsed);
                prices = extraction.acceptedPrices();
                logs.add(log("DOCUMENT_EXTRACTION_SAVED", Map.of(
                        "extractionRunId", extraction.runId(),
                        "accepted", prices.size(),
                        "pendingReview", extraction.pendingReview(),
                        "rejected", extraction.rejected(),
                        "status", extraction.status())));
                if (prices.isEmpty() && extraction.pendingReview() > 0) {
                    finishExtractionReview(runId, source, fetched, sourceChecksum, parsed, parseDiagnostics,
                            extraction, logs);
                    return new SyncSummary(runId, "REVIEW_REQUIRED", parsed.prices().size(),
                            parsed.prices().size(), 0, 0, extraction.pendingReview(), snapshotId);
                }
            }
            if (prices.isEmpty()) throw new IllegalStateException("价格来源未解析出任何可进入价格差异流程的有效记录");
            StructureChange structure = updateStructureFingerprint(source, runId, snapshotId, parsed.structureFingerprint());
            int unmappedChanged = mappings == null ? 0
                    : mappings.persistUnmapped(text(source.get("id")), runId, snapshotId, parsed.sourceEvidence());
            int aliasesChanged = persistAliasCandidates(source, snapshotId, parsed.aliases());
            int candidatesChanged = upsertOfficialModelCandidates(source, snapshotId, parsed.prices());
            int childSourcesChanged = persistDiscoveredPriceSources(source, parsed.discoveredPricePages());
            if (!parsed.warnings().isEmpty()) logs.add(log("PARSE_WARNINGS", Map.of("warnings", parsed.warnings())));
            if (!parsed.discoveredPricePages().isEmpty()) logs.add(log("PRICING_PAGES_DISCOVERED",
                    Map.of("count", parsed.discoveredPricePages().size(), "pages", parsed.discoveredPricePages())));
            if (unmappedChanged > 0) logs.add(log("UNMAPPED_PRICE_RECORDS_SAVED", Map.of("count", unmappedChanged)));
            if (aliasesChanged > 0) logs.add(log("MODEL_ALIAS_CANDIDATES_SAVED", Map.of("count", aliasesChanged)));
            if (candidatesChanged > 0) logs.add(log("MODEL_DISCOVERY_CANDIDATES_SAVED", Map.of("count", candidatesChanged)));
            if (childSourcesChanged > 0) logs.add(log("PRICING_CHILD_SOURCES_SAVED", Map.of("count", childSourcesChanged)));
            boolean publicReference = "PUBLIC_REFERENCE".equals(text(source.get("source_class")));
            ProcessResult processed = publicReference
                    ? processReferences(source, runId, snapshotId, sourceChecksum, prices)
                    : processOfficial(source, runId, snapshotId, sourceChecksum, prices,
                            structure.changed(), structure.currentFingerprint());
            if (publicReference) {
                int activeBindings = refreshReferencePriceBindings();
                logs.add(log(activeBindings >= 0 ? "REFERENCE_BINDINGS_REFRESHED" : "REFERENCE_BINDINGS_REFRESH_DEFERRED",
                        activeBindings >= 0 ? Map.of("activeBindings", activeBindings)
                                : Map.of("message", "绑定刷新失败，后台定时任务将自动重试")));
            }
            if (extraction != null) extractions.linkDiffEvidence(runId, extraction);
            int extractionReview = extraction == null ? 0 : extraction.pendingReview();
            int totalReviewRequired = processed.reviewRequired() + extractionReview;
            String state = totalReviewRequired > 0 ? "REVIEW_REQUIRED" : "SUCCEEDED";
            logs.add(log("COMPLETED", Map.of("normalized", prices.size(), "changed", processed.changed(),
                    "autoPublished", processed.autoPublished(), "reviewRequired", totalReviewRequired)));
            jdbc.update("""
                update provider_price_sync_run set status=?,http_status=?,records_fetched=?,records_normalized=?,
                  records_changed=?,records_auto_published=?,records_review_required=?,parse_status=?,
                  parsed_table_count=?,matched_table_count=?,generated_price_count=?,diagnostic_snapshot=cast(? as jsonb),
                  completed_at=now(),execution_log=cast(? as jsonb),heartbeat_at=now(),updated_at=now() where id=?
                """, state, fetched.statusCode(), parsed.prices().size(), parsed.prices().size(), processed.changed(),
                    processed.autoPublished(), totalReviewRequired, parseDiagnostics.status(),
                    parseDiagnostics.tableCount(), parseDiagnostics.matchedTableCount(),
                    parseDiagnostics.generatedPriceCount(), write(parseDiagnostics.snapshot()), write(logs), runId);
            jdbc.update("""
                update provider_price_source set
                  status=case when status in ('PAUSED','DISABLED') then status else 'ACTIVE' end,
                  last_checked_at=now(),last_success_at=now(),last_good_sync_at=now(),last_error=null,
                  etag=?,last_modified=?,last_content_hash=?,updated_at=now() where id=?
                """, fetched.etag(), fetched.lastModified(), sourceChecksum, source.get("id"));
            audits.record("PROVIDER_PRICE_SYNC_COMPLETE", "ProviderPriceSyncRun", runId, null,
                    Map.of("status", state, "sourceId", source.get("id"), "snapshotId", snapshotId,
                            "changed", processed.changed(), "autoPublished", processed.autoPublished(),
                            "reviewRequired", totalReviewRequired));
            return new SyncSummary(runId, state, parsed.prices().size(), parsed.prices().size(), processed.changed(),
                    processed.autoPublished(), totalReviewRequired, snapshotId);
        } catch (Exception e) {
            logs.add(log("FAILED", Map.of("message", safe(e.getMessage()))));
            jdbc.update("""
                update provider_price_sync_run set status='FAILED',error_code='PRICE_SYNC_FAILED',error_message=?,
                  parse_status=?,parsed_table_count=?,matched_table_count=?,generated_price_count=?,
                  diagnostic_snapshot=cast(? as jsonb),completed_at=now(),execution_log=cast(? as jsonb),
                  heartbeat_at=now(),updated_at=now() where id=?
                """, safe(e.getMessage()), parseDiagnostics.status(), parseDiagnostics.tableCount(),
                    parseDiagnostics.matchedTableCount(), parseDiagnostics.generatedPriceCount(),
                    write(parseDiagnostics.snapshot()), write(logs), runId);
            OffsetDateTime retryAt = isSystemReference(source)
                    ? OffsetDateTime.now().plus(referenceRetryDelay(text(source.get("id"))))
                    : null;
            jdbc.update("""
                update provider_price_source set
                  status=case when status in ('PAUSED','DISABLED') then status else 'DEGRADED' end,
                  last_checked_at=now(),last_failure_at=now(),last_error=?,
                  next_run_at=case when ?::timestamptz is null then next_run_at else ? end,
                  updated_at=now() where id=?
                """, safe(e.getMessage()), retryAt, retryAt, source.get("id"));
            ensureSyncAlert(source, e);
            return new SyncSummary(runId, "FAILED", 0, 0, 0, 0, 0, null);
        }
    }

    private FetchResult fetch(Map<String,Object> source, boolean conditional) throws Exception {
        if ("HEADLESS".equalsIgnoreCase(text(source.get("fetch_mode")))) {
            return fetchHeadless(source);
        }
        URI initialUri = URI.create(text(source.get("endpoint")));
        String originalHost = initialUri.getHost();
        String adapter = text(source.get("adapter_code"));
        int responseLimit = responseLimit(source);
        ProviderInstance authenticatedInstance = authenticatedInstance(source);
        String managedApiKey = authenticatedInstance == null ? null : resolvePricingCredential(source, authenticatedInstance);
        HttpPage first = fetchHttpPage(source, initialUri, conditional, originalHost,
                authenticatedInstance, managedApiKey, responseLimit);
        if (first.statusCode() == 304) {
            return new FetchResult(304, "", "", 0, first.contentType(),
                    value(first.etag(), text(source.get("etag"))),
                    value(first.lastModified(), text(source.get("last_modified"))), first.finalUri().toString());
        }

        byte[] body = first.body();
        int pageCount = 1;
        if (Set.of("AZURE_RETAIL_PRICES", "GOOGLE_CLOUD_CATALOG").contains(adapter)) {
            AggregatedJson aggregated = aggregateCatalogPages(source, adapter, first, originalHost,
                    authenticatedInstance, managedApiKey, responseLimit);
            body = aggregated.body();
            pageCount = aggregated.pageCount();
        }
        if (body.length > responseLimit) throw new IllegalStateException("价格来源聚合响应超过配置上限");
        validateContentType(adapter, first.contentType());
        String content = first.contentType().toLowerCase(Locale.ROOT).contains("pdf")
                ? Base64.getEncoder().encodeToString(body)
                : snapshotContent(body);
        if (pageCount > 1 && !content.contains("\"_tokenseaPageCount\"")) {
            throw new IllegalStateException("结构化价格目录分页聚合结果缺少页数证据");
        }
        return new FetchResult(first.statusCode(), content, sha256(body), body.length,
                first.contentType(), first.etag(), first.lastModified(), first.finalUri().toString());
    }

    private HttpPage fetchHttpPage(Map<String,Object> source,
                                   URI requestedUri,
                                   boolean conditional,
                                   String originalHost,
                                   ProviderInstance authenticatedInstance,
                                   String managedApiKey,
                                   int responseLimit) throws Exception {
        URI uri = requestedUri;
        for (int redirect = 0; redirect <= 3; redirect++) {
            validateTarget(uri, source);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", accept(text(source.get("adapter_code"))))
                    .header("User-Agent", "TokenSea-PriceSync/1.0")
                    .GET();
            if (authenticatedInstance != null && uri.getHost().equalsIgnoreCase(originalHost)) {
                String authHeader = text(readMap(source.get("config")).get("authHeader"));
                if (blank(authHeader)) {
                    providerConnections.applyManagedAuthentication(builder, authenticatedInstance, managedApiKey);
                } else {
                    builder.header(authHeader, managedApiKey);
                }
            }
            if (conditional && redirect == 0 && source.get("etag") != null) {
                builder.header("If-None-Match", text(source.get("etag")));
            }
            if (conditional && redirect == 0 && source.get("last_modified") != null) {
                builder.header("If-Modified-Since", text(source.get("last_modified")));
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String etag = response.headers().firstValue("ETag").orElse(null);
            String lastModified = response.headers().firstValue("Last-Modified").orElse(null);
            if (response.statusCode() == 304) {
                return new HttpPage(304, new byte[0], contentType, etag, lastModified, uri);
            }
            if (Set.of(301,302,303,307,308).contains(response.statusCode())) {
                if (redirect == 3) throw new IllegalStateException("价格来源重定向次数超过限制");
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("价格来源重定向缺少 Location"));
                uri = uri.resolve(location);
                continue;
            }
            if (response.statusCode() != 200) throw new IllegalStateException("价格来源返回 HTTP " + response.statusCode());
            byte[] body = decodeBody(response.body(), response.headers().firstValue("Content-Encoding").orElse(""), responseLimit);
            if (body.length > responseLimit) throw new IllegalStateException("价格来源响应超过配置上限");
            return new HttpPage(response.statusCode(), body, contentType, etag, lastModified, uri);
        }
        throw new IllegalStateException("价格来源获取失败");
    }

    private AggregatedJson aggregateCatalogPages(Map<String,Object> source,
                                                  String adapter,
                                                  HttpPage first,
                                                  String originalHost,
                                                  ProviderInstance authenticatedInstance,
                                                  String managedApiKey,
                                                  int responseLimit) throws Exception {
        JsonNode root = json.readTree(first.body());
        String arrayField = "AZURE_RETAIL_PRICES".equals(adapter) ? "Items" : "skus";
        JsonNode initialItems = root.path(arrayField);
        if (!initialItems.isArray() && "AZURE_RETAIL_PRICES".equals(adapter)) {
            arrayField = "items";
            initialItems = root.path(arrayField);
        }
        if (!initialItems.isArray()) throw new IllegalStateException("价格目录响应缺少 " + arrayField + " 数组");
        com.fasterxml.jackson.databind.node.ArrayNode allItems = json.createArrayNode();
        allItems.addAll((com.fasterxml.jackson.databind.node.ArrayNode) initialItems);
        URI currentUri = first.finalUri();
        String next = nextCatalogPage(adapter, root);
        int pageCount = 1;
        int maxPages = maxPages(source);
        int rawBytes = first.body().length;
        while (!blank(next)) {
            if (pageCount >= maxPages) throw new IllegalStateException("价格目录分页超过 maxPages=" + maxPages);
            URI nextUri = "AZURE_RETAIL_PRICES".equals(adapter)
                    ? currentUri.resolve(next)
                    : withQueryParam(currentUri, "pageToken", next);
            HttpPage page = fetchHttpPage(source, nextUri, false, originalHost,
                    authenticatedInstance, managedApiKey, responseLimit);
            rawBytes += page.body().length;
            if (rawBytes > responseLimit) throw new IllegalStateException("价格目录分页累计响应超过配置上限");
            JsonNode pageRoot = json.readTree(page.body());
            JsonNode pageItems = pageRoot.path(arrayField);
            if (!pageItems.isArray()) throw new IllegalStateException("价格目录分页缺少 " + arrayField + " 数组");
            allItems.addAll((com.fasterxml.jackson.databind.node.ArrayNode) pageItems);
            next = nextCatalogPage(adapter, pageRoot);
            currentUri = page.finalUri();
            pageCount++;
        }
        com.fasterxml.jackson.databind.node.ObjectNode aggregate = root.deepCopy();
        aggregate.set(arrayField, allItems);
        aggregate.put("_tokenseaPageCount", pageCount);
        aggregate.remove(List.of("NextPageLink", "nextPageLink", "nextPageToken"));
        return new AggregatedJson(json.writeValueAsBytes(aggregate), pageCount);
    }

    private String nextCatalogPage(String adapter, JsonNode root) {
        if ("AZURE_RETAIL_PRICES".equals(adapter)) {
            String next = root.path("NextPageLink").asText("");
            return blank(next) ? root.path("nextPageLink").asText("") : next;
        }
        return root.path("nextPageToken").asText("");
    }

    private URI withQueryParam(URI uri, String name, String value) {
        try {
            Map<String,String> values = new LinkedHashMap<>();
            String query = uri.getRawQuery();
            if (query != null && !query.isBlank()) {
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String item = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    values.put(key, item);
                }
            }
            values.put(name, value);
            String nextQuery = values.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .reduce((left, right) -> left + "&" + right).orElse("");
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), nextQuery, uri.getFragment());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("价格目录分页地址无效", exception);
        }
    }

    private int responseLimit(Map<String,Object> source) {
        Map<String,Object> config = readMap(source.get("config"));
        int configured = integer(source.get("max_document_bytes"),
                integer(config.get("maxResponseBytes"), MAX_RESPONSE_BYTES));
        return Math.max(1_000_000, Math.min(configured, MAX_CONFIGURED_RESPONSE_BYTES));
    }

    private int maxPages(Map<String,Object> source) {
        Map<String,Object> config = readMap(source.get("config"));
        return Math.max(1, Math.min(integer(source.get("max_document_pages"),
                integer(config.get("maxPages"), 20)), 500));
    }

    private boolean shouldFallbackToHeadless(Map<String,Object> source, PriceSourceParseResult parsed) {
        return headlessFetcherUri != null
                && "AUTO".equalsIgnoreCase(value(text(source.get("fetch_mode")), "AUTO"))
                && parsed.prices().isEmpty()
                && parsed.headlessRecommended();
    }

    private FetchResult fetchHeadless(Map<String,Object> source) throws Exception {
        if (headlessFetcherUri == null || blank(headlessFetcherToken)) {
            throw new IllegalStateException("价格源要求 Headless 获取，但 Headless Fetcher 尚未配置");
        }
        URI target = URI.create(text(source.get("endpoint")));
        validateTarget(target, source);
        List<String> allowedHosts = readStrings(source.get("official_hosts"));
        if (allowedHosts.isEmpty()) throw new IllegalStateException("Headless 价格源未配置官方域名");
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("url", target.toString());
        payload.put("allowedHosts", allowedHosts);
        payload.put("timeoutMs", 30_000);
        payload.put("extraWaitMs", 1_500);
        payload.put("maxResponseBytes", MAX_RESPONSE_BYTES);
        HttpRequest request = HttpRequest.newBuilder(headlessFetcherUri)
                .timeout(Duration.ofSeconds(50))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-TokenSea-Headless-Token", headlessFetcherToken)
                .POST(HttpRequest.BodyPublishers.ofString(write(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = internalHttp.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > 12_000_000) {
            throw new IllegalStateException("Headless Fetcher 响应超过允许大小");
        }
        if (response.statusCode() != 200) {
            String detail = snapshotContent(response.body());
            throw new IllegalStateException("Headless Fetcher 返回 HTTP " + response.statusCode()
                    + (blank(detail) ? "" : ": " + safe(detail)));
        }
        Map<String,Object> rendered = json.readValue(snapshotContent(response.body()), new TypeReference<>() {});
        String html = text(rendered.get("html"));
        String finalUrl = value(text(rendered.get("finalUrl")), target.toString());
        String contentType = value(text(rendered.get("contentType")), "text/html; charset=utf-8");
        int statusCode = integer(rendered.get("statusCode"), 200);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        if (statusCode < 200 || statusCode >= 400) {
            throw new IllegalStateException("Headless 官方页面返回 HTTP " + statusCode);
        }
        if (bytes.length == 0) throw new IllegalStateException("Headless Fetcher 未返回渲染页面");
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("Headless 渲染页面超过 5MB 限制");
        validateTarget(URI.create(finalUrl), source);
        validateContentType(text(source.get("adapter_code")), contentType);
        return new FetchResult(statusCode, html.replace("\u0000", ""), sha256(bytes), bytes.length,
                contentType, null, null, finalUrl);
    }

    static byte[] decodeBody(byte[] body, String contentEncoding) throws IOException {
        return decodeBody(body, contentEncoding, MAX_RESPONSE_BYTES);
    }

    static byte[] decodeBody(byte[] body, String contentEncoding, int responseLimit) throws IOException {
        String encoding = contentEncoding == null ? "" : contentEncoding.trim().toLowerCase(Locale.ROOT);
        if (encoding.isBlank() || "identity".equals(encoding)) {
            if (body.length > responseLimit) throw new IllegalStateException("价格来源响应超过配置上限");
            return body;
        }
        InputStream input = switch (encoding) {
            case "gzip", "x-gzip" -> new GZIPInputStream(new ByteArrayInputStream(body));
            case "deflate" -> new InflaterInputStream(new ByteArrayInputStream(body));
            default -> throw new IllegalStateException("价格来源返回不支持的内容编码: " + contentEncoding);
        };
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                total += read;
                if (total > responseLimit) throw new IllegalStateException("价格来源响应超过配置上限");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static String snapshotContent(byte[] body) {
        return new String(body, StandardCharsets.UTF_8).replace("\u0000", "");
    }

    private PriceSourceParseResult parseDetailed(Map<String,Object> source, FetchResult fetched) {
        return parseDetailed(source, fetched.content(), fetched.contentType(), fetched.checksum(), fetched.finalEndpoint());
    }

    private PriceSourceParseResult parseDetailed(Map<String,Object> source, String content,
                                                  String contentType, String checksum, String endpoint) {
        Map<String,Object> config = readMap(source.get("config"));
        config.put("documentType", value(text(source.get("document_type")), "AUTO"));
        config.put("extractionMode", value(text(source.get("extraction_mode")), "DETERMINISTIC"));
        config.put("minimumConfidence", decimal(source.get("minimum_confidence"), new BigDecimal("0.85000")));
        config.put("requireManualReview", Boolean.TRUE.equals(source.get("require_manual_review")));
        config.put("maxPages", integer(source.get("max_document_pages"), integer(config.get("maxPages"), 200)));
        config.put("maxResponseBytes", integer(source.get("max_document_bytes"), integer(config.get("maxResponseBytes"), 20_000_000)));
        if (!blank(text(source.get("llm_model")))) config.put("llmModel", text(source.get("llm_model")));
        if (mappings != null) config = mappings.enrichConfig(text(source.get("id")), config);
        PriceSourceAdapterContext context = new PriceSourceAdapterContext(
                text(source.get("id")), text(source.get("adapter_code")), nullableText(source.get("provider_type")),
                value(endpoint, text(source.get("endpoint"))), value(text(source.get("region")), "global"),
                text(source.get("default_currency")), value(text(config.get("requestMode")), "STANDARD"),
                integer(source.get("source_priority"), 100), value(text(source.get("price_nature")), "ORIGINAL"),
                value(text(source.get("parser_version")), "1.0.0"), config);
        PriceSourceParseResult parsed = parser.parseDetailed(context,
                new PriceSourceDocument(content, context.endpoint(), contentType, checksum));
        if (parsed.prices().size() > 20_000) throw new IllegalStateException("单次价格同步最多允许 20000 条记录");
        return parsed;
    }

    private StructureChange updateStructureFingerprint(Map<String,Object> source, String runId,
                                                       String snapshotId, String currentFingerprint) {
        String sourceId = text(source.get("id"));
        String previous = text(source.get("structure_fingerprint"));
        if (blank(currentFingerprint)) return new StructureChange(false, previous, previous);
        if (blank(previous)) {
            jdbc.update("update provider_price_source set structure_fingerprint=?,updated_at=now() where id=?",
                    currentFingerprint, sourceId);
            return new StructureChange(false, null, currentFingerprint);
        }
        if (previous.equals(currentFingerprint)) return new StructureChange(false, previous, currentFingerprint);

        jdbc.update("""
            update provider_price_source set last_structure_fingerprint=structure_fingerprint,
              structure_fingerprint=?,structure_changed_at=now(),updated_at=now() where id=?
            """, currentFingerprint, sourceId);
        Integer existing = jdbc.queryForObject("""
            select count(*) from provider_price_diff where price_source_id=?
              and diff_type='SOURCE_STRUCTURE_CHANGED' and status='PENDING'
              and structure_fingerprint=?
            """, Integer.class, sourceId, currentFingerprint);
        if (existing == null || existing == 0) {
            jdbc.update("""
                insert into provider_price_diff(
                  id,price_source_id,sync_run_id,raw_snapshot_id,provider_type,provider_model_name,
                  region,request_mode,service_tier,context_tier,diff_type,old_value,new_value,
                  risk_level,source_priority,structure_fingerprint,status,confirmation_count,last_confirmed_at)
                values(?,?,?,?,?,'__SOURCE_STRUCTURE__',?,'STANDARD','DEFAULT','DEFAULT',
                  'SOURCE_STRUCTURE_CHANGED',cast(? as jsonb),cast(? as jsonb),'HIGH',?,?,'PENDING',1,now())
                """, id(), sourceId, runId, snapshotId, value(text(source.get("provider_type")), "system"),
                    value(text(source.get("region")), "global"), write(Map.of("fingerprint", previous)),
                    write(Map.of("fingerprint", currentFingerprint)), integer(source.get("source_priority"), 100),
                    currentFingerprint);
        }
        return new StructureChange(true, previous, currentFingerprint);
    }

    private int persistAliasCandidates(Map<String,Object> source, String snapshotId,
                                       List<PriceSourceParseResult.ModelAliasCandidate> aliases) {
        if (!"OFFICIAL".equals(text(source.get("source_class"))) || aliases == null || aliases.isEmpty()) return 0;
        int changed = 0;
        for (PriceSourceParseResult.ModelAliasCandidate alias : aliases) {
            if (blank(alias.providerType()) || blank(alias.providerModelName())
                    || blank(alias.targetProviderModelName()) || blank(alias.relationType())) continue;
            List<Map<String,Object>> existing = jdbc.queryForList("""
                select id,evidence_hash from provider_model_alias
                where lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?)
                  and lower(target_provider_model_name)=lower(?) and relation_type=? and lower(region)=lower(?)
                  and review_status in ('PENDING_REVIEW','APPROVED','MIGRATED_APPROVED')
                order by created_at desc limit 1
                """, alias.providerType(), alias.providerModelName(), alias.targetProviderModelName(),
                    alias.relationType(), value(alias.region(), "global"));
            if (!existing.isEmpty()) {
                Map<String,Object> row = existing.get(0);
                if (!Objects.equals(text(row.get("evidence_hash")), alias.evidenceHash())) {
                    jdbc.update("""
                        update provider_model_alias set source_ref=?,raw_snapshot_id=?,evidence_hash=?,
                          review_status=case when review_status='MIGRATED_APPROVED' then review_status else 'PENDING_REVIEW' end,
                          reviewed_by=null,reviewed_at=null,review_reason=null,updated_at=now() where id=?
                        """, alias.sourceRef(), snapshotId, alias.evidenceHash(), row.get("id"));
                    changed++;
                }
                continue;
            }
            jdbc.update("""
                insert into provider_model_alias(
                  id,provider_type,provider_model_name,target_provider_model_name,relation_type,region,
                  source_type,source_ref,raw_snapshot_id,evidence_hash,review_status)
                values(?,?,?,?,?,?, 'OFFICIAL_REFERENCE',?,?,?,'PENDING_REVIEW')
                """, id(), alias.providerType(), alias.providerModelName(), alias.targetProviderModelName(),
                    alias.relationType(), value(alias.region(), "global"), alias.sourceRef(), snapshotId,
                    alias.evidenceHash());
            changed++;
        }
        return changed;
    }

    private int upsertOfficialModelCandidates(Map<String,Object> source, String snapshotId,
                                              List<PriceSourceParser.NormalizedPrice> prices) {
        if (!"OFFICIAL".equals(text(source.get("source_class"))) || prices == null || prices.isEmpty()) return 0;
        int changed = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (PriceSourceParser.NormalizedPrice price : prices) {
            String providerType = value(price.providerType(), text(source.get("provider_type")));
            String modelName = price.providerModelName();
            String region = value(price.region(), value(text(source.get("region")), "global"));
            if (blank(providerType) || blank(modelName)) continue;
            String key = providerType.toLowerCase(Locale.ROOT) + '|' + modelName.toLowerCase(Locale.ROOT)
                    + '|' + region.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) continue;
            Integer verified = jdbc.queryForObject("""
                select count(*) from channel_model_deployment d
                join provider_instance p on p.id=d.provider_instance_id
                where lower(p.provider_type)=lower(?) and lower(d.provider_model_name)=lower(?)
                  and (lower(p.region)=lower(?) or lower(?)='global')
                """, Integer.class, providerType, modelName, region, region);
            int verifiedCount = verified == null ? 0 : verified;
            String candidateStatus = verifiedCount > 0 ? "CHANNEL_VERIFIED" : "PRICE_ONLY";
            String evidence = sha256(write(normalizedMap(price)).getBytes(StandardCharsets.UTF_8));
            int affected = jdbc.update("""
                insert into model_discovery_candidate(
                  id,provider_type,candidate_model_name,display_name,source_type,source_ref,raw_snapshot_id,
                  evidence_hash,region,raw_attributes,channel_verified_count,status,verified_at)
                values(?,?,?,?,?,?,?, ?,?,cast(? as jsonb),?,?,case when ?>0 then now() else null end)
                on conflict(provider_type,candidate_model_name,region,source_ref) do update set
                  display_name=excluded.display_name,raw_snapshot_id=excluded.raw_snapshot_id,
                  evidence_hash=excluded.evidence_hash,raw_attributes=excluded.raw_attributes,
                  channel_verified_count=excluded.channel_verified_count,status=excluded.status,
                  verified_at=excluded.verified_at,last_seen_at=now(),updated_at=now()
                """, id(), providerType, modelName, value(price.displayName(), modelName),
                    "OFFICIAL_PRICE", price.sourceRef(), snapshotId, evidence, region,
                    write(normalizedMap(price)), verifiedCount, candidateStatus, verifiedCount);
            changed += affected;
        }
        return changed;
    }

    private int persistDiscoveredPriceSources(Map<String,Object> source,
                                              List<PriceSourceParseResult.OfficialSubPage> discoveredPages) {
        if (!"KIMI_OFFICIAL_PAGE".equals(text(source.get("adapter_code")))) return 0;
        LinkedHashMap<String,String> pages = new LinkedHashMap<>();
        if (discoveredPages != null) {
            for (PriceSourceParseResult.OfficialSubPage page : discoveredPages) {
                if (page == null) continue;
                String canonical = canonicalPricingPageUrl(page.url());
                if (!blank(canonical) && KimiOfficialPriceAdapter.isSupportedPricingPage(canonical)) {
                    pages.put(canonical, value(page.label(), "Kimi 官方定价"));
                }
            }
        }
        Object configured = readMap(source.get("config")).get("seedPricingPages");
        if (configured instanceof Collection<?> collection) {
            for (Object item : collection) if (item != null && !String.valueOf(item).isBlank()) {
                String canonical = canonicalPricingPageUrl(String.valueOf(item));
                if (!blank(canonical) && KimiOfficialPriceAdapter.isSupportedPricingPage(canonical)) {
                    pages.putIfAbsent(canonical, "Kimi 官方定价");
                }
            }
        }
        if (pages.isEmpty()) return 0;
        Set<String> allowedHosts = new LinkedHashSet<>(readStrings(source.get("official_hosts")));
        int changed = 0;
        for (Map.Entry<String,String> page : pages.entrySet()) {
            URI uri;
            try { uri = URI.create(page.getKey()); }
            catch (Exception ignored) { continue; }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) continue;
            if (page.getKey().equals(canonicalPricingPageUrl(text(source.get("endpoint"))))) continue;
            Integer exists = jdbc.queryForObject("""
                select count(*) from provider_price_source where adapter_code='KIMI_OFFICIAL_PAGE'
                  and lower(coalesce(provider_type,''))=lower(coalesce(?,'')) and endpoint=?
                """, Integer.class, nullableText(source.get("provider_type")), page.getKey());
            if (exists != null && exists > 0) continue;

            String sourceId = "auto_kimi_" + sha256(page.getKey().getBytes(StandardCharsets.UTF_8)).substring(0, 40);
            Map<String,Object> childConfig = new LinkedHashMap<>(readMap(source.get("config")));
            childConfig.remove("seedPricingPages");
            childConfig.put("parentSourceId", source.get("id"));
            childConfig.put("autoDiscovered", true);
            childConfig.put("discoverPricingPages", false);
            String parentStatus = text(source.get("status"));
            String childStatus = Set.of("ACTIVE", "DEGRADED").contains(parentStatus) ? "ACTIVE" : "PAUSED";
            OffsetDateTime nextRun = "ACTIVE".equals(childStatus) ? OffsetDateTime.now() : null;
            jdbc.update("""
                insert into provider_price_source(
                  id,name,source_class,adapter_code,provider_type,provider_instance_id,auth_mode,endpoint,
                  official_hosts,region,default_currency,schedule_expression,auto_publish,max_auto_change_ratio,
                  confirmation_runs,config,status,next_run_at,parser_version,fetch_mode,source_priority,price_nature,
                  connector_code,data_scope,trust_level,publish_policy,schema_version,credential_ref,
                  credential_purpose,mapping_profile,document_type,extraction_mode,minimum_confidence,
                  require_manual_review,max_document_pages,max_document_bytes,llm_model,created_by,updated_by)
                values(?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do nothing
                """, sourceId, value(text(source.get("name")), "Kimi 官方价格") + " - " + page.getValue(),
                    source.get("source_class"), source.get("adapter_code"), source.get("provider_type"),
                    source.get("provider_instance_id"), source.get("auth_mode"), page.getKey(),
                    write(allowedHosts), source.get("region"), source.get("default_currency"),
                    source.get("schedule_expression"), source.get("auto_publish"), source.get("max_auto_change_ratio"),
                    source.get("confirmation_runs"), write(childConfig), childStatus, nextRun,
                    source.get("parser_version"), source.get("fetch_mode"), source.get("source_priority"),
                    source.get("price_nature"), source.get("connector_code"), source.get("data_scope"),
                    source.get("trust_level"), source.get("publish_policy"), source.get("schema_version"),
                    source.get("credential_ref"), source.get("credential_purpose"), source.get("mapping_profile"),
                    source.get("document_type"), source.get("extraction_mode"), source.get("minimum_confidence"),
                    source.get("require_manual_review"), source.get("max_document_pages"),
                    source.get("max_document_bytes"), source.get("llm_model"), "SYSTEM", "SYSTEM");
            changed++;
        }
        return changed;
    }

    private static String canonicalPricingPageUrl(String raw) {
        if (blank(raw)) return "";
        try {
            URI uri = URI.create(raw.trim());
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    path, uri.getQuery(), null).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private int refreshReferencePriceBindings() {
        try {
            Integer count = jdbc.queryForObject(
                    "select tokensea_refresh_reference_price_bindings()",
                    Integer.class);
            return count == null ? 0 : count;
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private ProcessResult processReferences(Map<String,Object> source, String runId, String snapshotId,
                                            String checksum, List<PriceSourceParser.NormalizedPrice> prices) {
        int changed = 0;
        for (PriceSourceParser.NormalizedPrice price : prices) {
            String canonical = canonicalReference(price);
            List<Map<String,Object>> components = componentList(price);
            PricingComponentService.Summary summary = pricingComponents.summarize(
                    components, price.inputUnitPrice(), price.outputUnitPrice());
            String priceJson = write(normalizedMap(price));
            String componentJson = pricingComponents.writeComponents(components);
            String evidenceHash = sha256((checksum + ":" + priceJson).getBytes(StandardCharsets.UTF_8));
            changed += jdbc.update("""
                insert into public_model_price_reference(
                  id,price_source_id,raw_snapshot_id,sync_run_id,provider_type,provider_model_name,canonical_name,
                  display_name,currency,billing_basis,billing_quantity,region,request_mode,service_tier,context_tier,
                  input_unit_price,cache_read_unit_price,cache_read_mode,cache_write_unit_price,cache_write_mode,
                  output_unit_price,price_components,component_schema_version,price_completeness_status,
                  price_nature,pricing_conditions,source_priority,source_evidence_path,source_published_at,
                  source_ref,evidence_hash,source_confidence,status,observed_at,
                  bundle_version,source_rank,is_current,last_seen_at,stale_at,price_status)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),2,?,?,cast(? as jsonb),?,?,?,?,?,0.7000,'ACTIVE',now(),
                  null,?,true,now(),now()+make_interval(hours => ?),'CURRENT')
                on conflict(price_source_id,provider_type,provider_model_name,region,request_mode,service_tier,context_tier)
                do update set raw_snapshot_id=excluded.raw_snapshot_id,sync_run_id=excluded.sync_run_id,
                  canonical_name=excluded.canonical_name,display_name=excluded.display_name,currency=excluded.currency,
                  billing_basis=excluded.billing_basis,billing_quantity=excluded.billing_quantity,
                  input_unit_price=excluded.input_unit_price,cache_read_unit_price=excluded.cache_read_unit_price,
                  cache_read_mode=excluded.cache_read_mode,cache_write_unit_price=excluded.cache_write_unit_price,
                  cache_write_mode=excluded.cache_write_mode,output_unit_price=excluded.output_unit_price,
                  price_components=excluded.price_components,component_schema_version=excluded.component_schema_version,
                  price_completeness_status=excluded.price_completeness_status,price_nature=excluded.price_nature,
                  pricing_conditions=excluded.pricing_conditions,source_priority=excluded.source_priority,
                  source_evidence_path=excluded.source_evidence_path,source_published_at=excluded.source_published_at,
                  source_ref=excluded.source_ref,evidence_hash=excluded.evidence_hash,status='ACTIVE',
                  source_rank=excluded.source_rank,is_current=true,last_seen_at=now(),stale_at=excluded.stale_at,
                  price_status='CURRENT',observed_at=now(),updated_at=now()
                where public_model_price_reference.evidence_hash is distinct from excluded.evidence_hash
                """, id(), source.get("id"), snapshotId, runId, price.providerType(), price.providerModelName(),
                    canonical, price.displayName(), price.currency(), price.billingBasis(), price.billingQuantity(),
                    price.region(), price.requestMode(), price.serviceTier(), price.contextTier(),
                    summary.inputUncachedUnitPrice(), summary.cacheReadUnitPrice(), summary.cacheReadMode(),
                    summary.cacheWriteUnitPrice(), summary.cacheWriteMode(), summary.outputUnitPrice(), componentJson,
                    summary.priceCompletenessStatus(), priceNature(price, source), write(pricingConditions(price)),
                    sourcePriority(price, source), sourceEvidencePath(price), sourcePublishedAt(price),
                    price.sourceRef(), evidenceHash, sourcePriority(price, source),
                    integer(source.get("stale_after_hours"), 168));
            jdbc.update("""
                insert into public_model_reference(id,canonical_name,display_name,vendor,source_type,source_ref,
                  source_confidence,reference_prices,reference_source_hash,reference_updated_at)
                values(?,?,?,?, 'SYNC_IMPORT',?,0.7000,jsonb_build_object(?::text,cast(? as jsonb)),?,now())
                on conflict(canonical_name) do update set display_name=excluded.display_name,vendor=excluded.vendor,
                  source_type=excluded.source_type,source_ref=excluded.source_ref,source_confidence=excluded.source_confidence,
                  reference_prices=public_model_reference.reference_prices || excluded.reference_prices,
                  reference_source_hash=excluded.reference_source_hash,reference_updated_at=now(),
                  version=public_model_reference.version+1,updated_at=now()
                where not (public_model_reference.reference_prices @> excluded.reference_prices)
                """, id(), canonical, price.displayName(), price.providerType(), price.sourceRef(),
                    source.get("id"), priceJson, evidenceHash);
        }
        return new ProcessResult(changed, 0, 0);
    }

    private ProcessResult processOfficial(Map<String,Object> source, String runId, String snapshotId,
                                          String checksum, List<PriceSourceParser.NormalizedPrice> prices,
                                          boolean structureChanged, String structureFingerprint) {
        int changed = 0, autoPublished = 0, reviewRequired = 0;
        Set<String> seen = new HashSet<>();
        for (PriceSourceParser.NormalizedPrice price : prices) {
            if (blank(price.providerType()) || blank(price.providerModelName())) continue;
            String scopeKey = scopeKey(price);
            seen.add(scopeKey);
            List<Map<String,Object>> currentRows = jdbc.queryForList("""
                select * from provider_model_price_catalog where status='ACTIVE'
                  and lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?)
                  and lower(region)=lower(?) and lower(request_mode)=lower(?)
                  and lower(service_tier)=lower(?) and lower(context_tier)=lower(?)
                limit 1
                """, price.providerType(), price.providerModelName(), price.region(), price.requestMode(),
                    price.serviceTier(), price.contextTier());
            Map<String,Object> current = currentRows.isEmpty() ? null : currentRows.get(0);
            DiffAssessment assessment = assess(current, price, decimal(source.get("max_auto_change_ratio"), new BigDecimal("0.3")));
            if (assessment.type() == null) continue;
            changed++;
            String newJson = write(normalizedMap(price));
            String confirmationHash = sha256(newJson.getBytes(StandardCharsets.UTF_8));
            List<Map<String,Object>> pendingRows = jdbc.queryForList("""
                select id,sync_run_id,new_value,confirmation_count,last_confirmed_hash
                from provider_price_diff where price_source_id=? and provider_type=? and provider_model_name=?
                  and region=? and request_mode=? and service_tier=? and context_tier=? and diff_type=? and status='PENDING'
                order by created_at desc
                """, source.get("id"), price.providerType(), price.providerModelName(), price.region(),
                    price.requestMode(), price.serviceTier(), price.contextTier(), assessment.type());
            String diffId;
            if (pendingRows.isEmpty()) {
                diffId = id();
                jdbc.update("""
                    insert into provider_price_diff(id,price_source_id,sync_run_id,raw_snapshot_id,provider_type,
                      provider_model_name,region,request_mode,service_tier,context_tier,diff_type,old_value,new_value,
                      change_ratio,risk_level,source_priority,structure_fingerprint,status,
                      confirmation_count,last_confirmed_hash,last_confirmed_at)
                    values(?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?, 'PENDING',1,?,now())
                    """, diffId, source.get("id"), runId, snapshotId, price.providerType(), price.providerModelName(),
                        price.region(), price.requestMode(), price.serviceTier(), price.contextTier(), assessment.type(),
                        current == null ? null : write(catalogNormalized(current)), newJson, assessment.changeRatio(),
                        assessment.risk(), sourcePriority(price, source), structureFingerprint, confirmationHash);
            } else {
                Map<String,Object> pending = pendingRows.get(0);
                diffId = text(pending.get("id"));
                int previousCount = pending.get("confirmation_count") instanceof Number count ? count.intValue() : 1;
                boolean sameRun = runId.equals(text(pending.get("sync_run_id")));
                boolean sameValue = confirmationHash.equals(text(pending.get("last_confirmed_hash")))
                        || jsonValueEquals(readMap(pending.get("new_value")), readMap(newJson));
                int confirmationCount = sameValue ? (sameRun ? previousCount : previousCount + 1) : 1;
                jdbc.update("""
                    update provider_price_diff set sync_run_id=?,raw_snapshot_id=?,old_value=cast(? as jsonb),
                      new_value=cast(? as jsonb),change_ratio=?,risk_level=?,source_priority=?,
                      structure_fingerprint=?,confirmation_count=?,last_confirmed_hash=?,last_confirmed_at=now(),updated_at=now()
                    where id=? and status='PENDING'
                    """, runId, snapshotId, current == null ? null : write(catalogNormalized(current)), newJson,
                        assessment.changeRatio(), assessment.risk(), sourcePriority(price, source), structureFingerprint,
                        confirmationCount, confirmationHash, diffId);
                if (pendingRows.size() > 1) {
                    jdbc.update("""
                        update provider_price_diff set status='IGNORED',decision_reason='同一价格范围的待审核差异已合并',
                          decided_by='SYSTEM',decided_at=now(),updated_at=now()
                        where price_source_id=? and provider_type=? and provider_model_name=? and region=?
                          and request_mode=? and service_tier=? and context_tier=? and diff_type=? and status='PENDING' and id<>?
                        """, source.get("id"), price.providerType(), price.providerModelName(), price.region(),
                            price.requestMode(), price.serviceTier(), price.contextTier(), assessment.type(), diffId);
                }
            }
            boolean canAutoPublish = Boolean.TRUE.equals(source.get("auto_publish"))
                    && !structureChanged
                    && "LOW".equals(assessment.risk())
                    && "PRICE_CHANGED".equals(assessment.type())
                    && "ORIGINAL".equals(priceNature(price, source))
                    && "STANDARD".equalsIgnoreCase(price.requestMode())
                    && confirmed(source, diffId);
            if (canAutoPublish) {
                Map<String,Object> diff = require("provider_price_diff", diffId, "价格差异不存在");
                String catalogId = publish(diff, price, "AUTO", "SYSTEM");
                jdbc.update("""
                    update provider_price_diff set status='AUTO_PUBLISHED',decided_by='SYSTEM',decided_at=now(),
                      decision_reason='满足可信来源与低风险自动发布规则',published_catalog_id=?,updated_at=now()
                    where status='PENDING' and price_source_id=? and provider_type=? and provider_model_name=?
                      and region=? and request_mode=? and service_tier=? and context_tier=?
                      and new_value=cast(? as jsonb)
                    """, catalogId, source.get("id"), price.providerType(), price.providerModelName(), price.region(),
                        price.requestMode(), price.serviceTier(), price.contextTier(), newJson);
                autoPublished++;
            } else reviewRequired++;
        }
        reviewRequired += createRemovedDiffs(source, runId, snapshotId, seen);
        return new ProcessResult(changed, autoPublished, reviewRequired);
    }

    private int createRemovedDiffs(Map<String,Object> source, String runId, String snapshotId, Set<String> seen) {
        List<Map<String,Object>> active = jdbc.queryForList("""
            select * from provider_model_price_catalog where price_source_id=? and status='ACTIVE'
            """, source.get("id"));
        int created = 0;
        for (Map<String,Object> row : active) {
            String key = scopeKey(row);
            if (seen.contains(key)) continue;
            Integer exists = jdbc.queryForObject("""
                select count(*) from provider_price_diff where price_source_id=? and provider_model_name=?
                  and region=? and request_mode=? and service_tier=? and context_tier=?
                  and diff_type='MODEL_REMOVED' and status='PENDING'
                """, Integer.class, source.get("id"), row.get("provider_model_name"), row.get("region"),
                    row.get("request_mode"), row.get("service_tier"), row.get("context_tier"));
            if (exists != null && exists > 0) continue;
            jdbc.update("""
                insert into provider_price_diff(id,price_source_id,sync_run_id,raw_snapshot_id,provider_type,
                  provider_model_name,region,request_mode,service_tier,context_tier,diff_type,old_value,
                  risk_level,source_priority,structure_fingerprint,status)
                values(?,?,?,?,?,?,?,?,?,?,'MODEL_REMOVED',cast(? as jsonb),'HIGH',?,?,'PENDING')
                """, id(), source.get("id"), runId, snapshotId, row.get("provider_type"), row.get("provider_model_name"),
                    row.get("region"), row.get("request_mode"), row.get("service_tier"), row.get("context_tier"),
                    write(catalogNormalized(row)), integer(source.get("source_priority"), 100),
                    source.get("structure_fingerprint"));
            created++;
        }
        return created;
    }

    protected String publish(Map<String,Object> diff, PriceSourceParser.NormalizedPrice price,
                             String publishMode, String actor) {
        return transactions.execute(status -> publishInternal(diff, price, publishMode, actor));
    }

    private String publishInternal(Map<String,Object> diff, PriceSourceParser.NormalizedPrice price,
                                   String publishMode, String actor) {
        Map<String,Object> source = requireSource(text(diff.get("price_source_id")));
        jdbc.update("""
            update provider_model_price_catalog set status='INACTIVE',effective_to=coalesce(effective_to,now()),
              updated_by=?,updated_at=now()
            where status='ACTIVE' and lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?)
              and lower(region)=lower(?) and lower(request_mode)=lower(?) and lower(service_tier)=lower(?)
              and lower(context_tier)=lower(?)
            """, actor, price.providerType(), price.providerModelName(), price.region(), price.requestMode(),
                price.serviceTier(), price.contextTier());
        Integer revision = jdbc.queryForObject("""
            select coalesce(max(revision),0)+1 from provider_model_price_catalog
            where lower(provider_type)=lower(?) and lower(provider_model_name)=lower(?)
            """, Integer.class, price.providerType(), price.providerModelName());
        String catalogId = id();
        List<Map<String,Object>> components = componentList(price);
        PricingComponentService.Summary summary = pricingComponents.summarize(
                components, price.inputUnitPrice(), price.outputUnitPrice());
        String componentJson = pricingComponents.writeComponents(components);
        String normalizedJson = write(normalizedMap(price));
        String sourceType = "OFFICIAL_JSON".equals(source.get("adapter_code")) || "OFFICIAL_CSV".equals(source.get("adapter_code"))
                ? "PROVIDER_API" : "OFFICIAL_REFERENCE";
        jdbc.update("""
            insert into provider_model_price_catalog(
              id,provider_type,provider_model_name,display_name,aliases,currency,billing_basis,billing_quantity,
              input_unit_price,cache_read_unit_price,cache_read_mode,cache_write_unit_price,cache_write_mode,
              output_unit_price,price_components,component_schema_version,price_completeness_status,cache_pricing_status,
              price_nature,pricing_conditions,source_priority,source_evidence_path,source_published_at,
              source_type,source_ref,source_confidence,source_updated_at,effective_from,effective_to,revision,status,
              created_by,updated_by,price_source_id,raw_snapshot_id,sync_run_id,parser_version,publish_mode,evidence_hash,
              region,request_mode,service_tier,context_tier,normalized_price)
            values(?,?,?,?,cast('[]' as jsonb),?,?,?,?,?,?,?,?,?,cast(? as jsonb),2,?,?,?,cast(? as jsonb),?,?,?,?,?,1,now(),?,?,?,'ACTIVE',?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
            """, catalogId, price.providerType(), price.providerModelName(), price.displayName(), price.currency(),
                price.billingBasis(), price.billingQuantity(), summary.inputUncachedUnitPrice(),
                summary.cacheReadUnitPrice(), summary.cacheReadMode(), summary.cacheWriteUnitPrice(),
                summary.cacheWriteMode(), summary.outputUnitPrice(), componentJson,
                summary.priceCompletenessStatus(), summary.cachePricingStatus(), priceNature(price, source),
                write(pricingConditions(price)), sourcePriority(price, source), sourceEvidencePath(price),
                sourcePublishedAt(price), sourceType, price.sourceRef(),
                price.effectiveFrom() == null ? OffsetDateTime.now() : price.effectiveFrom(), price.effectiveTo(),
                revision == null ? 1 : revision, actor, actor, source.get("id"), diff.get("raw_snapshot_id"),
                diff.get("sync_run_id"), source.get("parser_version"), publishMode,
                snapshotChecksum(diff.get("raw_snapshot_id")), price.region(), price.requestMode(), price.serviceTier(),
                price.contextTier(), normalizedJson);
        saveComponents(catalogId, components);
        enqueueOutbox("PRICE_CATALOG_PUBLISHED", "ProviderModelPriceCatalog", catalogId,
                Map.of("catalogId", catalogId, "providerType", price.providerType(),
                        "providerModelName", price.providerModelName()));
        ProviderPriceCatalogService.RematchSummary rematch = matcher.rematchCatalog(catalogId);
        audits.record("PROVIDER_PRICE_PUBLISH", "ProviderModelPriceCatalog", catalogId, null,
                Map.of("mode", publishMode, "sourceId", source.get("id"), "diffId", diff.get("id"),
                        "rematch", rematch));
        return catalogId;
    }

    private void enqueueOutbox(String eventType, String aggregateType, String aggregateId, Map<String,Object> payload) {
        jdbc.update("""
            insert into governance_event_outbox(id,event_type,aggregate_type,aggregate_id,payload)
            values(?,?,?,?,cast(? as jsonb))
            """, id(), eventType, aggregateType, aggregateId, write(payload));
    }

    private void saveComponents(String catalogId, List<Map<String,Object>> components) {
        for (Map<String,Object> component : components) {
            Map<String,Object> scope = component.get("scope") instanceof Map<?,?> map ? stringMap(map) : Map.of();
            Map<String,Object> metadata = component.get("metadata") instanceof Map<?,?> map ? stringMap(map) : Map.of();
            String scopeJson = write(scope);
            jdbc.update("""
                insert into provider_price_component(
                  id,catalog_price_id,component_type,variant,unit_price,unit_basis,unit_quantity,component_mode,
                  priority,scope,scope_hash,source_ref,metadata)
                values(?,?,?,?,?,?,?, ?,?,cast(? as jsonb),?,?,cast(? as jsonb))
                """, id(), catalogId, component.get("componentType"), component.get("variant"),
                    component.get("unitPrice"), component.get("unitBasis"), component.get("unitQuantity"),
                    component.get("mode"), component.get("priority"), scopeJson, pricingComponents.scopeHash(scope),
                    component.get("sourceRef"), write(metadata));
        }
    }

    private DiffAssessment assess(Map<String,Object> current, PriceSourceParser.NormalizedPrice price, BigDecimal threshold) {
        if (current == null) return new DiffAssessment("MODEL_ADDED", BigDecimal.ZERO, "HIGH");
        if (!Objects.equals(text(current.get("currency")), price.currency())) return new DiffAssessment("CURRENCY_CHANGED", null, "HIGH");
        if (!Objects.equals(value(text(current.get("price_nature")), "ORIGINAL"), priceNature(price, Map.of()))) {
            return new DiffAssessment("PRICE_NATURE_CHANGED", null, "HIGH");
        }
        if (!Objects.equals(text(current.get("billing_basis")), price.billingBasis())
                || !Objects.equals(String.valueOf(current.get("billing_quantity")), String.valueOf(price.billingQuantity()))) {
            return new DiffAssessment("UNIT_CHANGED", null, "HIGH");
        }
        List<Map<String,Object>> oldComponents = pricingComponents.readComponents(catalogNormalized(current).get("components"));
        List<Map<String,Object>> newComponents = componentList(price);
        if (!componentSignatures(oldComponents).equals(componentSignatures(newComponents))) {
            return new DiffAssessment("BILLING_DIMENSION_CHANGED", null, "HIGH");
        }
        if (jsonValueEquals(oldComponents, newComponents)) return new DiffAssessment(null, BigDecimal.ZERO, "LOW");
        BigDecimal ratio = maxComponentRatio(oldComponents, newComponents);
        return new DiffAssessment("PRICE_CHANGED", ratio, ratio.compareTo(threshold) <= 0 ? "LOW" : "HIGH");
    }

    static boolean jsonValueEquals(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left instanceof Number && right instanceof Number) {
            try { return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0; }
            catch (NumberFormatException ignored) { return Objects.equals(left, right); }
        }
        if (left instanceof Map<?,?> leftMap && right instanceof Map<?,?> rightMap) {
            if (!leftMap.keySet().equals(rightMap.keySet())) return false;
            for (Object key : leftMap.keySet()) {
                if (!jsonValueEquals(leftMap.get(key), rightMap.get(key))) return false;
            }
            return true;
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) return false;
            for (int index = 0; index < leftList.size(); index++) {
                if (!jsonValueEquals(leftList.get(index), rightList.get(index))) return false;
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private boolean confirmed(Map<String,Object> source, String diffId) {
        int required = ((Number) source.get("confirmation_runs")).intValue();
        if (required <= 1) return true;
        Integer count = jdbc.queryForObject("""
            select confirmation_count from provider_price_diff where id=? and status='PENDING'
            """, Integer.class, diffId);
        return count != null && count >= required;
    }

    private boolean needsConfirmation(Map<String,Object> source) {
        if (!"OFFICIAL".equals(text(source.get("source_class")))) return false;
        int required = ((Number) source.get("confirmation_runs")).intValue();
        if (required <= 1) return false;
        Integer pending = jdbc.queryForObject("""
            select count(*) from provider_price_diff where price_source_id=? and status='PENDING'
            """, Integer.class, source.get("id"));
        return pending != null && pending > 0;
    }

    private Map<String,Object> latestSnapshot(String sourceId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select * from provider_price_raw_snapshot where price_source_id=? order by fetched_at desc limit 1
            """, sourceId);
        if (rows.isEmpty()) throw new IllegalStateException("价格来源缺少可用于连续确认的原始快照");
        return rows.get(0);
    }

    private String saveSnapshot(String runId, Map<String,Object> source, FetchResult fetched) {
        List<Map<String,Object>> existing = jdbc.queryForList("""
            select id from provider_price_raw_snapshot where price_source_id=? and checksum=? limit 1
            """, source.get("id"), fetched.checksum());
        if (!existing.isEmpty()) return text(existing.get(0).get("id"));
        String snapshotId = id();
        jdbc.update("""
            insert into provider_price_raw_snapshot(id,price_source_id,sync_run_id,source_endpoint,final_endpoint,
              http_status,content_type,etag,last_modified,checksum,response_bytes,raw_content,parser_version)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, snapshotId, source.get("id"), runId, source.get("endpoint"), fetched.finalEndpoint(),
                fetched.statusCode(), fetched.contentType(), fetched.etag(), fetched.lastModified(), fetched.checksum(),
                fetched.bytes(), fetched.content(), source.get("parser_version"));
        return snapshotId;
    }

    private ParseDiagnostics parseDiagnostics(PriceSourceParseResult parsed) {
        return new ParseDiagnostics(
                parsed.parseStatus(),
                parsed.tableCount(),
                parsed.matchedTableCount(),
                parsed.generatedPriceCount(),
                parsed.sourceEvidence()
        );
    }

    private void finishExtractionReview(String runId,
                                        Map<String,Object> source,
                                        FetchResult fetched,
                                        String sourceChecksum,
                                        PriceSourceParseResult parsed,
                                        ParseDiagnostics diagnostics,
                                        PriceDocumentExtractionService.PersistenceResult extraction,
                                        List<Map<String,Object>> logs) {
        logs.add(log("EXTRACTION_REVIEW_REQUIRED", Map.of(
                "extractionRunId", extraction.runId(),
                "pendingReview", extraction.pendingReview(),
                "rejected", extraction.rejected())));
        jdbc.update("""
            update provider_price_sync_run set status='REVIEW_REQUIRED',http_status=?,records_fetched=?,
              records_normalized=?,records_changed=0,records_auto_published=0,records_review_required=?,
              parse_status=?,parsed_table_count=?,matched_table_count=?,generated_price_count=?,
              diagnostic_snapshot=cast(? as jsonb),completed_at=now(),execution_log=cast(? as jsonb),
              heartbeat_at=now(),updated_at=now() where id=?
            """, fetched.statusCode(), parsed.prices().size(), parsed.prices().size(), extraction.pendingReview(),
                diagnostics.status(), diagnostics.tableCount(), diagnostics.matchedTableCount(),
                diagnostics.generatedPriceCount(), write(diagnostics.snapshot()), write(logs), runId);
        jdbc.update("""
            update provider_price_source set
              status=case when status in ('PAUSED','DISABLED') then status else 'ACTIVE' end,
              last_checked_at=now(),last_success_at=now(),last_good_sync_at=now(),last_error=null,
              etag=?,last_modified=?,last_content_hash=?,updated_at=now()
            where id=?
            """, fetched.etag(), fetched.lastModified(), sourceChecksum, source.get("id"));
        audits.record("PRICE_DOCUMENT_EXTRACTION_REVIEW_REQUIRED", "PriceDocumentExtractionRun",
                extraction.runId(), null, Map.of(
                        "sourceId", source.get("id"),
                        "syncRunId", runId,
                        "pendingReview", extraction.pendingReview(),
                        "rejected", extraction.rejected()));
    }

    private void finishNoChange(String runId, Map<String,Object> source, FetchResult fetched, List<Map<String,Object>> logs) {
        logs.add(log("NO_CHANGE", Map.of("httpStatus", fetched.statusCode())));
        jdbc.update("""
            update provider_price_sync_run set status='NO_CHANGE',http_status=?,parse_status='NOT_PARSED_UNCHANGED',
              parsed_table_count=0,matched_table_count=0,generated_price_count=0,
              diagnostic_snapshot='{}'::jsonb,completed_at=now(),execution_log=cast(? as jsonb),
              heartbeat_at=now(),updated_at=now() where id=?
            """, fetched.statusCode(), write(logs), runId);
        jdbc.update("""
            update provider_price_source set
              status=case when status in ('PAUSED','DISABLED') then status else 'ACTIVE' end,
              last_checked_at=now(),last_success_at=now(),last_good_sync_at=now(),last_error=null,
              etag=coalesce(?,etag),last_modified=coalesce(?,last_modified),updated_at=now() where id=?
            """, fetched.etag(), fetched.lastModified(), source.get("id"));
    }

    private void ensureSyncAlert(Map<String,Object> source, Exception error) {
        Integer exists = jdbc.queryForObject("""
            select count(*) from alert_event where alert_type='PRICE_SOURCE_SYNC_FAILED'
              and resource_type='PRICE_SOURCE' and resource_id=? and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class, source.get("id"));
        if (exists != null && exists > 0) return;
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'PRICE_SOURCE_SYNC_FAILED','WARNING','PRICE_SOURCE',?,'价格来源同步失败',cast(? as jsonb))
            """, id(), source.get("id"), write(Map.of("sourceName", source.get("name"),
                    "adapter", source.get("adapter_code"), "message", safe(error.getMessage()))));
    }

    private void validateTarget(URI uri, Map<String,Object> source) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalStateException("价格来源必须使用无用户信息且具有明确主机名的 HTTPS 地址");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Set<String> sourceHosts = new HashSet<>(readStrings(source.get("official_hosts")));
        if (!sourceHosts.contains(host)) throw new IllegalStateException("目标主机未列入该价格源官方域名");
        if (!proxyConfigured && (globalAllowedHosts.isEmpty() || !globalAllowedHosts.contains(host))) {
            throw new IllegalStateException("未配置出口代理时，目标主机必须列入 TokenSea 出口硬边界");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalStateException("价格来源解析到不允许的网络地址");
            }
        }
    }

    private ProviderInstance authenticatedInstance(Map<String,Object> source) {
        if (!"PROVIDER_INSTANCE".equals(text(source.get("auth_mode")))) return null;
        String providerInstanceId = nullableText(source.get("provider_instance_id"));
        if (blank(providerInstanceId)) throw new IllegalStateException("价格来源未绑定供应商渠道凭据");
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select id,provider_type,api_style,credential_ref,key_status,status
            from provider_instance where id=?
            """, providerInstanceId);
        if (rows.isEmpty()) throw new IllegalStateException("价格来源绑定的供应商渠道不存在");
        Map<String,Object> row = rows.get(0);
        String configuredProvider = nullableText(source.get("provider_type"));
        if (!blank(configuredProvider) && !configuredProvider.equalsIgnoreCase(text(row.get("provider_type")))) {
            throw new IllegalStateException("价格来源供应商与绑定渠道不一致");
        }
        if (!Set.of("启用","ACTIVE").contains(text(row.get("status")).toUpperCase(Locale.ROOT))
                && !"启用".equals(text(row.get("status")))) {
            throw new IllegalStateException("价格来源绑定的供应商渠道未启用");
        }
        ProviderInstance instance = new ProviderInstance();
        instance.setId(providerInstanceId);
        instance.setProviderType(text(row.get("provider_type")));
        instance.setApiStyle(text(row.get("api_style")));
        instance.setCredentialRef(nullableText(row.get("credential_ref")));
        instance.setKeyStatus(text(row.get("key_status")));
        instance.setStatus(text(row.get("status")));
        return instance;
    }

    private String resolvePricingCredential(Map<String,Object> source, ProviderInstance instance) {
        if (purposeCredentials == null) {
            throw new IllegalStateException("价格目录专用凭据服务未配置");
        }
        String reference = nullableText(source.get("credential_ref"));
        return purposeCredentials.resolve(reference, instance.getId(), "PRICING_READ");
    }

    private void validateContentType(String adapter, String contentType) {
        String type = value(contentType, "").toLowerCase(Locale.ROOT);
        boolean accepted = switch (adapter) {
            case "OFFICIAL_CSV" -> type.contains("csv") || type.contains("text/plain") || type.contains("octet-stream");
            case "GENERIC_DOCUMENT" -> type.contains("json") || type.contains("html") || type.contains("csv")
                    || type.contains("pdf") || type.contains("text/plain") || type.contains("octet-stream");
            case "DEEPSEEK_OFFICIAL_PAGE","QWEN_OFFICIAL_PAGE","KIMI_OFFICIAL_PAGE",
                    "XIAOMI_MIMO_OFFICIAL_PAGE","ZHIPU_OFFICIAL_PAGE" ->
                    type.contains("html") || type.contains("text/plain");
            default -> type.contains("json") || type.contains("text/plain") || type.contains("octet-stream");
        };
        if (!accepted) throw new IllegalStateException("价格来源 Content-Type 不符合适配器要求: " + contentType);
    }

    private Map<String,Object> normalizedMap(PriceSourceParser.NormalizedPrice price) {
        List<Map<String,Object>> components = componentList(price);
        PricingComponentService.Summary summary = pricingComponents.summarize(
                components, price.inputUnitPrice(), price.outputUnitPrice());
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("providerType", price.providerType());
        map.put("providerModelName", price.providerModelName());
        map.put("displayName", price.displayName());
        map.put("currency", price.currency());
        map.put("billingBasis", price.billingBasis());
        map.put("billingQuantity", price.billingQuantity());
        map.put("inputUnitPrice", summary.inputUncachedUnitPrice());
        map.put("cacheReadUnitPrice", summary.cacheReadUnitPrice());
        map.put("cacheReadMode", summary.cacheReadMode());
        map.put("cacheWriteUnitPrice", summary.cacheWriteUnitPrice());
        map.put("cacheWriteMode", summary.cacheWriteMode());
        map.put("outputUnitPrice", summary.outputUnitPrice());
        map.put("componentSchemaVersion", PricingComponentService.SCHEMA_VERSION);
        map.put("priceCompletenessStatus", summary.priceCompletenessStatus());
        map.put("cachePricingStatus", summary.cachePricingStatus());
        map.put("region", price.region());
        map.put("requestMode", price.requestMode());
        map.put("serviceTier", price.serviceTier());
        map.put("contextTier", price.contextTier());
        map.put("components", components);
        map.put("priceNature", priceNature(price, Map.of()));
        map.put("pricingConditions", pricingConditions(price));
        map.put("sourcePriority", sourcePriority(price, Map.of()));
        map.put("sourceEvidencePath", sourceEvidencePath(price));
        map.put("sourcePublishedAt", sourcePublishedAt(price));
        map.put("sourceRef", price.sourceRef());
        map.put("effectiveFrom", price.effectiveFrom());
        map.put("effectiveTo", price.effectiveTo());
        return map;
    }

    private Map<String,Object> catalogNormalized(Map<String,Object> row) {
        Map<String,Object> normalized = readMap(row.get("normalized_price"));
        if (!normalized.isEmpty()) return normalized;
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("providerType", row.get("provider_type"));
        result.put("providerModelName", row.get("provider_model_name"));
        result.put("displayName", row.get("display_name"));
        result.put("currency", row.get("currency"));
        result.put("billingBasis", row.get("billing_basis"));
        result.put("billingQuantity", row.get("billing_quantity"));
        result.put("inputUnitPrice", row.get("input_unit_price"));
        result.put("cacheReadUnitPrice", row.get("cache_read_unit_price"));
        result.put("cacheReadMode", row.get("cache_read_mode"));
        result.put("cacheWriteUnitPrice", row.get("cache_write_unit_price"));
        result.put("cacheWriteMode", row.get("cache_write_mode"));
        result.put("outputUnitPrice", row.get("output_unit_price"));
        result.put("componentSchemaVersion", row.get("component_schema_version"));
        result.put("priceCompletenessStatus", row.get("price_completeness_status"));
        result.put("cachePricingStatus", row.get("cache_pricing_status"));
        result.put("region", row.get("region"));
        result.put("requestMode", row.get("request_mode"));
        result.put("serviceTier", row.get("service_tier"));
        result.put("contextTier", row.get("context_tier"));
        result.put("components", pricingComponents.readComponents(row.get("price_components")));
        result.put("priceNature", row.get("price_nature"));
        result.put("pricingConditions", readMap(row.get("pricing_conditions")));
        result.put("sourcePriority", row.get("source_priority"));
        result.put("sourceEvidencePath", row.get("source_evidence_path"));
        result.put("sourcePublishedAt", row.get("source_published_at"));
        result.put("sourceRef", row.get("source_ref"));
        result.put("effectiveFrom", row.get("effective_from"));
        result.put("effectiveTo", row.get("effective_to"));
        return result;
    }

    private List<Map<String,Object>> componentList(PriceSourceParser.NormalizedPrice price) {
        return pricingComponents.normalizeParsed(price.inputUnitPrice(), price.outputUnitPrice(),
                price.providerType(), price.billingBasis(), price.billingQuantity(), price.components(), price.sourceRef());
    }

    private String priceNature(PriceSourceParser.NormalizedPrice price, Map<String,Object> source) {
        String raw = text(raw(price).get("priceNature"));
        if (!blank(raw)) return raw.toUpperCase(Locale.ROOT);
        String configured = text(source.get("price_nature"));
        return value(configured, "ORIGINAL").toUpperCase(Locale.ROOT);
    }

    private Map<String,Object> pricingConditions(PriceSourceParser.NormalizedPrice price) {
        Object value = raw(price).get("pricingConditions");
        return value instanceof Map<?,?> map ? stringMap(map) : Map.of();
    }

    private int sourcePriority(PriceSourceParser.NormalizedPrice price, Map<String,Object> source) {
        Object rawPriority = raw(price).get("sourcePriority");
        if (rawPriority != null) return integer(rawPriority, 100);
        return integer(source.get("source_priority"), 100);
    }

    private String sourceEvidencePath(PriceSourceParser.NormalizedPrice price) {
        return nullableText(raw(price).get("sourceEvidencePath"));
    }

    private OffsetDateTime sourcePublishedAt(PriceSourceParser.NormalizedPrice price) {
        Object value = raw(price).get("sourcePublishedAt");
        return parseTimeNullable(value);
    }

    private static Map<String,Object> raw(PriceSourceParser.NormalizedPrice price) {
        return price.raw() == null ? Map.of() : price.raw();
    }

    private Set<String> componentSignatures(List<Map<String,Object>> components) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String,Object> component : components) {
            result.add(String.join("|",
                    text(component.get("componentType")),
                    value(text(component.get("variant")), "DEFAULT"),
                    value(text(component.get("mode")), "EXPLICIT"),
                    value(text(component.get("unitBasis")), "TOKEN"),
                    String.valueOf(component.getOrDefault("unitQuantity", 1)),
                    pricingComponents.scopeHash(component.get("scope"))));
        }
        return result;
    }

    private BigDecimal maxComponentRatio(List<Map<String,Object>> oldComponents,
                                         List<Map<String,Object>> newComponents) {
        Map<String,BigDecimal> oldPrices = componentPrices(oldComponents);
        Map<String,BigDecimal> newPrices = componentPrices(newComponents);
        BigDecimal max = BigDecimal.ZERO;
        for (String key : oldPrices.keySet()) {
            BigDecimal oldValue = oldPrices.get(key);
            BigDecimal newValue = newPrices.get(key);
            if (oldValue == null || newValue == null) {
                if (!(oldValue == null && newValue == null)) max = BigDecimal.ONE;
            } else max = max.max(ratio(oldValue, newValue));
        }
        return max;
    }

    private Map<String,BigDecimal> componentPrices(List<Map<String,Object>> components) {
        Map<String,BigDecimal> result = new LinkedHashMap<>();
        for (Map<String,Object> component : components) {
            String signature = String.join("|",
                    text(component.get("componentType")),
                    value(text(component.get("variant")), "DEFAULT"),
                    value(text(component.get("mode")), "EXPLICIT"),
                    value(text(component.get("unitBasis")), "TOKEN"),
                    String.valueOf(component.getOrDefault("unitQuantity", 1)),
                    pricingComponents.scopeHash(component.get("scope")));
            result.put(signature, component.get("unitPrice") == null ? null
                    : decimal(component.get("unitPrice"), BigDecimal.ZERO));
        }
        return result;
    }

    private Map<String,Object> componentMap(Object value) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (Map<String,Object> component : pricingComponents.readComponents(value)) {
            String type = text(component.get("componentType"));
            Map<String,Object> spec = new LinkedHashMap<>(component);
            spec.remove("componentType");
            Object existing = result.get(type);
            if (existing == null) result.put(type, spec);
            else if (existing instanceof List<?> list) {
                List<Object> values = new ArrayList<>(list);
                values.add(spec);
                result.put(type, values);
            } else result.put(type, new ArrayList<>(List.of(existing, spec)));
        }
        return result;
    }

    private PriceSourceParser.NormalizedPrice priceFromJson(Object value) {
        Map<String,Object> map = readMap(value);
        return new PriceSourceParser.NormalizedPrice(text(map.get("providerType")), text(map.get("providerModelName")),
                text(map.get("displayName")), text(map.get("currency")), value(text(map.get("billingBasis")), "TOKEN"),
                longValue(map.get("billingQuantity"), 1_000_000L),
                decimal(map.get("inputUnitPrice"), BigDecimal.ZERO),
                decimal(map.get("outputUnitPrice"), BigDecimal.ZERO), value(text(map.get("region")), "global"),
                value(text(map.get("requestMode")), "STANDARD"), value(text(map.get("serviceTier")), "DEFAULT"),
                value(text(map.get("contextTier")), "DEFAULT"), componentMap(map.get("components")), text(map.get("sourceRef")),
                parseTime(map.get("effectiveFrom")), parseTimeNullable(map.get("effectiveTo")), map);
    }

    private Map<String,Object> requireSource(String id) {
        return require("provider_price_source", id, "价格来源不存在");
    }

    private Map<String,Object> require(String table, String id, String message) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from " + table + " where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return rows.get(0);
    }

    private String snapshotChecksum(Object snapshotId) {
        if (snapshotId == null) return null;
        List<Map<String,Object>> rows = jdbc.queryForList("select checksum from provider_price_raw_snapshot where id=?", snapshotId);
        return rows.isEmpty() ? null : text(rows.get(0).get("checksum"));
    }

    private static BigDecimal maxRatio(BigDecimal oldA, BigDecimal newA, BigDecimal oldB, BigDecimal newB) {
        return ratio(oldA, newA).max(ratio(oldB, newB));
    }

    private static BigDecimal ratio(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue.compareTo(newValue) == 0) return BigDecimal.ZERO;
        if (oldValue.signum() == 0) return BigDecimal.ONE;
        return newValue.subtract(oldValue).abs().divide(oldValue.abs(), 6, RoundingMode.HALF_UP);
    }

    private static String canonicalReference(PriceSourceParser.NormalizedPrice price) {
        return ReferenceModelMatcher.canonical(price.providerType(), price.providerModelName());
    }

    private static String scopeKey(PriceSourceParser.NormalizedPrice price) {
        return String.join("|", lower(price.providerType()), lower(price.providerModelName()), lower(price.region()),
                lower(price.requestMode()), lower(price.serviceTier()), lower(price.contextTier()));
    }

    private static String scopeKey(Map<String,Object> row) {
        return String.join("|", lower(text(row.get("provider_type"))), lower(text(row.get("provider_model_name"))),
                lower(text(row.get("region"))), lower(text(row.get("request_mode"))),
                lower(text(row.get("service_tier"))), lower(text(row.get("context_tier"))));
    }

    private static Map<String,Object> log(String event, Map<String,Object> detail) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("at", OffsetDateTime.now().toString());
        value.put("event", event);
        value.putAll(detail);
        return value;
    }

    private Map<String,Object> readMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?,?> map) return stringMap(map);
        try { return json.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private List<String> readStrings(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> collection) return collection.stream().map(String::valueOf).map(String::toLowerCase).toList();
        try { return json.readValue(String.valueOf(value), json.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception e) { return List.of(); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }

    private boolean isSystemReference(Map<String,Object> source) {
        return "SYSTEM".equals(text(source.get("managed_by")))
                && "REFERENCE".equals(text(source.get("source_purpose")));
    }

    private Duration referenceRetryDelay(String sourceId) {
        Integer failures = jdbc.queryForObject("""
            select count(*) from provider_price_sync_run
            where price_source_id=? and status='FAILED' and created_at>=now()-interval '24 hours'
            """, Integer.class, sourceId);
        int count = failures == null ? 1 : failures;
        if (count <= 1) return Duration.ofMinutes(30);
        if (count == 2) return Duration.ofHours(2);
        return Duration.ofDays(1);
    }

    private OffsetDateTime nextRun(String expression, boolean addJitter) {
        try {
            Duration interval = Duration.parse(expression);
            OffsetDateTime next = OffsetDateTime.now().plus(interval);
            if (addJitter && interval.compareTo(Duration.ofHours(1)) >= 0) {
                long upperBound = Math.min(900L, Math.max(1L, interval.toSeconds() / 20L));
                next = next.plusSeconds(ThreadLocalRandom.current().nextLong(upperBound + 1L));
            }
            return next;
        } catch (Exception e) {
            throw new IllegalArgumentException("同步周期必须是 ISO-8601 Duration");
        }
    }

    private static String accept(String adapter) {
        if ("OFFICIAL_CSV".equals(adapter)) return "text/csv,text/plain;q=0.9,*/*;q=0.1";
        if ("GENERIC_DOCUMENT".equals(adapter)) {
            return "application/json,text/csv,text/html,application/xhtml+xml,application/pdf,text/plain;q=0.8,*/*;q=0.1";
        }
        if (Set.of("DEEPSEEK_OFFICIAL_PAGE","QWEN_OFFICIAL_PAGE","KIMI_OFFICIAL_PAGE",
                "XIAOMI_MIMO_OFFICIAL_PAGE","ZHIPU_OFFICIAL_PAGE").contains(adapter)) {
            return "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.5";
        }
        return "application/json,text/plain;q=0.9,*/*;q=0.1";
    }

    private static Set<String> parseHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String host : value.split(",")) if (!host.isBlank()) result.add(host.trim().toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(result);
    }

    private static Map<String,Object> stringMap(Map<?,?> map) {
        Map<String,Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static OffsetDateTime parseTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return OffsetDateTime.now();
        if (value instanceof OffsetDateTime time) return time;
        try { return OffsetDateTime.parse(String.valueOf(value)); }
        catch (Exception e) { return OffsetDateTime.now(); }
    }

    private static OffsetDateTime parseTimeNullable(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) return null;
        if (value instanceof OffsetDateTime time) return time;
        try { return OffsetDateTime.parse(String.valueOf(value)); }
        catch (Exception e) { return null; }
    }

    private static BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null) return fallback;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private static long longValue(Object value, long fallback) {
        if (value == null) return fallback;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String nullableText(Object value) { return value == null ? null : String.valueOf(value); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) { return value == null ? "未知错误" : value.length() > 1000 ? value.substring(0, 1000) : value; }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private record FetchResult(int statusCode, String content, String checksum, int bytes, String contentType,
                               String etag, String lastModified, String finalEndpoint) {}
    private record HttpPage(int statusCode, byte[] body, String contentType,
                            String etag, String lastModified, URI finalUri) {}
    private record AggregatedJson(byte[] body, int pageCount) {}
    private record ProcessResult(int changed, int autoPublished, int reviewRequired) {}
    private record DiffAssessment(String type, BigDecimal changeRatio, String risk) {}
    private record StructureChange(boolean changed, String previousFingerprint, String currentFingerprint) {}
}
