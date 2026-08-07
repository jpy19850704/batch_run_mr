package com.zcyh.mr.springboot.input.market;

import com.zcyh.mr.marketdata.input.MarketDataType;
import com.zcyh.mr.marketdata.input.MarketFieldDefinition;
import com.zcyh.mr.marketdata.input.MarketInputDefinition;
import com.zcyh.mr.marketdata.input.MarketInputDefinitionRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MarketImportSchema {
    private static final Map<String, List<String>> COLUMNS = buildColumns();

    private MarketImportSchema() {
    }

    public static Set<String> supportedTypes() {
        return Collections.unmodifiableSet(COLUMNS.keySet());
    }

    public static List<String> columns(String marketDataType) {
        List<String> columns = COLUMNS.get(marketDataType);
        if (columns == null) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + marketDataType);
        }
        return columns;
    }

    public static boolean isPointField(String marketDataType, String field) {
        MarketInputDefinition definition = MarketInputDefinitionRegistry.get(marketDataType);
        for (MarketFieldDefinition pointField : definition.getPointFields()) {
            if (pointField.getName().equals(field)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> templateColumns(String marketDataType) {
        List<String> columns = columns(marketDataType);
        ArrayList<String> result = new ArrayList<String>();
        for (String column : columns) if (!"CURVE_DATA_START".equals(column)) result.add(column);
        return result;
    }

    private static Map<String, List<String>> buildColumns() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<MarketDataType, MarketInputDefinition> entry
                : MarketInputDefinitionRegistry.all().entrySet()) {
            List<String> columns = new ArrayList<String>();
            for (MarketFieldDefinition field : entry.getValue().getFields()) {
                if (!"CURVE_TYPE".equals(field.getName())
                        && !"DATA_DATE".equals(field.getName())
                        && !"CURVE_DATA".equals(field.getName())) {
                    columns.add(field.getName());
                }
            }
            columns.add("CURVE_DATA_START");
            for (MarketFieldDefinition pointField : entry.getValue().getPointFields()) {
                columns.add(pointField.getName());
            }
            result.put(entry.getKey().name(), Collections.unmodifiableList(columns));
        }
        return Collections.unmodifiableMap(result);
    }
}
