package com.zcyh.mr.springboot.input.trade;

public final class TradeAttributeDefinition {
    private final String fieldName;
    private final String columnName;
    private final TradeAttributeCategory category;
    private final Class<?> valueType;

    public TradeAttributeDefinition(String fieldName, String columnName,
            TradeAttributeCategory category, Class<?> valueType) {
        this.fieldName = fieldName;
        this.columnName = columnName;
        this.category = category;
        this.valueType = valueType;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getColumnName() {
        return columnName;
    }

    public TradeAttributeCategory getCategory() {
        return category;
    }

    public Class<?> getValueType() {
        return valueType;
    }
}
