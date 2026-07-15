package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.service.BatchJobStateRepository.BatchItemRow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批量任务状态计算器。
 */
@Component
class BatchStatusCalculator {
    private static final String BATCH_SUBMITTED = "SUBMITTED";
    private static final String BATCH_RUNNING = "RUNNING";
    private static final String BATCH_SUCCESS = "SUCCESS";
    private static final String BATCH_FAILED = "FAILED";
    private static final String BATCH_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String BATCH_CANCELLED = "CANCELLED";

    BatchStatusSnapshot calculate(List<BatchItemRow> rows, int totalJobs) {
        BatchStatusSnapshot result = new BatchStatusSnapshot();
        for (BatchItemRow row : rows) {
            String status = defaultJobStatus(row.jobStatus);
            switch (status) {
                case "PENDING":
                    result.pendingJobs++;
                    break;
                case "RUNNING":
                    result.runningJobs++;
                    break;
                case "SUCCESS":
                    result.successJobs++;
                    break;
                case "FAILED":
                    result.failedJobs++;
                    break;
                case "CANCELLED":
                    result.cancelledJobs++;
                    break;
                default:
                    result.pendingJobs++;
                    break;
            }
        }
        int known = result.pendingJobs
                + result.runningJobs
                + result.successJobs
                + result.failedJobs
                + result.cancelledJobs;
        if (known < totalJobs) {
            result.pendingJobs += totalJobs - known;
        }
        result.done = result.pendingJobs == 0 && result.runningJobs == 0 && totalJobs > 0;
        result.success = result.done && result.failedJobs == 0 && result.cancelledJobs == 0;
        result.status = deriveStatus(result, totalJobs);
        return result;
    }

    boolean isTerminal(String status) {
        return BATCH_SUCCESS.equalsIgnoreCase(status)
                || BATCH_FAILED.equalsIgnoreCase(status)
                || BATCH_PARTIAL_FAILED.equalsIgnoreCase(status)
                || BATCH_CANCELLED.equalsIgnoreCase(status);
    }

    static String defaultJobStatus(String status) {
        String safe = trimToNull(status);
        return safe == null ? "PENDING" : safe;
    }

    private String deriveStatus(BatchStatusSnapshot result, int totalJobs) {
        if (totalJobs <= 0) {
            return BATCH_FAILED;
        }
        if (result.pendingJobs == totalJobs) {
            return BATCH_SUBMITTED;
        }
        if (result.runningJobs > 0 || result.pendingJobs > 0) {
            return BATCH_RUNNING;
        }
        if (result.successJobs == totalJobs) {
            return BATCH_SUCCESS;
        }
        if (result.cancelledJobs == totalJobs) {
            return BATCH_CANCELLED;
        }
        if (result.successJobs == 0) {
            return BATCH_FAILED;
        }
        return BATCH_PARTIAL_FAILED;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    static final class BatchStatusSnapshot {
        String status;
        int pendingJobs;
        int runningJobs;
        int successJobs;
        int failedJobs;
        int cancelledJobs;
        boolean done;
        boolean success;

        boolean matches(BatchJobStateRepository.BatchJobRow row) {
            return equalsNullable(row.status, status)
                    && row.pendingJobs == pendingJobs
                    && row.runningJobs == runningJobs
                    && row.successJobs == successJobs
                    && row.failedJobs == failedJobs
                    && row.cancelledJobs == cancelledJobs;
        }

        private static boolean equalsNullable(String left, String right) {
            return left == null ? right == null : left.equals(right);
        }
    }
}
