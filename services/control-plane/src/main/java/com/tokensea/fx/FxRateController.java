package com.tokensea.fx;

import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fx-rates")
public class FxRateController {
    private final FxRateService service;

    public FxRateController(FxRateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String,Object>>> list(
            @RequestParam(required = false) LocalDate rateMonth,
            @RequestParam(required = false) String fromCurrency,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(rateMonth, fromCurrency, status));
    }

    @GetMapping("/status")
    public ApiResponse<Map<String,Object>> status() {
        return ApiResponse.ok(service.status());
    }

    @PostMapping("/sync")
    public ApiResponse<FxRateService.SyncSummary> sync(Authentication authentication) {
        return ApiResponse.ok(service.syncNow(actor(authentication)));
    }

    @PostMapping("/manual")
    public ApiResponse<Map<String,Object>> manual(@RequestBody FxRateService.ManualRateRequest request,
                                                   Authentication authentication) {
        return ApiResponse.ok(service.saveManual(request, actor(authentication)));
    }

    @PostMapping("/{id}/restore-auto")
    public ApiResponse<FxRateService.SyncSummary> restoreAuto(@PathVariable String id,
                                                               Authentication authentication) {
        return ApiResponse.ok(service.restoreAutomatic(id, actor(authentication)));
    }

    public record AutoUpdateRequest(boolean enabled) {}

    @PutMapping("/auto-update")
    public ApiResponse<Map<String,Object>> autoUpdate(@RequestBody AutoUpdateRequest request,
                                                       Authentication authentication) {
        return ApiResponse.ok(service.setAutoUpdate(request.enabled(), actor(authentication)));
    }

    private static String actor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity) {
            return identity.userId();
        }
        return "SYSTEM";
    }
}
