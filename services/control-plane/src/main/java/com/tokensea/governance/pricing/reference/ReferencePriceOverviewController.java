package com.tokensea.governance.pricing.reference;

import com.tokensea.common.ApiResponse;
import com.tokensea.common.PageResult;
import com.tokensea.governance.ProviderPriceSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reference-prices")
public class ReferencePriceOverviewController {
    private final ReferencePriceHealthService health;
    private final ProviderPriceSyncService sync;

    public ReferencePriceOverviewController(ReferencePriceHealthService health,
                                            ProviderPriceSyncService sync) {
        this.health = health;
        this.sync = sync;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String,Object>> overview() {
        return ApiResponse.ok(health.overview());
    }

    @GetMapping("/sources")
    public ApiResponse<List<Map<String,Object>>> sources() {
        return ApiResponse.ok(health.sources());
    }

    @GetMapping("/models")
    public ApiResponse<PageResult<Map<String,Object>>> models(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String providerType,
            @RequestParam(required = false) String priceStatus,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        return ApiResponse.ok(health.models(page, size, keyword, providerType, priceStatus, sort, order));
    }

    @PostMapping("/sources/{id}/retry")
    public ApiResponse<Map<String,Object>> retry(@PathVariable("id") String id) {
        if (!health.isSystemReferenceSource(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "系统参考价格源不存在");
        }
        if (BuiltInReferenceSourceCatalog.BUNDLE_SOURCE_ID.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置离线快照无需联网重试");
        }
        String runId = sync.enqueue(id, "MANUAL");
        return ApiResponse.ok(Map.of("sourceId", id, "runId", runId, "status", "PENDING"));
    }

    @GetMapping("/sources/{id}/runs")
    public ApiResponse<List<Map<String,Object>>> runs(@PathVariable("id") String id) {
        if (!health.isSystemReferenceSource(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "系统参考价格源不存在");
        }
        return ApiResponse.ok(health.runs(id));
    }
}
