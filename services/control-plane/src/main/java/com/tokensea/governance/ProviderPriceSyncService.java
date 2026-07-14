package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
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
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ProviderPriceSyncService {
    private static final int MAX_RESPONSE_BYTES = 5_000_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PriceSourceParser parser;
    private final ProviderPriceCatalogService matcher;
    private final AuditService audits;
    private final ProviderConnectionService providerConnections;
    private final TransactionTemplate transactions;
    private final HttpClient http;
    private final Set<String> globalAllowedHosts;
    private final boolean proxyConfigured;
    private final String owner = UUID.randomUUID().toString();

    public ProviderPriceSyncService(JdbcTemplate jdbc, ObjectMapper json, PriceSourceParser parser,
                                    ProviderPriceCatalogService matcher, AuditService audits,
                                    ProviderConnectionService providerConnections,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${tokensea.egress.proxy-host:}") String proxyHost,
                                    @Value("${tokensea.egress.proxy-port:18080}") int proxyPort,
                                    @Value("${tokensea.egress.allowed-hosts:}") String allowedHosts) {
        this.jdbc = jdbc;
        this.json = json;
        this.parser = parser;
        this.matcher = matcher;
        this.audits = audits;
        this.providerConnections = providerConnections;
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
    }

    public record FetchPreview(int httpStatus, String contentType, String checksum, int responseBytes,
                               int recordsNormalized, List<Map<String,Object>> sample) {}
    public record SyncSummary(String runId, String status, int fetched, int normalized, int changed,
                              int autoPublished, int reviewRequired, String snapshotId) {}

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

    public FetchPreview preview(String sourceId) {
        Map<String,Object> source = requireSource(sourceId);
        try {
            FetchResult fetched = fetch(source, false);
            if (fetched.statusCode() == 304) return new FetchPreview(304, fetched.contentType(),
                    text(source.get("last_content_hash")), 0, 0, List.of());
            List<PriceSourceParser.NormalizedPrice> prices = parse(source, fetched.content());
            List<Map<String,Object>> sample = prices.stream().limit(10).map(this::normalizedMap).toList();
            return new FetchPreview(fetched.statusCode(), fetched.contentType(), fetched.checksum(),
                    fetched.bytes(), prices.size(), sample);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safe(e.getMessage()));
        }
    }

    @Transactional
    public Map<String,Object> approveDiff(String diffId, String actor, String reason) {
        Map<String,Object> diff = require("provider_price_diff", diffId, "价格差异不存在");
        if (!"PENDING".equals(text(diff.get("status")))) conflict("仅待审核价格差异可批准");
        if ("MODEL_REMOVED".equals(text(diff.get("diff_type")))) {
            jdbc.update("update provider_price_diff set status='APPROVED',decision_reason=?,decided_by=?,decided_at=now(),updated_at=now() where id=?",
                    reason, actor, diffId);
            audits.record("PROVIDER_PRICE_REMOVAL_ACKNOWLEDGED", "ProviderPriceDiff", diffId, diff,
                    Map.of("reason", value(reason, ""), "actor", actor));
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
            String sourceId = text(source.get("id"));
            enqueue(sourceId, "SCHEDULED");
            jdbc.update("update provider_price_source set next_run_at=?,updated_at=now() where id=?",
                    nextRun(text(source.get("schedule_expression"))), sourceId);
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
            List<PriceSourceParser.NormalizedPrice> prices = parse(source, sourceContent);
            if (prices.isEmpty()) throw new IllegalStateException("价格来源未解析出任何有效价格记录");
            ProcessResult processed = "PUBLIC_REFERENCE".equals(text(source.get("source_class")))
                    ? processReferences(source, runId, snapshotId, sourceChecksum, prices)
                    : processOfficial(source, runId, snapshotId, sourceChecksum, prices);
            String state = processed.reviewRequired() > 0 ? "REVIEW_REQUIRED" : "SUCCEEDED";
            logs.add(log("COMPLETED", Map.of("normalized", prices.size(), "changed", processed.changed(),
                    "autoPublished", processed.autoPublished(), "reviewRequired", processed.reviewRequired())));
            jdbc.update("""
                update provider_price_sync_run set status=?,http_status=?,records_fetched=?,records_normalized=?,
                  records_changed=?,records_auto_published=?,records_review_required=?,completed_at=now(),
                  execution_log=cast(? as jsonb),heartbeat_at=now(),updated_at=now() where id=?
                """, state, fetched.statusCode(), prices.size(), prices.size(), processed.changed(),
                    processed.autoPublished(), processed.reviewRequired(), write(logs), runId);
            jdbc.update("""
                update provider_price_source set
                  status=case when status in ('PAUSED','DISABLED') then status else 'ACTIVE' end,
                  last_success_at=now(),last_error=null,etag=?,last_modified=?,last_content_hash=?,updated_at=now() where id=?
                """, fetched.etag(), fetched.lastModified(), sourceChecksum, source.get("id"));
            audits.record("PROVIDER_PRICE_SYNC_COMPLETE", "ProviderPriceSyncRun", runId, null,
                    Map.of("status", state, "sourceId", source.get("id"), "snapshotId", snapshotId,
                            "changed", processed.changed(), "autoPublished", processed.autoPublished(),
                            "reviewRequired", processed.reviewRequired()));
            return new SyncSummary(runId, state, prices.size(), prices.size(), processed.changed(),
                    processed.autoPublished(), processed.reviewRequired(), snapshotId);
        } catch (Exception e) {
            logs.add(log("FAILED", Map.of("message", safe(e.getMessage()))));
            jdbc.update("""
                update provider_price_sync_run set status='FAILED',error_code='PRICE_SYNC_FAILED',error_message=?,
                  completed_at=now(),execution_log=cast(? as jsonb),heartbeat_at=now(),updated_at=now() where id=?
                """, safe(e.getMessage()), write(logs), runId);
            jdbc.update("""
                update provider_price_source set
                  status=case when status in ('PAUSED','DISABLED') then status else 'DEGRADED' end,
                  last_failure_at=now(),last_error=?,updated_at=now() where id=?
                """, safe(e.getMessage()), source.get("id"));
            ensureSyncAlert(source, e);
            return new SyncSummary(runId, "FAILED", 0, 0, 0, 0, 0, null);
        }
    }

    private FetchResult fetch(Map<String,Object> source, boolean conditional) throws Exception {
        URI uri = URI.create(text(source.get("endpoint")));
        String originalHost = uri.getHost();
        ProviderInstance authenticatedInstance = authenticatedInstance(source);
        String managedApiKey = authenticatedInstance == null ? null : providerConnections.resolveManagedApiKey(authenticatedInstance);
        for (int redirect = 0; redirect <= 3; redirect++) {
            validateTarget(uri, source);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", accept(text(source.get("adapter_code"))))
                    .header("User-Agent", "TokenSea-PriceSync/1.0")
                    .GET();
            if (authenticatedInstance != null && uri.getHost().equalsIgnoreCase(originalHost)) {
                providerConnections.applyManagedAuthentication(builder, authenticatedInstance, managedApiKey);
            }
            if (conditional && redirect == 0 && source.get("etag") != null) {
                builder.header("If-None-Match", text(source.get("etag")));
            }
            if (conditional && redirect == 0 && source.get("last_modified") != null) {
                builder.header("If-Modified-Since", text(source.get("last_modified")));
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 304) return new FetchResult(304, "", "", 0,
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("ETag").orElse(text(source.get("etag"))),
                    response.headers().firstValue("Last-Modified").orElse(text(source.get("last_modified"))), uri.toString());
            if (Set.of(301,302,303,307,308).contains(response.statusCode())) {
                if (redirect == 3) throw new IllegalStateException("价格来源重定向次数超过限制");
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("价格来源重定向缺少 Location"));
                uri = uri.resolve(location);
                continue;
            }
            if (response.statusCode() != 200) throw new IllegalStateException("价格来源返回 HTTP " + response.statusCode());
            if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalStateException("价格来源响应超过 5MB 限制");
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            validateContentType(text(source.get("adapter_code")), contentType);
            String content = new String(response.body(), StandardCharsets.UTF_8);
            return new FetchResult(response.statusCode(), content, sha256(response.body()), response.body().length,
                    contentType, response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null), uri.toString());
        }
        throw new IllegalStateException("价格来源获取失败");
    }

    private List<PriceSourceParser.NormalizedPrice> parse(Map<String,Object> source, String content) {
        Map<String,Object> config = readMap(source.get("config"));
        List<PriceSourceParser.NormalizedPrice> prices = parser.parse(text(source.get("adapter_code")), content,
                text(source.get("endpoint")), nullableText(source.get("provider_type")),
                text(source.get("default_currency")), config);
        if (prices.size() > 20_000) throw new IllegalStateException("单次价格同步最多允许 20000 条记录");
        return prices;
    }

    private ProcessResult processReferences(Map<String,Object> source, String runId, String snapshotId,
                                            String checksum, List<PriceSourceParser.NormalizedPrice> prices) {
        int changed = 0;
        for (PriceSourceParser.NormalizedPrice price : prices) {
            String canonical = canonicalReference(price);
            String priceJson = write(normalizedMap(price));
            String evidenceHash = sha256((checksum + ":" + priceJson).getBytes(StandardCharsets.UTF_8));
            changed += jdbc.update("""
                insert into public_model_price_reference(
                  id,price_source_id,raw_snapshot_id,sync_run_id,provider_type,provider_model_name,canonical_name,
                  display_name,currency,region,request_mode,service_tier,context_tier,input_amount_per_1k,
                  output_amount_per_1k,price_components,source_ref,evidence_hash,source_confidence,status,observed_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,0.7000,'ACTIVE',now())
                on conflict(price_source_id,provider_type,provider_model_name,region,request_mode,service_tier,context_tier)
                do update set raw_snapshot_id=excluded.raw_snapshot_id,sync_run_id=excluded.sync_run_id,
                  canonical_name=excluded.canonical_name,display_name=excluded.display_name,currency=excluded.currency,
                  input_amount_per_1k=excluded.input_amount_per_1k,output_amount_per_1k=excluded.output_amount_per_1k,
                  price_components=excluded.price_components,source_ref=excluded.source_ref,
                  evidence_hash=excluded.evidence_hash,status='ACTIVE',observed_at=now(),updated_at=now()
                where public_model_price_reference.evidence_hash is distinct from excluded.evidence_hash
                """, id(), source.get("id"), snapshotId, runId, price.providerType(), price.providerModelName(),
                    canonical, price.displayName(), price.currency(), price.region(), price.requestMode(),
                    price.serviceTier(), price.contextTier(), price.inputAmountPer1k(), price.outputAmountPer1k(),
                    write(price.components()), price.sourceRef(), evidenceHash);
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
                                          String checksum, List<PriceSourceParser.NormalizedPrice> prices) {
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
            String diffId = id();
            String newJson = write(normalizedMap(price));
            jdbc.update("""
                insert into provider_price_diff(id,price_source_id,sync_run_id,raw_snapshot_id,provider_type,
                  provider_model_name,region,request_mode,service_tier,context_tier,diff_type,old_value,new_value,
                  change_ratio,risk_level,status)
                values(?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?, 'PENDING')
                """, diffId, source.get("id"), runId, snapshotId, price.providerType(), price.providerModelName(),
                    price.region(), price.requestMode(), price.serviceTier(), price.contextTier(), assessment.type(),
                    current == null ? null : write(catalogNormalized(current)), newJson, assessment.changeRatio(), assessment.risk());
            boolean canAutoPublish = Boolean.TRUE.equals(source.get("auto_publish"))
                    && "LOW".equals(assessment.risk())
                    && confirmed(source, price, newJson);
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
                  risk_level,status)
                values(?,?,?,?,?,?,?,?,?,?,'MODEL_REMOVED',cast(? as jsonb),'HIGH','PENDING')
                """, id(), source.get("id"), runId, snapshotId, row.get("provider_type"), row.get("provider_model_name"),
                    row.get("region"), row.get("request_mode"), row.get("service_tier"), row.get("context_tier"),
                    write(catalogNormalized(row)));
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
              and lower(region)=lower(?) and lower(request_mode)=lower(?) and lower(service_tier)=lower(?)
              and lower(context_tier)=lower(?)
            """, Integer.class, price.providerType(), price.providerModelName(), price.region(), price.requestMode(),
                price.serviceTier(), price.contextTier());
        String catalogId = id();
        String normalizedJson = write(normalizedMap(price));
        String sourceType = "OFFICIAL_JSON".equals(source.get("adapter_code")) || "OFFICIAL_CSV".equals(source.get("adapter_code"))
                ? "PROVIDER_API" : "OFFICIAL_REFERENCE";
        jdbc.update("""
            insert into provider_model_price_catalog(
              id,provider_type,provider_model_name,display_name,aliases,currency,billing_unit,input_amount_per_1k,
              output_amount_per_1k,source_type,source_ref,source_confidence,source_updated_at,effective_from,effective_to,
              revision,status,created_by,updated_by,price_source_id,raw_snapshot_id,sync_run_id,parser_version,publish_mode,
              evidence_hash,region,request_mode,service_tier,context_tier,normalized_price)
            values(?,?,?,?,cast('[]' as jsonb),?,'PER_1K_TOKENS',?,?,?,?,1,now(),?,?,?,'ACTIVE',?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
            """, catalogId, price.providerType(), price.providerModelName(), price.displayName(), price.currency(),
                price.inputAmountPer1k(), price.outputAmountPer1k(), sourceType, price.sourceRef(),
                price.effectiveFrom() == null ? OffsetDateTime.now() : price.effectiveFrom(), price.effectiveTo(),
                revision == null ? 1 : revision, actor, actor, source.get("id"), diff.get("raw_snapshot_id"),
                diff.get("sync_run_id"), source.get("parser_version"), publishMode,
                snapshotChecksum(diff.get("raw_snapshot_id")), price.region(), price.requestMode(), price.serviceTier(),
                price.contextTier(), normalizedJson);
        saveComponents(catalogId, price.components());
        ProviderPriceCatalogService.RematchSummary rematch = matcher.rematchCatalog(catalogId);
        audits.record("PROVIDER_PRICE_PUBLISH", "ProviderModelPriceCatalog", catalogId, null,
                Map.of("mode", publishMode, "sourceId", source.get("id"), "diffId", diff.get("id"),
                        "rematch", rematch));
        return catalogId;
    }

    private void saveComponents(String catalogId, Map<String,Object> components) {
        for (Map.Entry<String,Object> entry : components.entrySet()) {
            if (!(entry.getValue() instanceof Map<?,?> component)) continue;
            BigDecimal unitPrice = decimal(component.get("unitPrice"), BigDecimal.ZERO);
            String unitBasis = text(component.get("unitBasis"));
            Map<String,Object> scope = component.get("scope") instanceof Map<?,?> map ? stringMap(map) : Map.of();
            String scopeJson = write(scope);
            jdbc.update("""
                insert into provider_price_component(id,catalog_price_id,component_type,unit_price,unit_basis,scope,scope_hash)
                values(?,?,?,?,?,cast(? as jsonb),?)
                """, id(), catalogId, entry.getKey(), unitPrice, value(unitBasis, "PER_1K_TOKENS"),
                    scopeJson, sha256(scopeJson.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private DiffAssessment assess(Map<String,Object> current, PriceSourceParser.NormalizedPrice price, BigDecimal threshold) {
        if (current == null) return new DiffAssessment("MODEL_ADDED", BigDecimal.ZERO, "LOW");
        if (!Objects.equals(text(current.get("currency")), price.currency())) return new DiffAssessment("CURRENCY_CHANGED", null, "HIGH");
        Map<String,Object> oldNormalized = catalogNormalized(current);
        Map<String,Object> oldComponents = readMap(oldNormalized.get("components"));
        if (!oldComponents.keySet().equals(price.components().keySet())) return new DiffAssessment("BILLING_DIMENSION_CHANGED", null, "HIGH");
        BigDecimal oldInput = decimal(current.get("input_amount_per_1k"), BigDecimal.ZERO);
        BigDecimal oldOutput = decimal(current.get("output_amount_per_1k"), BigDecimal.ZERO);
        if (oldInput.compareTo(price.inputAmountPer1k()) == 0 && oldOutput.compareTo(price.outputAmountPer1k()) == 0
                && Objects.equals(oldComponents, price.components())) return new DiffAssessment(null, BigDecimal.ZERO, "LOW");
        BigDecimal ratio = maxRatio(oldInput, price.inputAmountPer1k(), oldOutput, price.outputAmountPer1k());
        return new DiffAssessment("PRICE_CHANGED", ratio, ratio.compareTo(threshold) <= 0 ? "LOW" : "HIGH");
    }

    private boolean confirmed(Map<String,Object> source, PriceSourceParser.NormalizedPrice price, String newJson) {
        int required = ((Number) source.get("confirmation_runs")).intValue();
        if (required <= 1) return true;
        Integer count = jdbc.queryForObject("""
            select count(distinct sync_run_id) from provider_price_diff
            where price_source_id=? and provider_type=? and provider_model_name=? and region=?
              and request_mode=? and service_tier=? and context_tier=? and new_value=cast(? as jsonb)
            """, Integer.class, source.get("id"), price.providerType(), price.providerModelName(), price.region(),
                price.requestMode(), price.serviceTier(), price.contextTier(), newJson);
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

    private void finishNoChange(String runId, Map<String,Object> source, FetchResult fetched, List<Map<String,Object>> logs) {
        logs.add(log("NO_CHANGE", Map.of("httpStatus", fetched.statusCode())));
        jdbc.update("""
            update provider_price_sync_run set status='NO_CHANGE',http_status=?,completed_at=now(),
              execution_log=cast(? as jsonb),heartbeat_at=now(),updated_at=now() where id=?
            """, fetched.statusCode(), write(logs), runId);
        jdbc.update("""
            update provider_price_source set
              status=case when status in ('PAUSED','DISABLED') then status else 'ACTIVE' end,
              last_success_at=now(),last_error=null,etag=coalesce(?,etag),
              last_modified=coalesce(?,last_modified),updated_at=now() where id=?
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

    private void validateContentType(String adapter, String contentType) {
        String type = value(contentType, "").toLowerCase(Locale.ROOT);
        boolean accepted = switch (adapter) {
            case "OFFICIAL_CSV" -> type.contains("csv") || type.contains("text/plain") || type.contains("octet-stream");
            case "DEEPSEEK_OFFICIAL_PAGE" -> type.contains("html") || type.contains("text/plain");
            default -> type.contains("json") || type.contains("text/plain") || type.contains("octet-stream");
        };
        if (!accepted) throw new IllegalStateException("价格来源 Content-Type 不符合适配器要求: " + contentType);
    }

    private Map<String,Object> normalizedMap(PriceSourceParser.NormalizedPrice price) {
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("providerType", price.providerType());
        map.put("providerModelName", price.providerModelName());
        map.put("displayName", price.displayName());
        map.put("currency", price.currency());
        map.put("billingUnit", "PER_1K_TOKENS");
        map.put("inputAmountPer1k", price.inputAmountPer1k());
        map.put("outputAmountPer1k", price.outputAmountPer1k());
        map.put("region", price.region());
        map.put("requestMode", price.requestMode());
        map.put("serviceTier", price.serviceTier());
        map.put("contextTier", price.contextTier());
        map.put("components", price.components());
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
        result.put("billingUnit", row.get("billing_unit"));
        result.put("inputAmountPer1k", row.get("input_amount_per_1k"));
        result.put("outputAmountPer1k", row.get("output_amount_per_1k"));
        result.put("region", row.get("region"));
        result.put("requestMode", row.get("request_mode"));
        result.put("serviceTier", row.get("service_tier"));
        result.put("contextTier", row.get("context_tier"));
        result.put("components", Map.of(
                "INPUT_TOKEN", Map.of("unitPrice", row.get("input_amount_per_1k"), "unitBasis", "PER_1K_TOKENS"),
                "OUTPUT_TOKEN", Map.of("unitPrice", row.get("output_amount_per_1k"), "unitBasis", "PER_1K_TOKENS")));
        result.put("sourceRef", row.get("source_ref"));
        result.put("effectiveFrom", row.get("effective_from"));
        result.put("effectiveTo", row.get("effective_to"));
        return result;
    }

    private PriceSourceParser.NormalizedPrice priceFromJson(Object value) {
        Map<String,Object> map = readMap(value);
        return new PriceSourceParser.NormalizedPrice(text(map.get("providerType")), text(map.get("providerModelName")),
                text(map.get("displayName")), text(map.get("currency")), decimal(map.get("inputAmountPer1k"), BigDecimal.ZERO),
                decimal(map.get("outputAmountPer1k"), BigDecimal.ZERO), value(text(map.get("region")), "global"),
                value(text(map.get("requestMode")), "STANDARD"), value(text(map.get("serviceTier")), "DEFAULT"),
                value(text(map.get("contextTier")), "DEFAULT"), readMap(map.get("components")), text(map.get("sourceRef")),
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
        String model = price.providerModelName();
        return model.contains("/") ? model : price.providerType() + "/" + model;
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

    private OffsetDateTime nextRun(String expression) {
        try { return OffsetDateTime.now().plus(Duration.parse(expression)); }
        catch (Exception e) { throw new IllegalArgumentException("同步周期必须是 ISO-8601 Duration"); }
    }

    private static String accept(String adapter) {
        if ("OFFICIAL_CSV".equals(adapter)) return "text/csv,text/plain;q=0.9,*/*;q=0.1";
        if ("DEEPSEEK_OFFICIAL_PAGE".equals(adapter)) return "text/html,application/xhtml+xml;q=0.9";
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
    private record ProcessResult(int changed, int autoPublished, int reviewRequired) {}
    private record DiffAssessment(String type, BigDecimal changeRatio, String risk) {}
}
