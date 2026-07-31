package com.tokensea.governance.pricing.extractor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.governance.PriceSourceParser;
import com.tokensea.governance.pricing.adapter.PriceSourceParseResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

@Service
public class PriceDocumentExtractionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PriceExtractionValidator validator;
    private final ExtractionConfidenceCalculator confidence;

    public PriceDocumentExtractionService(JdbcTemplate jdbc,
                                          ObjectMapper json,
                                          PriceExtractionValidator validator,
                                          ExtractionConfidenceCalculator confidence) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
        this.confidence = confidence;
    }

    @Transactional
    public PersistenceResult persist(Map<String,Object> source,
                                     String syncRunId,
                                     String snapshotId,
                                     PriceSourceParseResult parsed) {
        String runId = id();
        Map<String,Object> sourceEvidence = parsed.sourceEvidence() == null ? Map.of() : parsed.sourceEvidence();
        Map<String,Object> config = map(source.get("config"));
        String documentType = value(sourceEvidence.get("documentType"), "TEXT").toUpperCase(Locale.ROOT);
        String extractionMode = extractionMode(config, sourceEvidence);
        String schemaVersion = value(source.get("schema_version"), "price-record-v1");
        jdbc.update("""
            insert into price_document_extraction_run(
              id,price_source_id,sync_run_id,raw_snapshot_id,document_type,extractor_code,
              extraction_mode,schema_version,llm_model,llm_request_id,llm_prompt_hash,
              llm_response_hash,llm_latency_ms,status)
            values(?,?,?,?,?,'GENERIC_DOCUMENT',?,?,?,?,?,?,?,'RUNNING')
            """, runId, source.get("id"), syncRunId, snapshotId, documentType, extractionMode,
                schemaVersion, nullable(sourceEvidence.get("llmModel")), nullable(sourceEvidence.get("llmRequestId")),
                nullable(sourceEvidence.get("llmPromptHash")), nullable(sourceEvidence.get("llmResponseHash")),
                integerNullable(sourceEvidence.get("llmLatencyMs")));

        List<PriceSourceParser.NormalizedPrice> accepted = new ArrayList<>();
        int deterministic = 0;
        int llm = 0;
        int pending = 0;
        int rejected = 0;
        int evidenceComplete = 0;
        BigDecimal confidenceTotal = BigDecimal.ZERO;
        BigDecimal minConfidence = decimal(source.get("minimum_confidence"),
                decimal(config.get("minimumConfidence"), new BigDecimal("0.85")));
        boolean requireManualReview = source.get("require_manual_review") != null
                ? Boolean.TRUE.equals(source.get("require_manual_review"))
                : Boolean.TRUE.equals(config.get("requireManualReview"));
        Map<String,String> evidenceByRecordKey = new LinkedHashMap<>();

        for (PriceSourceParser.NormalizedPrice price : parsed.prices()) {
            Map<String,Object> raw = price.raw() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(price.raw());
            String method = value(raw.get("extractionMethod"), value(sourceEvidence.get("extractionMethod"), "DETERMINISTIC_MAPPING"));
            if (method.startsWith("LLM")) llm++; else deterministic++;
            Map<String,Object> evidence = evidence(raw, price, documentType);
            PriceExtractionValidator.Validation validation = validator.validate(price, evidence);
            BigDecimal score = confidence.calculate(method, evidence, validation, raw.get("confidence"));
            confidenceTotal = confidenceTotal.add(score);
            if (!value(evidence.get("sourceText"), "").isBlank()) evidenceComplete++;
            String recordKey = recordKey(price);
            String evidenceId = saveEvidence(runId, recordKey, evidence);
            evidenceByRecordKey.put(recordKey, evidenceId);

            String reviewStatus;
            if (!validation.valid()) {
                reviewStatus = "REJECTED";
                rejected++;
            } else if (method.startsWith("LLM") || requireManualReview
                    || score.compareTo(minConfidence) < 0 || "WARNING".equals(validation.status())) {
                reviewStatus = "PENDING";
                pending++;
            } else {
                reviewStatus = "ACCEPTED";
                accepted.add(price);
            }
            Map<String,Object> validationResult = new LinkedHashMap<>();
            validationResult.put("errors", validation.errors());
            validationResult.put("warnings", validation.warnings());
            validationResult.put("evidenceComplete", !value(evidence.get("sourceText"), "").isBlank());
            jdbc.update("""
                insert into price_document_extracted_record(
                  id,extraction_run_id,evidence_id,record_key,provider_type,provider_model_name,
                  normalized_record,extraction_method,confidence,validation_status,validation_result,review_status)
                values(?,?,?,?,?,?,cast(? as jsonb),?,?,?,cast(? as jsonb),?)
                """, id(), runId, evidenceId, recordKey, price.providerType(), price.providerModelName(),
                    write(priceMap(price)), method, score, validation.status(), write(validationResult), reviewStatus);
        }

        int total = parsed.prices().size();
        String status = total == 0 ? "FAILED"
                : pending > 0 ? "REVIEW_REQUIRED"
                : rejected == total ? "FAILED" : "SUCCEEDED";
        BigDecimal average = total == 0 ? BigDecimal.ZERO
                : confidenceTotal.divide(BigDecimal.valueOf(total), 5, java.math.RoundingMode.HALF_UP);
        Map<String,Object> confidenceSummary = Map.of(
                "average", average,
                "minimumRequired", minConfidence,
                "evidenceCompleteness", total == 0 ? BigDecimal.ZERO
                        : BigDecimal.valueOf(evidenceComplete).divide(BigDecimal.valueOf(total), 5, java.math.RoundingMode.HALF_UP));
        Map<String,Object> validationSummary = Map.of(
                "total", total,
                "accepted", accepted.size(),
                "pendingReview", pending,
                "rejected", rejected,
                "requireManualReview", requireManualReview);
        jdbc.update("""
            update price_document_extraction_run set deterministic_record_count=?,llm_record_count=?,
              accepted_record_count=?,rejected_record_count=?,evidence_complete_count=?,
              confidence_summary=cast(? as jsonb),validation_summary=cast(? as jsonb),
              status=?,finished_at=now() where id=?
            """, deterministic, llm, accepted.size(), rejected, evidenceComplete,
                write(confidenceSummary), write(validationSummary), status, runId);
        return new PersistenceResult(runId, List.copyOf(accepted), pending, rejected,
                Map.copyOf(evidenceByRecordKey), status);
    }

    @Transactional
    public Map<String,Object> reviewRecord(String recordId,
                                           String decision,
                                           Map<String,Object> correction,
                                           String actor,
                                           String reason) {
        Map<String,Object> before = requireRecord(recordId);
        String normalizedDecision = value(decision, "").toUpperCase(Locale.ROOT);
        if (!List.of("ACCEPTED", "CORRECTED", "REJECTED", "NON_PRICE").contains(normalizedDecision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抽取记录审核决定无效");
        }
        String current = value(before.get("review_status"), "PENDING");
        if (!List.of("PENDING", "ACCEPTED", "CORRECTED").contains(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前抽取记录状态不允许再次审核");
        }
        Map<String,Object> normalized = map(before.get("normalized_record"));
        Map<String,Object> correctionValue = correction == null ? Map.of() : correction;
        String validationStatus = value(before.get("validation_status"), "PENDING");
        Map<String,Object> validationResult = map(before.get("validation_result"));
        if ("ACCEPTED".equals(normalizedDecision) && "INVALID".equals(validationStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "校验失败的抽取记录不能直接接受，请先修正");
        }
        if ("CORRECTED".equals(normalizedDecision)) {
            if (correctionValue.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修正记录必须提供 correction");
            }
            normalized.putAll(correctionValue);
            PriceSourceParser.NormalizedPrice corrected = toPrice(normalized);
            Map<String,Object> evidence = evidenceForRecord(recordId);
            PriceExtractionValidator.Validation validation = validator.validate(corrected, evidence);
            if (!validation.valid()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "修正后的价格记录仍未通过校验：" + String.join("；", validation.errors()));
            }
            validationStatus = validation.status();
            validationResult = Map.of("errors", validation.errors(), "warnings", validation.warnings(),
                    "evidenceComplete", !value(evidence.get("sourceText"), "").isBlank());
        }
        jdbc.update("""
            update price_document_extracted_record set normalized_record=cast(? as jsonb),
              correction=cast(? as jsonb),validation_status=?,validation_result=cast(? as jsonb),
              review_status=?,reviewed_by=?,reviewed_at=now(),review_reason=?,updated_at=now()
            where id=?
            """, write(normalized), write(correctionValue), validationStatus, write(validationResult),
                normalizedDecision, actor, reason, recordId);
        refreshRunCounts(value(before.get("extraction_run_id"), ""));
        return requireRecord(recordId);
    }

    public int pendingCount(String extractionRunId) {
        Integer count = jdbc.queryForObject("""
            select count(*) from price_document_extracted_record
            where extraction_run_id=? and review_status='PENDING'
            """, Integer.class, extractionRunId);
        return count == null ? 0 : count;
    }

    public List<PriceSourceParser.NormalizedPrice> acceptedPrices(String extractionRunId) {
        return acceptedPrices(extractionRunId, false);
    }

    public List<PriceSourceParser.NormalizedPrice> reviewedPrices(String extractionRunId) {
        return acceptedPrices(extractionRunId, true);
    }

    private List<PriceSourceParser.NormalizedPrice> acceptedPrices(String extractionRunId, boolean reviewedOnly) {
        String reviewedFilter = reviewedOnly ? " and reviewed_at is not null" : "";
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select normalized_record from price_document_extracted_record
            where extraction_run_id=? and review_status in ('ACCEPTED','CORRECTED')
            """ + reviewedFilter + " order by created_at,id", extractionRunId);
        List<PriceSourceParser.NormalizedPrice> result = new ArrayList<>();
        for (Map<String,Object> row : rows) result.add(toPrice(map(row.get("normalized_record"))));
        return result;
    }

    public Map<String,Object> requireRun(String runId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select r.*,s.adapter_code,s.source_class,s.provider_type,s.id source_id,
              p.checksum snapshot_checksum,p.content_type snapshot_content_type
            from price_document_extraction_run r
            join provider_price_source s on s.id=r.price_source_id
            join provider_price_raw_snapshot p on p.id=r.raw_snapshot_id
            where r.id=?
            """, runId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "价格文档抽取运行不存在");
        Map<String,Object> row = new LinkedHashMap<>(rows.getFirst());
        normalizeJson(row, "confidence_summary", "validation_summary");
        return row;
    }

    public void markSubmitted(String runId) {
        jdbc.update("""
            update price_document_extraction_run set status='SUCCEEDED',finished_at=now(),
              accepted_record_count=(select count(*) from price_document_extracted_record
                where extraction_run_id=? and review_status in ('ACCEPTED','CORRECTED'))
            where id=?
            """, runId, runId);
    }

    public void linkDiffEvidence(String syncRunId, PersistenceResult extraction) {
        if (extraction == null || extraction.runId() == null) return;
        linkDiffEvidence(syncRunId, extraction.runId());
    }

    public void linkDiffEvidence(String syncRunId, String extractionRunId) {
        if (syncRunId == null || extractionRunId == null) return;
        jdbc.update("""
            update provider_price_diff d set extraction_run_id=?,evidence_id=r.evidence_id,updated_at=now()
            from price_document_extracted_record r
            where r.extraction_run_id=? and d.sync_run_id=?
              and lower(d.provider_type)=lower(r.provider_type)
              and lower(d.provider_model_name)=lower(r.provider_model_name)
              and lower(d.region)=lower(coalesce(r.normalized_record->>'region','global'))
              and lower(d.request_mode)=lower(coalesce(r.normalized_record->>'requestMode','STANDARD'))
              and lower(d.service_tier)=lower(coalesce(r.normalized_record->>'serviceTier','DEFAULT'))
              and lower(d.context_tier)=lower(coalesce(r.normalized_record->>'contextTier','DEFAULT'))
            """, extractionRunId, extractionRunId, syncRunId);
    }

    private String saveEvidence(String runId, String recordKey, Map<String,Object> evidence) {
        String sourceText = value(evidence.get("sourceText"), "");
        if (sourceText.length() > 20_000) sourceText = sourceText.substring(0, 20_000);
        String hash = sha256(sourceText);
        String id = id();
        jdbc.update("""
            insert into price_document_evidence(
              id,extraction_run_id,record_key,page_number,table_index,row_index,column_index,
              source_text,source_hash,coordinates)
            values(?,?,?,?,?,?,?,?,?,cast(? as jsonb))
            """, id, runId, recordKey, integerNullable(evidence.get("pageNumber")),
                integerNullable(evidence.get("tableIndex")), integerNullable(evidence.get("rowIndex")),
                integerNullable(evidence.get("columnIndex")), sourceText, hash,
                write(map(evidence.get("coordinates"))));
        return id;
    }

    private Map<String,Object> requireRecord(String recordId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select r.*,e.source_text,e.page_number,e.table_index,e.row_index,e.column_index,e.coordinates
            from price_document_extracted_record r
            left join price_document_evidence e on e.id=r.evidence_id
            where r.id=?
            """, recordId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "价格文档抽取记录不存在");
        Map<String,Object> row = new LinkedHashMap<>(rows.getFirst());
        normalizeJson(row, "normalized_record", "validation_result", "correction", "coordinates");
        return row;
    }

    private Map<String,Object> evidenceForRecord(String recordId) {
        Map<String,Object> row = requireRecord(recordId);
        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceText", value(row.get("source_text"), ""));
        evidence.put("pageNumber", row.get("page_number"));
        evidence.put("tableIndex", row.get("table_index"));
        evidence.put("rowIndex", row.get("row_index"));
        evidence.put("columnIndex", row.get("column_index"));
        evidence.put("coordinates", map(row.get("coordinates")));
        return evidence;
    }

    private void refreshRunCounts(String runId) {
        if (runId.isBlank()) return;
        Map<String,Object> counts = jdbc.queryForMap("""
            select count(*) filter(where review_status in ('ACCEPTED','CORRECTED')) accepted,
                   count(*) filter(where review_status in ('REJECTED','NON_PRICE')) rejected,
                   count(*) filter(where review_status='PENDING') pending
            from price_document_extracted_record where extraction_run_id=?
            """, runId);
        jdbc.update("""
            update price_document_extraction_run set accepted_record_count=?,rejected_record_count=?,
              status=case when ?>0 then 'REVIEW_REQUIRED' else status end where id=?
            """, ((Number) counts.get("accepted")).intValue(), ((Number) counts.get("rejected")).intValue(),
                ((Number) counts.get("pending")).intValue(), runId);
    }

    private PriceSourceParser.NormalizedPrice toPrice(Map<String,Object> value) {
        String provider = value(value.get("providerType"), "");
        String model = value(value.get("providerModelName"), "");
        if (provider.isBlank() || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准化价格缺少供应商或模型名");
        }
        BigDecimal input = decimalNullable(value.get("inputUnitPrice"));
        BigDecimal output = decimalNullable(value.get("outputUnitPrice"));
        Map<String,Object> components = map(value.get("components"));
        Map<String,Object> raw = map(value.get("raw"));
        return new PriceSourceParser.NormalizedPrice(
                provider,
                model,
                value(value.get("displayName"), model),
                value(value.get("currency"), "USD"),
                value(value.get("billingBasis"), "TOKEN"),
                longValue(value.get("billingQuantity"), 1_000_000L),
                input,
                output,
                value(value.get("region"), "global"),
                value(value.get("requestMode"), "STANDARD"),
                value(value.get("serviceTier"), "DEFAULT"),
                value(value.get("contextTier"), "DEFAULT"),
                components,
                value(value.get("sourceRef"), ""),
                time(value.get("effectiveFrom")),
                time(value.get("effectiveTo")),
                raw);
    }

    private OffsetDateTime time(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof OffsetDateTime time) return time;
        try { return OffsetDateTime.parse(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private BigDecimal decimalNullable(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准化价格金额格式无效");
        }
    }

    private long longValue(Object value, long fallback) {
        if (value == null) return fallback;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private Map<String,Object> evidence(Map<String,Object> raw,
                                        PriceSourceParser.NormalizedPrice price,
                                        String documentType) {
        Map<String,Object> result = map(raw.get("evidence"));
        if (result.isEmpty()) {
            result.put("sourceText", value(raw.get("sourceText"), price.providerModelName()));
            result.put("rowIndex", raw.get("sourceRow"));
        }
        result.putIfAbsent("documentType", documentType);
        result.putIfAbsent("sourceRef", price.sourceRef());
        return result;
    }

    private Map<String,Object> priceMap(PriceSourceParser.NormalizedPrice price) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("providerType", price.providerType());
        value.put("providerModelName", price.providerModelName());
        value.put("displayName", price.displayName());
        value.put("currency", price.currency());
        value.put("billingBasis", price.billingBasis());
        value.put("billingQuantity", price.billingQuantity());
        value.put("inputUnitPrice", price.inputUnitPrice());
        value.put("outputUnitPrice", price.outputUnitPrice());
        value.put("region", price.region());
        value.put("requestMode", price.requestMode());
        value.put("serviceTier", price.serviceTier());
        value.put("contextTier", price.contextTier());
        value.put("components", price.components());
        value.put("sourceRef", price.sourceRef());
        value.put("effectiveFrom", price.effectiveFrom());
        value.put("effectiveTo", price.effectiveTo());
        value.put("raw", price.raw());
        return value;
    }

    private String recordKey(PriceSourceParser.NormalizedPrice price) {
        return String.join("|", lower(price.providerType()), lower(price.providerModelName()),
                lower(price.region()), lower(price.requestMode()), lower(price.serviceTier()), lower(price.contextTier()));
    }

    private String extractionMode(Map<String,Object> config, Map<String,Object> evidence) {
        String configured = value(config.get("extractionMode"), "").toUpperCase(Locale.ROOT);
        if (List.of("DETERMINISTIC", "DETERMINISTIC_LLM", "SPECIALIZED").contains(configured)) return configured;
        return Boolean.TRUE.equals(evidence.get("llmRequested")) ? "DETERMINISTIC_LLM" : "DETERMINISTIC";
    }

    private void normalizeJson(Map<String,Object> row, String... fields) {
        for (String field : fields) {
            if (row.containsKey(field)) row.put(field, map(row.get(field)));
        }
    }

    private Map<String,Object> map(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?,?> source) {
            Map<String,Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try { return json.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (Exception ignored) {
            try { return json.convertValue(value, new TypeReference<>() {}); }
            catch (Exception conversionFailure) { return new LinkedHashMap<>(); }
        }
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        try { return value == null ? fallback : new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private Integer integerNullable(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private String nullable(Object value) {
        String text = value(value, "");
        return text.isBlank() ? null : text;
    }

    private String value(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalStateException("价格抽取 JSON 序列化失败", exception); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    public record PersistenceResult(String runId,
                                    List<PriceSourceParser.NormalizedPrice> acceptedPrices,
                                    int pendingReview,
                                    int rejected,
                                    Map<String,String> evidenceByRecordKey,
                                    String status) {}
}
