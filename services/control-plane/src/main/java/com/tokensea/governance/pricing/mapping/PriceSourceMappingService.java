package com.tokensea.governance.pricing.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PriceSourceMappingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PriceSourceMappingService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String,Object> enrichConfig(String sourceId, Map<String,Object> original) {
        Map<String,Object> result = new LinkedHashMap<>(original == null ? Map.of() : original);
        result.put("mappingRules", rules(sourceId));
        return result;
    }

    public List<Map<String,Object>> rules(String sourceId) {
        return jdbc.queryForList("""
            select id,rule_name "ruleName",mapping_profile "mappingProfile",
              external_service_pattern "externalServicePattern",
              external_product_pattern "externalProductPattern",
              external_sku_pattern "externalSkuPattern",
              external_meter_pattern "externalMeterPattern",
              external_model_pattern "externalModelPattern",
              target_provider_type "targetProviderType",target_model_name "targetModelName",
              target_component_type "targetComponentType",target_request_mode "targetRequestMode",
              target_service_tier "targetServiceTier",target_context_tier "targetContextTier",
              target_region "targetRegion",billing_basis "billingBasis",
              billing_quantity "billingQuantity",transform_config "transformConfig",priority,status
            from price_source_mapping_rule
            where price_source_id=? and status='ACTIVE'
            order by priority,id
            """, sourceId);
    }

    @Transactional
    public int persistUnmapped(String sourceId,
                               String runId,
                               String snapshotId,
                               Map<String,Object> sourceEvidence) {
        Object value = sourceEvidence == null ? null : sourceEvidence.get("unmappedRecords");
        if (!(value instanceof List<?> records) || records.isEmpty()) return 0;
        int changed = 0;
        for (Object item : records) {
            Map<String,Object> record = map(item);
            if (record.isEmpty()) continue;
            String raw = write(record.getOrDefault("rawPayload", record));
            String evidenceHash = sha256(String.join("|",
                    sourceId,
                    text(record.get("externalRecordId")),
                    text(record.get("externalService")),
                    text(record.get("externalProduct")),
                    text(record.get("externalSku")),
                    text(record.get("externalMeter")),
                    text(record.get("externalModel")),
                    text(record.get("externalRegion")),
                    text(record.get("externalCurrency")),
                    text(record.get("externalUnit")),
                    text(record.get("externalPrice")),
                    text(record.get("reasonCode")), raw));
            changed += jdbc.update("""
                insert into price_source_unmapped_record(
                  id,price_source_id,sync_run_id,raw_snapshot_id,external_record_id,external_service,
                  external_product,external_sku,external_meter,external_model,external_region,
                  external_currency,external_unit,external_price,reason_code,reason_message,
                  evidence_hash,raw_payload,status)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?,cast(? as jsonb),'OPEN')
                on conflict(price_source_id,evidence_hash) do update set
                  sync_run_id=excluded.sync_run_id,raw_snapshot_id=excluded.raw_snapshot_id,
                  occurrence_count=price_source_unmapped_record.occurrence_count+1,
                  last_seen_at=now(),reason_code=excluded.reason_code,reason_message=excluded.reason_message,
                  raw_payload=excluded.raw_payload,updated_at=now()
                """, id(), sourceId, runId, snapshotId,
                    nullable(record.get("externalRecordId")), nullable(record.get("externalService")),
                    nullable(record.get("externalProduct")), nullable(record.get("externalSku")),
                    nullable(record.get("externalMeter")), nullable(record.get("externalModel")),
                    nullable(record.get("externalRegion")), nullable(record.get("externalCurrency")),
                    nullable(record.get("externalUnit")), decimalText(record.get("externalPrice")),
                    value(record.get("reasonCode"), "MAPPING_NOT_FOUND"),
                    nullable(record.get("reasonMessage")), evidenceHash, raw);
        }
        return changed;
    }

    public Map<String,Object> previewCoverage(String sourceId, List<Map<String,Object>> externalRecords) {
        List<Map<String,Object>> configuredRules = rules(sourceId);
        int mapped = 0;
        List<Map<String,Object>> unmatched = new ArrayList<>();
        for (Map<String,Object> record : externalRecords == null ? List.<Map<String,Object>>of() : externalRecords) {
            boolean match = configuredRules.stream().anyMatch(rule -> matches(rule, record));
            if (match) mapped++; else unmatched.add(record);
        }
        int total = externalRecords == null ? 0 : externalRecords.size();
        return Map.of(
                "total", total,
                "mapped", mapped,
                "unmapped", total - mapped,
                "coverageRatio", total == 0 ? 0D : (double) mapped / total,
                "unmatchedSample", unmatched.stream().limit(20).toList());
    }

    private boolean matches(Map<String,Object> rule, Map<String,Object> record) {
        return match(rule.get("externalServicePattern"), record.get("externalService"))
                && match(rule.get("externalProductPattern"), record.get("externalProduct"))
                && match(rule.get("externalSkuPattern"), record.get("externalSku"))
                && match(rule.get("externalMeterPattern"), record.get("externalMeter"))
                && match(rule.get("externalModelPattern"), record.get("externalModel"));
    }

    private boolean match(Object expression, Object value) {
        String pattern = text(expression);
        if (pattern.isBlank()) return true;
        try {
            return java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(text(value)).find();
        } catch (Exception exception) {
            return false;
        }
    }

    private Map<String,Object> map(Object value) {
        if (value instanceof Map<?,?> source) {
            Map<String,Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return json.convertValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("价格映射 JSON 序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String nullable(Object value) {
        String text = text(value);
        return text.isBlank() ? null : text;
    }

    private String decimalText(Object value) {
        String text = text(value);
        return text.isBlank() ? null : text;
    }

    private String value(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }
}
