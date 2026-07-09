
package com.tokensea.dashboard;

import com.tokensea.apikey.mapper.ApiKeyEntityMapper;
import com.tokensea.common.ApiResponse;
import com.tokensea.model.mapper.ModelAssetMapper;
import com.tokensea.provider.mapper.ProviderMapper;
import com.tokensea.tenant.mapper.TenantMapper;
import com.tokensea.usage.mapper.UsageRecordMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final TenantMapper tenants;
    private final ProviderMapper providers;
    private final ModelAssetMapper models;
    private final ApiKeyEntityMapper keys;
    private final UsageRecordMapper usage;
    public DashboardController(TenantMapper tenants, ProviderMapper providers, ModelAssetMapper models, ApiKeyEntityMapper keys, UsageRecordMapper usage) {
        this.tenants = tenants; this.providers = providers; this.models = models; this.keys = keys; this.usage = usage;
    }
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        long usageCount = usage.selectCount(null);
        Map<String, Object> usageStats = usage.aggregateStats();
        return ApiResponse.ok(Map.of(
            "tenants", tenants.selectCount(null),
            "providers", providers.selectCount(null),
            "models", models.selectCount(null),
            "keys", keys.selectCount(null),
            "requests", usageCount,
            "errors", usageStats.getOrDefault("errors", 0),
            "tokens", usageStats.getOrDefault("tokens", 0),
            "providerHealth", 0
        ));
    }
}
