package com.tokensea.governance.pricing.connector;

abstract class StaticPriceSourceConnector implements PriceSourceConnector {
    private final PriceSourceConnectorDefinition definition;

    protected StaticPriceSourceConnector(PriceSourceConnectorDefinition definition) {
        this.definition = definition;
    }

    @Override
    public PriceSourceConnectorDefinition definition() {
        return definition;
    }
}
