package com.zcyh.mr.springboot.input.db;

import com.zcyh.mr.springboot.input.trade.TradeAttributeCategory;
import com.zcyh.mr.springboot.input.trade.TradeAttributeDefinition;
import com.zcyh.mr.springboot.input.trade.TradeAttributeRegistry;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易输入查询行。
 */
public class TradeInputRow {
    public long id;
    public String instrumentId;
    public String productCode;
    public String tradeContentText;
    public Map<String, Object> attributes = new LinkedHashMap<>();

    public String getTextAttribute(String fieldName) {
        Object value = attributes.get(fieldName);
        return value == null ? null : value.toString();
    }

    public BigDecimal getDecimalAttribute(String fieldName) {
        Object value = attributes.get(fieldName);
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
    }

    public Map<String, String> dimensionAttributes() {
        Map<String, String> result = new LinkedHashMap<>();
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions(TradeAttributeCategory.DIMENSION)) {
            String value = getTextAttribute(definition.getFieldName());
            if (value != null) {
                result.put(definition.getColumnName(), value);
            }
        }
        return result;
    }
}
