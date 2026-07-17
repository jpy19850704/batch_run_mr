package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.batch.model.JobStatus;
import com.zcyh.mr.springboot.batch.BatchJobStateRepository.BatchItemRow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批量任务状态计算器。
 */
@Component
class BatchStatusCalculator {
    BatchStatusSnapshot calculate(List<BatchItemRow> rows, int totalJobs) {
        if (rows == null) {
            throw new IllegalArgumentException("批次子任务列表不能为空");
        }
        if (totalJobs < 0 || rows.size() > totalJobs) {
            throw new IllegalStateException("批次子任务数量不一致: totalJobs=" + totalJobs
                    + ", itemRows=" + rows.size());
        }
        BatchStatusSnapshot result = new BatchStatusSnapshot();
        for (BatchItemRow row : rows) {
            JobStatus status = requireJobStatus(row);
            switch (status) {
                case PENDING:
                    result.pendingJobs++;
                    break;
                case RUNNING:
                    result.runningJobs++;
                    break;
                case SUCCESS:
                    result.successJobs++;
                    break;
                case FAILED:
                    result.failedJobs++;
                    break;
                case CANCELLED:
                    result.cancelledJobs++;
                    break;
                default:
                    throw new IllegalStateException("批次子任务状态非法: jobId=" + row.jobId
                            + ", status=" + status);
            }
        }
        result.pendingJobs += totalJobs - rows.size();
        result.done = totalJobs == 0 || result.pendingJobs == 0 && result.runningJobs == 0;
        result.success = result.done && result.failedJobs == 0 && result.cancelledJobs == 0;
        result.status = deriveStatus(result, totalJobs);
        return result;
    }

    boolean isTerminal(JobStatus status) {
        return status != null && status.isTerminal();
    }

    static JobStatus requireJobStatus(BatchItemRow row) {
        if (row == null || row.jobStatus == null) {
            throw new IllegalStateException("批次子任务缺少任务状态: jobId="
                    + (row == null ? null : row.jobId));
        }
        return row.jobStatus;
    }

    private JobStatus deriveStatus(BatchStatusSnapshot result, int totalJobs) {
        if (totalJobs <= 0) {
            return JobStatus.FAILED;
        }
        if (result.pendingJobs == totalJobs) {
            return JobStatus.PENDING;
        }
        if (result.runningJobs > 0 || result.pendingJobs > 0) {
            return JobStatus.RUNNING;
        }
        if (result.successJobs == totalJobs) {
            return JobStatus.SUCCESS;
        }
        if (result.cancelledJobs == totalJobs) {
            return JobStatus.CANCELLED;
        }
        if (result.successJobs == 0) {
            return JobStatus.FAILED;
        }
        return JobStatus.PARTIAL_FAILED;
    }

    static final class BatchStatusSnapshot {
        JobStatus status;
        int pendingJobs;
        int runningJobs;
        int successJobs;
        int failedJobs;
        int cancelledJobs;
        boolean done;
        boolean success;

        boolean countsMatch(BatchJobStateRepository.BatchJobRow row) {
            return row.pendingJobs == pendingJobs
                    && row.runningJobs == runningJobs
                    && row.successJobs == successJobs
                    && row.failedJobs == failedJobs
                    && row.cancelledJobs == cancelledJobs;
        }

    }
}
