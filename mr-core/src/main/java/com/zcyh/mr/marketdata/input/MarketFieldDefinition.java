package com.zcyh.mr.marketdata.input;

import java.util.Collections;
import java.util.List;

public final class MarketFieldDefinition {
    private final String name;
    private final String label;
    private final MarketFieldType type;
    private final boolean required;
    private final int order;
    private final List<String> allowedValues;

    MarketFieldDefinition(
            String name,
            String label,
            MarketFieldType type,
            boolean required,
            int order,
            List<String> allowedValues) {
        this.name = name;
        this.label = label;
        this.type = type;
        this.required = required;
        this.order = order;
        this.allowedValues = Collections.unmodifiableList(allowedValues);
    }

    public String getName() {
        return name;
    }

    public String getLabel() {
        return label;
    }

    public MarketFieldType getType() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    public int getOrder() {
        return order;
    }

    public List<String> getAllowedValues() {
        return allowedValues;
    }
}
