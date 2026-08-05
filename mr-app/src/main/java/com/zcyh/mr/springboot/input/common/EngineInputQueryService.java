package com.zcyh.mr.springboot.input.common;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EngineInputQueryService {
    private final JdbcTemplate jdbcTemplate;

    public EngineInputQueryService(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public JSONObject queryTrades(Map<String, String> params) {
        StringBuilder where = new StringBuilder(" FROM MR_TRADE_INPUT WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendDate(where, args, "data_date", value(params, "dataDate"));
        appendEquals(where, args, "instrument_id", value(params, "instrumentId"));
        appendEquals(where, args, "product_code", value(params, "productCode"));
        appendKeyword(where, args, value(params, "keyword"),
                "instrument_id", "product_code", "portfolio", "desk", "trader", "rrao_type");
        return page("SELECT *", where, " ORDER BY updated_at DESC,created_at DESC", args, params);
    }

    public JSONObject queryMarket(Map<String, String> params, String dataKind) {
        String normalizedKind = normalizeDataKind(dataKind);
        String tableName = "RAW".equals(normalizedKind)
                ? "MR_MARKET_CURVE_RAW_INPUT" : "MR_MARKET_CURVE_INPUT";
        StringBuilder where = new StringBuilder(" FROM ").append(tableName).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendDate(where, args, "data_date", value(params, "dataDate"));
        appendEquals(where, args, "curve_id", value(params, "curveId"));
        appendEquals(where, args, "market_data_type", value(params, "marketDataType"));
        appendKeyword(where, args, value(params, "keyword"), "curve_id", "market_data_type");
        return page("SELECT *", where, " ORDER BY updated_at DESC,created_at DESC", args, params);
    }

    public List<String> tradeTypes() {
        return jdbcTemplate.queryForList("SELECT DISTINCT product_code FROM MR_TRADE_INPUT "
                + "WHERE product_code IS NOT NULL AND TRIM(product_code)<>'' ORDER BY product_code", String.class);
    }

    public List<String> marketTypes(String dataKind) {
        String tableName = "RAW".equals(normalizeDataKind(dataKind))
                ? "MR_MARKET_CURVE_RAW_INPUT" : "MR_MARKET_CURVE_INPUT";
        return jdbcTemplate.queryForList("SELECT DISTINCT market_data_type FROM " + tableName
                + " WHERE market_data_type IS NOT NULL AND TRIM(market_data_type)<>'' ORDER BY market_data_type",
                String.class);
    }

    private JSONObject page(String select, StringBuilder where, String orderBy,
            List<Object> args, Map<String, String> params) {
        int pageNum = positiveInt(value(params, "pageNum"), 1, "pageNum");
        int pageSize = positiveInt(value(params, "pageSize"), 20, "pageSize");
        if (pageSize > 500) {
            throw new IllegalArgumentException("pageSize不能超过500");
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1)" + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(pageSize);
        queryArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                select + where + orderBy + " LIMIT ? OFFSET ?", queryArgs.toArray());
        JSONObject result = new JSONObject();
        result.put("total", total == null ? 0 : total);
        result.put("rows", new JSONArray(rows));
        return result;
    }

    private static void appendDate(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null) {
            return;
        }
        try {
            where.append(" AND ").append(column).append("=?");
            args.add(Date.valueOf(LocalDate.parse(value)));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dataDate格式必须为yyyy-MM-dd");
        }
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null) {
            where.append(" AND ").append(column).append("=?");
            args.add(value);
        }
    }

    private static void appendKeyword(StringBuilder where, List<Object> args, String keyword, String... columns) {
        if (keyword == null || columns.length == 0) {
            return;
        }
        where.append(" AND (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                where.append(" OR ");
            }
            where.append(columns[i]).append(" LIKE ?");
            args.add("%" + keyword + "%");
        }
        where.append(')');
    }

    private static int positiveInt(String value, int defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int result = Integer.parseInt(value);
            if (result < 1) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + "必须为正整数");
        }
    }

    private static String normalizeDataKind(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"MARKET".equals(result) && !"RAW".equals(result)) {
            throw new IllegalArgumentException("dataKind必须为MARKET或RAW");
        }
        return result;
    }

    private static String value(Map<String, String> params, String key) {
        if (params == null) {
            return null;
        }
        String value = params.get(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
