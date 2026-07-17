package com.zcyh.mr.springboot.input.db;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易输入查询行。
 */
public class TradeInputRow {
    private static final List<String> DIMENSION_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "portfolio",
            "desk",
            "trader"
    ));

    public long id;
    public String instrumentId;
    public String productCode;
    public String tradeContentText;
    public String rraoType;
    public BigDecimal rraoNotional;
    public Map<String, String> tradeDimensions = new LinkedHashMap<String, String>();

    public static List<String> dimensionColumns() {
        return DIMENSION_COLUMNS;
    }
}
