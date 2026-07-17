package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.input.db.TradeInputRow;

import com.zcyh.mr.springboot.runtime.ExecutionContextHolder;
import com.zcyh.mr.springboot.batch.model.BatchDetailResult;
import com.zcyh.mr.springboot.batch.model.JobStatus;
import com.zcyh.mr.springboot.batch.model.JobSubmitRequest;
import com.zcyh.mr.springboot.batch.model.JobSubmitResult;
import com.zcyh.mr.springboot.batch.BatchJobStateRepository.BatchItemRow;
import com.zcyh.mr.springboot.batch.BatchJobStateRepository.BatchJobRow;
import com.zcyh.mr.springboot.batch.BatchStatusCalculator.BatchStatusSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 批量异步任务编排服务。
 */
@Service
public class BatchJobService {
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
        stateRepository.updateBatchProgress(batchId, 0, 0, 0, 0, 0, 0, now, message);
    }

    void markWorkflowRunning(String batchId, String message) {
        BatchJobRow batchRow = requireBatchRow(batchId);
        if (batchRow.status == JobStatus.RUNNING) {
            updateProgress(batchRow, message);
            return;
        }
        transitionBatch(
                batchRow,
                AsyncTaskStateMachine.Event.START,
                batchRow.pendingJobs,
                batchRow.runningJobs,
                batchRow.successJobs,
                batchRow.failedJobs,
                batchRow.cancelledJobs,
                message);
    }

    void markWorkflowFailed(String batchId, String message) {
        BatchJobRow batchRow = requireBatchRow(batchId);
        List<BatchItemRow> itemRows = stateRepository.loadBatchItems(batchId);
        BatchStatusSnapshot progress = batchRow.totalJobs > 0 || !itemRows.isEmpty()
                ? calculateProgress(batchRow, itemRows)
                : null;
        transitionBatch(
                batchRow,
                AsyncTaskStateMachine.Event.FAIL,
                progress == null ? batchRow.pendingJobs : progress.pendingJobs,
                progress == null ? batchRow.runningJobs : progress.runningJobs,
                progress == null ? batchRow.successJobs : progress.successJobs,
                progress == null ? Math.max(batchRow.failedJobs, 1) : progress.failedJobs,
                progress == null ? batchRow.cancelledJobs : progress.cancelledJobs,
                message);
    }

    void completeWorkflow(String batchId, BatchStatusSnapshot status, String message) {
        if (status == null || !status.done || !status.status.isTerminal()) {
            throw new IllegalStateException("批次子任务尚未形成最终状态: " + batchId);
        }
        BatchJobRow batchRow = requireBatchRow(batchId);
        transitionBatch(
                batchRow,
                completionEvent(status.status),
                status.pendingJobs,
                status.runningJobs,
                status.successJobs,
                status.failedJobs,
                status.cancelledJobs,
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
        if (!batchRow.status.isTerminal()) {
            throw new IllegalStateException("batch_id 工作流尚未结束，不能局部重跑: " + safeBatchId);
        }
        transitionBatch(
                batchRow,
                AsyncTaskStateMachine.Event.REOPEN,
                batchRow.pendingJobs,
                batchRow.runningJobs,
                batchRow.successJobs,
                batchRow.failedJobs,
                batchRow.cancelledJobs,
                "批次局部重跑准备完成");
        return stateRepository.nextSeqNo(safeBatchId);
    }

    public BatchDetailResult getDetail(String batchId) {
        String safeBatchId = requireNonBlank(batchId, "batchId 不能为空");
        BatchJobRow batchRow = requireBatchRow(safeBatchId);
        List<BatchItemRow> itemRows = stateRepository.loadBatchItems(safeBatchId);
        if (batchRow.totalJobs > 0 || !itemRows.isEmpty()) {
            BatchStatusSnapshot progress = calculateProgress(batchRow, itemRows);
            if (!progress.countsMatch(batchRow) || batchRow.totalJobs < itemRows.size()) {
                persistProgress(batchRow, progress, batchRow.message);
            }
            batchRow = requireBatchRow(safeBatchId);
        }
        boolean done = statusCalculator.isTerminal(batchRow.status);
        return detailAssembler.assemble(
                batchRow,
                itemRows,
                done,
                batchRow.status == JobStatus.SUCCESS,
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

    BatchStatusSnapshot refreshBatchProgress(String batchId, String message) {
        BatchJobRow batchRow = requireBatchRow(batchId);
        List<BatchItemRow> itemRows = stateRepository.loadBatchItems(batchId);
        BatchStatusSnapshot progress = calculateProgress(batchRow, itemRows);
        persistProgress(batchRow, progress, message);
        return progress;
    }

    private void ensureBatchNotRunning(String batchId) {
        if (stateRepository.countActiveBatchItems(batchId) > 0) {
            throw new IllegalStateException("batch_id 正在运行，不能覆盖重跑: " + batchId);
        }
    }

    private void ensureWorkflowNotRunning(String batchId) {
        BatchJobRow batchRow = stateRepository.findBatchRow(batchId);
        if (batchRow == null) {
            return;
        }
        if (!batchRow.status.isTerminal()) {
            throw new IllegalStateException("batch_id 工作流正在运行，不能覆盖重跑: " + batchId);
        }
    }

    private void ensureNoOtherWorkflowRunning(String batchId) {
        List<String> activeBatchIds = stateRepository.findOtherActiveBatchIds(batchId);
        if (activeBatchIds != null && !activeBatchIds.isEmpty()) {
            throw new IllegalStateException("已有批次工作流正在运行，不能同时启动新的批次: "
                    + activeBatchIds.get(0));
        }
    }

    private BatchStatusSnapshot calculateProgress(BatchJobRow batchRow, List<BatchItemRow> itemRows) {
        int totalJobs = Math.max(batchRow.totalJobs, itemRows.size());
        return statusCalculator.calculate(itemRows, totalJobs);
    }

    private void persistProgress(
            BatchJobRow batchRow,
            BatchStatusSnapshot progress,
            String message) {
        stateRepository.updateBatchProgress(
                batchRow.batchId,
                Math.max(batchRow.totalJobs,
                        progress.pendingJobs
                                + progress.runningJobs
                                + progress.successJobs
                                + progress.failedJobs
                                + progress.cancelledJobs),
                progress.pendingJobs,
                progress.runningJobs,
                progress.successJobs,
                progress.failedJobs,
                progress.cancelledJobs,
                System.currentTimeMillis(),
                message);
    }

    private void updateProgress(BatchJobRow batchRow, String message) {
        stateRepository.updateBatchProgress(
                batchRow.batchId,
                batchRow.totalJobs,
                batchRow.pendingJobs,
                batchRow.runningJobs,
                batchRow.successJobs,
                batchRow.failedJobs,
                batchRow.cancelledJobs,
                System.currentTimeMillis(),
                message);
    }

    private void transitionBatch(
            BatchJobRow batchRow,
            AsyncTaskStateMachine.Event event,
            int pendingJobs,
            int runningJobs,
            int successJobs,
            int failedJobs,
            int cancelledJobs,
            String message) {
        JobStatus targetStatus = AsyncTaskStateMachine.transition(batchRow.status, event);
        if (!stateRepository.transitionBatchStatus(
                batchRow.batchId,
                batchRow.status,
                targetStatus,
                pendingJobs,
                runningJobs,
                successJobs,
                failedJobs,
                cancelledJobs,
                System.currentTimeMillis(),
                message)) {
            BatchJobRow current = requireBatchRow(batchRow.batchId);
            throw new IllegalStateException("批次状态迁移并发冲突: batchId=" + batchRow.batchId
                    + ", expected=" + batchRow.status + ", actual=" + current.status
                    + ", event=" + event);
        }
    }

    private static AsyncTaskStateMachine.Event completionEvent(JobStatus status) {
        switch (status) {
            case SUCCESS:
                return AsyncTaskStateMachine.Event.SUCCEED;
            case FAILED:
                return AsyncTaskStateMachine.Event.FAIL;
            case PARTIAL_FAILED:
                return AsyncTaskStateMachine.Event.PARTIAL_FAIL;
            case CANCELLED:
                return AsyncTaskStateMachine.Event.CANCEL;
            default:
                throw new IllegalStateException("批次完成状态非法: " + status);
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
                ExecutionContextHolder.snapshot());
    }

    private void insertBatchItem(
            String batchId,
            int seqNo,
            String jobId,
            List<TradeInputRow> chunkTrades) {
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
