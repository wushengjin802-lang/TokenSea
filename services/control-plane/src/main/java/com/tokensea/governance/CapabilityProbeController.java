package com.tokensea.governance;

import com.tokensea.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/channel-model-deployments")
public class CapabilityProbeController {
    private final CapabilityProbeService probes;

    public CapabilityProbeController(CapabilityProbeService probes) {
        this.probes = probes;
    }

    public record ProbeRequest(String capabilityCode) {}

    @PostMapping("/{id}/probe")
    public ApiResponse<Map<String, Object>> probe(@PathVariable String id,
                                                   @RequestBody ProbeRequest request,
                                                   Authentication authentication) {
        return ApiResponse.ok(probes.probe(
                id,
                request == null ? null : request.capabilityCode(),
                authentication));
    }
}
