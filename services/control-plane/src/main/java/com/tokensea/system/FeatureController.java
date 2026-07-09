package com.tokensea.system;

import com.tokensea.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/features")
public class FeatureController {
    @GetMapping
    public ApiResponse<Map<String, Boolean>> features() {
        return ApiResponse.ok(Map.of(
            "tenant", true,
            "key", true,
            "model", true,
            "usage", true,
            "billing_basic", true,
            "invoice", false,
            "agent_platform", false,
            "model_eval", false,
            "smart_routing", false,
            "marketplace", false
        ));
    }
}
