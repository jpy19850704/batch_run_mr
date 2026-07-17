package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.batch.model.BatchDetailResult;
import com.zcyh.mr.springboot.batch.BatchJobStateRepository.BatchItemRow;
import com.zcyh.mr.springboot.batch.BatchJobStateRepository.BatchJobRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量任务详情装配器。
 */
@Component
class BatchDetailAssembler {
    private static final String JOB_API_BASE_PATH = "/api/jobs";

    BatchDetailResult assemble(
            BatchJobRow batchRow,
            List<BatchItemRow> itemRows,
            boolean done,
            boolean success,
            long pollAfterMs,
            String detailUrl) {
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
        detail.setDetailUrl(detailUrl);
        detail.setMessage(batchRow.message);
        detail.setJobs(toBatchJobItems(itemRows));
        return detail;
    }

    private List<BatchDetailResult.BatchJobItem> toBatchJobItems(List<BatchItemRow> rows) {
        List<BatchDetailResult.BatchJobItem> items = new ArrayList<BatchDetailResult.BatchJobItem>();
        for (BatchItemRow row : rows) {
            BatchDetailResult.BatchJobItem item = new BatchDetailResult.BatchJobItem();
            item.setSeqNo(row.seqNo);
            item.setJobId(row.jobId);
            item.setStatus(BatchStatusCalculator.requireJobStatus(row));
            item.setTradeCount(row.tradeCount);
            item.setErrorCode(row.errorCode);
            item.setErrorMessage(row.errorMessage);
            item.setDetailUrl(JOB_API_BASE_PATH + "/" + row.jobId);
            item.setCancelUrl(JOB_API_BASE_PATH + "/" + row.jobId + "/cancel");
            items.add(item);
        }
        return items;
    }
}
