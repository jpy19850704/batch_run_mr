package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.mybatis.enginedb.AsyncJobStateMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.List;

/**
 * 异步任务状态仓储（MR_ASYNC_JOB）。
 */
@Repository
class AsyncJobStateRepository {

    private final AsyncJobStateMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;

    AsyncJobStateRepository(
            AsyncJobStateMapper mapper,
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("engineDbTransactionManager") PlatformTransactionManager transactionManager,
            @Value("${mr.job.store.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${mr.job.store.retry.backoff-ms:80}") long retryBackoffMs) {
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
    }

    void verifyJobSchema() {
        withRetry(() -> {
            mapper.verifyJobSchema();
            return null;
        }, "校验任务表结构");
    }

    void verifyConnection() {
        Integer one = withRetry(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class), "数据库探活");
        if (one == null || one.intValue() != 1) {
            throw new IllegalStateException("数据库探活返回异常结果");
        }
    }

    void insertJob(AsyncJobEntity create, String nodeId) {
        inTransaction(() -> mapper.insertJob(create, nodeId), "任务入库");
    }

    void insertFailedJob(AsyncJobEntity create, String nodeId) {
        inTransaction(() -> mapper.insertFailedJob(create, nodeId), "记录预失败任务");
    }

    AsyncJobEntity findByJobId(String jobId) {
        List<AsyncJobEntity> rows = withRetry(() -> mapper.findByJobId(jobId), "查询任务");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    AsyncJobEntity findByIdempotencyKey(String idempotencyKey) {
        List<AsyncJobEntity> rows = withRetry(() -> mapper.findByIdempotencyKey(idempotencyKey), "查询幂等任务");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    int countPendingJobs(String pendingStatus) {
        Integer count = withRetry(() -> mapper.countPendingJobs(pendingStatus), "统计待处理任务");
        return count == null ? 0 : count.intValue();
    }

    List<String> listPendingJobs(String pendingStatus, String nodeId, long staleCutoff, int claimLimit) {
        return withRetry(() -> mapper.listPendingJobs(pendingStatus, nodeId, staleCutoff, claimLimit), "拉取待分发任务");
    }

    boolean claimPendingJob(String jobId, long staleCutoff, long now, String nodeId, String pendingStatus) {
        return withRetry(() -> mapper.claimPendingJob(jobId, staleCutoff, now, nodeId, pendingStatus) > 0,
                "抢占待执行任务");
    }

    int recoverStaleRunningJobs(long now, long cutoff, String nodeId, String runningStatus, String failedStatus) {
        return withRetry(() -> mapper.recoverStaleRunningJobs(now, cutoff, nodeId, runningStatus, failedStatus),
                "回收超时运行任务");
    }

    void touchRunningHeartbeat(String jobId, long now, String nodeId, String runningStatus) {
        withRetry(() -> mapper.touchRunningHeartbeat(jobId, now, nodeId, runningStatus), "更新任务运行心跳");
    }

    boolean markRunning(String jobId, long start, String nodeId, String runningStatus, String pendingStatus) {
        return withRetry(() -> mapper.markRunning(jobId, start, nodeId, runningStatus, pendingStatus) > 0,
                "更新运行状态");
    }

    void persistRunResult(String jobId, String runningStatus, String finalStatus, long finish, long elapsed, boolean success, String errorCode, String errorMessage) {
        withRetry(() -> mapper.persistRunResult(jobId, runningStatus, finalStatus, finish, elapsed,
                success ? 1 : 0, errorCode, errorMessage), "持久化任务结果");
    }

    void persistRunFailure(String jobId, String runningStatus, String failedStatus, long finish, long elapsed, String errorCode, String errorMessage) {
        withRetry(() -> mapper.persistRunFailure(jobId, runningStatus, failedStatus, finish, elapsed,
                errorCode, errorMessage), "持久化失败结果");
    }

    void markRejected(String jobId, String pendingStatus, String failedStatus, long now, String reason) {
        withRetry(() -> mapper.markRejected(jobId, pendingStatus, failedStatus, now, reason), "更新队列拒绝状态");
    }

    void markCancelRequested(String jobId, long now, String pendingStatus, String runningStatus) {
        withRetry(() -> mapper.markCancelRequested(jobId, now, pendingStatus, runningStatus), "更新取消标记");
    }

    AsyncJobEntity markCancelRequestedIfNeeded(
            String jobId,
            long now,
            String pendingStatus,
            String runningStatus) {
        return inTransaction(() -> {
            AsyncJobEntity current = first(mapper.findByJobId(jobId));
            if (current == null) {
                throw new IllegalArgumentException("任务不存在: " + jobId);
            }
            if (!pendingStatus.equals(current.status) && !runningStatus.equals(current.status)) {
                return current;
            }
            mapper.markCancelRequested(jobId, now, pendingStatus, runningStatus);
            return first(mapper.findByJobId(jobId));
        }, "取消任务");
    }

    void markCancelled(String jobId, String expectedStatus, String cancelledStatus, long now, long elapsed) {
        withRetry(() -> mapper.markCancelled(jobId, expectedStatus, cancelledStatus, now, elapsed), "更新取消状态");
    }

    void deleteOldTerminalJobs(long cutoff) {
        withRetry(() -> mapper.deleteOldTerminalJobs(cutoff), "清理历史任务");
    }

    void markResultPersistFailed(String jobId, String successStatus, String failedStatus, String errorCode, String errorMessage, long now) {
        withRetry(() -> mapper.markResultPersistFailed(jobId, successStatus, failedStatus,
                errorCode, errorMessage, now), "回写结果落库失败状态");
    }

    boolean isDuplicateKey(DataAccessException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        Throwable root = ex.getMostSpecificCause();
        if (root instanceof SQLException) {
            String state = ((SQLException) root).getSQLState();
            if ("23505".equals(state) || "23000".equals(state)
                    || "42111".equals(state) || "42S11".equals(state)) {
                return true;
            }
        }
        String message = root == null ? ex.getMessage() : root.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("duplicate") || lower.contains("unique") || lower.contains("already exists");
    }

    private <T> T inTransaction(Callable<T> callable, String action) {
        return withRetry(() -> transactionTemplate.execute(status -> call(callable, action)), action);
    }

    private <T> T withRetry(Callable<T> callable, String action) {
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return callable.call();
            } catch (DataAccessException ex) {
                if (attempt >= retryMaxAttempts || !isTransientDbError(ex)) {
                    throw ex;
                }
                sleepBeforeRetry(attempt);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(action + "失败: " + ex.getMessage(), ex);
            }
        }
        throw new IllegalStateException(action + "失败: 重试次数耗尽");
    }

    private static <T> T call(Callable<T> callable, String action) {
        try {
            return callable.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(action + "失败: " + ex.getMessage(), ex);
        }
    }

    private static AsyncJobEntity first(List<AsyncJobEntity> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private void sleepBeforeRetry(int attempt) {
        long waitMs = retryBackoffMs * attempt;
        if (waitMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("数据库重试等待被中断", ex);
        }
    }

    private static boolean isTransientDbError(DataAccessException ex) {
        Throwable root = ex.getMostSpecificCause();
        if (root instanceof SQLException) {
            String state = ((SQLException) root).getSQLState();
            if (state != null && (state.startsWith("08") || "40001".equals(state)
                    || "40P01".equals(state) || "HYT00".equals(state))) {
                return true;
            }
        }
        String message = root == null ? ex.getMessage() : root.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("deadlock") || lower.contains("lock wait") || lower.contains("timeout")
                || lower.contains("temporarily") || lower.contains("connection reset")
                || lower.contains("communications link failure");
    }
}
