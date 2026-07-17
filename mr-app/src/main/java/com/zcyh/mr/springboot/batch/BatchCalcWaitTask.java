package com.zcyh.mr.springboot.batch;

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
        context.setBatchStatusSnapshot(waitBatchFinished(context.getBatchId()));
    }

    private BatchStatusCalculator.BatchStatusSnapshot waitBatchFinished(String batchId) {
        long deadline = System.currentTimeMillis() + waitTimeoutMs;
        BatchStatusCalculator.BatchStatusSnapshot last = null;
        while (System.currentTimeMillis() <= deadline) {
            last = batchJobService.refreshBatchProgress(batchId, "批量任务执行中");
            if (last.done) {
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
