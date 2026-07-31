package com.tokensea.governance.pricing.connector;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PriceSourceConnectorRegistry {
    private final Map<String,PriceSourceConnector> connectors;

    public PriceSourceConnectorRegistry(List<PriceSourceConnector> connectors) {
        Map<String,PriceSourceConnector> values = new LinkedHashMap<>();
        for (PriceSourceConnector connector : connectors) {
            String code = connector.definition().code();
            if (values.putIfAbsent(code, connector) != null) {
                throw new IllegalStateException("价格源连接器重复注册: " + code);
            }
        }
        this.connectors = Map.copyOf(values);
    }

    public List<PriceSourceConnectorDefinition> definitions() {
        return connectors.values().stream()
                .map(PriceSourceConnector::definition)
                .sorted(Comparator.comparing(PriceSourceConnectorDefinition::sourceMethod)
                        .thenComparing(PriceSourceConnectorDefinition::name))
                .toList();
    }

    public Optional<PriceSourceConnectorDefinition> find(String code) {
        PriceSourceConnector connector = connectors.get(code);
        return connector == null ? Optional.empty() : Optional.of(connector.definition());
    }

    public PriceSourceConnectorDefinition require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("不支持的价格源连接器: " + code));
    }

    public boolean supportsAdapter(String connectorCode, String adapterCode) {
        return find(connectorCode)
                .map(definition -> definition.supportedAdapterCodes().contains(adapterCode))
                .orElse(false);
    }

    public String connectorForAdapter(String adapterCode) {
        return definitions().stream()
                .filter(definition -> definition.supportedAdapterCodes().contains(adapterCode))
                .map(PriceSourceConnectorDefinition::code)
                .findFirst()
                .orElse("HTTP_DOCUMENT");
    }
}
