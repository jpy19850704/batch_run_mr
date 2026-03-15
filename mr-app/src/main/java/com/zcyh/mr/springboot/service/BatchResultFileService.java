package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次结果文件落地服务。
 * 当批次下所有子任务都进入终态后，输出一个带时间戳的结果快照文件。
 */
@Service
public class BatchResultFileService {
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String[] REQUIRED_TABLES = new String[]{
            "MR_ASYNC_BATCH_JOB",
            "MR_ASYNC_BATCH_ITEM",
            "MR_ASYNC_JOB",
            "TB_OUT_TRADE_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_DECOMP_DETAIL",
            "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
            "TB_OUT_TRADE_DRC_DETAIL"
    };

    private final JdbcTemplate jdbcTemplate;

    public BatchResultFileService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 如果任务属于批次，且批次已经全部结束，则输出一份批次快照。
     */
    public void tryWriteSnapshotForJob(String jobId) {
        String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            return;
        }
        if (!allRequiredTablesExist()) {
            return;
        }
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
        List<String> batchIds = jdbcTemplate.query(
                "SELECT batch_id FROM mr_async_batch_item WHERE job_id=?",
                ps -> ps.setString(1, jobId),
                (rs, rowNum) -> trimToNull(rs.getString(1))
        );
        if (batchIds == null || batchIds.isEmpty()) {
            return null;
        }
        return trimToNull(batchIds.get(0));
    }

    private boolean hasNonTerminalChild(String batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) "
                        + "FROM mr_async_batch_item i "
                        + "JOIN mr_async_job j ON i.job_id=j.job_id "
                        + "WHERE i.batch_id=? AND j.status IN ('PENDING','RUNNING')",
                Integer.class,
                batchId
        );
        return count != null && count > 0;
    }

    private void writeBatchSnapshot(String batchId) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("batch_job", queryBatchJob(batchId));
        snapshot.put("batch_items", queryForList(
                "SELECT * FROM mr_async_batch_item WHERE batch_id=? ORDER BY seq_no",
                batchId
        ));
        snapshot.put("async_jobs", queryForList(
                "SELECT j.* "
                        + "FROM mr_async_batch_item i "
                        + "JOIN mr_async_job j ON i.job_id=j.job_id "
                        + "WHERE i.batch_id=? ORDER BY i.seq_no",
                batchId
        ));
        snapshot.put("trade_result_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_RESULT_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId
        ));
        snapshot.put("trade_scenario_result_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId
        ));
        snapshot.put("trade_scenario_decomp_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_SCENARIO_DECOMP_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId
        ));
        snapshot.put("trade_frtb_sensitivity_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId
        ));
        snapshot.put("trade_drc_detail", queryForList(
                "SELECT * FROM TB_OUT_TRADE_DRC_DETAIL WHERE batch_id=? ORDER BY seq_no, id",
                batchId
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

    private Map<String, Object> queryBatchJob(String batchId) {
        List<Map<String, Object>> rows = queryForList(
                "SELECT * FROM mr_async_batch_job WHERE batch_id=?",
                batchId
        );
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> queryForList(String sql, String batchId) {
        return jdbcTemplate.queryForList(sql, batchId);
    }

    private boolean allRequiredTablesExist() {
        try {
            for (String tableName : REQUIRED_TABLES) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)=?",
                        Integer.class,
                        tableName
                );
                if (count == null || count <= 0) {
                    return false;
                }
            }
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
