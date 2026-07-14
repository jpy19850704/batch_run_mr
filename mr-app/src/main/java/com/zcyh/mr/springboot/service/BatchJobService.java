package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.context.RequestContext;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.BatchDetailResult;
import com.zcyh.mr.springboot.model.JobSubmitRequest;
import com.zcyh.mr.springboot.model.JobSubmitResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量异步任务服务。
 * 负责批次元数据、状态聚合和子任务记录。
 */
@Service
public class BatchJobService {
    private static final String BATCH_PENDING = "PENDING";
    private static final String BATCH_SUBMITTED = "SUBMITTED";
    private static final String BATCH_RUNNING = "RUNNING";
    private static final String BATCH_SUCCESS = "SUCCESS";
    private static final String BATCH_FAILED = "FAILED";
    private static final String BATCH_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String BATCH_CANCELLED = "CANCELLED";
    static final String PAYLOAD_JSON_PARSE_ERROR = "PAYLOAD_JSON_PARSE_ERROR";
    private static final String JOB_API_BASE_PATH = "/api/jobs";
    private static final RowMapper<BatchJobRow> BATCH_JOB_ROW_MAPPER = new RowMapper<BatchJobRow>() {
        @Override
        public BatchJobRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BatchJobRow row = new BatchJobRow();
            row.batchId = rs.getString("batch_id");
            row.requestId = rs.getString("request_id");
            row.engineCode = rs.getString("engine_code");
            row.traceId = rs.getString("trace_id");
            row.clientId = rs.getString("client_id");
            row.userId = rs.getString("user_id");
            row.userName = rs.getString("user_name");
            row.sourceSystem = rs.getString("source_system");
            row.opCode = rs.getString("op_code");
            Date dataDate = rs.getDate("data_date");
            row.dataDate = dataDate == null ? null : dataDate.toLocalDate();
            row.portfolio = rs.getString("portfolio");
            row.desk = rs.getString("desk");
            row.totalTrades = rs.getInt("total_trades");
            row.totalJobs = rs.getInt("total_jobs");
            row.weightBudget = rs.getInt("chunk_size");
            row.status = rs.getString("status");
            row.pendingJobs = rs.getInt("pending_jobs");
            row.runningJobs = rs.getInt("running_jobs");
            row.successJobs = rs.getInt("success_jobs");
            row.failedJobs = rs.getInt("failed_jobs");
            row.cancelledJobs = rs.getInt("cancelled_jobs");
            row.message = rs.getString("message");
            row.createdAt = rs.getLong("created_at");
            row.updatedAt = rs.getLong("updated_at");
            return row;
        }
    };

    private static final RowMapper<BatchItemRow> BATCH_ITEM_ROW_MAPPER = new RowMapper<BatchItemRow>() {
        @Override
        public BatchItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BatchItemRow row = new BatchItemRow();
            row.seqNo = rs.getInt("seq_no");
            row.jobId = rs.getString("job_id");
            row.tradeCount = rs.getInt("trade_count");
            row.productMixJson = rs.getString("product_mix_json");
            row.jobStatus = rs.getString("job_status");
            row.errorCode = rs.getString("error_code");
            row.errorMessage = rs.getString("error_message");
            return row;
        }
    };

    private final AsyncJobService asyncJobService;
    private final JdbcTemplate jdbcTemplate;
    private final int weightBudget;
    private final long pollAfterMs;
    private final String batchApiBasePath;

    public BatchJobService(
            AsyncJobService asyncJobService,
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Value("${mr.batch.weight-budget:100}") int weightBudget,
            @Value("${mr.batch.client.poll-after-ms:500}") long pollAfterMs,
            @Value("${mr.batch.api.base-path:/api/jobs/batch}") String batchApiBasePath
    ) {
        this.asyncJobService = asyncJobService;
        this.jdbcTemplate = jdbcTemplate;
        this.weightBudget = Math.max(1, weightBudget);
        this.pollAfterMs = Math.max(100L, pollAfterMs);
        this.batchApiBasePath = normalizeApiBasePath(batchApiBasePath);
        verifyBatchSchema();
    }

    // ==================== 表结构校验 ====================

    private void verifyBatchSchema() {
        jdbcTemplate.queryForList(
                "SELECT batch_id,request_id,engine_code,trace_id,client_id,user_id,user_name,source_system,op_code,data_date,portfolio,desk,total_trades,total_jobs,chunk_size,status,pending_jobs,running_jobs,success_jobs,failed_jobs,cancelled_jobs,message,created_at,updated_at "
                        + "FROM MR_ASYNC_BATCH_JOB WHERE 1=0");
        jdbcTemplate.queryForList(
                "SELECT id,batch_id,seq_no,job_id,trade_count,product_mix_json,created_at "
                        + "FROM MR_ASYNC_BATCH_ITEM WHERE 1=0");
    }

    // ==================== 提交入口 ====================

    void prepareBatchSubmission(
            String batchId,
            String requestId,
            String engineCode,
            String opCode,
            LocalDate dataDate,
            String portfolio,
            String desk,
            int totalTrades,
            int totalJobs,
            long now) {
        if (batchExists(batchId)) {
            updateBatchDefinition(batchId, requestId, engineCode, opCode, dataDate, portfolio, desk, totalTrades, totalJobs, now);
            return;
        }
        ensureBatchNotRunning(batchId);
        clearExistingBatchData(batchId);
        insertBatchJob(batchId, requestId, engineCode, opCode, dataDate, portfolio, desk, totalTrades, totalJobs, now);
    }

    void initializeWorkflowBatch(
            String batchId,
            String requestId,
            String engineCode,
            String opCode,
            LocalDate dataDate,
            String portfolio,
            String desk,
            long now,
            String message) {
        ensureNoOtherWorkflowRunning(batchId);
        ensureWorkflowNotRunning(batchId);
        ensureBatchNotRunning(batchId);
        clearExistingBatchData(batchId);
        insertBatchJob(batchId, requestId, engineCode, opCode, dataDate, portfolio, desk, 0, 0, now);
        updateBatchStatus(batchId, BATCH_PENDING, 0, 0, 0, 0, 0, now, message);
    }

    void markWorkflowRunning(String batchId, String message) {
        BatchJobRow batchRow = requireBatchRow(batchId);
        updateBatchStatus(
                batchId,
                BATCH_RUNNING,
                batchRow.pendingJobs,
                batchRow.runningJobs,
                batchRow.successJobs,
                batchRow.failedJobs,
                batchRow.cancelledJobs,
                System.currentTimeMillis(),
                message
        );
    }

    void markWorkflowFailed(String batchId, String message) {
        BatchJobRow batchRow = requireBatchRow(batchId);
        updateBatchStatus(
                batchId,
                BATCH_FAILED,
                0,
                0,
                batchRow.successJobs,
                Math.max(batchRow.failedJobs, 1),
                batchRow.cancelledJobs,
                System.currentTimeMillis(),
                message
        );
    }

    void markWorkflowSuccess(String batchId, String message) {
        BatchJobRow batchRow = requireBatchRow(batchId);
        updateBatchStatus(
                batchId,
                BATCH_SUCCESS,
                batchRow.pendingJobs,
                batchRow.runningJobs,
                batchRow.successJobs,
                batchRow.failedJobs,
                batchRow.cancelledJobs,
                System.currentTimeMillis(),
                message
        );
    }

    String submitBatchChildJob(
            String batchId,
            String requestId,
            String engineCode,
            BatchJobPayload jobPayload) {
        if (jobPayload == null) {
            throw new IllegalArgumentException("jobPayload 不能为空");
        }
        int seqNo = jobPayload.getSeqNo();
        String jobId = buildJobId(batchId, seqNo);
        if (jobPayload.isFailed()) {
            asyncJobService.recordFailedJob(
                    jobId,
                    buildJobRequestId(requestId, seqNo),
                    engineCode,
                    jobPayload.getErrorCode(),
                    jobPayload.getErrorMessage());
            insertBatchItem(batchId, seqNo, jobId, jobPayload.getChunkTrades());
            return jobId;
        }

        JobSubmitRequest jobRequest = new JobSubmitRequest();
        jobRequest.setJobId(jobId);
        jobRequest.setRequestId(buildJobRequestId(requestId, seqNo));
        jobRequest.setEngineCode(engineCode);
        jobRequest.setIdempotencyKey(jobId);
        jobRequest.setPayload(jobPayload.getPayload());

        JobSubmitResult submitResult = asyncJobService.submit(jobRequest);
        insertBatchItem(batchId, seqNo, submitResult.getJobId(), jobPayload.getChunkTrades());
        return submitResult.getJobId();
    }

    int prepareLocalRerun(String batchId, LocalDate dataDate) {
        String safeBatchId = requireNonBlank(batchId, "batchId 不能为空");
        if (dataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        BatchJobRow batchRow = requireBatchRow(safeBatchId);
        if (batchRow.dataDate == null || !batchRow.dataDate.equals(dataDate)) {
            throw new IllegalArgumentException("dataDate 与批次不一致: " + safeBatchId);
        }
        ensureBatchNotRunning(safeBatchId);
        return nextSeqNo(safeBatchId);
    }

    // ==================== 查询 ====================

    public BatchDetailResult getDetail(String batchId) {
        String safeBatchId = requireNonBlank(batchId, "batchId 不能为空");
        BatchJobRow batchRow = requireBatchRow(safeBatchId);
        List<BatchItemRow> itemRows = loadBatchItems(safeBatchId);
        if (batchRow.totalJobs <= 0 && itemRows.isEmpty()) {
            return buildBatchDetail(batchRow, itemRows, isBatchTerminal(batchRow.status), false);
        }
        AggregatedCount aggregated = aggregate(itemRows, batchRow.totalJobs);
        String mergedStatus = deriveBatchStatus(aggregated, batchRow.totalJobs);
        long now = System.currentTimeMillis();

        if (!equalsNullable(batchRow.status, mergedStatus)
                || batchRow.pendingJobs != aggregated.pendingJobs
                || batchRow.runningJobs != aggregated.runningJobs
                || batchRow.successJobs != aggregated.successJobs
                || batchRow.failedJobs != aggregated.failedJobs
                || batchRow.cancelledJobs != aggregated.cancelledJobs) {
            updateBatchStatus(
                    safeBatchId, mergedStatus,
                    aggregated.pendingJobs, aggregated.runningJobs,
                    aggregated.successJobs, aggregated.failedJobs,
                    aggregated.cancelledJobs, now, batchRow.message
            );
            batchRow = requireBatchRow(safeBatchId);
        }
        return buildBatchDetail(batchRow, itemRows, aggregated.done, aggregated.success);
    }

    int getWeightBudget() {
        return weightBudget;
    }

    long getPollAfterMs() {
        return pollAfterMs;
    }

    String getDetailUrl(String batchId) {
        return buildDetailUrl(batchId);
    }

    private BatchDetailResult buildBatchDetail(BatchJobRow batchRow, List<BatchItemRow> itemRows, boolean done, boolean success) {
        BatchDetailResult detail = new BatchDetailResult();
        detail.setBatchId(batchRow.batchId);
        detail.setRequestId(batchRow.requestId);
        detail.setEngineCode(batchRow.engineCode);
        detail.setOpCode(batchRow.opCode);
        detail.setDataDate(batchRow.dataDate == null ? null : batchRow.dataDate.toString());
        detail.setStatus(batchRow.status);
        detail.setTotalTrades(batchRow.totalTrades);
        detail.setTotalJobs(batchRow.totalJobs);
        detail.setWeightBudget(batchRow.weightBudget);
        detail.setPendingJobs(batchRow.pendingJobs);
        detail.setRunningJobs(batchRow.runningJobs);
        detail.setSuccessJobs(batchRow.successJobs);
        detail.setFailedJobs(batchRow.failedJobs);
        detail.setCancelledJobs(batchRow.cancelledJobs);
        detail.setSubmittedAt(batchRow.createdAt);
        detail.setUpdatedAt(batchRow.updatedAt);
        detail.setDone(done);
        detail.setSuccess(success);
        detail.setPollAfterMs(pollAfterMs);
        detail.setDetailUrl(buildDetailUrl(batchRow.batchId));
        detail.setMessage(batchRow.message);
        detail.setJobs(toBatchJobItems(itemRows));
        return detail;
    }

    // ==================== DB 操作 ====================

    private void ensureBatchNotRunning(String batchId) {
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM MR_ASYNC_BATCH_ITEM i "
                        + "LEFT JOIN MR_ASYNC_JOB j ON j.job_id=i.job_id "
                        + "WHERE i.batch_id=? AND (j.status IS NULL OR j.status IN ('PENDING','RUNNING'))",
                Integer.class,
                batchId
        );
        if (active != null && active > 0) {
            throw new IllegalStateException("batch_id 正在运行，不能覆盖重跑: " + batchId);
        }
    }

    private void ensureWorkflowNotRunning(String batchId) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?",
                String.class,
                batchId
        );
        if (statuses == null || statuses.isEmpty()) {
            return;
        }
        for (String status : statuses) {
            String safeStatus = trimToNull(status);
            if (BATCH_PENDING.equalsIgnoreCase(safeStatus)
                    || BATCH_SUBMITTED.equalsIgnoreCase(safeStatus)
                    || BATCH_RUNNING.equalsIgnoreCase(safeStatus)) {
                throw new IllegalStateException("batch_id 工作流正在运行，不能覆盖重跑: " + batchId);
            }
        }
    }

    private void ensureNoOtherWorkflowRunning(String batchId) {
        List<String> activeBatchIds = jdbcTemplate.queryForList(
                "SELECT batch_id FROM MR_ASYNC_BATCH_JOB "
                        + "WHERE batch_id<>? AND status IN ('PENDING','SUBMITTED','RUNNING')",
                String.class,
                batchId
        );
        if (activeBatchIds != null && !activeBatchIds.isEmpty()) {
            throw new IllegalStateException("已有批次工作流正在运行，不能同时启动新的批次: "
                    + activeBatchIds.get(0));
        }
    }

    private void clearExistingBatchData(String batchId) {
        List<String> oldJobIds = jdbcTemplate.queryForList(
                "SELECT job_id FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=? ORDER BY seq_no",
                String.class,
                batchId);

        jdbcTemplate.update("DELETE FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=?", batchId);
        jdbcTemplate.update("DELETE FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?", batchId);

        if (oldJobIds != null) {
            for (String jobId : oldJobIds) {
                if (trimToNull(jobId) != null) {
                    jdbcTemplate.update("DELETE FROM MR_ASYNC_JOB WHERE job_id=?", jobId);
                }
            }
        }
    }
    private boolean batchExists(String batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?",
                Integer.class,
                batchId
        );
        return count != null && count > 0;
    }

    void insertBatchJob(
            String batchId, String requestId, String engineCode, String opCode,
            LocalDate dataDate, String portfolio, String desk,
            int totalTrades, int totalJobs, long now
    ) {
        RequestContext context = RequestContextHolder.snapshot();
        String sql = "INSERT INTO MR_ASYNC_BATCH_JOB (batch_id, request_id, engine_code, trace_id, client_id, user_id, user_name, source_system, op_code, data_date, portfolio, desk, total_trades, total_jobs, chunk_size, status, pending_jobs, running_jobs, success_jobs, failed_jobs, cancelled_jobs, message, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?)";
        jdbcTemplate.update(
                sql,
                batchId, requestId, engineCode,
                context == null ? null : trimToNull(context.getTraceId()),
                context == null ? null : trimToNull(context.getClientId()),
                context == null ? null : trimToNull(context.getUserId()),
                context == null ? null : trimToNull(context.getUserName()),
                context == null ? null : trimToNull(context.getSourceSystem()),
                opCode, Date.valueOf(dataDate), portfolio, desk,
                totalTrades, totalJobs, weightBudget, BATCH_PENDING, totalJobs,
                "批量任务创建完成", now, now
        );
    }

    private void updateBatchDefinition(
            String batchId, String requestId, String engineCode, String opCode,
            LocalDate dataDate, String portfolio, String desk,
            int totalTrades, int totalJobs, long now
    ) {
        String sql = "UPDATE MR_ASYNC_BATCH_JOB "
                + "SET request_id=?, engine_code=?, op_code=?, data_date=?, portfolio=?, desk=?, "
                + "total_trades=?, total_jobs=?, chunk_size=?, status=?, pending_jobs=?, running_jobs=0, success_jobs=0, failed_jobs=0, cancelled_jobs=0, message=?, updated_at=? "
                + "WHERE batch_id=?";
        jdbcTemplate.update(
                sql,
                requestId,
                engineCode,
                opCode,
                Date.valueOf(dataDate),
                portfolio,
                desk,
                totalTrades,
                totalJobs,
                weightBudget,
                BATCH_PENDING,
                totalJobs,
                "批量任务准备提交",
                now,
                batchId
        );
    }

    void insertBatchItem(String batchId, int seqNo, String jobId, List<BatchTradeDataLoader.TradeRow> chunkTrades) {
        long now = System.currentTimeMillis();
        String productMix = JobPayloadBuilder.buildProductMixJson(chunkTrades);
        String sql = "INSERT INTO MR_ASYNC_BATCH_ITEM (batch_id, seq_no, job_id, trade_count, product_mix_json, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, batchId, seqNo, jobId, chunkTrades.size(), productMix, now);
    }

    private void updateBatchSummary(
            String batchId, int totalJobs, String status,
            int pendingJobs, int runningJobs, int successJobs,
            int failedJobs, int cancelledJobs, long updatedAt, String message
    ) {
        String sql = "UPDATE MR_ASYNC_BATCH_JOB "
                + "SET total_jobs=?, status=?, pending_jobs=?, running_jobs=?, success_jobs=?, failed_jobs=?, cancelled_jobs=?, message=?, updated_at=? "
                + "WHERE batch_id=?";
        jdbcTemplate.update(sql, totalJobs, status, pendingJobs, runningJobs, successJobs, failedJobs, cancelledJobs, message, updatedAt, batchId);
    }

    void updateBatchStatus(
            String batchId, String status,
            int pendingJobs, int runningJobs, int successJobs,
            int failedJobs, int cancelledJobs, long updatedAt, String message
    ) {
        String sql = "UPDATE MR_ASYNC_BATCH_JOB "
                + "SET status=?, pending_jobs=?, running_jobs=?, success_jobs=?, failed_jobs=?, cancelled_jobs=?, message=?, updated_at=? "
                + "WHERE batch_id=?";
        jdbcTemplate.update(sql, status, pendingJobs, runningJobs, successJobs, failedJobs, cancelledJobs, message, updatedAt, batchId);
    }

    private BatchJobRow requireBatchRow(String batchId) {
        String sql = "SELECT batch_id, request_id, engine_code, trace_id, client_id, user_id, user_name, source_system, op_code, data_date, portfolio, desk, total_trades, total_jobs, chunk_size, status, pending_jobs, running_jobs, success_jobs, failed_jobs, cancelled_jobs, message, created_at, updated_at "
                + "FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?";
        List<BatchJobRow> rows = jdbcTemplate.query(sql, BATCH_JOB_ROW_MAPPER, batchId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批次不存在: " + batchId);
        }
        return rows.get(0);
    }

    private List<BatchItemRow> loadBatchItems(String batchId) {
        String sql = "SELECT i.seq_no, i.job_id, i.trade_count, i.product_mix_json, j.status AS job_status, j.error_code, j.error_message "
                + "FROM MR_ASYNC_BATCH_ITEM i "
                + "LEFT JOIN MR_ASYNC_JOB j ON j.job_id=i.job_id "
                + "WHERE i.batch_id=? ORDER BY i.seq_no";
        return jdbcTemplate.query(sql, BATCH_ITEM_ROW_MAPPER, batchId);
    }

    private int nextSeqNo(String batchId) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq_no) FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=?",
                Integer.class,
                batchId
        );
        return maxSeq == null ? 1 : maxSeq + 1;
    }

    void refreshBatchSummary(String batchId, String message) {
        List<BatchItemRow> itemRows = loadBatchItems(batchId);
        int totalJobs = itemRows.size();
        AggregatedCount aggregated = aggregate(itemRows, totalJobs);
        String status = deriveBatchStatus(aggregated, totalJobs);
        updateBatchSummary(
                batchId, totalJobs, status,
                aggregated.pendingJobs, aggregated.runningJobs,
                aggregated.successJobs, aggregated.failedJobs,
                aggregated.cancelledJobs, System.currentTimeMillis(), message
        );
    }

    // ==================== 状态聚合 ====================

    private List<BatchDetailResult.BatchJobItem> toBatchJobItems(List<BatchItemRow> rows) {
        List<BatchDetailResult.BatchJobItem> items = new ArrayList<>();
        for (BatchItemRow row : rows) {
            BatchDetailResult.BatchJobItem item = new BatchDetailResult.BatchJobItem();
            item.setSeqNo(row.seqNo);
            item.setJobId(row.jobId);
            item.setStatus(defaultJobStatus(row.jobStatus));
            item.setTradeCount(row.tradeCount);
            item.setErrorCode(row.errorCode);
            item.setErrorMessage(row.errorMessage);
            item.setDetailUrl(JOB_API_BASE_PATH + "/" + row.jobId);
            item.setCancelUrl(JOB_API_BASE_PATH + "/" + row.jobId + "/cancel");
            items.add(item);
        }
        return items;
    }

    private AggregatedCount aggregate(List<BatchItemRow> rows, int totalJobs) {
        AggregatedCount aggregated = new AggregatedCount();
        for (BatchItemRow row : rows) {
            String status = defaultJobStatus(row.jobStatus);
            switch (status) {
                case "PENDING": aggregated.pendingJobs++; break;
                case "RUNNING": aggregated.runningJobs++; break;
                case "SUCCESS": aggregated.successJobs++; break;
                case "FAILED": aggregated.failedJobs++; break;
                case "CANCELLED": aggregated.cancelledJobs++; break;
                default: aggregated.pendingJobs++; break;
            }
        }
        int known = aggregated.pendingJobs + aggregated.runningJobs + aggregated.successJobs + aggregated.failedJobs + aggregated.cancelledJobs;
        if (known < totalJobs) {
            aggregated.pendingJobs += (totalJobs - known);
        }
        aggregated.done = aggregated.pendingJobs == 0 && aggregated.runningJobs == 0 && totalJobs > 0;
        aggregated.success = aggregated.done && aggregated.failedJobs == 0 && aggregated.cancelledJobs == 0;
        return aggregated;
    }

    private String deriveBatchStatus(AggregatedCount aggregated, int totalJobs) {
        if (totalJobs <= 0) {
            return BATCH_FAILED;
        }
        if (aggregated.pendingJobs == totalJobs) {
            return BATCH_SUBMITTED;
        }
        if (aggregated.runningJobs > 0 || aggregated.pendingJobs > 0) {
            return BATCH_RUNNING;
        }
        if (aggregated.successJobs == totalJobs) {
            return BATCH_SUCCESS;
        }
        if (aggregated.cancelledJobs == totalJobs) {
            return BATCH_CANCELLED;
        }
        if (aggregated.successJobs == 0) {
            return BATCH_FAILED;
        }
        return BATCH_PARTIAL_FAILED;
    }

    private boolean isBatchTerminal(String status) {
        return BATCH_SUCCESS.equalsIgnoreCase(status)
                || BATCH_FAILED.equalsIgnoreCase(status)
                || BATCH_PARTIAL_FAILED.equalsIgnoreCase(status)
                || BATCH_CANCELLED.equalsIgnoreCase(status);
    }

    // ==================== 工具方法 ====================

    static String buildJobId(String batchId, int seqNo) {
        return batchId + "_J" + seqNo;
    }

    static String buildJobRequestId(String requestId, int seqNo) {
        return requestId + "-J" + seqNo;
    }

    private String buildDetailUrl(String batchId) {
        return batchApiBasePath + "/" + batchId;
    }

    private static String defaultJobStatus(String status) {
        String safe = trimToNull(status);
        return safe == null ? "PENDING" : safe;
    }

    private static String normalizeApiBasePath(String raw) {
        String safe = trimToNull(raw);
        if (safe == null) {
            return "/api/jobs/batch";
        }
        String out = safe.startsWith("/") ? safe : "/" + safe;
        while (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static String requireNonBlank(String txt, String message) {
        String safe = trimToNull(txt);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    // ==================== 内部类 ====================

    private static class BatchJobRow {
        private String batchId;
        private String requestId;
        private String engineCode;
        private String traceId;
        private String clientId;
        private String userId;
        private String userName;
        private String sourceSystem;
        private String opCode;
        private LocalDate dataDate;
        private String portfolio;
        private String desk;
        private int totalTrades;
        private int totalJobs;
        private int weightBudget;
        private String status;
        private int pendingJobs;
        private int runningJobs;
        private int successJobs;
        private int failedJobs;
        private int cancelledJobs;
        private String message;
        private long createdAt;
        private long updatedAt;
    }

    private static class BatchItemRow {
        private int seqNo;
        private String jobId;
        private int tradeCount;
        private String productMixJson;
        private String jobStatus;
        private String errorCode;
        private String errorMessage;
    }

    private static class AggregatedCount {
        private int pendingJobs;
        private int runningJobs;
        private int successJobs;
        private int failedJobs;
        private int cancelledJobs;
        private boolean done;
        private boolean success;
    }
}
