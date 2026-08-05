package com.zcyh.mr.springboot.input.market;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.alibaba.fastjson2.JSONObject;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

@Repository
public class MarketImportRepository {
    private final JdbcTemplate jdbcTemplate;

    public MarketImportRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, String> findExisting(LocalDate dataDate, String marketDataType, List<String> curveIds) {
        if (curveIds == null || curveIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int start = 0; start < curveIds.size(); start += 500) {
            int end = Math.min(start + 500, curveIds.size());
            List<String> part = curveIds.subList(start, end);
            String placeholders = String.join(",", Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<Object>();
            args.add(Date.valueOf(dataDate));
            args.add(marketDataType);
            args.addAll(part);
            String sql = "SELECT curve_id,curve_content_text FROM MR_MARKET_CURVE_INPUT "
                    + "WHERE data_date=? AND market_data_type=? AND version_no=1 AND curve_id IN ("
                    + placeholders + ")";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
            for (Map<String, Object> row : rows) {
                result.put(Objects.toString(row.get("curve_id"), ""),
                        Objects.toString(row.get("curve_content_text"), ""));
            }
        }
        return result;
    }

    public JSONObject findMarketDetail(LocalDate dataDate, String marketDataType, String curveId, int versionNo) {
        return findDetail("MR_MARKET_CURVE_INPUT", dataDate, marketDataType, curveId, versionNo, null);
    }

    public JSONObject findRawDetail(LocalDate dataDate, String marketDataType, String curveId,
            int versionNo, String conversionType) {
        return findDetail("MR_MARKET_CURVE_RAW_INPUT", dataDate, marketDataType, curveId, versionNo, conversionType);
    }

    public Set<String> findNonPrimaryVersionCurveIds(
            LocalDate dataDate, String marketDataType, List<String> curveIds) {
        if (curveIds == null || curveIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<String>();
        for (int start = 0; start < curveIds.size(); start += 500) {
            int end = Math.min(start + 500, curveIds.size());
            List<String> part = curveIds.subList(start, end);
            String placeholders = String.join(",", Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<Object>();
            args.add(Date.valueOf(dataDate));
            args.add(marketDataType);
            args.addAll(part);
            String sql = "SELECT DISTINCT curve_id FROM MR_MARKET_CURVE_INPUT "
                    + "WHERE data_date=? AND market_data_type=? AND version_no<>1 AND curve_id IN ("
                    + placeholders + ")";
            result.addAll(jdbcTemplate.queryForList(sql, String.class, args.toArray()));
        }
        return result;
    }

    public void insert(List<MarketImportRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO MR_MARKET_CURVE_INPUT "
                + "(data_date,market_data_type,curve_id,curve_content_text,content_format,version_no,"
                + "source_system,created_at,updated_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))";
        jdbcTemplate.batchUpdate(sql, rows, 200, (ps, row) -> {
            ps.setDate(1, Date.valueOf(row.dataDate));
            ps.setString(2, row.marketDataType);
            ps.setString(3, row.curveId);
            ps.setString(4, row.curveContent.toJSONString());
            ps.setString(5, "JSON");
            ps.setInt(6, 1);
            ps.setString(7, "EXCEL_IMPORT");
        });
    }

    public void update(List<MarketImportRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = "UPDATE MR_MARKET_CURVE_INPUT SET curve_content_text=?,content_format='JSON',"
                + "source_system='EXCEL_IMPORT',updated_at=CURRENT_TIMESTAMP(3) "
                + "WHERE data_date=? AND market_data_type=? AND curve_id=? AND version_no=1";
        jdbcTemplate.batchUpdate(sql, rows, 200, (ps, row) -> {
            ps.setString(1, row.curveContent.toJSONString());
            ps.setDate(2, Date.valueOf(row.dataDate));
            ps.setString(3, row.marketDataType);
            ps.setString(4, row.curveId);
        });
    }

    public int updateEditedMarket(LocalDate dataDate, String marketDataType,
            String curveId, int versionNo, String curveContentText) {
        return jdbcTemplate.update("UPDATE MR_MARKET_CURVE_INPUT SET curve_content_text=?,"
                        + "content_format='JSON',version_no=version_no+1,updated_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE data_date=? AND market_data_type=? AND curve_id=? AND version_no=?",
                curveContentText, Date.valueOf(dataDate), marketDataType, curveId, versionNo);
    }

    public int updateEditedRaw(LocalDate dataDate, String marketDataType, String conversionType,
            String curveId, int versionNo, String curveContentText) {
        return jdbcTemplate.update("UPDATE MR_MARKET_CURVE_RAW_INPUT SET curve_content_text=?,"
                        + "content_format='JSON',version_no=version_no+1,updated_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE data_date=? AND market_data_type=? AND curve_id=? AND version_no=? "
                        + "AND conversion_type=?",
                curveContentText, Date.valueOf(dataDate), marketDataType, curveId, versionNo, conversionType);
    }

    public int delete(List<MarketDeleteKey> rows) {
        int[][] counts = jdbcTemplate.batchUpdate(
                "DELETE FROM MR_MARKET_CURVE_INPUT WHERE data_date=? AND market_data_type=? AND curve_id=? AND version_no=?",
                rows,
                200,
                (ps, row) -> {
                    ps.setDate(1, Date.valueOf(row.getDataDate()));
                    ps.setString(2, row.getMarketDataType());
                    ps.setString(3, row.getCurveId());
                    ps.setInt(4, row.getVersionNo());
                });
        return java.util.Arrays.stream(counts).flatMapToInt(java.util.Arrays::stream)
                .map(value -> value == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(value, 0)).sum();
    }

    private JSONObject findDetail(String tableName, LocalDate dataDate, String marketDataType,
            String curveId, int versionNo, String conversionType) {
        StringBuilder sql = new StringBuilder("SELECT data_date,market_data_type,curve_id,curve_content_text,")
                .append("content_format,version_no,source_system,created_at,updated_at");
        if ("MR_MARKET_CURVE_RAW_INPUT".equals(tableName)) {
            sql.append(",conversion_type");
        }
        sql.append(" FROM ").append(tableName)
                .append(" WHERE data_date=? AND market_data_type=? AND curve_id=? AND version_no=?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(dataDate));
        args.add(marketDataType);
        args.add(curveId);
        args.add(versionNo);
        if ("MR_MARKET_CURVE_RAW_INPUT".equals(tableName)) {
            sql.append(" AND conversion_type=?");
            args.add(conversionType);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        return rows.isEmpty() ? null : new JSONObject(rows.get(0));
    }
}
