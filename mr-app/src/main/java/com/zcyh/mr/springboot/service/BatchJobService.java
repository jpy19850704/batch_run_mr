package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.BatchDetailResult;
import com.zcyh.mr.springboot.model.JobSubmitRequest;
import com.zcyh.mr.springboot.model.JobSubmitResult;
import com.zcyh.mr.springboot.service.BatchJobStateRepository.BatchItemRow;
import com.zcyh.mr.springboot.service.BatchJobStateRepository.BatchJobRow;
import com.zcyh.mr.springboot.service.BatchStatusCalculator.BatchStatusSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 批量异步任务编排服务。
 */
@Service
public class BatchJobService {
    private static final String BATCH_PENDING = "PENDING";
    private static final String BATCH_SUBMITTED = "SUBMITTED";
    private static final String BATCH_RUNNING = "RUNNING";
    private static final String BATCH_SUCCESS = "SUCCESS";
    private static final String BATCH_FAILED = "FAILED";

    static final String PAYLOAD_JSON_PARSE_ERROR = "PAYLOAD_JSON_PARSE_ERROR";

    private final AsyncJobService asyncJobService;
    private final BatchJobStateRepository stateRepository;
    private final BatchStatusCalculator statusCalculator;
    private final BatchDetailAssembler detailAssembler;
    private final int weightBudget;
    private final long pollAfterMs;
    private final String batchApiBasePath;

    public BatchJobService(
            AsyncJobService asyncJobService,
            BatchJobStateRepository stateRepository,
            BatchStatusCalculator statusCalculator,
            BatchDetailAssembler detailAssembler,
            @Value("${mr.batch.weight-budget:100}") int weightBudget,
            @Value("${mr.batch.client.poll-after-ms:500}") long pollAfterMs,
            @Value("${mr.batch.api.base-path:/api/jobs/batch}") String batchApiBasePath) {
        this.asyncJobService = asyncJobService;
        this.stateRepository = stateRepository;
        this.statusCalculator = statusCalculator;
        this.detailAssembler = detailAssembler;
        this.weightBudget = Math.max(1, weightBudget);
        this.pollAfterMs = Math.max(100L, pollAfterMs);
        this.batchApiBasePath = normalizeApiBasePath(batchApiBasePath);
        this.stateRepository.verifySchema();
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
        if (stateRepository.batchExists(batchId)) {
            stateRepository.updateBatchDefinition(
                    batchId,
                    requestId,
                    engineCode,
                    opCode,
                    dataDate,
                    portfolio,
                    desk,
                    totalTrades,
                    totalJobs,
                    weightBudget,
                    now);
            return;
        }
        ensureBatchNotRunning(batchId);
        stateRepository.clearExistingBatchData(batchId);
        insertBatchJob(
                batchId,
                requestId,
                engineCode,
                opCode,
                dataDate,
                portfolio,
                desk,
                totalTrades,
                totalJobs,
                now);
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
        stateRepository.clearExistingBatchData(batchId);
        insertBatchJob(
                batchId,
                requestId,
                engineCode,
                opCode,
                dataDate,
                portfolio,
                desk,
                0,
                0,
                now);
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
                message);
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
                message);
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
                message);
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
        return stateRepository.nextSeqNo(safeBatchId);
    }

    public BatchDetailResult getDetail(String batchId) {
        String safeBatchId = requireNonBlank(batchId, "batchId 不能为空");
        BatchJobRow batchRow = requireBatchRow(safeBatchId);
        List<BatchItemRow> itemRows = stateRepository.loadBatchItems(safeBatchId);
        if (batchRow.totalJobs <= 0 && itemRows.isEmpty()) {
            return detailAssembler.assemble(
                    batchRow,
                    itemRows,
                    statusCalculator.isTerminal(batchRow.status),
                    false,
                    pollAfterMs,
                    buildDetailUrl(batchRow.batchId));
        }

        BatchStatusSnapshot status = statusCalculator.calculate(itemRows, batchRow.totalJobs);
        if (!status.matches(batchRow)) {
            updateBatchStatus(
                    safeBatchId,
                    status.status,
                    status.pendingJobs,
                    status.runningJobs,
                    status.successJobs,
                    status.failedJobs,
                    status.cancelledJobs,
                    System.currentTimeMillis(),
                    batchRow.message);
            batchRow = requireBatchRow(safeBatchId);
        }
        return detailAssembler.assemble(
                batchRow,
                itemRows,
                status.done,
                status.success,
                pollAfterMs,
                buildDetailUrl(batchRow.batchId));
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

    void refreshBatchSummary(String batchId, String message) {
        List<BatchItemRow> itemRows = stateRepository.loadBatchItems(batchId);
        int totalJobs = itemRows.size();
        BatchStatusSnapshot status = statusCalculator.calculate(itemRows, totalJobs);
        stateRepository.updateBatchSummary(
                batchId,
                totalJobs,
                status.status,
                status.pendingJobs,
                status.runningJobs,
                status.successJobs,
                status.failedJobs,
                status.cancelledJobs,
                System.currentTimeMillis(),
                message);
    }

    void updateBatchStatus(
            String batchId,
            String status,
            int pendingJobs,
            int runningJobs,
            int successJobs,
            int failedJobs,
            int cancelledJobs,
            long updatedAt,
            String message) {
        stateRepository.updateBatchStatus(
                batchId,
                status,
                pendingJobs,
                runningJobs,
                successJobs,
                failedJobs,
                cancelledJobs,
                updatedAt,
                message);
    }

    private void ensureBatchNotRunning(String batchId) {
        if (stateRepository.countActiveBatchItems(batchId) > 0) {
            throw new IllegalStateException("batch_id 正在运行，不能覆盖重跑: " + batchId);
        }
    }

    private void ensureWorkflowNotRunning(String batchId) {
        List<String> statuses = stateRepository.findBatchStatuses(batchId);
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
        List<String> activeBatchIds = stateRepository.findOtherActiveBatchIds(batchId);
        if (activeBatchIds != null && !activeBatchIds.isEmpty()) {
            throw new IllegalStateException("已有批次工作流正在运行，不能同时启动新的批次: "
                    + activeBatchIds.get(0));
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
            long now) {
        stateRepository.insertBatchJob(
                batchId,
                requestId,
                engineCode,
                opCode,
                dataDate,
                portfolio,
                desk,
                totalTrades,
                totalJobs,
                weightBudget,
                now,
                RequestContextHolder.snapshot());
    }

    private void insertBatchItem(
            String batchId,
            int seqNo,
            String jobId,
            List<BatchTradeDataLoader.TradeRow> chunkTrades) {
        stateRepository.insertBatchItem(
                batchId,
                seqNo,
                jobId,
                chunkTrades.size(),
                JobPayloadBuilder.buildProductMixJson(chunkTrades),
                System.currentTimeMillis());
    }

    private BatchJobRow requireBatchRow(String batchId) {
        BatchJobRow row = stateRepository.findBatchRow(batchId);
        if (row == null) {
            throw new IllegalArgumentException("批次不存在: " + batchId);
        }
        return row;
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

    private static String normalizeApiBasePath(String raw) {
        String safe = trimToNull(raw);
        if (safe == null) {
            return "/api/jobs/batch";
        }
        String result = safe.startsWith("/") ? safe : "/" + safe;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String requireNonBlank(String text, String message) {
        String safe = trimToNull(text);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
