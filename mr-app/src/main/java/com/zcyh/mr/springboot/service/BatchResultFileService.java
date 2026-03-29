package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 批次结果文件落地服务。
 * 当批次下所有子任务都进入终态后，输出一个带时间戳的结果快照文件。
 */
@Service
public class BatchResultFileService {
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String[] REQUIRED_SCENARIO_TABLES = new String[]{
            "MR_ASYNC_BATCH_JOB",
            "MR_ASYNC_BATCH_ITEM",
            "MR_ASYNC_JOB"
    };
    private static final String[] REQUIRED_OUTPUT_TABLES = new String[]{
            "TB_OUT_TRADE_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL",
            "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
            "TB_OUT_TRADE_DRC_DETAIL"
    };

    private final JdbcTemplate engineDbJdbcTemplate;
    private final JdbcTemplate engineResultDbJdbcTemplate;

    public BatchResultFileService(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
    }

    /**
     * 如果任务属于批次，且批次已经全部结束，则输出一份批次快照。
     */
    public void tryWriteSnapshotForJob(String jobId) {
        String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            return;
        }
        ensureRequiredTablesExist(engineDbJdbcTemplate, REQUIRED_SCENARIO_TABLES, "engine_db");
        ensureRequiredTablesExist(engineResultDbJdbcTemplate, REQUIRED_OUTPUT_TABLES, "engine_result_db");
        String batchId = findBatchIdByJobId(safeJobId);
        if (batchId == null) {
            return;
        }
        if (hasNonTerminalChild(batchId)) {
            return;
        }
        writeBatchSnapshot(batchId);
    }

    private String findBatchIdByJobId(String jobId) {
        List<String> batchIds = engineDbJdbcTemplate.query(
                "SELECT batch_id FROM MR_ASYNC_BATCH_ITEM WHERE job_id=?",
                ps -> ps.setString(1, jobId),
                (rs, rowNum) -> trimToNull(rs.getString(1))
        );
        if (batchIds == null || batchIds.isEmpty()) {
            return null;
        }
        return trimToNull(batchIds.get(0));
    }

    private boolean hasNonTerminalChild(String batchId) {
        Integer count = engineDbJdbcTemplate.queryForObject(
                "SELECT COUNT(1) "
                        + "FROM MR_ASYNC_BATCH_ITEM i "
                        + "JOIN MR_ASYNC_JOB j ON i.job_id=j.job_id "
                        + "WHERE i.batch_id=? AND j.status IN ('PENDING','RUNNING')",
                Integer.class,
                batchId
        );
        return count != null && count > 0;
    }

    private void writeBatchSnapshot(String batchId) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("batch_job", queryBatchJob(batchId, engineDbJdbcTemplate));
        snapshot.put("batch_items", queryForList(
                "SELECT * FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=? ORDER BY seq_no",
                batchId,
                engineDbJdbcTemplate
        ));
        snapshot.put("async_jobs", queryForList(
                "SELECT j.* "
                        + "FROM MR_ASYNC_BATCH_ITEM i "
                        + "JOIN MR_ASYNC_JOB j ON i.job_id=j.job_id "
                        + "WHERE i.batch_id=? ORDER BY i.seq_no",
                batchId,
                engineDbJdbcTemplate
        ));
        snapshot.put("trade_result_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_RESULT_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId,
                engineResultDbJdbcTemplate
        ));
        snapshot.put("trade_scenario_result_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId,
                engineResultDbJdbcTemplate
        ));
        snapshot.put("trade_scenario_var_result_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId,
                engineResultDbJdbcTemplate
        ));
        snapshot.put("trade_frtb_sensitivity_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId,
                engineResultDbJdbcTemplate
        ));
        snapshot.put("trade_drc_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_DRC_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId,
                engineResultDbJdbcTemplate
        ));

        String fileName = batchId + "_" + FILE_TS_FORMAT.format(LocalDateTime.now()) + ".json";
        Path directory = Paths.get("data", "batch-result").toAbsolutePath().normalize();
        Path file = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(
                    file,
                    JSON.toJSONString(snapshot, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteBigDecimalAsPlain)
                            .getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            throw new IllegalStateException("批次结果文件写入失败: " + file + "，原因=" + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> queryBatchJob(String batchId, JdbcTemplate jdbcTemplate) {
        List<Map<String, Object>> rows = queryForList(
                "SELECT * FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?",
                batchId,
                jdbcTemplate
        );
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> queryForList(String sql, String batchId, JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList(sql, batchId);
    }

    private void ensureRequiredTablesExist(JdbcTemplate jdbcTemplate, String[] requiredTables, String dataSourceName) {
        List<String> missingTables = new ArrayList<String>();
        for (String tableName : requiredTables) {
            if (!tableExists(jdbcTemplate, tableName)) {
                missingTables.add(tableName);
            }
        }
        if (!missingTables.isEmpty()) {
            throw new IllegalStateException("数据源[" + dataSourceName + "]缺少必需表: " + String.join(", ", missingTables));
        }
    }

    private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        try {
            Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
                DatabaseMetaData metaData = connection.getMetaData();
                String catalog = trimToNull(connection.getCatalog());
                String schema = trimToNull(connection.getSchema());
                String upper = tableName.toUpperCase(Locale.ROOT);
                String lower = tableName.toLowerCase(Locale.ROOT);
                String[] schemaCandidates = new String[]{schema, "%", null};

                for (String schemaCandidate : schemaCandidates) {
                    if (tableExists(metaData, catalog, schemaCandidate, tableName, tableName)) {
                        return true;
                    }
                    if (tableExists(metaData, catalog, schemaCandidate, upper, tableName)) {
                        return true;
                    }
                    if (tableExists(metaData, catalog, schemaCandidate, lower, tableName)) {
                        return true;
                    }
                    if (tableExists(metaData, null, schemaCandidate, tableName, tableName)) {
                        return true;
                    }
                    if (tableExists(metaData, null, schemaCandidate, upper, tableName)) {
                        return true;
                    }
                    if (tableExists(metaData, null, schemaCandidate, lower, tableName)) {
                        return true;
                    }
                }
                return false;
            });
            return exists != null && exists;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("检查表是否存在失败: " + tableName + "，原因=" + ex.getMessage(), ex);
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String catalog, String schemaPattern,
                                String tablePattern, String expectedTableName) throws SQLException {
        try (ResultSet rs = metaData.getTables(catalog, schemaPattern, tablePattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                String actualName = trimToNull(rs.getString("TABLE_NAME"));
                if (actualName != null && expectedTableName.equalsIgnoreCase(actualName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}


