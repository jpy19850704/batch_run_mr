package com.zcyh.mr.springboot.input.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * 市场曲线输入查询仓储。
 */
@Repository
public class MarketDataInputRepository {
    private final JdbcTemplate jdbcTemplate;

    public MarketDataInputRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MarketCurveInputRow> findByDataDate(LocalDate dataDate) {
        String sql = "SELECT market_data_type, curve_id, curve_content_text "
                + "FROM MR_MARKET_CURVE_INPUT WHERE data_date=? ORDER BY market_data_type, curve_id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            MarketCurveInputRow row = new MarketCurveInputRow();
            row.marketDataType = rs.getString("market_data_type");
            row.curveId = rs.getString("curve_id");
            row.curveContentText = rs.getString("curve_content_text");
            return row;
        }, Date.valueOf(dataDate));
    }
}
