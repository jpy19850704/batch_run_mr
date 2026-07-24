package com.zcyh.mr.springboot.input.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计量输入查询仓储。
 */
@Repository
public class MeasurementInputQueryRepository {
    private final JdbcTemplate jdbcTemplate;

    public MeasurementInputQueryRepository(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listBatches() {
        String sql = "SELECT BATCH_ID, DATA_DATE, COUNT(*) AS ROW_COUNT "
                + "FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL "
                + "GROUP BY BATCH_ID, DATA_DATE ORDER BY BATCH_ID DESC";
        return jdbcTemplate.queryForList(sql);
    }

    public List<String> listDomains(
            String tableName,
            String columnName,
            String batchId,
            String dataDate) {
        String normalizedTableName = normalizeIdentifier(tableName);
        String normalizedColumnName = normalizeIdentifier(columnName);
        validateWhitelist(normalizedTableName, normalizedColumnName);
        String sql = "SELECT DISTINCT " + normalizedColumnName
                + " FROM " + normalizedTableName
                + " WHERE BATCH_ID = ? AND DATA_DATE=? AND " + normalizedColumnName + " IS NOT NULL"
                + " ORDER BY " + normalizedColumnName;
        return jdbcTemplate.queryForList(sql, String.class, batchId,
                com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate));
    }

    public List<Map<String, Object>> listScenarios(String batchId, String dataDate) {
        String sql = "SELECT SCENARIO_ID, SCENARIO_NAME, COUNT(*) AS ROW_COUNT "
                + "FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL "
                + "WHERE BATCH_ID = ? AND DATA_DATE=? "
                + "GROUP BY SCENARIO_ID, SCENARIO_NAME ORDER BY SCENARIO_ID";
        return jdbcTemplate.queryForList(sql, batchId,
                com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate));
    }

    public List<Map<String, Object>> listInstrumentIds(String batchId, String dataDate) {
        String sql = "SELECT DISTINCT INSTRUMENT_ID, PRODUCT_CODE "
                + "FROM TB_OUT_TRADE_RESULT_DETAIL "
                + "WHERE BATCH_ID = ? AND DATA_DATE=? ORDER BY INSTRUMENT_ID";
        return jdbcTemplate.queryForList(sql, batchId,
                com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate));
    }

    private static String normalizeIdentifier(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("计量输入查询参数不能为空");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("计量输入查询参数不能为空字符串");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static void validateWhitelist(String tableName, String columnName) {
        if (!MeasurementInputDomainRegistry.isAllowed(tableName, columnName)) {
            throw new IllegalArgumentException("不允许的计量输入字段: " + tableName + "." + columnName);
        }
    }
}
