package com.zcyh.mr.springboot.input.trade;

import com.zcyh.mr.springboot.input.db.TradeInputRepository;
import com.zcyh.mr.springboot.input.db.TradeInputRow;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Repository
public class TradeImportRepository {
    private final JdbcTemplate jdbcTemplate;
    private final TradeInputRepository tradeInputRepository;

    public TradeImportRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            TradeInputRepository tradeInputRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.tradeInputRepository = tradeInputRepository;
    }

    public List<TradeInputRow> findExisting(LocalDate dataDate, List<String> instrumentIds) {
        if (instrumentIds == null || instrumentIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<TradeInputRow> result = new ArrayList<>();
        for (int start = 0; start < instrumentIds.size(); start += 500) {
            int end = Math.min(start + 500, instrumentIds.size());
            result.addAll(tradeInputRepository.findByInstrumentIds(dataDate,
                    instrumentIds.subList(start, end)));
        }
        return result;
    }

    public JSONObject findDetail(LocalDate dataDate, String instrumentId, String productCode, int versionNo) {
        StringBuilder sql = new StringBuilder(
                "SELECT data_date,instrument_id,product_code,trade_content_text,content_format,version_no");
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
            sql.append(',').append(definition.getColumnName());
        }
        sql.append(",created_at,updated_at FROM MR_TRADE_INPUT ")
                .append("WHERE data_date=? AND instrument_id=? AND product_code=? AND version_no=?");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), Date.valueOf(dataDate),
                instrumentId, productCode, versionNo);
        return rows.isEmpty() ? null : new JSONObject(rows.get(0));
    }

    public void insert(List<TradeImportRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder columns = new StringBuilder(
                "data_date,instrument_id,product_code,trade_content_text,content_format,version_no");
        StringBuilder values = new StringBuilder("?,?,?,?,?,?");
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
            columns.append(',').append(definition.getColumnName());
            values.append(",?");
        }
        columns.append(",created_at,updated_at");
        values.append(",CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)");
        String sql = "INSERT INTO MR_TRADE_INPUT (" + columns + ") VALUES (" + values + ")";
        jdbcTemplate.batchUpdate(sql, rows, 200, (ps, row) -> bindCommon(ps, row));
    }

    public void update(List<TradeImportRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "UPDATE MR_TRADE_INPUT SET trade_content_text=?,content_format=?,version_no=?");
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
            sql.append(',').append(definition.getColumnName()).append("=?");
        }
        sql.append(",updated_at=CURRENT_TIMESTAMP(3) WHERE data_date=? AND instrument_id=? AND product_code=?");
        jdbcTemplate.batchUpdate(sql.toString(), rows, 200, (ps, row) -> {
            int index = 1;
            ps.setString(index++, row.tradeData.toJSONString());
            ps.setString(index++, "JSON");
            ps.setInt(index++, 1);
            for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
                ps.setObject(index++, row.attributes.get(definition.getFieldName()));
            }
            ps.setDate(index++, Date.valueOf(row.dataDate));
            ps.setString(index++, row.instrumentId);
            ps.setString(index, row.productCode);
        });
    }

    public int updateEdited(LocalDate dataDate, String instrumentId, String productCode,
            int versionNo, String tradeContentText, Map<String, Object> attributes) {
        StringBuilder sql = new StringBuilder(
                "UPDATE MR_TRADE_INPUT SET trade_content_text=?,content_format='JSON',version_no=version_no+1");
        if (attributes != null) {
            for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
                sql.append(',').append(definition.getColumnName()).append("=?");
            }
        }
        sql.append(",updated_at=CURRENT_TIMESTAMP(3) WHERE data_date=? AND instrument_id=? AND product_code=? "
                + "AND version_no=?");
        List<Object> args = new ArrayList<>();
        args.add(tradeContentText);
        if (attributes != null) {
            for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
                args.add(attributes.get(definition.getFieldName()));
            }
        }
        args.add(Date.valueOf(dataDate));
        args.add(instrumentId);
        args.add(productCode);
        args.add(versionNo);
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    public int delete(List<TradeDeleteKey> rows) {
        int[][] counts = jdbcTemplate.batchUpdate(
                "DELETE FROM MR_TRADE_INPUT WHERE data_date=? AND instrument_id=? AND product_code=?",
                rows,
                200,
                (ps, row) -> {
                    ps.setDate(1, Date.valueOf(row.getDataDate()));
                    ps.setString(2, row.getInstrumentId());
                    ps.setString(3, row.getProductCode());
                });
        return java.util.Arrays.stream(counts).flatMapToInt(java.util.Arrays::stream)
                .map(value -> value == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(value, 0)).sum();
    }

    private static void bindCommon(java.sql.PreparedStatement ps, TradeImportRow row) throws java.sql.SQLException {
        int index = 1;
        ps.setDate(index++, Date.valueOf(row.dataDate));
        ps.setString(index++, row.instrumentId);
        ps.setString(index++, row.productCode);
        ps.setString(index++, row.tradeData.toJSONString());
        ps.setString(index++, "JSON");
        ps.setInt(index++, 1);
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
            ps.setObject(index++, row.attributes.get(definition.getFieldName()));
        }
    }
}
