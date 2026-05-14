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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量任务交易数据加载器。
 * 从 engine_db（输入/任务库）加载交易输入和市场曲线数据。
 */
@Component
public class BatchTradeDataLoader {

    private static final List<String> TRADE_DIMENSION_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "portfolio",
            "desk",
            "trader"
    ));

    private final JdbcTemplate jdbcTemplate;

    public BatchTradeDataLoader(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 行映射器 ====================

    private static final RowMapper<TradeRow> TRADE_ROW_MAPPER = new RowMapper<TradeRow>() {
        @Override
        public TradeRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            TradeRow row = new TradeRow();
            row.id = rs.getLong("id");
            row.instrumentId = rs.getString("instrument_id");
            row.productCode = rs.getString("product_code");
            row.tradeContentText = rs.getString("trade_content_text");
            for (String column : TRADE_DIMENSION_COLUMNS) {
                String value = trimToNull(rs.getString(column));
                if (value != null) {
                    row.tradeDimensions.put(column, value);
                }
            }
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

    private static final RowMapper<PortfolioFlatRow> PORTFOLIO_FLAT_ROW_MAPPER = new RowMapper<PortfolioFlatRow>() {
        @Override
        public PortfolioFlatRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PortfolioFlatRow row = new PortfolioFlatRow();
            row.portfolioCode = rs.getString("PORTFOLIO_CODE");
            row.portfolioCode1 = rs.getString("PORTFOLIO_CODE_1");
            row.portfolioCode2 = rs.getString("PORTFOLIO_CODE_2");
            row.portfolioCode3 = rs.getString("PORTFOLIO_CODE_3");
            row.portfolioCode4 = rs.getString("PORTFOLIO_CODE_4");
            row.portfolioCode5 = rs.getString("PORTFOLIO_CODE_5");
            row.portfolioCode6 = rs.getString("PORTFOLIO_CODE_6");
            row.portfolioCode7 = rs.getString("PORTFOLIO_CODE_7");
            row.portfolioName1 = rs.getString("PORTFOLIO_NAME_1");
            row.portfolioName2 = rs.getString("PORTFOLIO_NAME_2");
            row.portfolioName3 = rs.getString("PORTFOLIO_NAME_3");
            row.portfolioName4 = rs.getString("PORTFOLIO_NAME_4");
            row.portfolioName5 = rs.getString("PORTFOLIO_NAME_5");
            row.portfolioName6 = rs.getString("PORTFOLIO_NAME_6");
            row.portfolioName7 = rs.getString("PORTFOLIO_NAME_7");
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
        sql.append(buildTradeSelectSql()).append(" FROM MR_TRADE_INPUT WHERE data_date=?");
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
     * 按指定的 instrumentId 列表加载交易行。
     */
    public List<TradeRow> loadTradeRowsByInstrumentIds(LocalDate dataDate, List<String> instrumentIds) {
        if (instrumentIds == null || instrumentIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append(buildTradeSelectSql()).append(" FROM MR_TRADE_INPUT WHERE data_date=?");
        params.add(Date.valueOf(dataDate));
        sql.append(" AND instrument_id IN (");
        for (int i = 0; i < instrumentIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(instrumentIds.get(i));
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

    /**
     * 按投组代码批量读取投组层级平铺信息。
     */
    public Map<String, PortfolioFlatRow> loadPortfolioFlatByCodes(List<String> portfolioCodes) {
        List<String> normalizedCodes = normalizePortfolioCodes(portfolioCodes);
        if (normalizedCodes.isEmpty()) {
            return Collections.emptyMap();
        }

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT PORTFOLIO_CODE, ");
        sql.append("PORTFOLIO_CODE_1, PORTFOLIO_CODE_2, PORTFOLIO_CODE_3, PORTFOLIO_CODE_4, PORTFOLIO_CODE_5, PORTFOLIO_CODE_6, PORTFOLIO_CODE_7, ");
        sql.append("PORTFOLIO_NAME_1, PORTFOLIO_NAME_2, PORTFOLIO_NAME_3, PORTFOLIO_NAME_4, PORTFOLIO_NAME_5, PORTFOLIO_NAME_6, PORTFOLIO_NAME_7 ");
        sql.append("FROM V_PORTFOLIO_HIERARCHY_FLAT WHERE PORTFOLIO_CODE IN (");
        for (int i = 0; i < normalizedCodes.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(normalizedCodes.get(i));
        }
        sql.append(")");

        List<PortfolioFlatRow> rows = jdbcTemplate.query(sql.toString(), PORTFOLIO_FLAT_ROW_MAPPER, params.toArray());
        Map<String, PortfolioFlatRow> result = new LinkedHashMap<String, PortfolioFlatRow>();
        for (PortfolioFlatRow row : rows) {
            String key = trimToNull(row.portfolioCode);
            if (key != null) {
                result.put(key, row);
            }
        }
        return result;
    }

    // ==================== 校验 ====================

    /**
     * 校验所有 instrumentId 均已成功加载，否则抛出异常。
     */
    public static void ensureAllInstrumentIdsLoaded(List<String> instrumentIds, List<TradeRow> trades) {
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        if (trades != null) {
            for (TradeRow trade : trades) {
                String instrumentId = trimToNull(trade.instrumentId);
                if (instrumentId != null) {
                    found.add(instrumentId);
                }
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String instrumentId : instrumentIds) {
            if (!found.contains(instrumentId)) {
                missing.add(instrumentId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("以下 instrumentId 未查询到输入交易: " + String.join(", ", missing));
        }
    }

    /**
     * 标准化 instrumentId 列表（去空去重）。
     */
    public static List<String> normalizeInstrumentIds(List<String> instrumentIdList) {
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        if (instrumentIdList != null) {
            for (String instrumentId : instrumentIdList) {
                String safe = trimToNull(instrumentId);
                if (safe != null) {
                    normalized.add(safe);
                }
            }
        }
        return new ArrayList<String>(normalized);
    }

    /**
     * 标准化投组代码列表（去空去重）。
     */
    public static List<String> normalizePortfolioCodes(List<String> portfolioCodeList) {
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        if (portfolioCodeList != null) {
            for (String portfolioCode : portfolioCodeList) {
                String safe = trimToNull(portfolioCode);
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
        public String instrumentId;
        public String productCode;
        public String tradeContentText;
        public Map<String, String> tradeDimensions = new LinkedHashMap<String, String>();
    }

    /**
     * 市场曲线输入行。
     */
    public static class CurveRow {
        public String marketDataType;
        public String curveId;
        public String curveContentText;
    }

    /**
     * 投组层级平铺行。
     */
    public static class PortfolioFlatRow {
        public String portfolioCode;
        public String portfolioCode1;
        public String portfolioCode2;
        public String portfolioCode3;
        public String portfolioCode4;
        public String portfolioCode5;
        public String portfolioCode6;
        public String portfolioCode7;
        public String portfolioName1;
        public String portfolioName2;
        public String portfolioName3;
        public String portfolioName4;
        public String portfolioName5;
        public String portfolioName6;
        public String portfolioName7;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    private static String buildTradeSelectSql() {
        StringBuilder sql = new StringBuilder("SELECT id, instrument_id, product_code, trade_content_text");
        for (String column : TRADE_DIMENSION_COLUMNS) {
            sql.append(", ").append(column);
        }
        return sql.toString();
    }
}


