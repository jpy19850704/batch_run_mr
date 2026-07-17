package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.runtime.AlertService;

import com.zcyh.mr.springboot.output.cache.JobScenarioPnlCacheService;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.measurement.valuation.ValuationExecutionAdapter;
import com.zcyh.mr.springboot.runtime.ExecutionContext;
import com.zcyh.mr.springboot.runtime.ExecutionContextHolder;
import com.zcyh.mr.springboot.batch.model.JobDetailResult;
import com.zcyh.mr.springboot.batch.model.JobStatus;
import com.zcyh.mr.springboot.batch.model.JobSubmitRequest;
import com.zcyh.mr.springboot.batch.model.JobSubmitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于数据源的异步任务服务，支持多实例共享任务状态。
 */
@Service
public class AsyncJobService {
    private static final String PAYLOAD_BUILD_FAILED = "PAYLOAD_BUILD_FAILED";
    /** MR_ASYNC_JOB.error_message 列长度保护阈值（留少量余量避免方言差异）。 */
    private static final int ERROR_MESSAGE_MAX_LEN = 1000;

    private final JobScenarioPnlCacheService jobScenarioPnlCacheService;
    private final AlertService alertService;
    private final AsyncJobStateRepository jobStateRepository;
    private final AsyncJobExecutionService executionService;
    private final AsyncJobDispatcher dispatcher;
    private final AtomicLong submitCounter = new AtomicLong(0L);

    private final String nodeId;
    private final int cleanupEverySubmit;
    private final int retentionDays;
    private final long pollAfterMs;
    private final String jobApiBasePath;
    public AsyncJobService(
            JobScenarioPnlCacheService jobScenarioPnlCacheService,
            AlertService alertService,
            AsyncJobStateRepository jobStateRepository,
            AsyncJobExecutionService executionService,
            AsyncJobDispatcher dispatcher,
            @Value("${mr.job.store.node-id:node-default}") String nodeId,
            @Value("${mr.job.store.cleanup.every-submit:100}") int cleanupEverySubmit,
            @Value("${mr.job.store.cleanup.retention-days:7}") int retentionDays,
            @Value("${mr.job.client.poll-after-ms:500}") long pollAfterMs,
            @Value("${mr.job.api.base-path:/api/jobs}") String jobApiBasePath
    ) {
        this.jobScenarioPnlCacheService = jobScenarioPnlCacheService;
        this.alertService = alertService;
        this.jobStateRepository = jobStateRepository;
        this.executionService = executionService;
        this.dispatcher = dispatcher;

        this.nodeId = nodeId;
        this.cleanupEverySubmit = Math.max(1, cleanupEverySubmit);
        this.retentionDays = Math.max(1, retentionDays);
        this.pollAfterMs = Math.max(100L, pollAfterMs);
        this.jobApiBasePath = normalizeApiBasePath(jobApiBasePath);
        verifyJobSchema();
    }

    JobSubmitResult submit(JobSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (request.getPayload() == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }

        String idempotencyKey = trimToNull(request.getIdempotencyKey());
        if (idempotencyKey != null) {
            AsyncJobEntity existed = findByIdempotencyKey(idempotencyKey);
            if (existed != null) {
                return toSubmitResult(existed, true, "幂等键命中，返回已存在任务");
            }
        }

        String jobId = trimToNull(request.getJobId());
        if (jobId == null) {
            jobId = newJobId();
        }
        String requestId = trimToNull(request.getRequestId());
        if (requestId == null) {
            requestId = jobId;
        }

        final AsyncJobEntity create = new AsyncJobEntity();
        ExecutionContext executionContext = ExecutionContextHolder.snapshot();
        create.jobId = jobId;
        create.requestId = requestId;
        create.engineCode = defaultEngineCode(request.getEngineCode());
        create.payloadJson = toPayloadJson(request.getPayload());
        create.status = JobStatus.PENDING;
        create.createdAt = System.currentTimeMillis();
        create.updatedAt = create.createdAt;
        create.idempotencyKey = idempotencyKey;
        if (executionContext != null) {
            create.traceId = trimToNull(executionContext.getTraceId());
            create.clientId = trimToNull(executionContext.getClientId());
            create.userId = trimToNull(executionContext.getUserId());
            create.userName = trimToNull(executionContext.getUserName());
            create.sourceSystem = trimToNull(executionContext.getSourceSystem());
        }

        try {
            jobStateRepository.insertJob(create, nodeId);
        } catch (DataAccessException ex) {
            if (create.idempotencyKey != null && jobStateRepository.isDuplicateKey(ex)) {
                AsyncJobEntity existed = findByIdempotencyKey(create.idempotencyKey);
                if (existed != null) {
                    return toSubmitResult(existed, true, "幂等键命中，返回已存在任务");
                }
            }
            throw new IllegalStateException("任务入库失败: " + ex.getMessage(), ex);
        }

        ExecutionContextHolder.setJobId(jobId);
        ExecutionContextHolder.setEngineCode(create.engineCode);
        try {
            dispatcher.submit(jobId);
        } catch (RejectedExecutionException ex) {
            executionService.markRejected(jobId, ex.getMessage());
            executionService.writeTerminalSnapshotIfNeeded(jobId, create.payloadJson);
            alertService.error("EXECUTOR_QUEUE_FULL", "任务队列已满，jobId=" + jobId, ex);
            throw new IllegalStateException("任务队列已满，请稍后重试");
        }

        dispatcher.ensureRunning();
        cleanupIfNeeded();
        return toSubmitResult(create, false, "任务已提交");
    }

    public JobSubmitResult recordFailedJob(
            String jobId,
            String requestId,
            String engineCode,
            String errorCode,
            String errorMessage) {
        String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        AsyncJobEntity existed = findByJobId(safeJobId);
        if (existed != null) {
            return toSubmitResult(existed, true, "返回已存在任务");
        }

        long now = System.currentTimeMillis();
        final AsyncJobEntity create = new AsyncJobEntity();
        ExecutionContext executionContext = ExecutionContextHolder.snapshot();
        create.jobId = safeJobId;
        create.requestId = trimToNull(requestId) == null ? safeJobId : trimToNull(requestId);
        create.engineCode = defaultEngineCode(engineCode);
        create.payloadJson = "{}";
        create.status = JobStatus.FAILED;
        create.createdAt = now;
        create.startedAt = now;
        create.finishedAt = now;
        create.elapsedMs = 0L;
        create.successFlag = 0;
        create.errorCode = trimToNull(errorCode) == null ? PAYLOAD_BUILD_FAILED : trimToNull(errorCode);
        create.errorMessage = truncateForErrorMessage(errorMessage);
        create.idempotencyKey = safeJobId;
        create.updatedAt = now;
        if (executionContext != null) {
            create.traceId = trimToNull(executionContext.getTraceId());
            create.clientId = trimToNull(executionContext.getClientId());
            create.userId = trimToNull(executionContext.getUserId());
            create.userName = trimToNull(executionContext.getUserName());
            create.sourceSystem = trimToNull(executionContext.getSourceSystem());
        }

        jobStateRepository.insertFailedJob(create, nodeId);
        cleanupIfNeeded();
        return toSubmitResult(create, false, "任务已记录为失败");
    }

    public JobDetailResult getDetail(String jobId) {
        AsyncJobEntity job = requireJob(jobId);
        return toDetail(job);
    }

    public JSONObject getScenarioPnl(String jobId) {
        AsyncJobEntity job = requireJob(jobId);
        if (job.status == JobStatus.PENDING || job.status == JobStatus.RUNNING) {
            throw new IllegalStateException("任务尚未完成");
        }
        if (job.status != JobStatus.SUCCESS) {
            throw new IllegalStateException("任务未成功完成: " + jobId);
        }
        JSONArray scenarioResult = jobScenarioPnlCacheService.getScenarioPnl(jobId);
        if (scenarioResult == null) {
            throw new IllegalStateException("Job 情景结果缓存不存在或已过期: " + jobId);
        }
        JSONObject result = new JSONObject();
        result.put("job_id", jobId);
        result.put("scenario_result", scenarioResult);
        result.put("cache_ttl_seconds", jobScenarioPnlCacheService.getTtlSeconds());
        return result;
    }

    public JobDetailResult cancel(String jobId) {
        final String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        final long now = System.currentTimeMillis();

        AsyncJobEntity job = jobStateRepository.markCancelRequestedIfNeeded(safeJobId, now);

        if (job.status == JobStatus.PENDING) {
            executionService.markCancelled(safeJobId, now, JobStatus.PENDING);
            executionService.writeTerminalSnapshotIfNeeded(safeJobId, job.payloadJson);
        } else if (job.status == JobStatus.RUNNING) {
            dispatcher.cancelLocal(safeJobId);
        }
        return toDetail(requireJob(safeJobId));
    }

    /**
     * 生成就绪状态快照，供 /readyz 使用。
     */
    public Map<String, Object> readinessSnapshot() {
        Map<String, Object> data = dispatcher.readinessSnapshot();
        boolean dbReady = false;
        String dbMessage = "OK";
        try {
            jobStateRepository.verifyConnection();
            dbReady = true;
        } catch (Exception ex) {
            dbMessage = ex.getMessage();
        }

        boolean executorReady = Boolean.TRUE.equals(data.get("executorReady"));
        boolean dispatcherReady = Boolean.TRUE.equals(data.get("dispatcherReady"));
        boolean ready = dbReady && executorReady && dispatcherReady;
        data.put("status", ready ? "READY" : "NOT_READY");
        data.put("dbReady", dbReady);
        data.put("dbMessage", dbMessage);
        return data;
    }


    private void cleanupIfNeeded() {
        long count = submitCounter.incrementAndGet();
        if (count % cleanupEverySubmit != 0L) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L;
        deleteOldTerminalJobs(cutoff);
    }



    private void verifyJobSchema() {
        jobStateRepository.verifyJobSchema();
    }


    private void deleteOldTerminalJobs(long cutoff) {
        jobStateRepository.deleteOldTerminalJobs(cutoff);
    }

    private AsyncJobEntity requireJob(String rawJobId) {
        String jobId = trimToNull(rawJobId);
        if (jobId == null) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        AsyncJobEntity job = findByJobId(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        return job;
    }

    private AsyncJobEntity findByJobId(String jobId) {
        return jobStateRepository.findByJobId(jobId);
    }

    private AsyncJobEntity findByIdempotencyKey(String idempotencyKey) {
        return jobStateRepository.findByIdempotencyKey(idempotencyKey);
    }

    private JobSubmitResult toSubmitResult(AsyncJobEntity job, boolean reused, String message) {
        JobSubmitResult result = new JobSubmitResult();
        result.setJobId(job.jobId);
        result.setRequestId(job.requestId);
        result.setEngineCode(defaultEngineCode(job.engineCode));
        result.setStatus(requireStatus(job));
        result.setReused(reused);
        result.setMessage(message);
        result.setSubmittedAt(job.createdAt);
        result.setPollAfterMs(pollAfterMs);
        result.setDetailUrl(buildDetailUrl(job.jobId));
        result.setCancelUrl(buildCancelUrl(job.jobId));
        return result;
    }

    private JobDetailResult toDetail(AsyncJobEntity job) {
        JobDetailResult detail = new JobDetailResult();
        detail.setJobId(job.jobId);
        detail.setRequestId(job.requestId);
        detail.setEngineCode(defaultEngineCode(job.engineCode));
        JobStatus status = requireStatus(job);
        detail.setStatus(status);
        detail.setCreatedAt(job.createdAt);
        detail.setStartedAt(job.startedAt);
        detail.setFinishedAt(job.finishedAt);
        detail.setDone(status.isTerminal());
        detail.setSuccess(status == JobStatus.SUCCESS);
        detail.setElapsedMs(resolveElapsed(job));
        detail.setCancelRequested(job.cancelRequested);
        detail.setResultReady(status.isTerminal());
        detail.setPollAfterMs(pollAfterMs);
        detail.setErrorCode(job.errorCode);
        detail.setErrorMessage(job.errorMessage);
        detail.setDetailUrl(buildDetailUrl(job.jobId));
        detail.setCancelUrl(buildCancelUrl(job.jobId));
        return detail;
    }

    private Long resolveElapsed(AsyncJobEntity job) {
        if (job.elapsedMs != null) {
            return job.elapsedMs;
        }
        if (job.startedAt == null) {
            return null;
        }
        long end = job.finishedAt != null ? job.finishedAt : System.currentTimeMillis();
        return Math.max(0L, end - job.startedAt);
    }

    private static String newJobId() {
        return "JOB-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String toPayloadJson(Object payload) {
        if (payload instanceof String) {
            String txt = trimToNull((String) payload);
            if (txt == null) {
                throw new IllegalArgumentException("payload 不能为空字符串");
            }
            return txt;
        }
        String txt = JSON.toJSONString(payload, JSONWriter.Feature.WriteBigDecimalAsPlain);
        if (trimToNull(txt) == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        return txt;
    }

    private static String defaultEngineCode(String engineCode) {
        String value = trimToNull(engineCode);
        return value == null ? ValuationExecutionAdapter.CODE : value;
    }

    private String buildDetailUrl(String jobId) {
        return jobApiBasePath + "/" + jobId;
    }

    private String buildCancelUrl(String jobId) {
        return jobApiBasePath + "/" + jobId + "/cancel";
    }

    private static String normalizeApiBasePath(String raw) {
        String base = trimToNull(raw);
        if (base == null) {
            return "/api/jobs";
        }
        String normalized = base.startsWith("/") ? base : "/" + base;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    private static String buildErrorMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        if (trimToNull(message) != null) {
            return truncateForErrorMessage(message);
        }
        return truncateForErrorMessage(ex.getClass().getSimpleName());
    }


    /**
     * 截断任务错误信息，避免写入 MR_ASYNC_JOB.error_message 时出现超长异常。
     */
    private static String truncateForErrorMessage(String message) {
        String value = trimToNull(message);
        if (value == null) {
            return null;
        }
        if (value.length() <= ERROR_MESSAGE_MAX_LEN) {
            return value;
        }
        return value.substring(0, ERROR_MESSAGE_MAX_LEN);
    }

    private static JobStatus requireStatus(AsyncJobEntity job) {
        if (job == null || job.status == null) {
            throw new IllegalStateException("异步任务状态不能为空");
        }
        if (job.status == JobStatus.PARTIAL_FAILED) {
            throw new IllegalStateException("单任务不能使用批次部分失败状态: " + job.jobId);
        }
        return job.status;
    }

}
