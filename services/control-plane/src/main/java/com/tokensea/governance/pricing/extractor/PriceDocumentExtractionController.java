package com.tokensea.governance.pricing.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import com.tokensea.governance.ProviderPriceSyncService;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PriceDocumentExtractionController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PriceDocumentExtractionService extractions;
    private final ProviderPriceSyncService priceSync;
    private final AuditService audits;

    public PriceDocumentExtractionController(JdbcTemplate jdbc,
                                             ObjectMapper json,
                                             PriceDocumentExtractionService extractions,
                                             ProviderPriceSyncService priceSync,
                                             AuditService audits) {
        this.jdbc = jdbc;
        this.json = json;
        this.extractions = extractions;
        this.priceSync = priceSync;
        this.audits = audits;
    }

    public record ReviewRequest(String decision, Map<String,Object> correction, String reason) {}
    public record SubmitRequest(String reason) {}

    @GetMapping("/price-document-extraction-runs")
    public ApiResponse<PageResult<Map<String,Object>>> runs(
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "createdAt", "r.created_at", "startedAt", "r.started_at", "finishedAt", "r.finished_at",
                "status", "r.status", "documentType", "r.document_type",
                "acceptedRecordCount", "r.accepted_record_count"), "createdAt", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String normalizedStatus = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or lower(s.name) like ? or lower(coalesce(s.provider_type,'')) like ?)
              and (?::text is null or r.status=?)
            """;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select r.*,s.name "sourceName",s.provider_type "providerType",s.adapter_code "adapterCode",
              p.content_type "snapshotContentType",p.checksum "snapshotChecksum"
            from price_document_extraction_run r
            join provider_price_source s on s.id=r.price_source_id
            join provider_price_raw_snapshot p on p.id=r.raw_snapshot_id
            """ + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                + ",r.id " + paging.direction() + " limit ? offset ?",
                q, q, q, normalizedStatus, normalizedStatus, paging.size(), paging.offset());
        Long total = jdbc.queryForObject("""
            select count(*) from price_document_extraction_run r
            join provider_price_source s on s.id=r.price_source_id
            """ + filter, Long.class, q, q, q, normalizedStatus, normalizedStatus);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/price-document-extraction-runs/{id}")
    public ApiResponse<Map<String,Object>> run(@PathVariable String id) {
        Map<String,Object> run = new LinkedHashMap<>(extractions.requireRun(id));
        List<Map<String,Object>> snapshots = jdbc.queryForList("""
            select p.id,p.content_type,p.source_endpoint,p.final_endpoint,p.checksum,p.response_bytes,
              p.raw_content,p.fetched_at
            from provider_price_raw_snapshot p
            join price_document_extraction_run r on r.raw_snapshot_id=p.id where r.id=?
            """, id);
        run.put("snapshot", snapshots.isEmpty() ? Map.of() : snapshots.getFirst());
        run.put("records", jdbc.queryForList("""
            select r.*,e.page_number,e.table_index,e.row_index,e.column_index,e.source_text,e.source_hash,e.coordinates
            from price_document_extracted_record r
            left join price_document_evidence e on e.id=r.evidence_id
            where r.extraction_run_id=? order by r.created_at,r.id
            """, id).stream().map(this::normalizeRecord).toList());
        return ApiResponse.ok(run);
    }

    @GetMapping("/price-document-extracted-records")
    public ApiResponse<PageResult<Map<String,Object>>> records(
            @RequestParam(required=false) String runId,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size,
            @RequestParam(required=false) String sort,
            @RequestParam(required=false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.of(
                "createdAt", "r.created_at", "confidence", "r.confidence",
                "reviewStatus", "r.review_status", "providerModelName", "r.provider_model_name"),
                "createdAt", "desc");
        String q = blank(keyword) ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String normalizedStatus = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String filter = """
            where (?::text is null or r.extraction_run_id=?)
              and (?::text is null or r.review_status=?)
              and (?::text is null or lower(r.provider_model_name) like ? or lower(s.name) like ?)
            """;
        Object[] args = {runId, runId, normalizedStatus, normalizedStatus, q, q, q};
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select r.id,r.extraction_run_id "extractionRunId",r.record_key "recordKey",
              r.provider_type "providerType",r.provider_model_name "providerModelName",
              r.extraction_method "extractionMethod",r.confidence,r.validation_status "validationStatus",
              r.validation_result "validationResult",r.review_status "reviewStatus",r.review_reason "reviewReason",
              r.reviewed_by "reviewedBy",r.reviewed_at "reviewedAt",r.created_at "createdAt",
              s.name "sourceName",e.page_number "pageNumber",e.table_index "tableIndex",
              e.row_index "rowIndex",e.source_text "sourceText"
            from price_document_extracted_record r
            join price_document_extraction_run x on x.id=r.extraction_run_id
            join provider_price_source s on s.id=x.price_source_id
            left join price_document_evidence e on e.id=r.evidence_id
            """ + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                + ",r.id " + paging.direction() + " limit ? offset ?",
                append(args, paging.size(), paging.offset()));
        Long total = jdbc.queryForObject("""
            select count(*) from price_document_extracted_record r
            join price_document_extraction_run x on x.id=r.extraction_run_id
            join provider_price_source s on s.id=x.price_source_id
            """ + filter, Long.class, args);
        return ApiResponse.ok(new PageResult<>(rows.stream().map(this::normalizeRecord).toList(),
                total == null ? 0 : total, paging.page(), paging.size()));
    }

    @GetMapping("/price-document-extracted-records/{id}")
    public ApiResponse<Map<String,Object>> record(@PathVariable String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select r.*,e.page_number,e.table_index,e.row_index,e.column_index,e.source_text,e.source_hash,e.coordinates
            from price_document_extracted_record r
            left join price_document_evidence e on e.id=r.evidence_id where r.id=?
            """, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "价格文档抽取记录不存在");
        return ApiResponse.ok(normalizeRecord(rows.getFirst()));
    }

    @PostMapping("/price-document-extracted-records/{id}/review")
    public ApiResponse<Map<String,Object>> review(@PathVariable String id,
                                                   @RequestBody ReviewRequest request,
                                                   Authentication authentication) {
        requireAdmin(authentication);
        Map<String,Object> before = record(id).data();
        Map<String,Object> after = extractions.reviewRecord(id, request == null ? null : request.decision(),
                request == null ? null : request.correction(), actor(authentication),
                request == null ? null : request.reason());
        audits.record("PRICE_DOCUMENT_EXTRACTION_RECORD_REVIEW", "PriceDocumentExtractedRecord", id, before, after);
        return ApiResponse.ok(after);
    }

    @PostMapping("/price-document-extraction-runs/{id}/submit")
    public ApiResponse<Map<String,Object>> submit(@PathVariable String id,
                                                   @RequestBody(required=false) SubmitRequest request,
                                                   Authentication authentication) {
        requireAdmin(authentication);
        return ApiResponse.ok(priceSync.submitExtractionRun(id, actor(authentication),
                request == null ? null : request.reason()));
    }

    private Map<String,Object> normalizeRecord(Map<String,Object> value) {
        Map<String,Object> row = new LinkedHashMap<>(value);
        for (String key : List.of("normalized_record", "normalizedRecord", "validation_result", "validationResult",
                "correction", "coordinates")) {
            if (!row.containsKey(key) || row.get(key) == null || row.get(key) instanceof Map<?,?>) continue;
            try { row.put(key, json.readValue(String.valueOf(row.get(key)), Map.class)); }
            catch (Exception ignored) { row.put(key, Map.of()); }
        }
        return row;
    }

    private void requireAdmin(Authentication authentication) {
        if (!(authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                && identity.roles().contains("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅平台管理员可以审核价格文档抽取记录");
        }
    }

    private String actor(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity
                ? identity.userId() : "SYSTEM";
    }

    private Object[] append(Object[] values, Object... extra) {
        Object[] result = new Object[values.length + extra.length];
        System.arraycopy(values, 0, result, 0, values.length);
        System.arraycopy(extra, 0, result, values.length, extra.length);
        return result;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
