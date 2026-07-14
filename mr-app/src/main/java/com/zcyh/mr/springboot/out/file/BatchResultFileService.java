package com.zcyh.mr.springboot.out.file;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * 默认不导出；开启后在批次下所有子任务都进入终态时输出一个带时间戳的结果快照文件。
 */
@Service
public class BatchResultFileService {
    private static final Logger log = LoggerFactory.getLogger(BatchResultFileService.class);
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final byte[] LINE_SEPARATOR = new byte[]{'\n'};

    private final JdbcTemplate engineDbJdbcTemplate;
    private final JdbcTemplate engineResultDbJdbcTemplate;
    private final boolean batchFileResultEnabled;
    private final String batchFileResultDir;

    public BatchResultFileService(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate,
            @Value("${mr.batch.file-result.enabled:false}") boolean batchFileResultEnabled,
            @Value("${mr.batch.file-result.dir:./data/batch-file-result}") String batchFileResultDir) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
        this.batchFileResultEnabled = batchFileResultEnabled;
        this.batchFileResultDir = batchFileResultDir == null ? "./data/batch-file-result" : batchFileResultDir.trim();
    }

    /**
     * 如果任务属于批次，且批次已经全部结束，则输出一份批次快照。
     */
    public void tryWriteSnapshotForJob(String jobId) {
        if (!batchFileResultEnabled) {
            return;
        }
        String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            return;
        }
        try {
            String batchId = findBatchIdByJobId(safeJobId);
            if (batchId == null) {
                return;
            }
            if (hasNonTerminalChild(batchId)) {
                return;
            }
            writeBatchSnapshot(batchId);
        } catch (Exception ex) {
            log.warn("批次文件结果导出跳过，jobId={}，原因={}", safeJobId, cleanReason(ex), ex);
            return;
        }
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
        Path directory = Paths.get(batchFileResultDir).toAbsolutePath().normalize().resolve(snapshotName);
        try {
            Files.createDirectories(directory);

            List<Map<String, Object>> sections = new ArrayList<Map<String, Object>>();
            Map<String, Object> batchJob = queryBatchJob(batchId);
            String dataDate = resolveBatchDataDate(batchJob);
            if (batchJob == null) {
                sections.add(skippedSectionMeta("batch_job", "未找到批次控制记录"));
            } else {
                writeSingleJson(directory.resolve("batch_job.json"), batchJob);
                sections.add(sectionMeta("batch_job", "batch_job.json", 1, "json"));
            }

            sections.add(writeOptionalJsonlGzipSection(directory, "batch_items",
                    "SELECT * FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=? ORDER BY seq_no",
                    batchId,
                    engineDbJdbcTemplate));
            sections.add(writeOptionalJsonlGzipSection(directory, "async_jobs",
                    "SELECT j.* "
                            + "FROM MR_ASYNC_BATCH_ITEM i "
                            + "JOIN MR_ASYNC_JOB j ON i.job_id=j.job_id "
                            + "WHERE i.batch_id=? ORDER BY i.seq_no",
                    batchId,
                    engineDbJdbcTemplate));
            addResultDbSections(directory, sections, batchId, dataDate);

            Map<String, Object> manifest = new LinkedHashMap<String, Object>();
            manifest.put("batch_id", batchId);
            manifest.put("format", "jsonl.gz");
            manifest.put("snapshot_dir", directory.toString());
            manifest.put("created_at", LocalDateTime.now().toString());
            manifest.put("sections", sections);
            writeSingleJson(directory.resolve("manifest.json"), manifest);
        } catch (Exception ex) {
            throw new IllegalStateException("批次文件结果写入失败: " + directory + "，原因=" + ex.getMessage(), ex);
        }
    }

    private void addResultDbSections(Path directory, List<Map<String, Object>> sections, String batchId, String dataDate) {
        addResultDbSection(directory, sections, "trade_result_detail", "TB_OUT_TRADE_RESULT_DETAIL", batchId, dataDate);
        addResultDbSection(directory, sections, "trade_scenario_result_detail", "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL", batchId, dataDate);
        addResultDbSection(directory, sections, "trade_scenario_var_result_detail", "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL", batchId, dataDate);
        addResultDbSection(directory, sections, "trade_frtb_sensitivity_detail", "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL", batchId, dataDate);
        addResultDbSection(directory, sections, "trade_drc_detail", "TB_OUT_TRADE_DRC_DETAIL", batchId, dataDate);
        addResultDbSection(directory, sections, "market_data_detail", "TB_OUT_MARKET_DATA_DETAIL", batchId, dataDate);
        addResultDbSection(directory, sections, "portfolio_hierarchy", "TB_OUT_PORTFOLIO_HIERARCHY", batchId, dataDate);
        addResultDbSection(directory, sections, "scenario_file_detail", "TB_OUT_SCENARIO_FILE_DETAIL", batchId, dataDate);
        addResultDbSection(directory, sections, "ima_modellable_scenario_pnl", "TB_OUT_IMA_MODELLABLE_SCENARIO_PNL", batchId, dataDate);
        addResultDbSection(directory, sections, "ima_nmrf_scenario_pnl", "TB_OUT_IMA_NMRF_SCENARIO_PNL", batchId, dataDate);
    }

    private void addResultDbSection(Path directory, List<Map<String, Object>> sections, String sectionName,
                                    String tableName, String batchId, String dataDate) {
        if (trimToNull(dataDate) == null) {
            sections.add(skippedSectionMeta(sectionName, "批次控制记录缺少 data_date"));
            return;
        }
        sections.add(writeOptionalJsonlGzipSection(directory, sectionName,
                "SELECT * FROM " + tableName + " WHERE batch_id=? AND data_date=?",
                new Object[]{batchId, dataDate},
                engineResultDbJdbcTemplate));
    }

    private void writeSingleJson(Path file, Object data) {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            JSON.writeTo(out, data, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteBigDecimalAsPlain);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON文件写入失败: " + file + "，原因=" + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> writeOptionalJsonlGzipSection(Path directory, String sectionName, String sql,
                                                              String batchId, JdbcTemplate jdbcTemplate) {
        return writeOptionalJsonlGzipSection(directory, sectionName, sql, new Object[]{batchId}, jdbcTemplate);
    }

    private Map<String, Object> writeOptionalJsonlGzipSection(Path directory, String sectionName, String sql,
                                                              Object[] args, JdbcTemplate jdbcTemplate) {
        try {
            jdbcTemplate.queryForList(sql + " LIMIT 0", args);
        } catch (Exception ex) {
            String reason = cleanReason(ex);
            log.warn("批次文件结果 section 跳过，section={}，原因={}", sectionName, reason);
            return skippedSectionMeta(sectionName, reason);
        }
        try {
            return writeJsonlGzipSection(directory, sectionName, sql, args, jdbcTemplate);
        } catch (Exception ex) {
            String reason = cleanReason(ex);
            log.warn("批次文件结果 section 写入失败，section={}，原因={}", sectionName, reason, ex);
            return skippedSectionMeta(sectionName, reason);
        }
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
        meta.put("skipped", false);
        return meta;
    }

    private Map<String, Object> skippedSectionMeta(String name, String reason) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("name", name);
        meta.put("file", null);
        meta.put("format", null);
        meta.put("row_count", 0);
        meta.put("skipped", true);
        meta.put("skip_reason", reason);
        return meta;
    }

    private Map<String, Object> queryBatchJob(String batchId) {
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                    "SELECT * FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?",
                    batchId
            );
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (Exception ex) {
            log.warn("批次文件结果批次控制记录读取失败，batchId={}，原因={}", batchId, cleanReason(ex));
            return null;
        }
    }

    private static String resolveBatchDataDate(Map<String, Object> batchJob) {
        if (batchJob == null || batchJob.isEmpty()) {
            return null;
        }
        Object value = batchJob.get("data_date");
        if (value == null) {
            value = batchJob.get("DATA_DATE");
        }
        String text = value == null ? null : value.toString().trim();
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.matches("\\d{8}")) {
            return text;
        }
        if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return text.replace("-", "");
        }
        return null;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    private static String cleanReason(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return message.replace('\r', ' ').replace('\n', ' ').trim();
    }
}


