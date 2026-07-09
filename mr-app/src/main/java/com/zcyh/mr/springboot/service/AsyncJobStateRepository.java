package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.mybatis.enginedb.AsyncJobStateMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 异步任务状态仓储（MR_ASYNC_JOB）。
 */
@Repository
class AsyncJobStateRepository {

    private final AsyncJobStateMapper mapper;

    AsyncJobStateRepository(AsyncJobStateMapper mapper) {
        this.mapper = mapper;
    }

    void verifyJobSchema() {
        mapper.verifyJobSchema();
    }

    void insertJob(AsyncJobEntity create, String nodeId) {
        mapper.insertJob(create, nodeId);
    }

    void insertFailedJob(AsyncJobEntity create, String nodeId) {
        mapper.insertFailedJob(create, nodeId);
    }

    AsyncJobEntity findByJobId(String jobId) {
        List<AsyncJobEntity> rows = mapper.findByJobId(jobId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    AsyncJobEntity findByIdempotencyKey(String idempotencyKey) {
        List<AsyncJobEntity> rows = mapper.findByIdempotencyKey(idempotencyKey);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    int countPendingJobs(String pendingStatus) {
        Integer count = mapper.countPendingJobs(pendingStatus);
        return count == null ? 0 : count.intValue();
    }

    List<String> listPendingJobs(String pendingStatus, String nodeId, long staleCutoff, int claimLimit, boolean oracleDialect) {
        if (oracleDialect) {
            return mapper.listPendingJobsOracle(pendingStatus, nodeId, staleCutoff, claimLimit);
        }
        return mapper.listPendingJobsMysql(pendingStatus, nodeId, staleCutoff, claimLimit);
    }

    boolean claimPendingJob(String jobId, long staleCutoff, long now, String nodeId, String pendingStatus) {
        return mapper.claimPendingJob(jobId, staleCutoff, now, nodeId, pendingStatus) > 0;
    }

    int recoverStaleRunningJobs(long now, long cutoff, String nodeId, String runningStatus, String failedStatus) {
        return mapper.recoverStaleRunningJobs(now, cutoff, nodeId, runningStatus, failedStatus);
    }

    void touchRunningHeartbeat(String jobId, long now, String nodeId, String runningStatus) {
        mapper.touchRunningHeartbeat(jobId, now, nodeId, runningStatus);
    }

    boolean markRunning(String jobId, long start, String nodeId, String runningStatus, String pendingStatus) {
        return mapper.markRunning(jobId, start, nodeId, runningStatus, pendingStatus) > 0;
    }

    void persistRunResult(String jobId, String runningStatus, String finalStatus, long finish, long elapsed, boolean success, String errorCode, String errorMessage, String resultJson) {
        mapper.persistRunResult(jobId, runningStatus, finalStatus, finish, elapsed, success ? 1 : 0, errorCode, errorMessage, resultJson);
    }

    void persistRunFailure(String jobId, String runningStatus, String failedStatus, long finish, long elapsed, String errorCode, String errorMessage) {
        mapper.persistRunFailure(jobId, runningStatus, failedStatus, finish, elapsed, errorCode, errorMessage);
    }

    void markRejected(String jobId, String pendingStatus, String failedStatus, long now, String reason) {
        mapper.markRejected(jobId, pendingStatus, failedStatus, now, reason);
    }

    void markCancelRequested(String jobId, long now, String pendingStatus, String runningStatus) {
        mapper.markCancelRequested(jobId, now, pendingStatus, runningStatus);
    }

    void markCancelled(String jobId, String expectedStatus, String cancelledStatus, long now, long elapsed) {
        mapper.markCancelled(jobId, expectedStatus, cancelledStatus, now, elapsed);
    }

    void deleteOldTerminalJobs(long cutoff) {
        mapper.deleteOldTerminalJobs(cutoff);
    }

    void markResultPersistFailed(String jobId, String successStatus, String failedStatus, String errorCode, String errorMessage, long now) {
        mapper.markResultPersistFailed(jobId, successStatus, failedStatus, errorCode, errorMessage, now);
    }
}
