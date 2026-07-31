package com.tokensea.governance.pricing.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReferencePriceBundleLoader {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ResourceLoader resources;
    private final String resourceLocation;
    private final int staleAfterHours;

    public ReferencePriceBundleLoader(JdbcTemplate jdbc,
                                      ObjectMapper json,
                                      ResourceLoader resources,
                                      @Value("${tokensea.reference-price.bootstrap-resource:classpath:reference-prices/reference-price-bootstrap.json}")
                                      String resourceLocation,
                                      @Value("${tokensea.reference-price.hard-stale-after-hours:720}") int staleAfterHours) {
        this.jdbc = jdbc;
        this.json = json;
        this.resources = resources;
        this.resourceLocation = resourceLocation;
        this.staleAfterHours = staleAfterHours;
    }

    @Transactional
    public BundleLoadResult load() {
        try {
            Resource resource = resources.getResource(resourceLocation);
            if (!resource.exists()) return new BundleLoadResult("MISSING", "", 0, 0);
            byte[] bytes = resource.getInputStream().readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            JsonNode root = json.readTree(content);
            String schemaVersion = root.path("schemaVersion").asText("");
            if (!"reference-price-bundle-v1".equals(schemaVersion)) {
                throw new IllegalStateException("不支持的参考价格快照 Schema: " + schemaVersion);
            }
            String bundleVersion = required(root, "bundleVersion");
            OffsetDateTime generatedAt = OffsetDateTime.parse(required(root, "generatedAt"));
            JsonNode prices = root.path("prices");
            if (!prices.isArray()) throw new IllegalStateException("参考价格快照缺少 prices 数组");

            String checksum = sha256(bytes);
            String runId = "bundle_run_" + checksum.substring(0, 32);
            String snapshotId = "bundle_snapshot_" + checksum.substring(0, 28);
            jdbc.update("""
                insert into provider_price_sync_run(
                  id,price_source_id,trigger_type,status,scheduled_for,started_at,completed_at,http_status,
                  records_fetched,records_normalized,records_changed,records_auto_published,
                  records_review_required,execution_log,lock_owner,heartbeat_at)
                values(?,?,'SCHEDULED','SUCCEEDED',now(),now(),now(),200,?,?,?,?,0,
                  cast(? as jsonb),'REFERENCE_BUNDLE',now())
                on conflict(id) do update set completed_at=excluded.completed_at,
                  records_fetched=excluded.records_fetched,records_normalized=excluded.records_normalized,
                  records_changed=excluded.records_changed,records_auto_published=excluded.records_auto_published,
                  execution_log=excluded.execution_log,updated_at=now()
                """, runId, BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID,
                    prices.size(), prices.size(), prices.size(), prices.size(),
                    json.writeValueAsString(List.of(Map.of(
                            "event", "REFERENCE_BUNDLE_IMPORTED",
                            "bundleVersion", bundleVersion,
                            "records", prices.size()))));
            jdbc.update("""
                insert into provider_price_raw_snapshot(
                  id,price_source_id,sync_run_id,source_endpoint,final_endpoint,http_status,content_type,
                  checksum,response_bytes,raw_content,parser_version,fetched_at)
                values(?,?,?,'bundle://tokensea/reference-prices','bundle://tokensea/reference-prices',200,
                  'application/json',?,?,?,'1.0.0',?)
                on conflict(price_source_id,checksum) do nothing
                """, snapshotId, BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID, runId,
                    checksum, bytes.length, content, generatedAt);
            String persistedSnapshotId = jdbc.queryForObject("""
                select id from provider_price_raw_snapshot where price_source_id=? and checksum=?
                """, String.class, BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID, checksum);

            int changed = 0;
            for (JsonNode price : prices) changed += upsertPrice(
                    price, bundleVersion, generatedAt, runId, persistedSnapshotId, checksum);
            jdbc.update("""
                update provider_price_source set bootstrap_version=?,last_checked_at=now(),
                  last_good_sync_at=coalesce(last_good_sync_at,?),last_success_at=coalesce(last_success_at,?),
                  last_content_hash=?,last_error=null,status='ACTIVE',updated_at=now()
                where id=?
                """, bundleVersion, generatedAt, generatedAt, checksum,
                    BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID);
            return new BundleLoadResult("LOADED", bundleVersion, prices.size(), changed);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("内置参考价格快照导入失败", exception);
        }
    }

    private int upsertPrice(JsonNode node,
                            String bundleVersion,
                            OffsetDateTime generatedAt,
                            String runId,
                            String snapshotId,
                            String bundleChecksum) throws Exception {
        String providerType = required(node, "providerType").trim().toLowerCase(Locale.ROOT);
        String modelName = required(node, "providerModelName").trim();
        String displayName = node.path("displayName").asText(modelName);
        String currency = node.path("currency").asText("USD").toUpperCase(Locale.ROOT);
        String region = node.path("region").asText("global");
        String billingBasis = node.path("billingBasis").asText("TOKEN").toUpperCase(Locale.ROOT);
        long billingQuantity = node.path("billingQuantity").asLong(1_000_000L);
        BigDecimal input = node.path("inputUnitPrice").decimalValue();
        BigDecimal output = node.path("outputUnitPrice").decimalValue();
        String requestMode = node.path("requestMode").asText("STANDARD");
        String serviceTier = node.path("serviceTier").asText("DEFAULT");
        String contextTier = node.path("contextTier").asText("DEFAULT");
        String sourceRef = node.path("sourceRef").asText("bundle://tokensea/" + bundleVersion);
        OffsetDateTime observedAt = node.hasNonNull("sourceObservedAt")
                ? OffsetDateTime.parse(node.path("sourceObservedAt").asText()) : generatedAt;
        String canonical = ReferenceModelMatcher.canonical(providerType, modelName);
        String scope = canonical + "|" + region + "|" + requestMode + "|" + serviceTier + "|" + contextTier;
        String evidenceHash = sha256((bundleChecksum + "|" + scope + "|" + input + "|" + output)
                .getBytes(StandardCharsets.UTF_8));
        String id = "bundle_ref_" + sha256(scope.getBytes(StandardCharsets.UTF_8)).substring(0, 52);
        OffsetDateTime staleAt = observedAt.plusHours(staleAfterHours);
        List<Map<String,Object>> components = List.of(
                component("INPUT_TOKEN", input, billingBasis, billingQuantity),
                component("OUTPUT_TOKEN", output, billingBasis, billingQuantity));
        Map<String,Object> normalized = new LinkedHashMap<>();
        normalized.put("providerType", providerType);
        normalized.put("providerModelName", modelName);
        normalized.put("displayName", displayName);
        normalized.put("currency", currency);
        normalized.put("billingBasis", billingBasis);
        normalized.put("billingQuantity", billingQuantity);
        normalized.put("inputUnitPrice", input);
        normalized.put("outputUnitPrice", output);
        normalized.put("region", region);
        normalized.put("requestMode", requestMode);
        normalized.put("serviceTier", serviceTier);
        normalized.put("contextTier", contextTier);
        normalized.put("sourceRef", sourceRef);
        normalized.put("referenceOnly", true);
        normalized.put("bundleVersion", bundleVersion);

        int changed = jdbc.update("""
            insert into public_model_price_reference(
              id,price_source_id,raw_snapshot_id,sync_run_id,provider_type,provider_model_name,canonical_name,
              display_name,currency,billing_basis,billing_quantity,region,request_mode,service_tier,context_tier,
              input_unit_price,output_unit_price,price_components,component_schema_version,
              price_completeness_status,price_nature,pricing_conditions,source_priority,source_evidence_path,
              source_published_at,source_ref,evidence_hash,source_confidence,status,observed_at,
              bundle_version,source_rank,is_current,last_seen_at,stale_at,price_status)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),2,'PARTIAL','ORIGINAL','{}',10,
              '$.prices',?,?,?,0.6000,'ACTIVE',?,?,10,true,?,?,'CURRENT')
            on conflict(price_source_id,provider_type,provider_model_name,region,request_mode,service_tier,context_tier)
            do update set raw_snapshot_id=excluded.raw_snapshot_id,sync_run_id=excluded.sync_run_id,
              canonical_name=excluded.canonical_name,display_name=excluded.display_name,currency=excluded.currency,
              billing_basis=excluded.billing_basis,billing_quantity=excluded.billing_quantity,
              input_unit_price=excluded.input_unit_price,output_unit_price=excluded.output_unit_price,
              price_components=excluded.price_components,source_ref=excluded.source_ref,
              evidence_hash=excluded.evidence_hash,observed_at=excluded.observed_at,
              bundle_version=excluded.bundle_version,source_rank=excluded.source_rank,is_current=true,
              last_seen_at=excluded.last_seen_at,stale_at=excluded.stale_at,price_status='CURRENT',updated_at=now()
            where public_model_price_reference.evidence_hash is distinct from excluded.evidence_hash
               or public_model_price_reference.price_status<>'CURRENT'
            """, id, BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID, snapshotId, runId,
                providerType, modelName, canonical, displayName, currency, billingBasis, billingQuantity,
                region, requestMode, serviceTier, contextTier, input, output,
                json.writeValueAsString(components), generatedAt, sourceRef, evidenceHash, observedAt,
                bundleVersion, observedAt, staleAt);

        jdbc.update("""
            insert into public_model_reference(
              id,canonical_name,display_name,vendor,source_type,source_ref,source_confidence,
              reference_prices,reference_source_hash,reference_updated_at)
            values(?,?,?,?,'BUNDLE_IMPORT',?,0.6000,jsonb_build_object(?::text,cast(? as jsonb)),?,?)
            on conflict(canonical_name) do update set
              display_name=excluded.display_name,vendor=excluded.vendor,
              reference_prices=public_model_reference.reference_prices || excluded.reference_prices,
              reference_source_hash=excluded.reference_source_hash,
              reference_updated_at=greatest(public_model_reference.reference_updated_at,excluded.reference_updated_at),
              version=public_model_reference.version+1,updated_at=now()
            where not (public_model_reference.reference_prices @> excluded.reference_prices)
            """, "bundle_model_" + sha256(canonical.getBytes(StandardCharsets.UTF_8)).substring(0, 51),
                canonical, displayName, providerType, sourceRef,
                BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID,
                json.writeValueAsString(normalized), evidenceHash, observedAt);
        return changed;
    }

    private static Map<String,Object> component(String type, BigDecimal unitPrice,
                                                 String basis, long quantity) {
        return Map.of(
                "componentType", type,
                "variant", "DEFAULT",
                "unitPrice", unitPrice,
                "billingBasis", basis,
                "billingQuantity", quantity,
                "mode", "EXPLICIT");
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalStateException("参考价格快照字段缺失: " + field);
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record BundleLoadResult(String status, String bundleVersion, int records, int changed) {}
}
