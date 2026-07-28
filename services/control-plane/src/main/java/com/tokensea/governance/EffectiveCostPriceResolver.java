package com.tokensea.governance;

import com.tokensea.common.OperationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EffectiveCostPriceResolver {
    private final JdbcTemplate jdbc;

    public EffectiveCostPriceResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ResolvedPrice(
            String priceVersionId,
            String deploymentId,
            String priceLayer,
            String currency,
            String billingBasis,
            long billingQuantity,
            Object inputUnitPrice,
            Object cacheReadUnitPrice,
            String cacheReadMode,
            Object cacheWriteUnitPrice,
            String cacheWriteMode,
            Object outputUnitPrice,
            Object priceComponents,
            String region,
            String requestMode,
            String serviceTier,
            String contextTier,
            String sourceRef,
            String evidenceHash,
            String resolutionReason,
            Map<String,Object> raw
    ) {}

    public ResolvedPrice resolve(String deploymentId, OffsetDateTime requestTime,
                                 String region, String requestMode,
                                 String serviceTier, String contextTier) {
        OffsetDateTime at = requestTime == null ? OffsetDateTime.now() : requestTime;
        String desiredRegion = normalize(region, deploymentRegion(deploymentId));
        String desiredMode = normalize(requestMode, "STANDARD").toUpperCase(Locale.ROOT);
        String desiredService = normalize(serviceTier, "DEFAULT").toUpperCase(Locale.ROOT);
        String desiredContext = normalize(contextTier, "DEFAULT").toUpperCase(Locale.ROOT);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select p.*,
              case p.price_layer when 'CONTRACT_PRICE' then 1 when 'CHANNEL_ACTUAL' then 2
                when 'PROVIDER_OFFICIAL' then 3 else 9 end layer_priority,
              case when lower(p.region)=lower(?) then 0 when lower(p.region)='global' then 1 else 9 end region_priority,
              case when upper(p.request_mode)=upper(?) then 0 when upper(p.request_mode)='STANDARD' then 1 else 9 end mode_priority,
              case when upper(p.service_tier)=upper(?) then 0 when upper(p.service_tier)='DEFAULT' then 1 else 9 end service_priority,
              case when upper(p.context_tier)=upper(?) then 0 when upper(p.context_tier)='DEFAULT' then 1 else 9 end context_priority
            from price_version p
            where p.deployment_id=?
              and p.price_layer in ('CONTRACT_PRICE','CHANNEL_ACTUAL','PROVIDER_OFFICIAL')
              and p.status='ACTIVE' and p.effective_from<=?
              and (p.effective_to is null or p.effective_to>?)
              and (lower(p.region)=lower(?) or lower(p.region)='global')
              and (upper(p.request_mode)=upper(?) or upper(p.request_mode)='STANDARD')
              and (upper(p.service_tier)=upper(?) or upper(p.service_tier)='DEFAULT')
              and (upper(p.context_tier)=upper(?) or upper(p.context_tier)='DEFAULT')
            order by layer_priority,region_priority,mode_priority,service_priority,context_priority,
              p.effective_from desc,p.version desc
            limit 1
            """, desiredRegion, desiredMode, desiredService, desiredContext,
                deploymentId, at, at, desiredRegion, desiredMode, desiredService, desiredContext);
        if (rows.isEmpty()) {
            throw OperationException.conflict(
                    "TOKENSEA_PRICE_NOT_CONFIGURED",
                    "模型部署 / 有效成本价格",
                    "没有匹配当前时间、区域、请求模式和服务层级的正式成本价格",
                    "维护合同价、渠道实际价或供应商官方价，并确认价格已生效后重试");
        }
        Map<String,Object> row = rows.getFirst();
        String completeness = text(row.get("price_completeness_status"));
        if ("PARTIAL".equals(completeness) || "UNKNOWN_CACHE_PRICE".equals(completeness)) {
            throw OperationException.conflict(
                    "TOKENSEA_CACHE_PRICE_MISSING",
                    "模型部署 / 有效成本价格",
                    "优先级最高的正式价格组件不完整，无法用于生产核算",
                    "补齐输入、输出及适用的缓存价格组件后重新激活价格");
        }
        String layer = text(row.get("price_layer"));
        String reason = switch (layer) {
            case "CONTRACT_PRICE" -> "命中有效合同价，优先于渠道实际价和供应商官方价";
            case "CHANNEL_ACTUAL" -> "未命中有效合同价，使用当前渠道实际成本价";
            case "PROVIDER_OFFICIAL" -> "未命中有效合同价或渠道实际价，使用供应商官方价";
            default -> "使用正式成本价格";
        };
        return new ResolvedPrice(
                text(row.get("id")), deploymentId, layer, text(row.get("currency")),
                text(row.get("billing_basis")), longValue(row.get("billing_quantity")),
                row.get("input_unit_price"), row.get("cache_read_unit_price"), text(row.get("cache_read_mode")),
                row.get("cache_write_unit_price"), text(row.get("cache_write_mode")),
                row.get("output_unit_price"), row.get("price_components"),
                text(row.get("region")), text(row.get("request_mode")), text(row.get("service_tier")),
                text(row.get("context_tier")), text(row.get("source_ref")), text(row.get("evidence_hash")),
                reason, row);
    }

    public boolean exists(String deploymentId) {
        try {
            resolve(deploymentId, OffsetDateTime.now(), null, "STANDARD", "DEFAULT", "DEFAULT");
            return true;
        } catch (OperationException exception) {
            return false;
        }
    }

    private String deploymentRegion(String deploymentId) {
        List<String> rows = jdbc.queryForList("""
            select coalesce(nullif(trim(p.region),''),'global')
            from channel_model_deployment d
            join provider_instance p on p.id=d.provider_instance_id
            where d.id=?
            """, String.class, deploymentId);
        return rows.isEmpty() ? "global" : normalize(rows.getFirst(), "global");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception exception) { return 0L; }
    }
}
