
package com.tokensea.dashboard;

import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.tenant.mapper.TenantMapper;
import com.tokensea.usage.mapper.UsageRecordMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final TenantMapper tenants;
    private final PlatformModelMapper models;
    private final ApiKeyEntityMapper keys;
    private final UsageRecordMapper usage;
    private final ProviderInstanceMapper providerInstances;
    public DashboardController(TenantMapper tenants, PlatformModelMapper models, ApiKeyEntityMapper keys, UsageRecordMapper usage, ProviderInstanceMapper providerInstances) {
        this.tenants = tenants; this.models = models; this.keys = keys; this.usage = usage; this.providerInstances = providerInstances;
    }
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        long usageCount = usage.selectCount(null);
        Map<String, Object> usageStats = usage.aggregateStats();
        return ApiResponse.ok(Map.of(
            "tenants", tenants.selectCount(null),
            "providers", providerInstances.selectCount(null),
            "models", models.selectCount(null),
            "keys", keys.selectCount(null),
            "requests", usageCount,
            "errors", usageStats.getOrDefault("errors", 0),
            "tokens", usageStats.getOrDefault("tokens", 0),
            "providerHealth", providerInstances.selectCount(new QueryWrapper<ProviderInstance>()
                .eq("status", "启用").eq("health_status", "健康")
                .eq("last_connection_test_status", "成功")
                .ge("last_connection_test_at", java.time.OffsetDateTime.now().minusMinutes(30)))
        ));
    }
}
