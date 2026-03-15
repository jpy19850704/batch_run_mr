package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.outer.engine.MrCalcEngineAdapter;
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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量异步任务服务。
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
    private static final String JOB_API_BASE_PATH = "/api/v1/jobs";
    private static final Pattern DATE_8_PATTERN = Pattern.compile("^(20\\d{6})$");
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final List<String> SUPPORTED_BATCH_OP_CODES = buildSupportedBatchOpCodes();

    private static final RowMapper<TradeRow> TRADE_ROW_MAPPER = new RowMapper<TradeRow>() {
        @Override
        public TradeRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            TradeRow row = new TradeRow();
            row.id = rs.getLong("id");
            row.tradeId = rs.getString("trade_id");
            row.productType = rs.getString("product_type");
            row.tradeContentText = rs.getString("trade_content_text");
            row.portfolio = rs.getString("portfolio");
            row.desk = rs.getString("desk");
            row.trader = rs.getString("trader");
            return row;
        }
    };

    private static final RowMapper<CurveRow> CURVE_ROW_MAPPER = new RowMapper<CurveRow>() {
        @Override
        public CurveRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CurveRow row = new CurveRow();
            row.marketDataType = rs.getString("market_data_type");
            row.curveId = rs.getString("curve_id");
            row.curveContentText = rs.getString("curve_content_text");
            return row;
        }
    };

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
    private final MrMarketDataSliceService marketDataSliceService;
    private final AlertService alertService;
    private final int weightBudget;
    private final int defaultWeight;
    private final Map<String, Integer> productWeightRules;
    private final long pollAfterMs;
    private final String batchApiBasePath;

    public BatchJobService(
            AsyncJobService asyncJobService,
            JdbcTemplate jdbcTemplate,
            MrMarketDataSliceService marketDataSliceService,
            AlertService alertService,
            @Value("${mr.batch.weight-budget:100}") int weightBudget,
            @Value("${mr.batch.weight-default:5}") int defaultWeight,
            @Value("${mr.batch.product-weight-rules:}") String weightRulesText,
            @Value("${mr.batch.client.poll-after-ms:500}") long pollAfterMs,
            @Value("${mr.batch.api.base-path:/api/v1/jobs/batch}") String batchApiBasePath,
            @Value("${mr.job.store.jdbc.init-schema:true}") boolean initSchema
    ) {
        this.asyncJobService = asyncJobService;
        this.jdbcTemplate = jdbcTemplate;
        this.marketDataSliceService = marketDataSliceService;
        this.alertService = alertService;
        this.weightBudget = Math.max(1, weightBudget);
        this.defaultWeight = normalizeWeight(defaultWeight);
        this.productWeightRules = parseWeightRules(weightRulesText);
        this.pollAfterMs = Math.max(100L, pollAfterMs);
        this.batchApiBasePath = normalizeApiBasePath(batchApiBasePath);
        if (initSchema) {
            initBatchSchema();
        }
    }

    /**
     * 自动初始化批量任务相关表结构。
     */
    private void initBatchSchema() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS mr_async_batch_job ("
                    + "batch_id VARCHAR(64) PRIMARY KEY,"
                    + "request_id VARCHAR(128) NOT NULL,"
                    + "engine_code VARCHAR(64) NOT NULL,"
                    + "trace_id VARCHAR(128),"
                    + "client_id VARCHAR(128),"
                    + "user_id VARCHAR(128),"
                    + "user_name VARCHAR(128),"
                    + "source_system VARCHAR(128),"
                    + "op_code VARCHAR(64) NOT NULL,"
                    + "data_date DATE NOT NULL,"
                    + "portfolio VARCHAR(128),"
                    + "desk VARCHAR(64),"
                    + "total_trades INT NOT NULL,"
                    + "total_jobs INT NOT NULL,"
                    + "chunk_size INT NOT NULL,"
                    + "status VARCHAR(32) NOT NULL,"
                    + "pending_jobs INT NOT NULL DEFAULT 0,"
                    + "running_jobs INT NOT NULL DEFAULT 0,"
                    + "success_jobs INT NOT NULL DEFAULT 0,"
                    + "failed_jobs INT NOT NULL DEFAULT 0,"
                    + "cancelled_jobs INT NOT NULL DEFAULT 0,"
                    + "message VARCHAR(1024),"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            jdbcTemplate.execute("ALTER TABLE mr_async_batch_job ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128)");
            jdbcTemplate.execute("ALTER TABLE mr_async_batch_job ADD COLUMN IF NOT EXISTS client_id VARCHAR(128)");
            jdbcTemplate.execute("ALTER TABLE mr_async_batch_job ADD COLUMN IF NOT EXISTS user_id VARCHAR(128)");
            jdbcTemplate.execute("ALTER TABLE mr_async_batch_job ADD COLUMN IF NOT EXISTS user_name VARCHAR(128)");
            jdbcTemplate.execute("ALTER TABLE mr_async_batch_job ADD COLUMN IF NOT EXISTS source_system VARCHAR(128)");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS mr_async_batch_item ("
                    + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "batch_id VARCHAR(64) NOT NULL,"
                    + "seq_no INT NOT NULL,"
                    + "job_id VARCHAR(64) NOT NULL,"
                    + "trade_count INT NOT NULL,"
                    + "product_mix_json TEXT,"
                    + "created_at BIGINT NOT NULL)");

            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_mr_async_batch_item_seq ON mr_async_batch_item(batch_id, seq_no)");
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_mr_async_batch_item_job ON mr_async_batch_item(job_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_mr_async_batch_item_batch ON mr_async_batch_item(batch_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_mr_async_batch_job_status ON mr_async_batch_job(status)");
        } catch (Exception ex) {
            // 表已存在时忽略异常
        }
    }

    public BatchSubmitResult submit(BatchSubmitRequest request) {
        return submitInternal(request, null);
    }

    public BatchSubmitResult submit(BatchSubmitRequest request, String scenarioIdList) {
        return submitInternal(request, scenarioIdList);
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

        List<TradeRow> trades = loadTradeRows(dataDate, portfolio, desk);
        if (trades.isEmpty()) {
            throw new IllegalArgumentException("未查询到交易数据，请检查 dataDate/portfolio/desk 条件");
        }
        List<CurveRow> curves = loadCurveRows(dataDate);
        if (curves.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 mr_market_curve_input");
        }

        ensureBatchNotRunning(batchId);
        clearExistingBatchData(batchId);

        List<List<TradeRow>> chunks = splitChunksByProductAndWeightBudget(trades, weightBudget);
        List<MrMarketDataSliceService.CurveSliceSource> curveSources = toCurveSliceSources(curves);
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
            for (int i = 0; i < chunks.size(); i++) {
                int seqNo = i + 1;
                List<TradeRow> chunkTrades = chunks.get(i);
                MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                        toTradeSliceSources(chunkTrades),
                        curveSources);
                JSONObject payload = buildPayload(opCode, dataDate, chunkTrades, sliceResult.getCurves(),
                        sliceResult.getTradeMarketDataKeys(), batchId, seqNo, scenarioIdList);

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
            // 补偿：取消已提交的子任务，防止幽灵任务继续执行
            for (String submittedId : submittedJobIds) {
                try {
                    asyncJobService.cancel(submittedId);
                } catch (Exception cancelEx) {
                    // 取消失败不影响整体异常传播
                    log.warn("批量任务补偿取消失败，batchId={}, jobId={}", batchId, submittedId);
                }
            }
            int pending = Math.max(0, chunks.size() - submittedJobs);
            updateBatchStatus(
                    batchId,
                    BATCH_FAILED,
                    pending,
                    0,
                    0,
                    submittedJobIds.size(),
                    0,
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
        List<String> tradeIds = normalizeTradeIds(request.getTradeIdList());
        if (tradeIds.isEmpty()) {
            throw new IllegalArgumentException("tradeIdList 不能为空");
        }

        List<TradeRow> trades = loadTradeRowsByTradeIds(dataDate, tradeIds);
        ensureAllTradeIdsLoaded(tradeIds, trades);
        List<CurveRow> curves = loadCurveRows(dataDate);
        if (curves.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 mr_market_curve_input");
        }

        List<List<TradeRow>> chunks = splitChunksByProductAndWeightBudget(trades, weightBudget);
        List<MrMarketDataSliceService.CurveSliceSource> curveSources = toCurveSliceSources(curves);
        String requestId = trimToNull(request.getRequestId());
        if (requestId == null) {
            requestId = batchId;
        }
        int nextSeqNo = nextSeqNo(batchId);

        try {
            for (int i = 0; i < chunks.size(); i++) {
                int seqNo = nextSeqNo + i;
                List<TradeRow> chunkTrades = chunks.get(i);
                MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                        toTradeSliceSources(chunkTrades),
                        curveSources
                );
                JSONObject payload = buildPayload(batchRow.opCode, dataDate, chunkTrades, sliceResult.getCurves(),
                        sliceResult.getTradeMarketDataKeys(), batchId, seqNo, null);

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

    public BatchDetailResult getDetail(String batchId) {
        String safeBatchId = requireNonBlank(batchId, "batchId 不能为空");
        BatchJobRow batchRow = requireBatchRow(safeBatchId);
        List<BatchItemRow> itemRows = loadBatchItems(safeBatchId);
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
                    safeBatchId,
                    mergedStatus,
                    aggregated.pendingJobs,
                    aggregated.runningJobs,
                    aggregated.successJobs,
                    aggregated.failedJobs,
                    aggregated.cancelledJobs,
                    now,
                    batchRow.message
            );
            batchRow = requireBatchRow(safeBatchId);
        }

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
        detail.setDone(aggregated.done);
        detail.setSuccess(aggregated.success);
        detail.setPollAfterMs(pollAfterMs);
        detail.setDetailUrl(buildDetailUrl(batchRow.batchId));
        detail.setMessage(batchRow.message);
        detail.setJobs(toBatchJobItems(itemRows));
        return detail;
    }

    /**
     * 如果同名批次仍在运行，则禁止直接覆盖。
     */
    private void ensureBatchNotRunning(String batchId) {
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM mr_async_batch_item i "
                        + "LEFT JOIN mr_async_job j ON j.job_id=i.job_id "
                        + "WHERE i.batch_id=? AND (j.status IS NULL OR j.status IN ('PENDING','RUNNING'))",
                Integer.class,
                batchId
        );
        if (active != null && active > 0) {
            throw new IllegalStateException("batch_id 正在运行，不能覆盖重跑: " + batchId);
        }
    }

    /**
     * 重跑同一批次前，先清理该 batch_id 下的旧结果和旧任务关系。
     */
    private void clearExistingBatchData(String batchId) {
        List<String> oldJobIds = jdbcTemplate.queryForList(
                "SELECT job_id FROM mr_async_batch_item WHERE batch_id=? ORDER BY seq_no",
                String.class,
                batchId
        );

        deleteIgnoreMissing("DELETE FROM TB_OUT_TRADE_RESULT_DETAIL WHERE BATCH_ID=?", batchId);
        deleteIgnoreMissing("DELETE FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL WHERE BATCH_ID=?", batchId);
        deleteIgnoreMissing("DELETE FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL WHERE BATCH_ID=?", batchId);
        deleteIgnoreMissing("DELETE FROM TB_OUT_TRADE_DRC_DETAIL WHERE BATCH_ID=?", batchId);

        jdbcTemplate.update("DELETE FROM mr_async_batch_item WHERE batch_id=?", batchId);
        jdbcTemplate.update("DELETE FROM mr_async_batch_job WHERE batch_id=?", batchId);

        if (oldJobIds != null) {
            for (String jobId : oldJobIds) {
                if (trimToNull(jobId) != null) {
                    jdbcTemplate.update("DELETE FROM mr_async_job WHERE job_id=?", jobId);
                }
            }
        }
        jdbcTemplate.update("DELETE FROM mr_async_job WHERE job_id IN (SELECT job_id FROM mr_async_batch_item WHERE batch_id=?)", batchId);
    }

    private void deleteIgnoreMissing(String sql, String batchId) {
        try {
            jdbcTemplate.update(sql, batchId);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || !message.toLowerCase(java.util.Locale.ROOT).contains("not found")) {
                throw ex;
            }
        }
    }

    private void insertBatchJob(
            String batchId,
            String requestId,
            String engineCode,
            String opCode,
            LocalDate dataDate,
            String portfolio,
            String desk,
            int totalTrades,
            int totalJobs,
            long now
    ) {
        RequestContext context = RequestContextHolder.snapshot();
        String sql = "INSERT INTO mr_async_batch_job (batch_id, request_id, engine_code, trace_id, client_id, user_id, user_name, source_system, op_code, data_date, portfolio, desk, total_trades, total_jobs, chunk_size, status, pending_jobs, running_jobs, success_jobs, failed_jobs, cancelled_jobs, message, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?)";
        jdbcTemplate.update(
                sql,
                batchId,
                requestId,
                engineCode,
                context == null ? null : trimToNull(context.getTraceId()),
                context == null ? null : trimToNull(context.getClientId()),
                context == null ? null : trimToNull(context.getUserId()),
                context == null ? null : trimToNull(context.getUserName()),
                context == null ? null : trimToNull(context.getSourceSystem()),
                opCode,
                Date.valueOf(dataDate),
                portfolio,
                desk,
                totalTrades,
                totalJobs,
                weightBudget,
                BATCH_PENDING,
                totalJobs,
                "批量任务创建完成",
                now,
                now
        );
    }

    private void insertBatchItem(String batchId, int seqNo, String jobId, List<TradeRow> chunkTrades) {
        long now = System.currentTimeMillis();
        String productMix = buildProductMixJson(chunkTrades);
        String sql = "INSERT INTO mr_async_batch_item (batch_id, seq_no, job_id, trade_count, product_mix_json, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, batchId, seqNo, jobId, chunkTrades.size(), productMix, now);
    }

    private void updateBatchSummary(
            String batchId,
            int totalJobs,
            String status,
            int pendingJobs,
            int runningJobs,
            int successJobs,
            int failedJobs,
            int cancelledJobs,
            long updatedAt,
            String message
    ) {
        String sql = "UPDATE mr_async_batch_job "
                + "SET total_jobs=?, status=?, pending_jobs=?, running_jobs=?, success_jobs=?, failed_jobs=?, cancelled_jobs=?, message=?, updated_at=? "
                + "WHERE batch_id=?";
        jdbcTemplate.update(sql, totalJobs, status, pendingJobs, runningJobs, successJobs, failedJobs, cancelledJobs, message, updatedAt, batchId);
    }

    private void updateBatchStatus(
            String batchId,
            String status,
            int pendingJobs,
            int runningJobs,
            int successJobs,
            int failedJobs,
            int cancelledJobs,
            long updatedAt,
            String message
    ) {
        String sql = "UPDATE mr_async_batch_job "
                + "SET status=?, pending_jobs=?, running_jobs=?, success_jobs=?, failed_jobs=?, cancelled_jobs=?, message=?, updated_at=? "
                + "WHERE batch_id=?";
        jdbcTemplate.update(sql, status, pendingJobs, runningJobs, successJobs, failedJobs, cancelledJobs, message, updatedAt, batchId);
    }

    private BatchJobRow requireBatchRow(String batchId) {
        String sql = "SELECT batch_id, request_id, engine_code, trace_id, client_id, user_id, user_name, source_system, op_code, data_date, portfolio, desk, total_trades, total_jobs, chunk_size, status, pending_jobs, running_jobs, success_jobs, failed_jobs, cancelled_jobs, message, created_at, updated_at "
                + "FROM mr_async_batch_job WHERE batch_id=?";
        List<BatchJobRow> rows = jdbcTemplate.query(sql, BATCH_JOB_ROW_MAPPER, batchId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批次不存在: " + batchId);
        }
        return rows.get(0);
    }

    private List<BatchItemRow> loadBatchItems(String batchId) {
        String sql = "SELECT i.seq_no, i.job_id, i.trade_count, i.product_mix_json, j.status AS job_status, j.error_code, j.error_message "
                + "FROM mr_async_batch_item i "
                + "LEFT JOIN mr_async_job j ON j.job_id=i.job_id "
                + "WHERE i.batch_id=? ORDER BY i.seq_no";
        return jdbcTemplate.query(sql, BATCH_ITEM_ROW_MAPPER, batchId);
    }

    private List<TradeRow> loadTradeRows(LocalDate dataDate, String portfolio, String desk) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT id, trade_id, product_type, trade_content_text, portfolio, desk, trader FROM mr_trade_input WHERE data_date=?");
        params.add(Date.valueOf(dataDate));
        if (portfolio != null) {
            sql.append(" AND portfolio=?");
            params.add(portfolio);
        }
        if (desk != null) {
            sql.append(" AND desk=?");
            params.add(desk);
        }
        sql.append(" ORDER BY id");
        return jdbcTemplate.query(sql.toString(), TRADE_ROW_MAPPER, params.toArray());
    }

    private List<TradeRow> loadTradeRowsByTradeIds(LocalDate dataDate, List<String> tradeIds) {
        if (tradeIds == null || tradeIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT id, trade_id, product_type, trade_content_text FROM mr_trade_input WHERE data_date=?");
        params.add(Date.valueOf(dataDate));
        sql.append(" AND trade_id IN (");
        for (int i = 0; i < tradeIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(tradeIds.get(i));
        }
        sql.append(") ORDER BY id");
        return jdbcTemplate.query(sql.toString(), TRADE_ROW_MAPPER, params.toArray());
    }

    private List<CurveRow> loadCurveRows(LocalDate dataDate) {
        String sql = "SELECT market_data_type, curve_id, curve_content_text FROM mr_market_curve_input WHERE data_date=? ORDER BY market_data_type, curve_id";
        return jdbcTemplate.query(sql, CURVE_ROW_MAPPER, Date.valueOf(dataDate));
    }

    private List<List<TradeRow>> splitChunksByProductAndWeightBudget(List<TradeRow> trades, int maxWeightBudget) {
        List<List<TradeRow>> chunks = new ArrayList<List<TradeRow>>();
        if (trades == null || trades.isEmpty()) {
            return chunks;
        }

        Map<String, List<TradeRow>> grouped = new LinkedHashMap<String, List<TradeRow>>();
        for (TradeRow trade : trades) {
            String productType = normalizeProductType(trade.productType);
            List<TradeRow> productTrades = grouped.get(productType);
            if (productTrades == null) {
                productTrades = new ArrayList<TradeRow>();
                grouped.put(productType, productTrades);
            }
            productTrades.add(trade);
        }

        for (Map.Entry<String, List<TradeRow>> entry : grouped.entrySet()) {
            chunks.addAll(splitSingleProductGroupByWeightBudget(entry.getValue(), maxWeightBudget));
        }
        return chunks;
    }

    private List<List<TradeRow>> splitSingleProductGroupByWeightBudget(List<TradeRow> productTrades, int maxWeightBudget) {
        List<List<TradeRow>> chunks = new ArrayList<List<TradeRow>>();
        if (productTrades == null || productTrades.isEmpty()) {
            return chunks;
        }

        List<TradeRow> currentChunk = new ArrayList<TradeRow>();
        int currentWeight = 0;
        for (TradeRow trade : productTrades) {
            int tradeWeight = resolveWeight(trade.productType);
            if (!currentChunk.isEmpty() && currentWeight + tradeWeight > maxWeightBudget) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<TradeRow>();
                currentWeight = 0;
            }
            currentChunk.add(trade);
            currentWeight += tradeWeight;
            if (currentWeight >= maxWeightBudget) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<TradeRow>();
                currentWeight = 0;
            }
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }
        return chunks;
    }

    private String normalizeProductType(String productType) {
        String normalized = trimToNull(productType);
        if (normalized == null) {
            return "UNKNOWN";
        }
        return normalized;
    }

    private static List<String> buildSupportedBatchOpCodes() {
        List<String> supported = new ArrayList<String>();
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

    // 【架构备注】当前将交易数据和市场数据完整嵌入 payload JSON，每个分片约 450KB。
    // 后续改造：此方法组装逻辑不变，组装好的完整 payload 不再写入 DB payload_json 列，
    // 而是直接存 Redis（key=job:payload:{jobId}），Worker GET 后直传 Calc.run()。
    // buildPayload + sliceCurves 在 submit 端执行一次，Worker 端无需重查市场数据。
    private JSONObject buildPayload(
            String opCode,
            LocalDate dataDate,
            List<TradeRow> chunkTrades,
            List<MrMarketDataSliceService.CurveSliceSource> curves,
            Map<String, Set<String>> tradeMarketDataKeys,
            String batchId,
            int seqNo,
            String scenarioIdList
    ) {
        JSONArray tradeData = new JSONArray();
        for (TradeRow trade : chunkTrades) {
            Object parsed = parseJsonSafely(trade.tradeContentText);
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.size(); i++) {
                    injectMarketDataKeys(arr.get(i), trade.tradeId, tradeMarketDataKeys);
                    tradeData.add(arr.get(i));
                }
            } else if (parsed != null) {
                injectMarketDataKeys(parsed, trade.tradeId, tradeMarketDataKeys);
                tradeData.add(parsed);
            }
        }

        JSONArray marketData = new JSONArray();
        for (MrMarketDataSliceService.CurveSliceSource curve : curves) {
            Object parsed = parseJsonSafely(curve.getCurveContentText());
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.size(); i++) {
                    marketData.add(arr.get(i));
                }
            } else if (parsed != null) {
                marketData.add(parsed);
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("oper_code", opCode);
        payload.put("data_date", dataDate.format(DateTimeFormatter.BASIC_ISO_DATE));
        payload.put("trade_data", tradeData);
        payload.put("market_data", marketData);

        JSONObject batchMeta = new JSONObject();
        batchMeta.put("batch_id", batchId);
        batchMeta.put("seq_no", seqNo);
        batchMeta.put("trade_count", chunkTrades.size());
        payload.put("batch_meta", batchMeta);
        if (Constants.OPER_CODE.SCENARIO.equalsIgnoreCase(opCode)) {
            String safeScenarioIdList = trimToNull(scenarioIdList);
            if (safeScenarioIdList != null) {
                // 场景批量任务通过 scenario_ref 让 MrCalcEngineAdapter 在执行时按 batchId/dataDate 装载对应文件。
                JSONObject scenarioRef = new JSONObject();
                scenarioRef.put("scenario_set_id", safeScenarioIdList);
                scenarioRef.put("data_date", dataDate.format(DateTimeFormatter.BASIC_ISO_DATE));
                scenarioRef.put("batch_id", batchId);
                payload.put("scenario_ref", scenarioRef);
            }
        }
        // 构建维度映射表，按 INSTRUMENT_ID 关联维度字段，与 trade_data 隔离
        JSONObject tradeDimension = new JSONObject();
        for (TradeRow trade : chunkTrades) {
            String dimTradeId = trimToNull(trade.tradeId);
            if (dimTradeId == null) {
                continue;
            }
            JSONObject dim = new JSONObject();
            if (trimToNull(trade.portfolio) != null) {
                dim.put("PORTFOLIO", trade.portfolio.trim());
            }
            if (trimToNull(trade.desk) != null) {
                dim.put("DESK", trade.desk.trim());
            }
            if (trimToNull(trade.trader) != null) {
                dim.put("TRADER", trade.trader.trim());
            }
            if (!dim.isEmpty()) {
                tradeDimension.put(dimTradeId, dim);
            }
        }
        if (!tradeDimension.isEmpty()) {
            payload.put("trade_dimension", tradeDimension);
        }
        return payload;
    }

    /**
     * 将交易引用的市场数据标识注入到交易 JSON 的 _MARKET_DATA_KEYS 字段。
     * 场景估值时 Calc 引擎可基于此字段逐笔判断是否受场景影响。
     */
    private static void injectMarketDataKeys(Object tradeJson, String tradeId,
                                             Map<String, Set<String>> tradeMarketDataKeys) {
        if (!(tradeJson instanceof JSONObject) || tradeMarketDataKeys == null) {
            return;
        }
        String safeId = trimToNull(tradeId);
        if (safeId == null) {
            return;
        }
        Set<String> keys = tradeMarketDataKeys.get(safeId);
        if (keys != null && !keys.isEmpty()) {
            ((JSONObject) tradeJson).put("_MARKET_DATA_KEYS", new JSONArray(new ArrayList<String>(keys)));
        }
    }

    private List<MrMarketDataSliceService.TradeSliceSource> toTradeSliceSources(List<TradeRow> chunkTrades) {
        List<MrMarketDataSliceService.TradeSliceSource> trades = new ArrayList<MrMarketDataSliceService.TradeSliceSource>();
        for (TradeRow trade : chunkTrades) {
            trades.add(new MrMarketDataSliceService.TradeSliceSource(trade.tradeId, trade.tradeContentText));
        }
        return trades;
    }

    private List<MrMarketDataSliceService.CurveSliceSource> toCurveSliceSources(List<CurveRow> curves) {
        List<MrMarketDataSliceService.CurveSliceSource> curveSources = new ArrayList<MrMarketDataSliceService.CurveSliceSource>();
        for (CurveRow curve : curves) {
            curveSources.add(new MrMarketDataSliceService.CurveSliceSource(
                    curve.marketDataType,
                    curve.curveId,
                    curve.curveContentText));
        }
        return curveSources;
    }

    private List<BatchDetailResult.BatchJobItem> toBatchJobItems(List<BatchItemRow> rows) {
        List<BatchDetailResult.BatchJobItem> items = new ArrayList<BatchDetailResult.BatchJobItem>();
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
            if ("PENDING".equals(status)) {
                aggregated.pendingJobs++;
            } else if ("RUNNING".equals(status)) {
                aggregated.runningJobs++;
            } else if ("SUCCESS".equals(status)) {
                aggregated.successJobs++;
            } else if ("FAILED".equals(status)) {
                aggregated.failedJobs++;
            } else if ("CANCELLED".equals(status)) {
                aggregated.cancelledJobs++;
            } else {
                aggregated.pendingJobs++;
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

    private static Map<String, Integer> parseWeightRules(String txt) {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        String raw = trimToNull(txt);
        if (raw == null) {
            return map;
        }
        String[] pairs = raw.split(",");
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            String item = pair.trim();
            if (item.isEmpty()) {
                continue;
            }
            int idx = item.indexOf('=');
            if (idx <= 0 || idx >= item.length() - 1) {
                continue;
            }
            String key = item.substring(0, idx).trim().toUpperCase();
            String val = item.substring(idx + 1).trim();
            try {
                map.put(key, normalizeWeight(Integer.parseInt(val)));
            } catch (NumberFormatException ignore) {
                // 忽略非法权重配置
            }
        }
        return map;
    }

    private int resolveWeight(String productType) {
        String key = productType == null ? null : productType.trim().toUpperCase();
        Integer weight = key == null ? null : productWeightRules.get(key);
        if (weight == null) {
            return defaultWeight;
        }
        return normalizeWeight(weight);
    }

    private static int normalizeWeight(int weight) {
        if (weight < 1) {
            return 1;
        }
        if (weight > 10) {
            return 10;
        }
        return weight;
    }

    private static String defaultJobStatus(String status) {
        String safe = trimToNull(status);
        return safe == null ? "PENDING" : safe;
    }

    private static Object parseJsonSafely(String text) {
        String safe = trimToNull(text);
        if (safe == null) {
            return null;
        }
        try {
            return JSON.parse(safe);
        } catch (Exception ignore) {
            return safe;
        }
    }

    private static String buildJobRequestId(String requestId, int seqNo) {
        return requestId + "-J" + seqNo;
    }

    private static String buildProductMixJson(List<TradeRow> chunkTrades) {
        Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        for (TradeRow trade : chunkTrades) {
            String productType = trimToNull(trade.productType);
            if (productType == null) {
                productType = "UNKNOWN";
            }
            Integer c = count.get(productType);
            if (c == null) {
                c = 0;
            }
            count.put(productType, c + 1);
        }
        return JSON.toJSONString(count, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private String buildDetailUrl(String batchId) {
        return batchApiBasePath + "/" + batchId;
    }

    private static LocalDate parseDataDate(String txt) {
        String safe = requireNonBlank(txt, "dataDate 不能为空");
        Matcher m = DATE_8_PATTERN.matcher(safe);
        if (m.matches()) {
            String d = m.group(1);
            return LocalDate.parse(d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8), DateTimeFormatter.ISO_LOCAL_DATE);
        }
        try {
            return LocalDate.parse(safe, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("dataDate 格式错误，支持 yyyy-MM-dd 或 yyyyMMdd");
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

    private static String buildJobId(String batchId, int seqNo) {
        return batchId + "_J" + seqNo;
    }

    private int nextSeqNo(String batchId) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq_no) FROM mr_async_batch_item WHERE batch_id=?",
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
                batchId,
                totalJobs,
                status,
                aggregated.pendingJobs,
                aggregated.runningJobs,
                aggregated.successJobs,
                aggregated.failedJobs,
                aggregated.cancelledJobs,
                System.currentTimeMillis(),
                message
        );
    }

    private static List<String> normalizeTradeIds(List<String> tradeIdList) {
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        if (tradeIdList != null) {
            for (String tradeId : tradeIdList) {
                String safe = trimToNull(tradeId);
                if (safe != null) {
                    normalized.add(safe);
                }
            }
        }
        return new ArrayList<String>(normalized);
    }

    private static void ensureAllTradeIdsLoaded(List<String> tradeIds, List<TradeRow> trades) {
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        if (trades != null) {
            for (TradeRow trade : trades) {
                String tradeId = trimToNull(trade.tradeId);
                if (tradeId != null) {
                    found.add(tradeId);
                }
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String tradeId : tradeIds) {
            if (!found.contains(tradeId)) {
                missing.add(tradeId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("以下 tradeId 未查询到输入交易: " + String.join(", ", missing));
        }
    }

    private static String requireNonBlank(String txt, String message) {
        String safe = trimToNull(txt);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String normalizeApiBasePath(String raw) {
        String safe = trimToNull(raw);
        if (safe == null) {
            return "/api/v1/jobs/batch";
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

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 交易输入行。
     */
    private static class TradeRow {
        private long id;
        private String tradeId;
        private String productType;
        private String tradeContentText;
        private String portfolio;
        private String desk;
        private String trader;
    }

    /**
     * 市场曲线输入行。
     */
    private static class CurveRow {
        private String marketDataType;
        private String curveId;
        private String curveContentText;
    }

    /**
     * 批次任务主表行。
     */
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

    /**
     * 批次子任务行。
     */
    private static class BatchItemRow {
        private int seqNo;
        private String jobId;
        private int tradeCount;
        private String productMixJson;
        private String jobStatus;
        private String errorCode;
        private String errorMessage;
    }

    /**
     * 批次状态聚合结果。
     */
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
