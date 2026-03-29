package com.zcyh.mr.springboot.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 批量任务交易数据加载器。
 * 从 engine_db（输入/任务库）加载交易输入和市场曲线数据。
 */
@Component
public class BatchTradeDataLoader {

    private final JdbcTemplate jdbcTemplate;

    public BatchTradeDataLoader(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== RowMapper ====================

    private static final RowMapper<TradeRow> TRADE_ROW_MAPPER = new RowMapper<TradeRow>() {
        @Override
        public TradeRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            TradeRow row = new TradeRow();
            row.id = rs.getLong("id");
            row.tradeId = rs.getString("trade_id");
            row.productType = rs.getString("product_type");
            row.tradeContentText = rs.getString("trade_content_text");
            row.portfolio = rs.getString("portfolio");
            row.desk = rs.getString("desk");
            row.trader = rs.getString("trader");
            return row;
        }
    };

    private static final RowMapper<CurveRow> CURVE_ROW_MAPPER = new RowMapper<CurveRow>() {
        @Override
        public CurveRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CurveRow row = new CurveRow();
            row.marketDataType = rs.getString("market_data_type");
            row.curveId = rs.getString("curve_id");
            row.curveContentText = rs.getString("curve_content_text");
            return row;
        }
    };

    // ==================== 数据加载 ====================

    /**
     * 按数据日期、组合、交易台加载交易行。
     */
    public List<TradeRow> loadTradeRows(LocalDate dataDate, String portfolio, String desk) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT id, trade_id, product_type, trade_content_text, portfolio, desk, trader FROM MR_TRADE_INPUT WHERE data_date=?");
        params.add(Date.valueOf(dataDate));
        if (portfolio != null) {
            sql.append(" AND portfolio=?");
            params.add(portfolio);
        }
        if (desk != null) {
            sql.append(" AND desk=?");
            params.add(desk);
        }
        sql.append(" ORDER BY id");
        return jdbcTemplate.query(sql.toString(), TRADE_ROW_MAPPER, params.toArray());
    }

    /**
     * 按指定的 tradeId 列表加载交易行。
     */
    public List<TradeRow> loadTradeRowsByTradeIds(LocalDate dataDate, List<String> tradeIds) {
        if (tradeIds == null || tradeIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT id, trade_id, product_type, trade_content_text FROM MR_TRADE_INPUT WHERE data_date=?");
        params.add(Date.valueOf(dataDate));
        sql.append(" AND trade_id IN (");
        for (int i = 0; i < tradeIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(tradeIds.get(i));
        }
        sql.append(") ORDER BY id");
        return jdbcTemplate.query(sql.toString(), TRADE_ROW_MAPPER, params.toArray());
    }

    /**
     * 按数据日期加载市场曲线行。
     */
    public List<CurveRow> loadCurveRows(LocalDate dataDate) {
        String sql = "SELECT market_data_type, curve_id, curve_content_text FROM MR_MARKET_CURVE_INPUT WHERE data_date=? ORDER BY market_data_type, curve_id";
        return jdbcTemplate.query(sql, CURVE_ROW_MAPPER, Date.valueOf(dataDate));
    }

    // ==================== 校验 ====================

    /**
     * 校验所有 tradeId 均已成功加载，否则抛出异常。
     */
    public static void ensureAllTradeIdsLoaded(List<String> tradeIds, List<TradeRow> trades) {
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        if (trades != null) {
            for (TradeRow trade : trades) {
                String tradeId = trimToNull(trade.tradeId);
                if (tradeId != null) {
                    found.add(tradeId);
                }
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String tradeId : tradeIds) {
            if (!found.contains(tradeId)) {
                missing.add(tradeId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("以下 tradeId 未查询到输入交易: " + String.join(", ", missing));
        }
    }

    /**
     * 标准化 tradeId 列表（去空去重）。
     */
    public static List<String> normalizeTradeIds(List<String> tradeIdList) {
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        if (tradeIdList != null) {
            for (String tradeId : tradeIdList) {
                String safe = trimToNull(tradeId);
                if (safe != null) {
                    normalized.add(safe);
                }
            }
        }
        return new ArrayList<String>(normalized);
    }

    // ==================== 数据类 ====================

    /**
     * 交易输入行。
     */
    public static class TradeRow {
        public long id;
        public String tradeId;
        public String productType;
        public String tradeContentText;
        public String portfolio;
        public String desk;
        public String trader;
    }

    /**
     * 市场曲线输入行。
     */
    public static class CurveRow {
        public String marketDataType;
        public String curveId;
        public String curveContentText;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}


