package com.tokensea.governance.pricing.connector;

import java.util.List;
import java.util.Map;

public record PriceSourceConnectorDefinition(
        String code,
        String name,
        String sourceMethod,
        String dataScope,
        String trustLevel,
        boolean credentialRequired,
        String credentialPurpose,
        String defaultEndpoint,
        List<String> officialHosts,
        List<String> supportedAdapterCodes,
        List<String> supportedPublishPolicies,
        Map<String,Object> configSchema
) {
    public PriceSourceConnectorDefinition {
        officialHosts = officialHosts == null ? List.of() : List.copyOf(officialHosts);
        supportedAdapterCodes = supportedAdapterCodes == null ? List.of() : List.copyOf(supportedAdapterCodes);
        supportedPublishPolicies = supportedPublishPolicies == null ? List.of() : List.copyOf(supportedPublishPolicies);
        configSchema = configSchema == null ? Map.of() : Map.copyOf(configSchema);
    }
}
