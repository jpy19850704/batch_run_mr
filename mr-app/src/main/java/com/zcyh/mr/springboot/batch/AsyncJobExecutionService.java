package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.execution.MeasurementExecutionService;

import com.zcyh.mr.springboot.runtime.AlertService;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.runtime.ExecutionContext;
import com.zcyh.mr.springboot.runtime.ExecutionContextHolder;
import com.zcyh.mr.springboot.measurement.valuation.ValuationExecutionAdapter;
import com.zcyh.mr.springboot.execution.MeasurementExecutionRequest;
import com.zcyh.mr.springboot.execution.MeasurementExecutionResult;
import com.zcyh.mr.springboot.batch.model.JobStatus;
import com.zcyh.mr.springboot.output.cache.JobScenarioPnlCacheService;
import com.zcyh.mr.springboot.output.file.BatchResultFileService;
import com.zcyh.mr.springboot.output.file.BatchResultStageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 异步任务执行与终态处理服务。
 */
@Service
class AsyncJobExecutionService {
    private static final String RESULT_PERSIST_FAILED = "RESULT_PERSIST_FAILED";
    private static final String ENGINE_EXECUTION_ERROR = "ENGINE_EXECUTION_ERROR";
    private static final String QUEUE_REJECTED = "QUEUE_REJECTED";
    private static final String CANCELLED_ERROR = "CANCELLED";
    private static final int ERROR_MESSAGE_MAX_LEN = 1000;
    private static final Logger log = LoggerFactory.getLogger(AsyncJobExecutionService.class);

    private final MeasurementExecutionService executionService;
    private final BatchResultStageService batchResultStageService;
    private final BatchResultFileService batchResultFileService;
    private final JobScenarioPnlCacheService jobScenarioPnlCacheService;
    private final AlertService alertService;
    private final AsyncJobStateRepository jobStateRepository;
    private final String nodeId;
    private final int engineRetryMaxAttempts;
    private final long engineRetryBackoffMs;

    AsyncJobExecutionService(
            MeasurementExecutionService executionService,
            BatchResultStageService batchResultStageService,
            BatchResultFileService batchResultFileService,
            JobScenarioPnlCacheService jobScenarioPnlCacheService,
            AlertService alertService,
            AsyncJobStateRepository jobStateRepository,
            @Value("${mr.job.store.node-id:node-default}") String nodeId,
            @Value("${mr.job.engine.retry.max-attempts:2}") int engineRetryMaxAttempts,
            @Value("${mr.job.engine.retry.backoff-ms:200}") long engineRetryBackoffMs) {
        this.executionService = executionService;
        this.batchResultStageService = batchResultStageService;
        this.batchResultFileService = batchResultFileService;
        this.jobScenarioPnlCacheService = jobScenarioPnlCacheService;
        this.alertService = alertService;
        this.jobStateRepository = jobStateRepository;
        this.nodeId = nodeId;
        this.engineRetryMaxAttempts = Math.max(1, engineRetryMaxAttempts);
        this.engineRetryBackoffMs = Math.max(0L, engineRetryBackoffMs);
    }

    void execute(String jobId) {
        try {
            long start = System.currentTimeMillis();
            if (!jobStateRepository.markRunning(jobId, start, nodeId)) {
                AsyncJobEntity current = jobStateRepository.findByJobId(jobId);
                if (current != null && current.status == JobStatus.PENDING && current.cancelRequested) {
                    markCancelled(jobId, start, JobStatus.PENDING);
                    writeTerminalSnapshotIfNeeded(jobId, current.payloadJson);
                }
                return;
            }

            AsyncJobEntity running = requireJob(jobId);
            bindJobContext(running);
            MeasurementExecutionRequest runRequest = new MeasurementExecutionRequest();
            runRequest.setRequestId(running.requestId);
            runRequest.setEngineCode(running.engineCode);
            runRequest.setPayload(running.payloadJson);

            try {
                log.info("异步任务开始执行，jobId={}, engineCode={}", jobId, running.engineCode);
                long engineStart = System.nanoTime();
                MeasurementExecutionResult runResult = runEngineWithRetry(jobId, runRequest);
                double engineMs = elapsedMs(engineStart);
                long finish = System.currentTimeMillis();
                if (isCancelRequested(jobId)) {
                    markCancelled(jobId, finish, JobStatus.RUNNING);
                    writeTerminalSnapshotIfNeeded(jobId, running.payloadJson);
                    return;
                }
                long finalizeStart = System.nanoTime();
                JobStatus finalStatus = finalizeRun(jobId, running, runResult, finish);
                double finalizeMs = elapsedMs(finalizeStart);
                log.info("异步任务执行完成，jobId={}, engineCode={}, status={}, elapsedMs={}",
                        jobId, running.engineCode, finalStatus, runResult.getElapsedMs());
                log.info("异步任务性能统计: jobId={}, requestId={}, engineMs={}, persistAndFinalizeMs={}, totalMs={}",
                        jobId, running.requestId, engineMs, finalizeMs, engineMs + finalizeMs);
            } catch (Exception ex) {
                long finish = System.currentTimeMillis();
                if (isCancelRequested(jobId)) {
                    markCancelled(jobId, finish, JobStatus.RUNNING);
                    writeTerminalSnapshotIfNeeded(jobId, running.payloadJson);
                    return;
                }
                completeFailure(
                        jobId,
                        JobStatus.RUNNING,
                        finish,
                        ENGINE_EXECUTION_ERROR,
                        buildErrorMessage(ex));
                alertService.error("JOB_FAILED",
                        "异步任务执行失败，jobId=" + jobId + ", engineCode=" + running.engineCode, ex);
                writeTerminalSnapshotIfNeeded(jobId, running.payloadJson);
            }
        } finally {
            ExecutionContextHolder.clear();
        }
    }

    private JobStatus finalizeRun(
            String jobId,
            AsyncJobEntity running,
            MeasurementExecutionResult runResult,
            long finish) {
        long elapsed = calcElapsed(running.startedAt, finish, runResult.getElapsedMs());
        runResult.setElapsedMs(elapsed);
        if (!runResult.isSuccess()) {
            completeFailure(
                    jobId,
                    JobStatus.RUNNING,
                    finish,
                    runResult.getErrorCode(),
                    truncateForErrorMessage(runResult.getErrorMessage()));
            writeTerminalSnapshotIfNeeded(jobId, running.payloadJson);
            return JobStatus.FAILED;
        }
        try {
            persistRequiredResult(jobId, running, runResult);
        } catch (Exception ex) {
            completeFailure(
                    jobId,
                    JobStatus.RUNNING,
                    finish,
                    RESULT_PERSIST_FAILED,
                    buildErrorMessage(ex));
            log.error("任务结果明细落库失败，jobId={}", jobId, ex);
            alertService.error("JOB_RESULT_PERSIST_FAILED", "任务结果明细落库失败，jobId=" + jobId, ex);
            writeTerminalSnapshotIfNeeded(jobId, running.payloadJson);
            return JobStatus.FAILED;
        }
        completeJob(
                jobId,
                JobStatus.RUNNING,
                AsyncTaskStateMachine.Event.SUCCEED,
                finish,
                elapsed,
                null,
                null);
        writeTerminalSnapshotIfNeeded(jobId, running.payloadJson);
        return JobStatus.SUCCESS;
    }

    private void persistRequiredResult(
            String jobId,
            AsyncJobEntity running,
            MeasurementExecutionResult runResult) {
        boolean persistResult = shouldPersistResult(running.payloadJson);
        cacheScenarioResultIfRequested(jobId, running.payloadJson, runResult);
        if (ValuationExecutionAdapter.CODE.equalsIgnoreCase(defaultEngineCode(running.engineCode)) && persistResult) {
            batchResultStageService.stage(
                    jobId, running.requestId, running.payloadJson, runResult);
        }
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0d;
    }

    void writeTerminalSnapshotIfNeeded(String jobId, String payloadJson) {
        try {
            if (shouldPersistResult(payloadJson) && !isStagedBatchResult(payloadJson)) {
                batchResultFileService.tryWriteSnapshotForJob(jobId);
            }
        } catch (Exception ex) {
            log.error("批次结果快照生成失败，jobId={}", jobId, ex);
        }
    }

    private static boolean isStagedBatchResult(String payloadJson) {
        JSONObject payload = parseJsonObject(payloadJson);
        JSONObject batchMeta = payload == null ? null : payload.getJSONObject("batch_meta");
        return batchMeta != null
                && trimToNull(batchMeta.getString(BatchResultStageService.META_EXECUTION_TYPE)) != null;
    }

    void markRejected(String jobId, String reason) {
        completeJob(
                jobId,
                JobStatus.PENDING,
                AsyncTaskStateMachine.Event.FAIL,
                System.currentTimeMillis(),
                0L,
                QUEUE_REJECTED,
                truncateForErrorMessage(reason));
    }

    void markCancelled(String jobId, long now, JobStatus expectedStatus) {
        AsyncJobEntity job = requireJob(jobId);
        long elapsed = calcElapsed(job.startedAt, now, 0L);
        completeJob(
                jobId,
                expectedStatus,
                AsyncTaskStateMachine.Event.CANCEL,
                now,
                elapsed,
                CANCELLED_ERROR,
                "任务已取消");
    }

    boolean isCancelRequested(String jobId) {
        AsyncJobEntity job = jobStateRepository.findByJobId(jobId);
        return job != null && job.cancelRequested;
    }

    private MeasurementExecutionResult runEngineWithRetry(String jobId, MeasurementExecutionRequest runRequest) {
        Exception last = null;
        for (int attempt = 1; attempt <= engineRetryMaxAttempts; attempt++) {
            if (isCancelRequested(jobId)) {
                throw new IllegalStateException("任务已取消");
            }
            jobStateRepository.touchRunningHeartbeat(jobId, System.currentTimeMillis(), nodeId);
            try {
                return executionService.run(runRequest);
            } catch (Exception ex) {
                last = ex;
                if (attempt >= engineRetryMaxAttempts || !isTransientEngineError(ex)) {
                    break;
                }
                sleepEngineRetry(attempt);
            }
        }
        if (last instanceof RuntimeException) {
            throw (RuntimeException) last;
        }
        throw new IllegalStateException("引擎执行失败", last);
    }

    private void completeFailure(
            String jobId,
            JobStatus expectedStatus,
            long finish,
            String errorCode,
            String errorMessage) {
        AsyncJobEntity running = requireJob(jobId);
        long elapsed = calcElapsed(running.startedAt, finish, 0L);
        completeJob(
                jobId,
                expectedStatus,
                AsyncTaskStateMachine.Event.FAIL,
                finish,
                elapsed,
                errorCode,
                truncateForErrorMessage(errorMessage));
    }

    private void cacheScenarioResultIfRequested(String jobId, String payloadJson, MeasurementExecutionResult runResult) {
        if (runResult == null || !runResult.isSuccess()) {
            return;
        }
        JSONObject payload = parseJsonObject(payloadJson);
        if (payload == null || !payload.getBooleanValue("cache_scenario_result")) {
            return;
        }
        JSONArray scenarioResult = extractScenarioResult(runResult.getData());
        if (scenarioResult == null) {
            throw new IllegalStateException(
                    "cache_scenario_result=true 但计算结果缺少 scenario_result: " + jobId);
        }
        jobScenarioPnlCacheService.putScenarioPnl(jobId, scenarioResult);
    }

    private void completeJob(
            String jobId,
            JobStatus expectedStatus,
            AsyncTaskStateMachine.Event event,
            long finish,
            long elapsed,
            String errorCode,
            String errorMessage) {
        if (!jobStateRepository.completeJob(
                jobId, expectedStatus, event, finish, elapsed, errorCode, errorMessage)) {
            AsyncJobEntity current = requireJob(jobId);
            throw new IllegalStateException("异步任务状态迁移并发冲突: jobId=" + jobId
                    + ", expected=" + expectedStatus + ", actual=" + current.status + ", event=" + event);
        }
    }

    private AsyncJobEntity requireJob(String jobId) {
        AsyncJobEntity job = jobStateRepository.findByJobId(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        return job;
    }

    private static void bindJobContext(AsyncJobEntity job) {
        ExecutionContext context = new ExecutionContext();
        context.setTraceId(trimToNull(job.traceId));
        context.setRequestId(trimToNull(job.requestId));
        context.setClientId(trimToNull(job.clientId));
        context.setUserId(trimToNull(job.userId));
        context.setUserName(trimToNull(job.userName));
        context.setSourceSystem(trimToNull(job.sourceSystem));
        context.setJobId(trimToNull(job.jobId));
        context.setEngineCode(trimToNull(job.engineCode));
        ExecutionContextHolder.bind(context);
    }

    private JSONArray extractScenarioResult(Object data) {
        JSONObject dataObj = toJsonObject(data);
        if (dataObj == null) {
            return null;
        }
        JSONObject nestedData = toJsonObject(dataObj.get("data"));
        return toJsonArray((nestedData == null ? dataObj : nestedData).get("scenario_result"));
    }

    private static boolean shouldPersistResult(String payloadJson) {
        JSONObject payload = JSON.parseObject(payloadJson);
        return payload == null || !payload.containsKey("persist_result")
                || readBoolean(payload, true, "persist_result");
    }

    private static boolean isTransientEngineError(Exception ex) {
        if (ex instanceof IllegalArgumentException) {
            return false;
        }
        String message = buildErrorMessage(ex).toLowerCase();
        return message.contains("timeout") || message.contains("temporarily")
                || message.contains("connection") || message.contains("reset")
                || message.contains("busy") || message.contains("unavailable");
    }

    private void sleepEngineRetry(int attempt) {
        long waitMs = engineRetryBackoffMs * attempt;
        if (waitMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("引擎重试等待被中断", ex);
        }
    }

    private static JSONObject parseJsonObject(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return JSON.parseObject(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static JSONObject toJsonObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        try {
            return JSON.parseObject(JSON.toJSONString(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private static JSONArray toJsonArray(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONArray) {
            return (JSONArray) value;
        }
        try {
            return JSON.parseArray(JSON.toJSONString(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String defaultEngineCode(String engineCode) {
        String value = trimToNull(engineCode);
        return value == null ? ValuationExecutionAdapter.CODE : value;
    }

    private static long calcElapsed(Long startedAt, long finishAt, long fallback) {
        return startedAt == null ? Math.max(0L, fallback) : Math.max(0L, finishAt - startedAt);
    }

    private static String buildErrorMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = trimToNull(ex.getMessage());
        return truncateForErrorMessage(message == null ? ex.getClass().getSimpleName() : message);
    }

    private static String truncateForErrorMessage(String message) {
        String value = trimToNull(message);
        if (value == null || value.length() <= ERROR_MESSAGE_MAX_LEN) {
            return value;
        }
        return value.substring(0, ERROR_MESSAGE_MAX_LEN);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
