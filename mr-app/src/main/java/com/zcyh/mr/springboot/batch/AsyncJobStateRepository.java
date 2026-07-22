package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.batch.model.JobStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 异步任务状态仓储（MR_ASYNC_JOB）。
 */
@Repository
class AsyncJobStateRepository {
    private static final String JOB_COLUMNS = "job_id,request_id,engine_code,payload_json,status,created_at,"
            + "started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,"
            + "client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at";
    private static final RowMapper<AsyncJobEntity> JOB_ROW_MAPPER =
            (resultSet, rowNum) -> mapJob(resultSet);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;

    AsyncJobStateRepository(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("engineDbTransactionManager") PlatformTransactionManager transactionManager,
            @Value("${mr.job.store.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${mr.job.store.retry.backoff-ms:80}") long retryBackoffMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
    }

    void verifyJobSchema() {
        withRetry(() -> {
            jdbcTemplate.queryForList("SELECT " + JOB_COLUMNS + " FROM MR_ASYNC_JOB WHERE 1=0");
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
        requireCreationStatus(create, JobStatus.PENDING);
        String sql = "INSERT INTO MR_ASYNC_JOB "
                + "(job_id, request_id, engine_code, payload_json, status, created_at, updated_at, idempotency_key, "
                + "trace_id, client_id, user_id, user_name, source_system, cancel_requested, owner_node) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)";
        inTransaction(() -> jdbcTemplate.update(
                sql,
                create.jobId,
                create.requestId,
                create.engineCode,
                create.payloadJson,
                create.status.name(),
                create.createdAt,
                create.updatedAt,
                create.idempotencyKey,
                create.traceId,
                create.clientId,
                create.userId,
                create.userName,
                create.sourceSystem,
                nodeId), "任务入库");
    }

    void insertFailedJob(AsyncJobEntity create, String nodeId) {
        requireCreationStatus(create, JobStatus.FAILED);
        String sql = "INSERT INTO MR_ASYNC_JOB "
                + "(job_id, request_id, engine_code, payload_json, status, created_at, started_at, finished_at, "
                + "elapsed_ms, success_flag, error_code, error_message, idempotency_key, trace_id, client_id, user_id, "
                + "user_name, source_system, cancel_requested, owner_node, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
        inTransaction(() -> jdbcTemplate.update(
                sql,
                create.jobId,
                create.requestId,
                create.engineCode,
                create.payloadJson,
                create.status.name(),
                create.createdAt,
                create.startedAt,
                create.finishedAt,
                create.elapsedMs,
                create.successFlag,
                create.errorCode,
                create.errorMessage,
                create.idempotencyKey,
                create.traceId,
                create.clientId,
                create.userId,
                create.userName,
                create.sourceSystem,
                nodeId,
                create.updatedAt), "记录预失败任务");
    }

    AsyncJobEntity findByJobId(String jobId) {
        List<AsyncJobEntity> rows = withRetry(
                () -> jdbcTemplate.query(
                        "SELECT " + JOB_COLUMNS + " FROM MR_ASYNC_JOB WHERE job_id=?",
                        JOB_ROW_MAPPER,
                        jobId),
                "查询任务");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return validateSingleJob(rows.get(0));
    }

    AsyncJobEntity findByIdempotencyKey(String idempotencyKey) {
        List<AsyncJobEntity> rows = withRetry(
                () -> jdbcTemplate.query(
                        "SELECT " + JOB_COLUMNS + " FROM MR_ASYNC_JOB WHERE idempotency_key=?",
                        JOB_ROW_MAPPER,
                        idempotencyKey),
                "查询幂等任务");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return validateSingleJob(rows.get(0));
    }

    int countPendingJobs() {
        Integer count = withRetry(
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM MR_ASYNC_JOB WHERE status=?",
                        Integer.class,
                        JobStatus.PENDING.name()),
                "统计待处理任务");
        return count == null ? 0 : count.intValue();
    }

    List<String> listPendingJobs(String nodeId, long staleCutoff, int claimLimit) {
        String sql = "SELECT job_id FROM MR_ASYNC_JOB WHERE status=? AND cancel_requested=0 "
                + "AND (owner_node=? OR owner_node IS NULL OR owner_node='' OR updated_at<=?) "
                + "ORDER BY created_at ASC LIMIT ?";
        return withRetry(() -> jdbcTemplate.queryForList(
                sql, String.class, JobStatus.PENDING.name(), nodeId, staleCutoff, claimLimit), "拉取待分发任务");
    }

    boolean claimPendingJob(String jobId, long staleCutoff, long now, String nodeId) {
        String sql = "UPDATE MR_ASYNC_JOB SET owner_node=?, updated_at=? WHERE job_id=? AND status=? "
                + "AND cancel_requested=0 AND (owner_node=? OR owner_node IS NULL OR owner_node='' OR updated_at<=?)";
        return withRetry(() -> jdbcTemplate.update(
                sql, nodeId, now, jobId, JobStatus.PENDING.name(), nodeId, staleCutoff) > 0, "抢占待执行任务");
    }

    int recoverStaleRunningJobs(long now, long cutoff) {
        JobStatus failed = AsyncTaskStateMachine.transition(
                JobStatus.RUNNING, AsyncTaskStateMachine.Event.FAIL);
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, "
                + "elapsed_ms=CASE WHEN started_at IS NULL THEN 0 ELSE ? - started_at END, success_flag=0, "
                + "error_code='OWNER_TIMEOUT', error_message='任务执行超时未完成，系统自动回收', updated_at=? "
                + "WHERE status=? AND updated_at<=?";
        return withRetry(() -> jdbcTemplate.update(
                sql, failed.name(), now, now, now, JobStatus.RUNNING.name(), cutoff), "回收超时运行任务");
    }

    boolean touchRunningHeartbeat(String jobId, long now, String nodeId) {
        return withRetry(() -> jdbcTemplate.update(
                "UPDATE MR_ASYNC_JOB SET updated_at=?, owner_node=? WHERE job_id=? AND status=?",
                now, nodeId, jobId, JobStatus.RUNNING.name()) > 0, "更新任务运行心跳");
    }

    boolean markRunning(String jobId, long start, String nodeId) {
        JobStatus running = AsyncTaskStateMachine.transition(
                JobStatus.PENDING, AsyncTaskStateMachine.Event.START);
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, started_at=?, updated_at=?, owner_node=? "
                + "WHERE job_id=? AND status=? AND cancel_requested=0";
        return withRetry(() -> jdbcTemplate.update(
                sql, running.name(), start, start, nodeId, jobId, JobStatus.PENDING.name()) > 0, "更新运行状态");
    }

    boolean completeJob(
            String jobId,
            JobStatus expectedStatus,
            AsyncTaskStateMachine.Event event,
            long finish,
            long elapsed,
            String errorCode,
            String errorMessage) {
        JobStatus targetStatus = AsyncTaskStateMachine.transition(expectedStatus, event);
        int successFlag = targetStatus == JobStatus.SUCCESS ? 1 : 0;
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=?, success_flag=?, "
                + "error_code=?, error_message=?, updated_at=? WHERE job_id=? AND status=?";
        return withRetry(() -> jdbcTemplate.update(
                sql,
                targetStatus.name(),
                finish,
                elapsed,
                successFlag,
                errorCode,
                errorMessage,
                finish,
                jobId,
                expectedStatus.name()) > 0, "完成异步任务状态迁移");
    }

    AsyncJobEntity markCancelRequestedIfNeeded(String jobId, long now) {
        return inTransaction(() -> {
            AsyncJobEntity current = validateSingleJob(queryJob(jobId));
            if (current == null) {
                throw new IllegalArgumentException("任务不存在: " + jobId);
            }
            if (current.status != JobStatus.PENDING && current.status != JobStatus.RUNNING) {
                return current;
            }
            jdbcTemplate.update(
                    "UPDATE MR_ASYNC_JOB SET cancel_requested=1, updated_at=? "
                            + "WHERE job_id=? AND status IN (?, ?)",
                    now,
                    jobId,
                    JobStatus.PENDING.name(),
                    JobStatus.RUNNING.name());
            return validateSingleJob(queryJob(jobId));
        }, "取消任务");
    }

    void deleteOldTerminalJobs(long cutoff) {
        String sql = "DELETE FROM MR_ASYNC_JOB WHERE status IN ('SUCCESS','FAILED','CANCELLED') "
                + "AND finished_at IS NOT NULL AND finished_at < ?";
        withRetry(() -> jdbcTemplate.update(sql, cutoff), "清理历史任务");
    }

    int deleteNonTerminalJobs() {
        return withRetry(() -> jdbcTemplate.update(
                "DELETE FROM MR_ASYNC_JOB WHERE status IN ('PENDING','RUNNING')"),
                "重置未完成任务");
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

    private AsyncJobEntity queryJob(String jobId) {
        List<AsyncJobEntity> rows = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM MR_ASYNC_JOB WHERE job_id=?",
                JOB_ROW_MAPPER,
                jobId);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static AsyncJobEntity mapJob(ResultSet resultSet) throws SQLException {
        AsyncJobEntity job = new AsyncJobEntity();
        job.jobId = resultSet.getString("job_id");
        job.requestId = resultSet.getString("request_id");
        job.engineCode = resultSet.getString("engine_code");
        job.payloadJson = resultSet.getString("payload_json");
        job.status = JobStatus.parse(resultSet.getString("status"));
        job.createdAt = resultSet.getLong("created_at");
        job.startedAt = nullableLong(resultSet, "started_at");
        job.finishedAt = nullableLong(resultSet, "finished_at");
        job.elapsedMs = nullableLong(resultSet, "elapsed_ms");
        job.successFlag = nullableInteger(resultSet, "success_flag");
        job.errorCode = resultSet.getString("error_code");
        job.errorMessage = resultSet.getString("error_message");
        job.idempotencyKey = resultSet.getString("idempotency_key");
        job.traceId = resultSet.getString("trace_id");
        job.clientId = resultSet.getString("client_id");
        job.userId = resultSet.getString("user_id");
        job.userName = resultSet.getString("user_name");
        job.sourceSystem = resultSet.getString("source_system");
        job.cancelRequested = resultSet.getInt("cancel_requested") != 0;
        job.ownerNode = resultSet.getString("owner_node");
        job.updatedAt = resultSet.getLong("updated_at");
        return job;
    }

    private static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : Long.valueOf(value);
    }

    private static Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : Integer.valueOf(value);
    }

    private static void requireCreationStatus(AsyncJobEntity create, JobStatus expectedStatus) {
        if (create == null) {
            throw new IllegalArgumentException("异步任务实体不能为空");
        }
        if (create.status != expectedStatus) {
            throw new IllegalStateException("异步任务初始状态错误: expected=" + expectedStatus
                    + ", actual=" + create.status);
        }
    }

    private static AsyncJobEntity validateSingleJob(AsyncJobEntity job) {
        if (job == null) {
            return null;
        }
        if (job.status == null) {
            throw new IllegalStateException("异步任务状态不能为空: " + job.jobId);
        }
        if (job.status == JobStatus.PARTIAL_FAILED) {
            throw new IllegalStateException("单任务不能使用批次部分失败状态: " + job.jobId);
        }
        return job;
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
