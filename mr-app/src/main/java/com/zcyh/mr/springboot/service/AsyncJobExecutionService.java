package com.zcyh.mr.springboot.service;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.context.RequestContext;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.EngineRunRequest;
import com.zcyh.mr.springboot.model.EngineRunResult;
import com.zcyh.mr.springboot.out.cache.JobScenarioPnlCacheService;
import com.zcyh.mr.springboot.out.db.PricingResultPersistService;
import com.zcyh.mr.springboot.out.file.BatchResultFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 异步任务执行与终态处理服务。
 */
@Service
class AsyncJobExecutionService {
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private static final String RESULT_PERSIST_FAILED = "RESULT_PERSIST_FAILED";
    private static final String ENGINE_EXECUTION_ERROR = "ENGINE_EXECUTION_ERROR";
    private static final int ERROR_MESSAGE_MAX_LEN = 1000;
    private static final Logger log = LoggerFactory.getLogger(AsyncJobExecutionService.class);

    private final EngineOrchestratorService orchestratorService;
    private final PricingResultPersistService pricingResultPersistService;
    private final BatchResultFileService batchResultFileService;
    private final JobScenarioPnlCacheService jobScenarioPnlCacheService;
    private final AlertService alertService;
    private final AsyncJobStateRepository jobStateRepository;
    private final String nodeId;
    private final int engineRetryMaxAttempts;
    private final long engineRetryBackoffMs;

    AsyncJobExecutionService(
            EngineOrchestratorService orchestratorService,
            PricingResultPersistService pricingResultPersistService,
            BatchResultFileService batchResultFileService,
            JobScenarioPnlCacheService jobScenarioPnlCacheService,
            AlertService alertService,
            AsyncJobStateRepository jobStateRepository,
            @Value("${mr.job.store.node-id:node-default}") String nodeId,
            @Value("${mr.job.engine.retry.max-attempts:2}") int engineRetryMaxAttempts,
            @Value("${mr.job.engine.retry.backoff-ms:200}") long engineRetryBackoffMs) {
        this.orchestratorService = orchestratorService;
        this.pricingResultPersistService = pricingResultPersistService;
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
            if (!jobStateRepository.markRunning(jobId, start, nodeId, RUNNING, PENDING)) {
                AsyncJobEntity current = jobStateRepository.findByJobId(jobId);
                if (current != null && PENDING.equals(current.status) && current.cancelRequested) {
                    markCancelled(jobId, start, PENDING);
                    handleTerminalSideEffects(jobId, current.requestId, current.payloadJson, current.engineCode, null);
                }
                return;
            }

            AsyncJobEntity running = requireJob(jobId);
            bindJobContext(running);
            EngineRunRequest runRequest = new EngineRunRequest();
            runRequest.setRequestId(running.requestId);
            runRequest.setEngineCode(running.engineCode);
            runRequest.setPayload(running.payloadJson);

            try {
                log.info("异步任务开始执行，jobId={}, engineCode={}", jobId, running.engineCode);
                EngineRunResult runResult = runEngineWithRetry(jobId, runRequest);
                long finish = System.currentTimeMillis();
                if (isCancelRequested(jobId)) {
                    markCancelled(jobId, finish, RUNNING);
                    handleTerminalSideEffects(jobId, running.requestId, running.payloadJson, running.engineCode, null);
                    return;
                }
                persistRunResult(jobId, runResult, finish, running.payloadJson);
                log.info("异步任务执行完成，jobId={}, engineCode={}, success={}, elapsedMs={}",
                        jobId, running.engineCode, runResult.isSuccess(), runResult.getElapsedMs());
                handleTerminalSideEffects(jobId, running.requestId, running.payloadJson, running.engineCode, runResult);
            } catch (Exception ex) {
                long finish = System.currentTimeMillis();
                if (isCancelRequested(jobId)) {
                    markCancelled(jobId, finish, RUNNING);
                    handleTerminalSideEffects(jobId, running.requestId, running.payloadJson, running.engineCode, null);
                    return;
                }
                persistRunFailure(jobId, buildErrorMessage(ex), finish);
                alertService.error("JOB_FAILED",
                        "异步任务执行失败，jobId=" + jobId + ", engineCode=" + running.engineCode, ex);
                handleTerminalSideEffects(jobId, running.requestId, running.payloadJson, running.engineCode, null);
            }
        } finally {
            RequestContextHolder.clear();
        }
    }

    void handleTerminalSideEffects(
            String jobId,
            String requestId,
            String payloadJson,
            String engineCode,
            EngineRunResult runResult) {
        boolean persistResult;
        try {
            persistResult = shouldPersistResult(payloadJson);
        } catch (Exception ex) {
            markResultPersistFailed(jobId, ex);
            return;
        }
        try {
            if (runResult != null && runResult.isSuccess()
                    && MrCalcEngineAdapter.CODE.equalsIgnoreCase(defaultEngineCode(engineCode))
                    && persistResult) {
                pricingResultPersistService.persistJobResult(requestId, jobId, payloadJson, runResult);
            }
        } catch (Exception ex) {
            markResultPersistFailed(jobId, ex);
        }
        try {
            if (persistResult && !isLocalRerun(payloadJson)) {
                batchResultFileService.tryWriteSnapshotForJob(jobId);
            }
        } catch (Exception ex) {
            log.error("批次结果快照生成失败，jobId={}", jobId, ex);
        }
    }

    private static boolean isLocalRerun(String payloadJson) {
        JSONObject payload = parseJsonObject(payloadJson);
        JSONObject batchMeta = payload == null ? null : payload.getJSONObject("batch_meta");
        return batchMeta != null && batchMeta.getBooleanValue("localRerun");
    }

    void markRejected(String jobId, String reason) {
        jobStateRepository.markRejected(
                jobId, PENDING, FAILED, System.currentTimeMillis(), truncateForErrorMessage(reason));
    }

    void markCancelled(String jobId, long now, String expectedStatus) {
        AsyncJobEntity job = requireJob(jobId);
        long elapsed = calcElapsed(job.startedAt, now, 0L);
        jobStateRepository.markCancelled(jobId, expectedStatus, CANCELLED, now, elapsed);
    }

    boolean isCancelRequested(String jobId) {
        AsyncJobEntity job = jobStateRepository.findByJobId(jobId);
        return job != null && job.cancelRequested;
    }

    private EngineRunResult runEngineWithRetry(String jobId, EngineRunRequest runRequest) {
        Exception last = null;
        for (int attempt = 1; attempt <= engineRetryMaxAttempts; attempt++) {
            if (isCancelRequested(jobId)) {
                throw new IllegalStateException("任务已取消");
            }
            jobStateRepository.touchRunningHeartbeat(jobId, System.currentTimeMillis(), nodeId, RUNNING);
            try {
                return orchestratorService.run(runRequest);
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

    private void persistRunResult(String jobId, EngineRunResult runResult, long finish, String payloadJson) {
        AsyncJobEntity running = requireJob(jobId);
        long elapsed = calcElapsed(running.startedAt, finish, runResult.getElapsedMs());
        runResult.setElapsedMs(elapsed);
        String finalStatus = runResult.isSuccess() ? SUCCESS : FAILED;
        cacheScenarioResultIfRequested(jobId, payloadJson, runResult);
        jobStateRepository.persistRunResult(
                jobId, RUNNING, finalStatus, finish, elapsed, runResult.isSuccess(),
                runResult.getErrorCode(), truncateForErrorMessage(runResult.getErrorMessage()));
    }

    private void persistRunFailure(String jobId, String message, long finish) {
        AsyncJobEntity running = requireJob(jobId);
        long elapsed = calcElapsed(running.startedAt, finish, 0L);
        jobStateRepository.persistRunFailure(
                jobId, RUNNING, FAILED, finish, elapsed,
                ENGINE_EXECUTION_ERROR, truncateForErrorMessage(message));
    }

    private void cacheScenarioResultIfRequested(String jobId, String payloadJson, EngineRunResult runResult) {
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

    private void markResultPersistFailed(String jobId, Exception cause) {
        String message = buildErrorMessage(cause);
        log.error("任务结果明细落库失败，jobId={}", jobId, cause);
        alertService.error("JOB_RESULT_PERSIST_FAILED", "任务结果明细落库失败，jobId=" + jobId, cause);
        try {
            jobStateRepository.markResultPersistFailed(
                    jobId, SUCCESS, FAILED, RESULT_PERSIST_FAILED,
                    truncateForErrorMessage(message), System.currentTimeMillis());
        } catch (Exception markEx) {
            log.error("任务结果落库失败状态回写失败，jobId={}", jobId, markEx);
            alertService.error("JOB_RESULT_PERSIST_MARK_FAILED",
                    "任务结果落库失败状态回写失败，jobId=" + jobId, markEx);
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
        RequestContext context = new RequestContext();
        context.setTraceId(trimToNull(job.traceId));
        context.setRequestId(trimToNull(job.requestId));
        context.setClientId(trimToNull(job.clientId));
        context.setUserId(trimToNull(job.userId));
        context.setUserName(trimToNull(job.userName));
        context.setSourceSystem(trimToNull(job.sourceSystem));
        context.setJobId(trimToNull(job.jobId));
        context.setEngineCode(trimToNull(job.engineCode));
        RequestContextHolder.bind(context);
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
        return value == null ? MrCalcEngineAdapter.CODE : value;
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
