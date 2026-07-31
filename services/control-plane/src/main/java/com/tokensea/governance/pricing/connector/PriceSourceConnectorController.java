package com.tokensea.governance.pricing.connector;

import com.tokensea.asset.mapper.ProviderTemplateMapper;
import com.tokensea.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/provider-price-connectors")
public class PriceSourceConnectorController {
    private final PriceSourceConnectorRegistry registry;
    private final ProviderTemplateMapper providerTemplates;
    private final ProviderPriceSourcePresetCatalog presets;

    public PriceSourceConnectorController(PriceSourceConnectorRegistry registry,
                                          ProviderTemplateMapper providerTemplates,
                                          ProviderPriceSourcePresetCatalog presets) {
        this.registry = registry;
        this.providerTemplates = providerTemplates;
        this.presets = presets;
    }

    @GetMapping
    public ApiResponse<List<PriceSourceConnectorDefinition>> list() {
        return ApiResponse.ok(registry.definitions());
    }

    @GetMapping("/provider-options")
    public ApiResponse<List<ProviderPriceSourcePresetCatalog.ProviderPriceSourceOption>> providerOptions() {
        return ApiResponse.ok(providerTemplates.selectList(null).stream().map(presets::option).toList());
    }

    @GetMapping("/{code}/schema")
    public ApiResponse<PriceSourceConnectorDefinition> schema(@PathVariable String code) {
        return ApiResponse.ok(registry.find(code).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "价格源连接器不存在")));
    }
}
