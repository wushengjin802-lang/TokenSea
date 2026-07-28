package com.tokensea.usage.controller;

import com.tokensea.common.ApiResponse;
import com.tokensea.usage.service.UsageAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/usage-analysis")
public class UsageAnalyticsController {
    private final UsageAnalyticsService service;

    public UsageAnalyticsController(UsageAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String,Object>> dashboard(
            @RequestParam(required=false) String startAt,
            @RequestParam(required=false) String endAt,
            @RequestParam(required=false) String tenantId,
            @RequestParam(required=false) String projectId,
            @RequestParam(required=false) String appId,
            @RequestParam(required=false) String apiKeyId,
            @RequestParam(required=false) String providerId,
            @RequestParam(required=false) String modelAlias,
            @RequestParam(required=false) String status) {
        return ApiResponse.ok(service.dashboard(query(startAt, endAt, tenantId, projectId, appId, apiKeyId,
                providerId, modelAlias, status, null, 1, 20, "createdAt", "desc")));
    }

    @GetMapping("/details")
    public ApiResponse<Map<String,Object>> details(
            @RequestParam(required=false) String startAt,
            @RequestParam(required=false) String endAt,
            @RequestParam(required=false) String tenantId,
            @RequestParam(required=false) String projectId,
            @RequestParam(required=false) String appId,
            @RequestParam(required=false) String apiKeyId,
            @RequestParam(required=false) String providerId,
            @RequestParam(required=false) String modelAlias,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="createdAt") String sort,
            @RequestParam(defaultValue="desc") String order) {
        return ApiResponse.ok(service.details(query(startAt, endAt, tenantId, projectId, appId, apiKeyId,
                providerId, modelAlias, status, keyword, page, size, sort, order)));
    }

    @GetMapping("/options")
    public ApiResponse<Map<String,Object>> options() {
        return ApiResponse.ok(service.options());
    }

    private UsageAnalyticsService.UsageQuery query(String startAt, String endAt, String tenantId, String projectId,
                                                     String appId, String apiKeyId, String providerId,
                                                     String modelAlias, String status, String keyword,
                                                     int page, int size, String sort, String order) {
        return UsageAnalyticsService.UsageQuery.of(startAt, endAt, tenantId, projectId, appId, apiKeyId,
                providerId, modelAlias, status, keyword, page, size, sort, order);
    }
}
