package com.tokensea.governance.pricing.connector;

public interface PriceSourceConnector {
    PriceSourceConnectorDefinition definition();

    default boolean supports(String connectorCode) {
        return definition().code().equals(connectorCode);
    }
}
