package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 批次结果文件落地服务。
 * 当批次下所有子任务都进入终态后，输出一个带时间戳的结果快照文件。
 */
@Service
public class BatchResultFileService {
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final byte[] LINE_SEPARATOR = new byte[]{'\n'};

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
        String snapshotName = batchId + "_" + FILE_TS_FORMAT.format(LocalDateTime.now());
        Path directory = Paths.get(batchResultDir).toAbsolutePath().normalize().resolve(snapshotName);
        try {
            Files.createDirectories(directory);

            List<Map<String, Object>> sections = new ArrayList<Map<String, Object>>();
            Map<String, Object> batchJob = queryBatchJob(batchId, engineDbJdbcTemplate);
            String dataDate = requireBatchDataDate(batchJob, batchId);
            writeSingleJson(directory.resolve("batch_job.json"), batchJob);
            sections.add(sectionMeta("batch_job", "batch_job.json", batchJob == null ? 0 : 1, "json"));

            sections.add(writeJsonlGzipSection(directory, "batch_items",
                    "SELECT * FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=? ORDER BY seq_no",
                    batchId,
                    engineDbJdbcTemplate));
            sections.add(writeJsonlGzipSection(directory, "async_jobs",
                    "SELECT j.* "
                            + "FROM MR_ASYNC_BATCH_ITEM i "
                            + "JOIN MR_ASYNC_JOB j ON i.job_id=j.job_id "
                            + "WHERE i.batch_id=? ORDER BY i.seq_no",
                    batchId,
                    engineDbJdbcTemplate));
            sections.add(writeJsonlGzipSection(directory, "trade_result_detail",
                    "SELECT * FROM TB_OUT_TRADE_RESULT_DETAIL WHERE batch_id=? AND data_date=? ORDER BY seq_no, id",
                    new Object[]{batchId, dataDate},
                    engineResultDbJdbcTemplate));
            sections.add(writeJsonlGzipSection(directory, "trade_frtb_sensitivity_detail",
                    "SELECT * FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL WHERE batch_id=? AND data_date=? ORDER BY seq_no, id",
                    new Object[]{batchId, dataDate},
                    engineResultDbJdbcTemplate));
            sections.add(writeJsonlGzipSection(directory, "trade_drc_detail",
                    "SELECT * FROM TB_OUT_TRADE_DRC_DETAIL WHERE batch_id=? AND data_date=? ORDER BY seq_no, id",
                    new Object[]{batchId, dataDate},
                    engineResultDbJdbcTemplate));
            sections.add(writeJsonlGzipSection(directory, "trade_drc_result",
                    "SELECT * FROM TB_OUT_TRADE_DRC_RESULT WHERE batch_id=? AND data_date=? ORDER BY data_date, capital_type, agg_level, drc_type, drc_bucket, legal_entity",
                    new Object[]{batchId, dataDate},
                    engineResultDbJdbcTemplate));
            sections.add(writeJsonlGzipSection(directory, "calc_rule_meta",
                    "SELECT * FROM TB_OUT_CALC_RULE_META WHERE batch_id=? AND data_date=? ORDER BY data_date, calc_type, rule_id",
                    new Object[]{batchId, dataDate},
                    engineResultDbJdbcTemplate));

            Map<String, Object> manifest = new LinkedHashMap<String, Object>();
            manifest.put("batch_id", batchId);
            manifest.put("format", "jsonl.gz");
            manifest.put("snapshot_dir", directory.toString());
            manifest.put("created_at", LocalDateTime.now().toString());
            manifest.put("sections", sections);
            writeSingleJson(directory.resolve("manifest.json"), manifest);
        } catch (Exception ex) {
            throw new IllegalStateException("批次结果文件写入失败: " + directory + "，原因=" + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> writeJobResultData(String jobId, Object data) {
        String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        String fileName = safeJobId + "_" + FILE_TS_FORMAT.format(LocalDateTime.now()) + ".json.gz";
        Path directory = Paths.get(batchResultDir).toAbsolutePath().normalize().resolve("job-result");
        Path file = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                JSON.writeTo(out, data, JSONWriter.Feature.WriteBigDecimalAsPlain);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("任务结果文件写入失败: " + file + "，原因=" + ex.getMessage(), ex);
        }

        Map<String, Object> reference = new LinkedHashMap<String, Object>();
        reference.put("RESULT_STORAGE", "LOCAL_FILE");
        reference.put("FORMAT", "json.gz");
        reference.put("PATH", file.toString());
        reference.put("JOB_ID", safeJobId);
        reference.put("CREATED_AT", LocalDateTime.now().toString());
        return reference;
    }

    private void writeSingleJson(Path file, Object data) {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            JSON.writeTo(out, data, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteBigDecimalAsPlain);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON文件写入失败: " + file + "，原因=" + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> writeJsonlGzipSection(Path directory, String sectionName, String sql, String batchId,
                                                      JdbcTemplate jdbcTemplate) {
        return writeJsonlGzipSection(directory, sectionName, sql, new Object[]{batchId}, jdbcTemplate);
    }

    private Map<String, Object> writeJsonlGzipSection(Path directory, String sectionName, String sql, Object[] args,
                                                      JdbcTemplate jdbcTemplate) {
        String fileName = sectionName + ".jsonl.gz";
        Path file = directory.resolve(fileName);
        final long[] rowCount = new long[]{0L};
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            jdbcTemplate.query(
                    sql,
                    ps -> {
                        for (int i = 0; i < args.length; i++) {
                            ps.setObject(i + 1, args[i]);
                        }
                    },
                    rs -> {
                        try {
                            ResultSetMetaData metaData = rs.getMetaData();
                            int columnCount = metaData.getColumnCount();
                            Map<String, Object> row = new LinkedHashMap<String, Object>();
                            for (int i = 1; i <= columnCount; i++) {
                                row.put(metaData.getColumnLabel(i), rs.getObject(i));
                            }
                            JSON.writeTo(out, row, JSONWriter.Feature.WriteBigDecimalAsPlain);
                            out.write(LINE_SEPARATOR);
                            rowCount[0]++;
                        } catch (Exception ex) {
                            throw new SQLException("写入JSONL行失败", ex);
                        }
                    }
            );
        } catch (Exception ex) {
            throw new IllegalStateException("JSONL文件写入失败: " + file + "，原因=" + ex.getMessage(), ex);
        }
        return sectionMeta(sectionName, fileName, rowCount[0], "jsonl.gz");
    }

    private Map<String, Object> sectionMeta(String name, String fileName, long rowCount, String format) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("name", name);
        meta.put("file", fileName);
        meta.put("format", format);
        meta.put("row_count", rowCount);
        return meta;
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

    private static String requireBatchDataDate(Map<String, Object> batchJob, String batchId) {
        if (batchJob == null || batchJob.isEmpty()) {
            throw new IllegalStateException("未找到批次控制记录: " + batchId);
        }
        Object value = batchJob.get("data_date");
        if (value == null) {
            value = batchJob.get("DATA_DATE");
        }
        String text = value == null ? null : value.toString().trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalStateException("批次控制记录缺少 data_date: " + batchId);
        }
        if (text.matches("\\d{8}")) {
            return text;
        }
        if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return text.replace("-", "");
        }
        throw new IllegalStateException("批次控制记录 data_date 格式错误: " + text);
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
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
                "SELECT batch_id, seq_no, id FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_DRC_DETAIL",
                "SELECT batch_id, seq_no, id FROM TB_OUT_TRADE_DRC_DETAIL WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_TRADE_DRC_RESULT",
                "SELECT batch_id, data_date, capital_type, agg_level FROM TB_OUT_TRADE_DRC_RESULT WHERE 1=0");
        verifyQuery(engineResultDbJdbcTemplate, "engine_result_db", "TB_OUT_CALC_RULE_META",
                "SELECT batch_id, data_date, calc_type, rule_id FROM TB_OUT_CALC_RULE_META WHERE 1=0");
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


