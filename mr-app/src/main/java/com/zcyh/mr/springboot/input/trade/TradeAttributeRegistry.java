package com.zcyh.mr.springboot.input.trade;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class TradeAttributeRegistry {
    private static final List<TradeAttributeDefinition> DEFINITIONS = Collections.unmodifiableList(Arrays.asList(
            new TradeAttributeDefinition("SOURCE_SYSTEM", "source_system", TradeAttributeCategory.SOURCE, String.class),
            new TradeAttributeDefinition("PORTFOLIO", "portfolio", TradeAttributeCategory.DIMENSION, String.class),
            new TradeAttributeDefinition("DESK", "desk", TradeAttributeCategory.DIMENSION, String.class),
            new TradeAttributeDefinition("TRADER", "trader", TradeAttributeCategory.DIMENSION, String.class),
            new TradeAttributeDefinition("RRAO_TYPE", "rrao_type", TradeAttributeCategory.REGULATORY, String.class),
            new TradeAttributeDefinition("RRAO_NOTIONAL", "rrao_notional", TradeAttributeCategory.REGULATORY, BigDecimal.class)
    ));
    private static final Map<String, TradeAttributeDefinition> BY_FIELD = buildByField();

    private TradeAttributeRegistry() {
    }

    public static List<TradeAttributeDefinition> definitions() {
        return DEFINITIONS;
    }

    public static TradeAttributeDefinition findByField(String fieldName) {
        return fieldName == null ? null : BY_FIELD.get(fieldName.trim().toUpperCase(Locale.ROOT));
    }

    public static List<TradeAttributeDefinition> definitions(TradeAttributeCategory category) {
        return DEFINITIONS.stream()
                .filter(item -> item.getCategory() == category)
                .collect(Collectors.toList());
    }

    private static Map<String, TradeAttributeDefinition> buildByField() {
        Map<String, TradeAttributeDefinition> result = new LinkedHashMap<>();
        for (TradeAttributeDefinition definition : DEFINITIONS) {
            result.put(definition.getFieldName(), definition);
        }
        return Collections.unmodifiableMap(result);
    }
}
