package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.context.RequestContext;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.BatchDetailResult;
import com.zcyh.mr.springboot.model.BatchPatchRequest;
import com.zcyh.mr.springboot.model.BatchSubmitRequest;
import com.zcyh.mr.springboot.model.BatchSubmitResult;
import com.zcyh.mr.springboot.model.JobSubmitRequest;
import com.zcyh.mr.springboot.model.JobSubmitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量异步任务服务。
 * 负责调度协调、状态管理和 DB 元数据操作。
 * 数据加载委托给 {@link BatchTradeDataLoader}，
 * 分片策略委托给 {@link TradeChunkSplitter}，
 * Payload 构建委托给 {@link JobPayloadBuilder}。
 */
@Service
public class BatchJobService {
    private static final Logger log = LoggerFactory.getLogger(BatchJobService.class);
    private static final String BATCH_PENDING = "PENDING";
    private static final String BATCH_SUBMITTED = "SUBMITTED";
    private static final String BATCH_RUNNING = "RUNNING";
    private static final String BATCH_SUCCESS = "SUCCESS";
    private static final String BATCH_FAILED = "FAILED";
    private static final String BATCH_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String BATCH_CANCELLED = "CANCELLED";
    private static final String JOB_API_BASE_PATH = "/api/jobs";
    private static final Pattern DATE_8_PATTERN = Pattern.compile("^(20\\d{6})$");
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final int RESULT_DB_CLEAR_MAX_ATTEMPTS = 5;
    private static final long RESULT_DB_CLEAR_RETRY_INTERVAL_MILLIS = 1000L;
    private static final String[] RESULT_DB_TRANSIENT_ERROR_KEYWORDS = {
            "no queryable replicas",
            "not alive",
            "no backend available",
            "backend is down",
            "connection refused",
            "communications link failure"
    };
    private static final List<String> SUPPORTED_BATCH_OP_CODES = buildSupportedBatchOpCodes();

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
    private final JdbcTemplate engineResultDbJdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final MrMarketDataSliceService marketDataSliceService;
    private final AlertService alertService;
    private final BatchTradeDataLoader dataLoader;
    private final TradeChunkSplitter chunkSplitter;
    private final JobPayloadBuilder payloadBuilder;
    private final int weightBudget;
    private final long pollAfterMs;
    private final String batchApiBasePath;

    public BatchJobService(
            AsyncJobService asyncJobService,
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService,
            MrMarketDataSliceService marketDataSliceService,
            AlertService alertService,
            BatchTradeDataLoader dataLoader,
            TradeChunkSplitter chunkSplitter,
            JobPayloadBuilder payloadBuilder,
            @Value("${mr.batch.weight-budget:100}") int weightBudget,
            @Value("${mr.batch.client.poll-after-ms:500}") long pollAfterMs,
            @Value("${mr.batch.api.base-path:/api/jobs/batch}") String batchApiBasePath
    ) {
        this.asyncJobService = asyncJobService;
        this.jdbcTemplate = jdbcTemplate;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.marketDataSliceService = marketDataSliceService;
        this.alertService = alertService;
        this.dataLoader = dataLoader;
        this.chunkSplitter = chunkSplitter;
        this.payloadBuilder = payloadBuilder;
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

    public BatchSubmitResult submit(BatchSubmitRequest request) {
        return submitInternal(request, null);
    }

    public BatchSubmitResult submit(BatchSubmitRequest request, String scenarioIdList) {
        return submitInternal(request, scenarioIdList);
    }

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
            String message,
            boolean clearResultData) {
        ensureWorkflowNotRunning(batchId);
        ensureBatchNotRunning(batchId);
        clearExistingBatchData(batchId, clearResultData);
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
                batchRow.pendingJobs,
                batchRow.runningJobs,
                batchRow.successJobs,
                batchRow.failedJobs,
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

    private BatchSubmitResult submitInternal(BatchSubmitRequest request, String scenarioIdList) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String opCode = requireSupportedOpCode(request.getOpCode());
        LocalDate dataDate = parseDataDate(request.getDataDate());
        String engineCode = MrCalcEngineAdapter.CODE;
        String batchId = resolveBatchId(request, dataDate);
        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode(engineCode);
        String portfolio = trimToNull(request.getPortfolio());
        String desk = trimToNull(request.getDesk());
        String runMode = normalizeRunMode(request.getRunMode());

        List<BatchTradeDataLoader.TradeRow> trades = dataLoader.loadTradeRows(dataDate, portfolio, desk);
        if (trades.isEmpty()) {
            throw new IllegalArgumentException("未查询到交易数据，请检查 dataDate/portfolio/desk 条件");
        }
        List<BatchTradeDataLoader.CurveRow> curves = dataLoader.loadCurveRows(dataDate);
        if (curves.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 MR_MARKET_CURVE_INPUT");
        }

        ensureWorkflowNotRunning(batchId);
        ensureBatchNotRunning(batchId);
        clearExistingBatchData(batchId);

        List<List<BatchTradeDataLoader.TradeRow>> chunks = chunkSplitter.splitChunks(trades, weightBudget);
        List<MrMarketDataSliceService.CurveSliceSource> curveSources = JobPayloadBuilder.toCurveSliceSources(curves);
        String requestId = trimToNull(request.getRequestId());
        if (requestId == null) {
            requestId = batchId;
        }
        long now = System.currentTimeMillis();

        insertBatchJob(batchId, requestId, engineCode, opCode, dataDate, portfolio, desk, trades.size(), chunks.size(), now);
        log.info("批量任务开始提交，batchId={}, totalTrades={}, totalJobs={}", batchId, trades.size(), chunks.size());

        int submittedJobs = 0;
        List<String> submittedJobIds = new ArrayList<String>();
        try {
            syncPortfolioHierarchySnapshot(batchId, dataDate);
            for (int i = 0; i < chunks.size(); i++) {
                int seqNo = i + 1;
                List<BatchTradeDataLoader.TradeRow> chunkTrades = chunks.get(i);
                MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                        JobPayloadBuilder.toTradeSliceSources(chunkTrades),
                        curveSources);
                JSONObject payload = payloadBuilder.buildPayload(opCode, dataDate, chunkTrades, sliceResult.getCurves(),
                        sliceResult.getTradeMarketDataKeys(), batchId, seqNo, scenarioIdList, null, true);
                if (runMode != null) {
                    payload.put("run_mode", runMode);
                }

                JobSubmitRequest jobRequest = new JobSubmitRequest();
                String jobId = buildJobId(batchId, seqNo);
                jobRequest.setJobId(jobId);
                jobRequest.setRequestId(buildJobRequestId(requestId, seqNo));
                jobRequest.setEngineCode(engineCode);
                jobRequest.setIdempotencyKey(jobId);
                jobRequest.setPayload(payload);

                JobSubmitResult submitResult = asyncJobService.submit(jobRequest);
                submittedJobIds.add(submitResult.getJobId());
                insertBatchItem(batchId, seqNo, submitResult.getJobId(), chunkTrades);
                submittedJobs++;
            }
            updateBatchStatus(batchId, BATCH_SUBMITTED, 0, 0, 0, 0, 0, now, "批量任务已提交");
        } catch (Exception ex) {
            for (String submittedId : submittedJobIds) {
                try {
                    asyncJobService.cancel(submittedId);
                } catch (Exception cancelEx) {
                    log.warn("批量任务补偿取消失败，batchId={}, jobId={}", batchId, submittedId);
                }
            }
            int pending = Math.max(0, chunks.size() - submittedJobs);
            updateBatchStatus(
                    batchId, BATCH_FAILED, pending, 0, 0, submittedJobIds.size(), 0,
                    System.currentTimeMillis(),
                    "批量提交失败(已取消" + submittedJobIds.size() + "个子任务): " + ex.getMessage()
            );
            alertService.error("BATCH_FAILED", "批量任务提交失败，batchId=" + batchId, ex);
            throw ex;
        }
        log.info("批量任务提交完成，batchId={}, totalJobs={}", batchId, chunks.size());

        BatchSubmitResult result = new BatchSubmitResult();
        result.setBatchId(batchId);
        result.setRequestId(requestId);
        result.setEngineCode(engineCode);
        result.setOpCode(opCode);
        result.setDataDate(dataDate.toString());
        result.setStatus(BATCH_SUBMITTED);
        result.setTotalTrades(trades.size());
        result.setTotalJobs(chunks.size());
        result.setWeightBudget(weightBudget);
        result.setSubmittedAt(now);
        result.setPollAfterMs(pollAfterMs);
        result.setDetailUrl(buildDetailUrl(batchId));
        result.setMessage("批量任务已提交");
        return result;
    }

    public BatchSubmitResult submitPatch(BatchPatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = requireNonBlank(request.getBatchId(), "batchId 不能为空");
        RequestContextHolder.setBatchId(batchId);
        LocalDate dataDate = parseDataDate(request.getDataDate());
        BatchJobRow batchRow = requireBatchRow(batchId);
        RequestContextHolder.setEngineCode(batchRow.engineCode);
        if (batchRow.dataDate == null || !batchRow.dataDate.equals(dataDate)) {
            throw new IllegalArgumentException("dataDate 与批次不一致: " + batchId);
        }

        ensureBatchNotRunning(batchId);
        List<String> instrumentIds = BatchTradeDataLoader.normalizeInstrumentIds(request.getInstrumentIdList());
        if (instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("instrumentIdList 不能为空");
        }

        List<BatchTradeDataLoader.TradeRow> trades = dataLoader.loadTradeRowsByInstrumentIds(dataDate, instrumentIds);
        BatchTradeDataLoader.ensureAllInstrumentIdsLoaded(instrumentIds, trades);
        List<BatchTradeDataLoader.CurveRow> curves = dataLoader.loadCurveRows(dataDate);
        if (curves.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 MR_MARKET_CURVE_INPUT");
        }

        List<List<BatchTradeDataLoader.TradeRow>> chunks = chunkSplitter.splitChunks(trades, weightBudget);
        List<MrMarketDataSliceService.CurveSliceSource> curveSources = JobPayloadBuilder.toCurveSliceSources(curves);
        String requestId = trimToNull(request.getRequestId());
        if (requestId == null) {
            requestId = batchId;
        }
        int nextSeqNo = nextSeqNo(batchId);

        try {
            syncPortfolioHierarchySnapshot(batchId, dataDate);
            for (int i = 0; i < chunks.size(); i++) {
                int seqNo = nextSeqNo + i;
                List<BatchTradeDataLoader.TradeRow> chunkTrades = chunks.get(i);
                MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                        JobPayloadBuilder.toTradeSliceSources(chunkTrades),
                        curveSources
                );
                JSONObject payload = payloadBuilder.buildPayload(batchRow.opCode, dataDate, chunkTrades, sliceResult.getCurves(),
                        sliceResult.getTradeMarketDataKeys(), batchId, seqNo, null, null, true);

                JobSubmitRequest jobRequest = new JobSubmitRequest();
                String jobId = buildJobId(batchId, seqNo);
                jobRequest.setJobId(jobId);
                jobRequest.setRequestId(buildJobRequestId(requestId, seqNo));
                jobRequest.setEngineCode(batchRow.engineCode);
                jobRequest.setIdempotencyKey(jobId);
                jobRequest.setPayload(payload);

                JobSubmitResult submitResult = asyncJobService.submit(jobRequest);
                insertBatchItem(batchId, seqNo, submitResult.getJobId(), chunkTrades);
            }
            refreshBatchSummary(batchId, "批次局部重跑任务已提交");
        } catch (Exception ex) {
            refreshBatchSummary(batchId, "批次局部重跑提交失败: " + ex.getMessage());
            alertService.error("BATCH_PATCH_FAILED", "批次局部重跑提交失败，batchId=" + batchId, ex);
            throw ex;
        }

        BatchJobRow latest = requireBatchRow(batchId);
        BatchSubmitResult result = new BatchSubmitResult();
        result.setBatchId(batchId);
        result.setRequestId(requestId);
        result.setEngineCode(latest.engineCode);
        result.setOpCode(latest.opCode);
        result.setDataDate(latest.dataDate == null ? null : latest.dataDate.toString());
        result.setStatus(latest.status);
        result.setTotalTrades(trades.size());
        result.setTotalJobs(chunks.size());
        result.setWeightBudget(weightBudget);
        result.setSubmittedAt(System.currentTimeMillis());
        result.setPollAfterMs(pollAfterMs);
        result.setDetailUrl(buildDetailUrl(batchId));
        result.setMessage("批次局部重跑任务已提交");
        return result;
    }

    // ==================== 查询 ====================

    public BatchDetailResult getDetail(String batchId) {
        String safeBatchId = requireNonBlank(batchId, "batchId 不能为空");
        BatchJobRow batchRow = requireBatchRow(safeBatchId);
        List<BatchItemRow> itemRows = loadBatchItems(safeBatchId);
        if (batchRow.totalJobs <= 0 && itemRows.isEmpty()) {
            return buildBatchDetail(batchRow, itemRows, false, false);
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

    private void clearExistingBatchData(String batchId) {
        clearExistingBatchData(batchId, true);
    }

    private void clearExistingBatchData(String batchId, boolean clearResultData) {
        List<String> oldJobIds = jdbcTemplate.queryForList(
                "SELECT job_id FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=? ORDER BY seq_no",
                String.class,
                batchId
        );

        if (clearResultData) {
            clearExistingResultData(batchId);
        }

        jdbcTemplate.update("DELETE FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=?", batchId);
        jdbcTemplate.update("DELETE FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?", batchId);

        if (oldJobIds != null) {
            for (String jobId : oldJobIds) {
                if (trimToNull(jobId) != null) {
                    jdbcTemplate.update("DELETE FROM MR_ASYNC_JOB WHERE job_id=?", jobId);
                }
            }
        }
        jdbcTemplate.update("DELETE FROM MR_ASYNC_JOB WHERE job_id IN (SELECT job_id FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=?)", batchId);
    }

    private void clearExistingResultData(String batchId) {
        clearExistingResultTableWithRetry("TB_OUT_TRADE_RESULT_DETAIL", batchId);
        clearExistingResultTableWithRetry("TB_OUT_TRADE_SCENARIO_RESULT_DETAIL", batchId);
        clearExistingResultTableWithRetry("TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL", batchId);
        clearExistingResultTableWithRetry("TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL", batchId);
        clearExistingResultTableWithRetry("TB_OUT_TRADE_DRC_DETAIL", batchId);
        clearExistingResultTableWithRetry("TB_OUT_TRADE_DRC_RESULT", batchId);
        clearExistingResultTableWithRetry("TB_OUT_MARKET_DATA_DETAIL", batchId);
        clearExistingResultTableWithRetry("TB_OUT_PORTFOLIO_HIERARCHY", batchId);
    }

    private void clearExistingResultTableWithRetry(String tableName, String batchId) {
        String sql = "DELETE FROM " + tableName + " WHERE BATCH_ID=?";
        for (int attempt = 1; attempt <= RESULT_DB_CLEAR_MAX_ATTEMPTS; attempt++) {
            try {
                engineResultDbJdbcTemplate.update(sql, batchId);
                return;
            } catch (DataAccessException ex) {
                if (!isTransientResultDbUnavailable(ex) || attempt >= RESULT_DB_CLEAR_MAX_ATTEMPTS) {
                    throw ex;
                }
                long delayMillis = RESULT_DB_CLEAR_RETRY_INTERVAL_MILLIS * attempt;
                log.warn("Doris结果表清理失败，准备重试，batchId={}, table={}, attempt={}, delayMillis={}, error={}",
                        batchId, tableName, attempt, delayMillis, rootMessage(ex));
                sleepBeforeResultDbRetry(delayMillis);
            }
        }
    }

    private boolean isTransientResultDbUnavailable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                for (String keyword : RESULT_DB_TRANSIENT_ERROR_KEYWORDS) {
                    if (normalized.contains(keyword)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        Throwable root = ex;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        String message = root == null ? null : root.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    private void sleepBeforeResultDbRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Doris结果表清理重试被中断", ex);
        }
    }

    void syncPortfolioHierarchySnapshot(String batchId, LocalDate dataDate) {
        engineResultDbJdbcTemplate.update("DELETE FROM TB_OUT_PORTFOLIO_HIERARCHY WHERE BATCH_ID=?", batchId);
        List<Map<String, Object>> hierarchyRows = jdbcTemplate.queryForList(
                "SELECT PORTFOLIO_CODE, PORTFOLIO_NAME, UPPER_LEVEL_PORTFOLIO, LEVEL_CODE FROM V_PORTFOLIO_HIERARCHY");
        if (hierarchyRows.isEmpty()) {
            return;
        }

        String normalizedDataDate = dataDate == null ? null : dataDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String now = ResultPersistTime.nowText();
        Set<String> uniqueKeys = new java.util.LinkedHashSet<String>();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                "TB_OUT_PORTFOLIO_HIERARCHY",
                "BATCH_ID,DATA_DATE,PORTFOLIO_CODE,PORTFOLIO_NAME,UPPER_LEVEL_PORTFOLIO,LEVEL_CODE,CREATED_AT,UPDATED_AT",
                "portfolio_hierarchy_" + batchId,
                5000);

        for (Map<String, Object> row : hierarchyRows) {
            String portfolioCode = trimToNull(stringValue(row.get("PORTFOLIO_CODE")));
            String levelCode = trimToNull(stringValue(row.get("LEVEL_CODE")));
            String upperLevelPortfolio = trimToNull(stringValue(row.get("UPPER_LEVEL_PORTFOLIO")));
            String uniqueKey = String.join("|",
                    valueOrEmpty(portfolioCode),
                    valueOrEmpty(levelCode),
                    valueOrEmpty(upperLevelPortfolio));
            if (!uniqueKeys.add(uniqueKey)) {
                continue;
            }
            buffer.appendRow(
                    batchId,
                    normalizedDataDate,
                    portfolioCode,
                    trimToNull(stringValue(row.get("PORTFOLIO_NAME"))),
                    upperLevelPortfolio,
                    levelCode,
                    now,
                    now
            );
        }
        buffer.flush();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
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

    private void refreshBatchSummary(String batchId, String message) {
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
            item.setResultUrl(JOB_API_BASE_PATH + "/" + row.jobId + "/result");
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

    // ==================== 工具方法 ====================

    private static List<String> buildSupportedBatchOpCodes() {
        List<String> supported = new ArrayList<>();
        supported.add(Constants.OPER_CODE.PRICING);
        supported.add(Constants.OPER_CODE.SCENARIO);
        supported.add(Constants.OPER_CODE.FRTB);
        return Collections.unmodifiableList(supported);
    }

    private String requireSupportedOpCode(String opCode) {
        String normalized = trimToNull(opCode);
        if (normalized == null) {
            throw new IllegalArgumentException("opCode 不能为空");
        }
        String upperCode = normalized.toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED_BATCH_OP_CODES.contains(upperCode)) {
            throw new IllegalArgumentException("opCode 不支持，当前仅支持: " + String.join(", ", SUPPORTED_BATCH_OP_CODES));
        }
        return upperCode;
    }

    private static LocalDate parseDataDate(String txt) {
        String safe = requireNonBlank(txt, "dataDate 不能为空");
        try {
            if (!DATE_8_PATTERN.matcher(safe).matches()) {
                throw new IllegalArgumentException("dataDate 格式错误，仅支持 yyyyMMdd");
            }
            return LocalDate.parse(safe, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("dataDate 格式错误，仅支持 yyyyMMdd");
        }
    }

    private static String resolveBatchId(BatchSubmitRequest request, LocalDate dataDate) {
        String raw = request == null ? null : trimToNull(request.getBatchId());
        String batchId = raw == null ? dataDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "_BATCH" : raw;
        if (!BATCH_ID_PATTERN.matcher(batchId).matches()) {
            throw new IllegalArgumentException("batchId 格式非法，仅支持字母、数字、点、下划线、中划线");
        }
        return batchId;
    }

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

    private static String normalizeRunMode(String runMode) {
        String value = trimToNull(runMode);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!"WHATIF".equals(value)) {
            throw new IllegalArgumentException("runMode 仅支持 WHATIF 或空值，实际: " + runMode);
        }
        return value;
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
