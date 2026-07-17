package com.zcyh.mr.springboot.input.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 交易输入查询仓储。
 */
@Repository
public class TradeInputRepository {
    private static final RowMapper<TradeInputRow> ROW_MAPPER = new RowMapper<TradeInputRow>() {
        @Override
        public TradeInputRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            TradeInputRow row = new TradeInputRow();
            row.id = rs.getLong("id");
            row.instrumentId = rs.getString("instrument_id");
            row.productCode = rs.getString("product_code");
            row.tradeContentText = rs.getString("trade_content_text");
            row.rraoType = trimToNull(rs.getString("rrao_type"));
            row.rraoNotional = rs.getBigDecimal("rrao_notional");
            for (String column : TradeInputRow.dimensionColumns()) {
                String value = trimToNull(rs.getString(column));
                if (value != null) {
                    row.tradeDimensions.put(column, value);
                }
            }
            return row;
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public TradeInputRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TradeInputRow> findByFilter(
            LocalDate dataDate,
            InputFilterExpression tradeFilter) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append(buildSelectSql("t")).append(" FROM MR_TRADE_INPUT t ");
        if (TradeInputFilterSqlBuilder.usesPortfolioFlatView(tradeFilter)) {
            sql.append("LEFT JOIN V_PORTFOLIO_HIERARCHY_FLAT p ON p.PORTFOLIO_CODE = t.portfolio ");
        }
        sql.append("WHERE t.data_date=?");
        params.add(Date.valueOf(dataDate));
        TradeInputFilterSqlBuilder.appendWhereClause(sql, params, tradeFilter);
        sql.append(" ORDER BY t.id");
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    public List<TradeInputRow> findByInstrumentIds(LocalDate dataDate, List<String> instrumentIds) {
        if (instrumentIds == null || instrumentIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append(buildSelectSql(null)).append(" FROM MR_TRADE_INPUT WHERE data_date=?");
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
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    private static String buildSelectSql(String tableAlias) {
        String prefix = trimToNull(tableAlias) == null ? "" : tableAlias + ".";
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(prefix).append("id AS id, ");
        sql.append(prefix).append("instrument_id AS instrument_id, ");
        sql.append(prefix).append("product_code AS product_code, ");
        sql.append(prefix).append("trade_content_text AS trade_content_text, ");
        sql.append(prefix).append("rrao_type AS rrao_type, ");
        sql.append(prefix).append("rrao_notional AS rrao_notional");
        for (String column : TradeInputRow.dimensionColumns()) {
            sql.append(", ").append(prefix).append(column).append(" AS ").append(column);
        }
        return sql.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
