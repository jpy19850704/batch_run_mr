package com.zcyh.mr.marketdata.input;

import java.util.Collections;
import java.util.List;

public final class MarketInputDefinition {
    private final MarketDataType marketDataType;
    private final Class<?> inputType;
    private final Class<?> pointType;
    private final List<MarketFieldDefinition> fields;
    private final List<MarketFieldDefinition> pointFields;

    MarketInputDefinition(
            MarketDataType marketDataType,
            Class<?> inputType,
            Class<?> pointType,
            List<MarketFieldDefinition> fields,
            List<MarketFieldDefinition> pointFields) {
        this.marketDataType = marketDataType;
        this.inputType = inputType;
        this.pointType = pointType;
        this.fields = Collections.unmodifiableList(fields);
        this.pointFields = Collections.unmodifiableList(pointFields);
    }

    public MarketDataType getMarketDataType() {
        return marketDataType;
    }

    public Class<?> getInputType() {
        return inputType;
    }

    public Class<?> getPointType() {
        return pointType;
    }

    public List<MarketFieldDefinition> getFields() {
        return fields;
    }

    public List<MarketFieldDefinition> getPointFields() {
        return pointFields;
    }
}
