package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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

    private final JdbcTemplate engineDbJdbcTemplate;
    private final JdbcTemplate engineResultDbJdbcTemplate;
    private final String batchResultDir;

    public BatchResultFileService(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate,
            @Value("${mr.batch.result.dir:./data/batch-result}") String batchResultDir) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
        this.batchResultDir = batchResultDir == null ? "./data/batch-result" : batchResultDir.trim();
    }

    /**
     * 系统启动后一次性校验批次快照依赖表结构。
     */
    @PostConstruct
    public void verifyRequiredSchemaOnStartup() {
        ensureRequiredSchema();
    }

    /**
     * 如果任务属于批次，且批次已经全部结束，则输出一份批次快照。
     */
    public void tryWriteSnapshotForJob(String jobId) {
        String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
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
        snapshot.put("trade_drc_result", queryForList(
                "SELECT * FROM TB_OUT_TRADE_DRC_RESULT WHERE batch_id=? ORDER BY data_date, decomp_flag, agg_level, drc_type, drc_bucket, legal_entity",
                batchId,
                engineResultDbJdbcTemplate
        ));

        String fileName = batchId + "_" + FILE_TS_FORMAT.format(LocalDateTime.now()) + ".json";
        Path directory = Paths.get(batchResultDir).toAbsolutePath().normalize();
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

    /**
     * 严格校验批次快照依赖的表列契约，避免运行时才暴露结构问题。
     */
    private void ensureRequiredSchema() {
        verifyQuery(engineDbJdbcTemplate, "engine_db", "MR_ASYNC_BATCH_JOB",
                "SELECT batch_id FROM MR_ASYNC_BATCH_JOB WHERE 1=0");
        verifyQuery(engineDbJdbcTemplate, "engine_db", "MR_ASYNC_BATCH_ITEM",
                "SELECT batch_id, seq_no, job_id FROM MR_ASYNC_BATCH_ITEM WHERE 1=0");
        verifyQuery(engineDbJdbcTemplate, "engine_db", "MR_ASYNC_JOB",
                "SELECT job_id, status FROM MR_ASYNC_JOB WHERE 1=0");

        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_RESULT_DETAIL",
                "SELECT batch_id, seq_no, id FROM TB_OUT_TRADE_RESULT_DETAIL WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
                "SELECT batch_id, seq_no, id FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL",
                "SELECT batch_id, seq_no, id FROM TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
                "SELECT batch_id, seq_no, id FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_DRC_DETAIL",
                "SELECT batch_id, seq_no, id FROM TB_OUT_TRADE_DRC_DETAIL WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_DRC_RESULT",
                "SELECT batch_id, data_date, decomp_flag, agg_level FROM TB_OUT_TRADE_DRC_RESULT WHERE 1=0");
    }

    private void verifyQuery(JdbcTemplate jdbcTemplate, String dataSourceName, String tableName, String sql) {
        try {
            jdbcTemplate.queryForList(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("数据源[" + dataSourceName + "]表结构校验失败: " + tableName + "，原因=" + ex.getMessage(), ex);
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


