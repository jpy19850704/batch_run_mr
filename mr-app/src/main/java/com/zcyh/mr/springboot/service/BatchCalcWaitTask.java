package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.model.BatchDetailResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 批量 Calc 等待任务。
 */
@Component
public class BatchCalcWaitTask implements BatchRunTask {
    private final BatchJobService batchJobService;
    private final long waitPollIntervalMs;
    private final long waitTimeoutMs;

    public BatchCalcWaitTask(
            BatchJobService batchJobService,
            @Value("${mr.batch.run.wait-poll-interval-ms:1000}") long waitPollIntervalMs,
            @Value("${mr.batch.run.wait-timeout-ms:7200000}") long waitTimeoutMs) {
        this.batchJobService = batchJobService;
        this.waitPollIntervalMs = Math.max(200L, waitPollIntervalMs);
        this.waitTimeoutMs = Math.max(1000L, waitTimeoutMs);
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        BatchDetailResult batchDetail = waitBatchFinished(context.getBatchId());
        if (!batchDetail.isSuccess() && !"PARTIAL_FAILED".equalsIgnoreCase(batchDetail.getStatus())) {
            throw new IllegalStateException("批量任务执行失败，batchId=" + context.getBatchId()
                    + ", status=" + batchDetail.getStatus());
        }
        context.setBatchDetail(batchDetail);
    }

    private BatchDetailResult waitBatchFinished(String batchId) {
        long deadline = System.currentTimeMillis() + waitTimeoutMs;
        BatchDetailResult last = null;
        while (System.currentTimeMillis() <= deadline) {
            last = batchJobService.getDetail(batchId);
            if (last != null && last.isDone()) {
                return last;
            }
            sleepQuietly(waitPollIntervalMs);
        }
        throw new IllegalStateException("批量任务等待超时，batchId=" + batchId + ", timeoutMs=" + waitTimeoutMs);
    }

    private static void sleepQuietly(long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待批量任务完成时被中断", ex);
        }
    }
}
