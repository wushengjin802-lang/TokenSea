package com.tokensea.governance.pricing.connector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConnectorSchemas {
    private ConnectorSchemas() {}

    static Map<String,Object> schema(Object... fields) {
        Map<String,Object> properties = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 4) {
            properties.put(String.valueOf(fields[index]), Map.of(
                    "label", fields[index + 1],
                    "type", fields[index + 2],
                    "required", fields[index + 3]));
        }
        return Map.of("type", "object", "properties", properties);
    }

    static List<String> manualOrLowRisk() {
        return List.of("MANUAL_ONLY", "AUTO_LOW_RISK");
    }
}
