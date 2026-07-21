package com.zcyh.mr.springboot.input.market;

import java.util.Arrays;
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
        if ("DATADATE".equals(field) || "CURVECODE".equals(field)) return true;
        List<String> columns = columns(marketDataType);
        int curveDataIndex = columns.indexOf("CURVE_DATA_START");
        return curveDataIndex >= 0 && columns.subList(curveDataIndex + 1, columns.size()).contains(field);
    }

    public static List<String> templateColumns(String marketDataType) {
        List<String> columns = columns(marketDataType);
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String column : columns) if (!"CURVE_DATA_START".equals(column)) result.add(column);
        return result;
    }

    private static Map<String, List<String>> buildColumns() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("IR_SPOT", list("CURVE_ID", "FREQ", "DAYCOUNT", "CURVE_DATA_START", "TERM", "RATE"));
        result.put("CREDIT_SPOT", list("CURVE_ID", "FREQ", "DAYCOUNT", "CURVE_DATA_START", "TERM", "RATE"));
        result.put("FX_SPOT", list("CURVE_ID", "CURVE_DATA_START", "CURRENCY", "RATE"));
        result.put("EQ_SPOT", list("CURVE_ID", "CURRENCY_CODE", "CURVE_DATA_START", "TERM", "EQ_PRICE"));
        result.put("COMM_SPOT", list("CURVE_ID", "CURRENCY_CODE", "CURVE_DATA_START", "TERM", "COMM_PRICE"));
        result.put("FIXING", list("CURVE_ID", "CURVE_DATA_START", "TRADE_DATE", "FIXING_VALUE"));
        result.put("IR_VOL", list("CURVE_ID", "AXIS2_TYPE", "CURVE_DATA_START", "OPTION_TERM", "UNDERLYING_TERM", "DELTA", "MONEYNESS", "STRIKE", "VOLATILITY_RATE"));
        result.put("FX_VOL", list("CURVE_ID", "AXIS2_TYPE", "CURVE_DATA_START", "OPTION_TERM", "DELTA", "UNDERLYING_TERM", "MONEYNESS", "STRIKE", "VOLATILITY_RATE"));
        result.put("EQ_VOL", list("CURVE_ID", "AXIS2_TYPE", "CURVE_DATA_START", "OPTION_TERM", "DELTA", "UNDERLYING_TERM", "MONEYNESS", "STRIKE", "VOLATILITY_RATE"));
        result.put("COMM_VOL", list("CURVE_ID", "AXIS2_TYPE", "CURVE_DATA_START", "OPTION_TERM", "DELTA", "UNDERLYING_TERM", "MONEYNESS", "STRIKE", "VOLATILITY_RATE"));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> list(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
