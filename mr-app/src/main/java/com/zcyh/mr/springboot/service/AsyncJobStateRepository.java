package com.zcyh.mr.springboot.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 异步任务状态仓储（MR_ASYNC_JOB）。
 */
class AsyncJobStateRepository {
    private static final RowMapper<AsyncJobEntity> JOB_ROW_MAPPER = new RowMapper<AsyncJobEntity>() {
        @Override
        public AsyncJobEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            AsyncJobEntity job = new AsyncJobEntity();
            job.jobId = rs.getString("job_id");
            job.requestId = rs.getString("request_id");
            job.engineCode = rs.getString("engine_code");
            job.payloadJson = rs.getString("payload_json");
            job.status = rs.getString("status");
            job.createdAt = rs.getLong("created_at");
            job.startedAt = getNullableLong(rs, "started_at");
            job.finishedAt = getNullableLong(rs, "finished_at");
            job.elapsedMs = getNullableLong(rs, "elapsed_ms");
            job.successFlag = getNullableInt(rs, "success_flag");
            job.errorCode = rs.getString("error_code");
            job.errorMessage = rs.getString("error_message");
            job.idempotencyKey = rs.getString("idempotency_key");
            job.traceId = rs.getString("trace_id");
            job.clientId = rs.getString("client_id");
            job.userId = rs.getString("user_id");
            job.userName = rs.getString("user_name");
            job.sourceSystem = rs.getString("source_system");
            job.cancelRequested = rs.getInt("cancel_requested") == 1;
            job.ownerNode = rs.getString("owner_node");
            job.updatedAt = rs.getLong("updated_at");
            return job;
        }
    };

    private final JdbcTemplate jdbcTemplate;

    AsyncJobStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void verifyJobSchema() {
        jdbcTemplate.queryForList(
                "SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at "
                        + "FROM MR_ASYNC_JOB WHERE 1=0");
    }

    void insertJob(AsyncJobEntity create, String nodeId) {
        String sql = "INSERT INTO MR_ASYNC_JOB (job_id, request_id, engine_code, payload_json, status, created_at, updated_at, idempotency_key, trace_id, client_id, user_id, user_name, source_system, cancel_requested, owner_node) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)";
        jdbcTemplate.update(
                sql,
                create.jobId,
                create.requestId,
                create.engineCode,
                create.payloadJson,
                create.status,
                create.createdAt,
                create.updatedAt,
                create.idempotencyKey,
                create.traceId,
                create.clientId,
                create.userId,
                create.userName,
                create.sourceSystem,
                nodeId
        );
    }

    AsyncJobEntity findByJobId(String jobId) {
        String sql = "SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at "
                + "FROM MR_ASYNC_JOB WHERE job_id=?";
        List<AsyncJobEntity> rows = jdbcTemplate.query(sql, JOB_ROW_MAPPER, jobId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    AsyncJobEntity findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at "
                + "FROM MR_ASYNC_JOB WHERE idempotency_key=?";
        List<AsyncJobEntity> rows = jdbcTemplate.query(sql, JOB_ROW_MAPPER, idempotencyKey);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    int countPendingJobs(String pendingStatus) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM MR_ASYNC_JOB WHERE status=?", Integer.class, pendingStatus);
        return count == null ? 0 : count.intValue();
    }

    List<String> listPendingJobs(String pendingStatus, String nodeId, long staleCutoff, int claimLimit, boolean oracleDialect) {
        String sql;
        if (oracleDialect) {
            sql = "SELECT job_id FROM ("
                    + "SELECT job_id FROM MR_ASYNC_JOB "
                    + "WHERE status=? AND cancel_requested=0 "
                    + "AND (owner_node=? OR owner_node IS NULL OR owner_node='' OR updated_at<=?) "
                    + "ORDER BY created_at ASC"
                    + ") WHERE ROWNUM <= " + claimLimit;
        } else {
            sql = "SELECT job_id FROM MR_ASYNC_JOB "
                    + "WHERE status=? AND cancel_requested=0 "
                    + "AND (owner_node=? OR owner_node IS NULL OR owner_node='' OR updated_at<=?) "
                    + "ORDER BY created_at ASC LIMIT " + claimLimit;
        }
        return jdbcTemplate.queryForList(sql, String.class, pendingStatus, nodeId, staleCutoff);
    }

    boolean claimPendingJob(String jobId, long staleCutoff, long now, String nodeId, String pendingStatus) {
        String sql = "UPDATE MR_ASYNC_JOB SET owner_node=?, updated_at=? "
                + "WHERE job_id=? AND status=? AND cancel_requested=0 "
                + "AND (owner_node=? OR owner_node IS NULL OR owner_node='' OR updated_at<=?)";
        Integer updated = jdbcTemplate.update(sql, nodeId, now, jobId, pendingStatus, nodeId, staleCutoff);
        return updated != null && updated > 0;
    }

    int recoverStaleRunningJobs(long now, long cutoff, String nodeId, String runningStatus, String failedStatus) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=CASE WHEN started_at IS NULL THEN 0 ELSE ? - started_at END, "
                + "success_flag=0, error_code='OWNER_TIMEOUT', error_message='任务执行超时未完成，系统自动回收', updated_at=? "
                + "WHERE status=? AND updated_at<=? AND (owner_node IS NULL OR owner_node<>?)";
        Integer recovered = jdbcTemplate.update(sql, failedStatus, now, now, now, runningStatus, cutoff, nodeId);
        return recovered == null ? 0 : recovered.intValue();
    }

    void touchRunningHeartbeat(String jobId, long now, String nodeId, String runningStatus) {
        String sql = "UPDATE MR_ASYNC_JOB SET updated_at=?, owner_node=? WHERE job_id=? AND status=?";
        jdbcTemplate.update(sql, now, nodeId, jobId, runningStatus);
    }

    boolean markRunning(String jobId, long start, String nodeId, String runningStatus, String pendingStatus) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, started_at=?, updated_at=?, owner_node=? "
                + "WHERE job_id=? AND status=? AND cancel_requested=0";
        Integer updated = jdbcTemplate.update(sql, runningStatus, start, start, nodeId, jobId, pendingStatus);
        return updated != null && updated > 0;
    }

    void persistRunResult(String jobId, String runningStatus, String finalStatus, long finish, long elapsed, boolean success, String errorCode, String errorMessage) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=?, success_flag=?, error_code=?, error_message=?, updated_at=? "
                + "WHERE job_id=? AND status=?";
        jdbcTemplate.update(
                sql,
                finalStatus,
                finish,
                elapsed,
                success ? 1 : 0,
                errorCode,
                errorMessage,
                finish,
                jobId,
                runningStatus
        );
    }

    void persistRunFailure(String jobId, String runningStatus, String failedStatus, long finish, long elapsed, String errorCode, String errorMessage) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=?, success_flag=0, error_code=?, error_message=?, updated_at=? "
                + "WHERE job_id=? AND status=?";
        jdbcTemplate.update(
                sql,
                failedStatus,
                finish,
                elapsed,
                errorCode,
                errorMessage,
                finish,
                jobId,
                runningStatus
        );
    }

    void markRejected(String jobId, String pendingStatus, String failedStatus, long now, String reason) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=0, success_flag=0, error_code=?, error_message=?, updated_at=? "
                + "WHERE job_id=? AND status=?";
        jdbcTemplate.update(sql, failedStatus, now, "QUEUE_REJECTED", reason, now, jobId, pendingStatus);
    }

    void markCancelRequested(String jobId, long now, String pendingStatus, String runningStatus) {
        String sql = "UPDATE MR_ASYNC_JOB SET cancel_requested=1, updated_at=? WHERE job_id=? AND status IN (?, ?)";
        jdbcTemplate.update(sql, now, jobId, pendingStatus, runningStatus);
    }

    void markCancelled(String jobId, String expectedStatus, String cancelledStatus, long now, long elapsed) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=?, success_flag=0, error_code='CANCELLED', error_message='任务已取消', updated_at=? "
                + "WHERE job_id=? AND status=?";
        jdbcTemplate.update(sql, cancelledStatus, now, elapsed, now, jobId, expectedStatus);
    }

    void deleteOldTerminalJobs(long cutoff) {
        String sql = "DELETE FROM MR_ASYNC_JOB WHERE status IN ('SUCCESS','FAILED','CANCELLED') AND finished_at IS NOT NULL AND finished_at < ?";
        jdbcTemplate.update(sql, cutoff);
    }

    void markResultPersistFailed(String jobId, String successStatus, String failedStatus, String errorCode, String errorMessage, long now) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, success_flag=0, error_code=?, error_message=?, updated_at=? "
                + "WHERE job_id=? AND status=?";
        jdbcTemplate.update(sql, failedStatus, errorCode, errorMessage, now, jobId, successStatus);
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(value);
    }
}

