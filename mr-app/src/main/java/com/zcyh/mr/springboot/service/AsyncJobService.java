package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.context.RequestContext;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zcyh.mr.springboot.model.EngineRunRequest;
import com.zcyh.mr.springboot.model.EngineRunResult;
import com.zcyh.mr.springboot.model.JobDetailResult;
import com.zcyh.mr.springboot.model.JobStatus;
import com.zcyh.mr.springboot.model.JobSubmitRequest;
import com.zcyh.mr.springboot.model.JobSubmitResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于数据源的异步任务服务，支持多实例共享任务状态。
 */
@Service
public class AsyncJobService {
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private static final String RESULT_PERSIST_FAILED = "RESULT_PERSIST_FAILED";
    /** MR_ASYNC_JOB.error_message 列长度保护阈值（留少量余量避免方言差异）。 */
    private static final int ERROR_MESSAGE_MAX_LEN = 1000;
    private static final Logger log = LoggerFactory.getLogger(AsyncJobService.class);

    private final EngineOrchestratorService orchestratorService;
    private final PricingResultPersistService pricingResultPersistService;
    private final BatchResultFileService batchResultFileService;
    private final AlertService alertService;
    private final JdbcTemplate jdbcTemplate;
    private final AsyncJobStateRepository jobStateRepository;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService dispatcherExecutor;
    /** 分发器定时任务句柄，null 表示分发器当前未运行。 */
    private volatile ScheduledFuture<?> dispatcherFuture;
    private final ConcurrentMap<String, Future<?>> localFutureMap = new ConcurrentHashMap<String, Future<?>>();
    private final AtomicLong submitCounter = new AtomicLong(0L);
    private volatile boolean shuttingDown = false;

    private final boolean dispatcherEnabled;
    private final String nodeId;
    private final int cleanupEverySubmit;
    private final int retentionDays;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;
    private final long dispatchIntervalMs;
    private final int claimBatchSize;
    private final long stalePendingMs;
    private final long staleRunningMs;
    private final long shutdownAwaitSeconds;
    private final int engineRetryMaxAttempts;
    private final long engineRetryBackoffMs;
    private final long pollAfterMs;
    private final String jobApiBasePath;
    private final int pendingJobAlertThreshold;
    private final int executorQueueAlertThreshold;
    /** 是否使用 Oracle 方言分页。MySQL 走 LIMIT。 */
    private final boolean oracleDialect;

    public AsyncJobService(
            EngineOrchestratorService orchestratorService,
            PricingResultPersistService pricingResultPersistService,
            BatchResultFileService batchResultFileService,
            AlertService alertService,
            AsyncJobStateRepository jobStateRepository,
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("engineDbTransactionManager") PlatformTransactionManager transactionManager,
            @Value("${mr.job.executor.core-size:4}") int coreSize,
            @Value("${mr.job.executor.max-size:16}") int maxSize,
            @Value("${mr.job.executor.queue-capacity:1000}") int queueCapacity,
            @Value("${mr.job.store.node-id:node-default}") String nodeId,
            @Value("${mr.job.store.cleanup.every-submit:100}") int cleanupEverySubmit,
            @Value("${mr.job.store.cleanup.retention-days:7}") int retentionDays,
            @Value("${mr.job.store.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${mr.job.store.retry.backoff-ms:80}") long retryBackoffMs,
            @Value("${mr.job.dispatcher.enabled:false}") boolean dispatcherEnabled,
            @Value("${mr.job.dispatcher.interval-ms:500}") long dispatchIntervalMs,
            @Value("${mr.job.dispatcher.claim-batch-size:50}") int claimBatchSize,
            @Value("${mr.job.dispatcher.stale-pending-ms:30000}") long stalePendingMs,
            @Value("${mr.job.dispatcher.stale-running-ms:600000}") long staleRunningMs,
            @Value("${mr.job.executor.shutdown-await-seconds:30}") long shutdownAwaitSeconds,
            @Value("${mr.job.engine.retry.max-attempts:2}") int engineRetryMaxAttempts,
            @Value("${mr.job.engine.retry.backoff-ms:200}") long engineRetryBackoffMs,
            @Value("${mr.job.client.poll-after-ms:500}") long pollAfterMs,
            @Value("${mr.job.api.base-path:/api/jobs}") String jobApiBasePath,
            @Value("${mr.alert.pending-job-threshold:200}") int pendingJobAlertThreshold,
            @Value("${mr.alert.executor-queue-threshold:800}") int executorQueueAlertThreshold
    ) {
        this.orchestratorService = orchestratorService;
        this.pricingResultPersistService = pricingResultPersistService;
        this.batchResultFileService = batchResultFileService;
        this.alertService = alertService;
        this.jdbcTemplate = jdbcTemplate;
        this.jobStateRepository = jobStateRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        this.dispatcherEnabled = dispatcherEnabled;
        this.nodeId = nodeId;
        this.cleanupEverySubmit = Math.max(1, cleanupEverySubmit);
        this.retentionDays = Math.max(1, retentionDays);
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
        this.dispatchIntervalMs = Math.max(100L, dispatchIntervalMs);
        this.claimBatchSize = Math.max(1, claimBatchSize);
        this.stalePendingMs = Math.max(1000L, stalePendingMs);
        this.staleRunningMs = Math.max(0L, staleRunningMs);
        this.shutdownAwaitSeconds = Math.max(1L, shutdownAwaitSeconds);
        this.engineRetryMaxAttempts = Math.max(1, engineRetryMaxAttempts);
        this.engineRetryBackoffMs = Math.max(0L, engineRetryBackoffMs);
        this.pollAfterMs = Math.max(100L, pollAfterMs);
        this.jobApiBasePath = normalizeApiBasePath(jobApiBasePath);
        this.pendingJobAlertThreshold = Math.max(1, pendingJobAlertThreshold);
        this.executorQueueAlertThreshold = Math.max(1, executorQueueAlertThreshold);
        this.oracleDialect = detectOracleDialect();

        int safeCore = Math.max(1, coreSize);
        int safeMax = Math.max(safeCore, maxSize);
        int safeQueue = Math.max(100, queueCapacity);
        this.executor = new ThreadPoolExecutor(
                safeCore,
                safeMax,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(safeQueue),
                new NamedThreadFactory("mr-job-worker-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
        this.dispatcherExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("mr-job-dispatcher-", true));

        verifyJobSchema();
    }

    public JobSubmitResult submit(JobSubmitRequest request) {
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
        RequestContext requestContext = RequestContextHolder.snapshot();
        create.jobId = jobId;
        create.requestId = requestId;
        create.engineCode = defaultEngineCode(request.getEngineCode());
        create.payloadJson = toPayloadJson(request.getPayload());
        create.status = PENDING;
        create.createdAt = System.currentTimeMillis();
        create.updatedAt = create.createdAt;
        create.idempotencyKey = idempotencyKey;
        if (requestContext != null) {
            create.traceId = trimToNull(requestContext.getTraceId());
            create.clientId = trimToNull(requestContext.getClientId());
            create.userId = trimToNull(requestContext.getUserId());
            create.userName = trimToNull(requestContext.getUserName());
            create.sourceSystem = trimToNull(requestContext.getSourceSystem());
        }

        try {
            runInTransaction(new Callable<Void>() {
                @Override
                public Void call() {
                    insertJob(create);
                    return null;
                }
            }, "任务入库");
        } catch (DataAccessException ex) {
            if (create.idempotencyKey != null && isDuplicateKey(ex)) {
                AsyncJobEntity existed = findByIdempotencyKey(create.idempotencyKey);
                if (existed != null) {
                    return toSubmitResult(existed, true, "幂等键命中，返回已存在任务");
                }
            }
            throw new IllegalStateException("任务入库失败: " + ex.getMessage(), ex);
        }

        RequestContextHolder.setJobId(jobId);
        RequestContextHolder.setEngineCode(create.engineCode);
        try {
            submitLocalExecution(jobId);
        } catch (RejectedExecutionException ex) {
            markRejected(jobId, ex.getMessage());
            handleTerminalSideEffects(jobId, create.requestId, create.payloadJson, create.engineCode, null);
            alertService.error("EXECUTOR_QUEUE_FULL", "任务队列已满，jobId=" + jobId, ex);
            throw new IllegalStateException("任务队列已满，请稍后重试");
        }

        ensureDispatcherRunning();
        cleanupIfNeeded();
        return toSubmitResult(create, false, "任务已提交");
    }

    public JobDetailResult getDetail(String jobId) {
        AsyncJobEntity job = requireJob(jobId);
        return toDetail(job);
    }

    public EngineRunResult getResult(String jobId) {
        AsyncJobEntity job = requireJob(jobId);
        if (PENDING.equals(job.status) || RUNNING.equals(job.status)) {
            throw new IllegalStateException("任务尚未完成");
        }
        return buildResult(job);
    }

    public JobDetailResult cancel(String jobId) {
        final String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        final long now = System.currentTimeMillis();

        AsyncJobEntity job = runInTransaction(new Callable<AsyncJobEntity>() {
            @Override
            public AsyncJobEntity call() {
                return markCancelRequestedIfNeeded(safeJobId, now);
            }
        }, "取消任务");

        if (PENDING.equals(job.status)) {
            markCancelled(safeJobId, now, PENDING);
            handleTerminalSideEffects(safeJobId, job.requestId, job.payloadJson, job.engineCode, null);
        } else if (RUNNING.equals(job.status)) {
            Future<?> future = localFutureMap.get(safeJobId);
            if (future != null && future.cancel(true)) {
                markCancelled(safeJobId, now, RUNNING);
                handleTerminalSideEffects(safeJobId, job.requestId, job.payloadJson, job.engineCode, null);
            }
        }
        return toDetail(requireJob(safeJobId));
    }

    /**
     * 生成就绪状态快照，供 /readyz 使用。
     */
    public Map<String, Object> readinessSnapshot() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        boolean dbReady = false;
        String dbMessage = "OK";
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbReady = (one != null && one.intValue() == 1);
            if (!dbReady) {
                dbMessage = "数据库探活返回异常结果";
            }
        } catch (Exception ex) {
            dbMessage = ex.getMessage();
        }

        int active = executor.getActiveCount();
        int max = executor.getMaximumPoolSize();
        int queueSize = executor.getQueue().size();
        int queueRemain = executor.getQueue().remainingCapacity();
        int pendingJobs = countPendingJobs();
        boolean executorReady = !shuttingDown && queueRemain > 0;
        boolean dispatcherReady = !dispatcherExecutor.isShutdown();

        boolean ready = dbReady && executorReady && dispatcherReady;
        data.put("status", ready ? "READY" : "NOT_READY");
        data.put("dbReady", dbReady);
        data.put("dbMessage", dbMessage);
        data.put("executorReady", executorReady);
        data.put("dispatcherReady", dispatcherReady);
        data.put("activeThreads", active);
        data.put("maxThreads", max);
        data.put("queueSize", queueSize);
        data.put("queueRemaining", queueRemain);
        data.put("pendingJobs", pendingJobs);
        data.put("nodeId", nodeId);
        checkAlertThresholds(pendingJobs, queueSize);
        return data;
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        stopDispatcher();
        dispatcherExecutor.shutdown();
        executor.shutdown();
        try {
            dispatcherExecutor.awaitTermination(Math.min(5L, shutdownAwaitSeconds), TimeUnit.SECONDS);
            if (!executor.awaitTermination(shutdownAwaitSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dispatcherExecutor.shutdownNow();
            executor.shutdownNow();
        }
    }

    private void executeJob(String jobId) {
        try {
            long start = System.currentTimeMillis();
            if (!markRunning(jobId, start)) {
                AsyncJobEntity current = findByJobId(jobId);
                if (current != null && PENDING.equals(current.status) && current.cancelRequested) {
                    markCancelled(jobId, start, PENDING);
                    handleTerminalSideEffects(jobId, current.requestId, current.payloadJson, current.engineCode, null);
                }
                return;
            }

            AsyncJobEntity running = requireJob(jobId);
            bindJobContext(running);
            RequestContextHolder.setJobId(jobId);
            RequestContextHolder.setEngineCode(running.engineCode);
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
                persistRunFailure(jobId, running.requestId, running.engineCode, buildErrorMessage(ex), finish);
                alertService.error("JOB_FAILED", "异步任务执行失败，jobId=" + jobId + ", engineCode=" + running.engineCode, ex);
                handleTerminalSideEffects(jobId, running.requestId, running.payloadJson, running.engineCode, null);
            }
        } finally {
            localFutureMap.remove(jobId);
            RequestContextHolder.clear();
        }
    }

    /**
     * 处理任务终态后的附加动作。
     * 结果明细落库属于关键动作，失败会将任务从 SUCCESS 回写为 FAILED；
     * 批次结果快照生成属于非关键动作，失败仅记录日志。
     */
    private void handleTerminalSideEffects(String jobId, String requestId, String payloadJson, String engineCode, EngineRunResult runResult) {
        boolean whatifPayload = isWhatifPayload(payloadJson);
        try {
            if (runResult != null
                    && runResult.isSuccess()
                    && MrCalcEngineAdapter.CODE.equalsIgnoreCase(defaultEngineCode(engineCode))
                    && !whatifPayload) {
                pricingResultPersistService.persistJobResult(requestId, jobId, payloadJson, runResult);
            }
        } catch (Exception ex) {
            markResultPersistFailed(jobId, ex);
        }

        try {
            if (!whatifPayload) {
                batchResultFileService.tryWriteSnapshotForJob(jobId);
            }
        } catch (Exception ex) {
            log.error("批次结果快照生成失败，jobId={}", jobId, ex);
        }
    }

    private void markResultPersistFailed(String jobId, Exception cause) {
        String message = buildErrorMessage(cause);
        log.error("任务结果明细落库失败，jobId={}", jobId, cause);
        alertService.error("JOB_RESULT_PERSIST_FAILED", "任务结果明细落库失败，jobId=" + jobId, cause);
        try {
            final long now = System.currentTimeMillis();
            final String safeMessage = truncateForErrorMessage(message);
            withRetry(new Callable<Void>() {
                @Override
                public Void call() {
                    jobStateRepository.markResultPersistFailed(jobId, SUCCESS, FAILED, RESULT_PERSIST_FAILED, safeMessage, now);
                    return null;
                }
            }, "回写结果落库失败状态");
        } catch (Exception markEx) {
            log.error("任务结果落库失败状态回写失败，jobId={}", jobId, markEx);
            alertService.error("JOB_RESULT_PERSIST_MARK_FAILED", "任务结果落库失败状态回写失败，jobId=" + jobId, markEx);
        }
    }

    /**
     * 幂等启动分发器。已在运行中则跳过。
     */
    private synchronized void startDispatcher() {
        if (shuttingDown) {
            return;
        }
        if (dispatcherFuture != null && !dispatcherFuture.isDone()) {
            return;
        }
        log.info("启动任务分发器，轮询间隔={}ms", dispatchIntervalMs);
        dispatcherFuture = dispatcherExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                runDispatchLoop();
            }
        }, 0, dispatchIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止分发器定时任务。分发线程本身不销毁，可再次启动。
     */
    private synchronized void stopDispatcher() {
        if (dispatcherFuture != null && !dispatcherFuture.isDone()) {
            dispatcherFuture.cancel(false);
            log.info("任务分发器已停止（无待处理任务）");
        }
        dispatcherFuture = null;
    }

    /**
     * 确保分发器正在运行。由 submit() 调用，按需唤醒分发器。
     */
    private void ensureDispatcherRunning() {
        if (!dispatcherEnabled || shuttingDown) {
            return;
        }
        if (dispatcherFuture != null && !dispatcherFuture.isDone()) {
            return;
        }
        startDispatcher();
    }

    /**
     * 分发循环：处理 PENDING 任务，并可选回收超时 RUNNING 任务。
     * 空闲时自动停止分发器，等待下次 submit() 唤醒。
     */
    private void runDispatchLoop() {
        if (shuttingDown) {
            return;
        }
        try {
            dispatchPendingJobs();
            recoverStaleRunningJobs();
            // 空闲检测：无本地执行中任务且无 DB 待处理任务时，停止轮询
            if (localFutureMap.isEmpty() && countPendingJobs() == 0) {
                stopDispatcher();
            }
        } catch (Exception ex) {
            // 分发线程不抛出异常，避免被调度器终止
            alertService.error("JOB_DISPATCH_FAILED", "任务分发线程执行异常", ex);
        }
    }

    /**
     * 抢占并提交待执行任务到本地执行器。
     */
    private void dispatchPendingJobs() {
        int queueRemaining = executor.getQueue().remainingCapacity();
        int queueSize = executor.getQueue().size();
        int claimLimit = Math.min(claimBatchSize, Math.max(0, queueRemaining));
        checkAlertThresholds(countPendingJobs(), queueSize);
        if (claimLimit <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long staleCutoff = now - stalePendingMs;
        List<String> jobIds = withRetry(new Callable<List<String>>() {
            @Override
            public List<String> call() {
                return jobStateRepository.listPendingJobs(PENDING, nodeId, staleCutoff, claimLimit, oracleDialect);
            }
        }, "拉取待分发任务");
        if (jobIds == null || jobIds.isEmpty()) {
            return;
        }
        for (String jobId : jobIds) {
            if (trimToNull(jobId) == null) {
                continue;
            }
            if (localFutureMap.containsKey(jobId)) {
                continue;
            }
            if (!claimPendingJob(jobId, staleCutoff, now)) {
                continue;
            }
            try {
                submitLocalExecution(jobId);
            } catch (RejectedExecutionException ex) {
                // 队列短时占满，等待下一轮分发
                alertService.error("EXECUTOR_QUEUE_FULL", "任务分发失败，执行队列已满，jobId=" + jobId, ex);
                return;
            }
        }
    }

    /**
     * 抢占任务归属权，避免多实例重复执行。
     */
    private boolean claimPendingJob(String jobId, long staleCutoff, long now) {
        return withRetry(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return jobStateRepository.claimPendingJob(jobId, staleCutoff, now, nodeId, PENDING);
            }
        }, "抢占待执行任务");
    }

    /**
     * 回收超时 RUNNING 任务，防止任务永久挂起。
     */
    private void recoverStaleRunningJobs() {
        if (staleRunningMs <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - staleRunningMs;
        Integer recovered = withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jobStateRepository.recoverStaleRunningJobs(now, cutoff, nodeId, RUNNING, FAILED);
            }
        }, "回收超时运行任务");
        if (recovered != null && recovered.intValue() > 0) {
            alertService.warn("JOB_OWNER_TIMEOUT", "检测到超时运行任务被系统回收，count=" + recovered);
        }
    }

    /**
     * 将任务提交到本地执行器。
     */
    private void submitLocalExecution(final String jobId) {
        localFutureMap.computeIfAbsent(jobId, new java.util.function.Function<String, Future<?>>() {
            @Override
            public Future<?> apply(String k) {
                return executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        executeJob(k);
                    }
                });
            }
        });
    }

    /**
     * 执行引擎并按配置重试。
     */
    private EngineRunResult runEngineWithRetry(String jobId, EngineRunRequest runRequest) {
        Exception last = null;
        for (int attempt = 1; attempt <= engineRetryMaxAttempts; attempt++) {
            if (isCancelRequested(jobId)) {
                throw new IllegalStateException("任务已取消");
            }
            touchRunningHeartbeat(jobId);
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

    /**
     * 更新运行心跳，避免误回收正在执行的任务。
     */
    private void touchRunningHeartbeat(String jobId) {
        long now = System.currentTimeMillis();
        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.touchRunningHeartbeat(jobId, now, nodeId, RUNNING);
                return null;
            }
        }, "更新任务运行心跳");
    }

    /**
     * 判定引擎异常是否可重试。
     */
    private static boolean isTransientEngineError(Exception ex) {
        if (ex instanceof IllegalArgumentException) {
            return false;
        }
        String message = buildErrorMessage(ex).toLowerCase();
        return message.contains("timeout")
                || message.contains("temporarily")
                || message.contains("connection")
                || message.contains("reset")
                || message.contains("busy")
                || message.contains("unavailable");
    }

    /**
     * 引擎重试等待。
     */
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

    private void cleanupIfNeeded() {
        long count = submitCounter.incrementAndGet();
        if (count % cleanupEverySubmit != 0L) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L;
        deleteOldTerminalJobs(cutoff);
    }

    private int countPendingJobs() {
        return withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jobStateRepository.countPendingJobs(PENDING);
            }
        }, "统计待处理任务");
    }

    private void checkAlertThresholds(int pendingJobs, int queueSize) {
        if (pendingJobs >= pendingJobAlertThreshold) {
            alertService.warn("JOB_BACKLOG_HIGH", "待处理任务积压过高，pendingJobs=" + pendingJobs + ", threshold=" + pendingJobAlertThreshold);
        }
        if (queueSize >= executorQueueAlertThreshold) {
            alertService.warn("EXECUTOR_QUEUE_HIGH", "执行队列占用过高，queueSize=" + queueSize + ", threshold=" + executorQueueAlertThreshold);
        }
    }

    private void bindJobContext(AsyncJobEntity job) {
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

    private void verifyJobSchema() {
        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.verifyJobSchema();
                return null;
            }
        }, "校验任务表结构");
    }

    private void insertJob(AsyncJobEntity create) {
        jobStateRepository.insertJob(create, nodeId);
    }

    private boolean markRunning(String jobId, long start) {
        return withRetry(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return jobStateRepository.markRunning(jobId, start, nodeId, RUNNING, PENDING);
            }
        }, "更新运行状态");
    }

    private void persistRunResult(String jobId, EngineRunResult runResult, long finish, String payloadJson) {
        AsyncJobEntity running = requireJob(jobId);
        long elapsed = calcElapsed(running.startedAt, finish, runResult.getElapsedMs());
        runResult.setElapsedMs(elapsed);
        final String finalStatus = runResult.isSuccess() ? SUCCESS : FAILED;
        final String safeErrorMessage = truncateForErrorMessage(runResult.getErrorMessage());
        final String resultJson = isWhatifPayload(payloadJson)
                ? JSON.toJSONString(runResult.getData(), JSONWriter.Feature.WriteBigDecimalAsPlain)
                : null;
        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.persistRunResult(
                        jobId, RUNNING, finalStatus, finish, elapsed,
                        runResult.isSuccess(), runResult.getErrorCode(), safeErrorMessage, resultJson);
                return null;
            }
        }, "持久化任务结果");
    }

    private void persistRunFailure(String jobId, String requestId, String engineCode, String message, long finish) {
        AsyncJobEntity running = requireJob(jobId);
        long elapsed = calcElapsed(running.startedAt, finish, 0L);
        final String safeMessage = truncateForErrorMessage(message);
        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.persistRunFailure(
                        jobId, RUNNING, FAILED, finish, elapsed, "ENGINE_EXECUTION_ERROR", safeMessage);
                return null;
            }
        }, "持久化失败结果");
    }

    private void markRejected(String jobId, String reason) {
        final long now = System.currentTimeMillis();
        final String safeReason = truncateForErrorMessage(reason);
        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.markRejected(jobId, PENDING, FAILED, now, safeReason);
                return null;
            }
        }, "更新队列拒绝状态");
    }

    private AsyncJobEntity markCancelRequestedIfNeeded(String jobId, long now) {
        AsyncJobEntity current = requireJob(jobId);
        if (!PENDING.equals(current.status) && !RUNNING.equals(current.status)) {
            return current;
        }
        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.markCancelRequested(jobId, now, PENDING, RUNNING);
                return null;
            }
        }, "更新取消标记");
        return requireJob(jobId);
    }

    private void markCancelled(String jobId, long now, String expectedStatus) {
        AsyncJobEntity job = requireJob(jobId);
        long elapsed = calcElapsed(job.startedAt, now, 0L);

        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.markCancelled(jobId, expectedStatus, CANCELLED, now, elapsed);
                return null;
            }
        }, "更新取消状态");
    }

    private boolean isCancelRequested(String jobId) {
        AsyncJobEntity job = findByJobId(jobId);
        return job != null && job.cancelRequested;
    }

    private void deleteOldTerminalJobs(long cutoff) {
        withRetry(new Callable<Void>() {
            @Override
            public Void call() {
                jobStateRepository.deleteOldTerminalJobs(cutoff);
                return null;
            }
        }, "清理历史任务");
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
        return withRetry(new Callable<AsyncJobEntity>() {
            @Override
            public AsyncJobEntity call() {
                return jobStateRepository.findByJobId(jobId);
            }
        }, "查询任务");
    }

    private AsyncJobEntity findByIdempotencyKey(String idempotencyKey) {
        return withRetry(new Callable<AsyncJobEntity>() {
            @Override
            public AsyncJobEntity call() {
                return jobStateRepository.findByIdempotencyKey(idempotencyKey);
            }
        }, "查询幂等任务");
    }

    private JobSubmitResult toSubmitResult(AsyncJobEntity job, boolean reused, String message) {
        JobSubmitResult result = new JobSubmitResult();
        result.setJobId(job.jobId);
        result.setRequestId(job.requestId);
        result.setEngineCode(defaultEngineCode(job.engineCode));
        result.setStatus(toJobStatus(job.status));
        result.setReused(reused);
        result.setMessage(message);
        result.setSubmittedAt(job.createdAt);
        result.setPollAfterMs(pollAfterMs);
        result.setDetailUrl(buildDetailUrl(job.jobId));
        result.setResultUrl(buildResultUrl(job.jobId));
        result.setCancelUrl(buildCancelUrl(job.jobId));
        return result;
    }

    private JobDetailResult toDetail(AsyncJobEntity job) {
        JobDetailResult detail = new JobDetailResult();
        detail.setJobId(job.jobId);
        detail.setRequestId(job.requestId);
        detail.setEngineCode(defaultEngineCode(job.engineCode));
        detail.setStatus(toJobStatus(job.status));
        detail.setCreatedAt(job.createdAt);
        detail.setStartedAt(job.startedAt);
        detail.setFinishedAt(job.finishedAt);
        detail.setDone(isTerminal(job.status));
        detail.setSuccess(SUCCESS.equals(job.status));
        detail.setElapsedMs(resolveElapsed(job));
        detail.setCancelRequested(job.cancelRequested);
        detail.setResultReady(isTerminal(job.status));
        detail.setPollAfterMs(pollAfterMs);
        detail.setErrorCode(job.errorCode);
        detail.setErrorMessage(job.errorMessage);
        detail.setDetailUrl(buildDetailUrl(job.jobId));
        detail.setResultUrl(buildResultUrl(job.jobId));
        detail.setCancelUrl(buildCancelUrl(job.jobId));
        return detail;
    }

    private EngineRunResult buildResult(AsyncJobEntity job) {
        EngineRunResult result = new EngineRunResult();
        result.setRequestId(job.requestId);
        result.setEngineCode(defaultEngineCode(job.engineCode));
        result.setSuccess(SUCCESS.equals(job.status));
        result.setElapsedMs(resolveElapsed(job) == null ? 0L : resolveElapsed(job));
        result.setErrorCode(job.errorCode);
        result.setErrorMessage(job.errorMessage);
        result.setData(parseJsonSafely(job.resultJson));
        return result;
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

    private <T> T runInTransaction(final Callable<T> callable, final String action) {
        return withRetry(new Callable<T>() {
            @Override
            public T call() {
                return transactionTemplate.execute(new TransactionCallback<T>() {
                    @Override
                    public T doInTransaction(TransactionStatus status) {
                        try {
                            return callable.call();
                        } catch (RuntimeException ex) {
                            throw ex;
                        } catch (Exception ex) {
                            throw new IllegalStateException(action + "失败: " + ex.getMessage(), ex);
                        }
                    }
                });
            }
        }, action);
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
            if (state != null) {
                if (state.startsWith("08")) {
                    return true;
                }
                if ("40001".equals(state) || "40P01".equals(state) || "HYT00".equals(state)) {
                    return true;
                }
            }
        }
        String message = root == null ? ex.getMessage() : root.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("deadlock")
                || lower.contains("lock wait")
                || lower.contains("timeout")
                || lower.contains("temporarily")
                || lower.contains("connection reset")
                || lower.contains("communications link failure");
    }

    private static boolean isDuplicateKey(DataAccessException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        Throwable root = ex.getMostSpecificCause();
        if (root instanceof SQLException) {
            String state = ((SQLException) root).getSQLState();
            if ("23505".equals(state)
                    || "23000".equals(state)
                    || "42111".equals(state)
                    || "42S11".equals(state)) {
                return true;
            }
        }
        String message = root == null ? ex.getMessage() : root.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("duplicate")
                || lower.contains("unique")
                || lower.contains("already exists");
    }

    /**
     * 检测底层数据库是否 Oracle。
     * 检测失败时默认按 MySQL 方言执行（LIMIT）。
     */
    private boolean detectOracleDialect() {
        if (jdbcTemplate.getDataSource() == null) {
            return false;
        }
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            if (metaData == null) {
                return false;
            }
            String dbName = metaData.getDatabaseProductName();
            return dbName != null && dbName.toLowerCase().contains("oracle");
        } catch (Exception ex) {
            log.warn("检测数据库方言失败，默认使用 MySQL LIMIT 语法", ex);
            return false;
        }
    }

    private static long calcElapsed(Long startedAt, long finishAt, long fallback) {
        if (startedAt == null) {
            return Math.max(0L, fallback);
        }
        return Math.max(0L, finishAt - startedAt);
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
        return value == null ? MrCalcEngineAdapter.CODE : value;
    }

    private String buildDetailUrl(String jobId) {
        return jobApiBasePath + "/" + jobId;
    }

    private String buildResultUrl(String jobId) {
        return jobApiBasePath + "/" + jobId + "/result";
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

    private static boolean isWhatifPayload(String payloadJson) {
        try {
            JSONObject payload = JSON.parseObject(payloadJson);
            if (payload == null) {
                return false;
            }
            String runMode = firstNonBlank(
                    payload.getString("run_mode"),
                    payload.getString("runMode"));
            if (runMode != null && "WHATIF".equalsIgnoreCase(runMode.trim())) {
                return true;
            }
            String batchId = resolveBatchId(payload);
            return batchId != null && batchId.toLowerCase(java.util.Locale.ROOT).startsWith("stresswhatif_");
        } catch (Exception ex) {
            return false;
        }
    }

    private static String resolveBatchId(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        String batchId = firstNonBlank(
                payload.getString("batch_id"),
                payload.getString("batchId"));
        if (batchId != null) {
            return batchId;
        }
        JSONObject batchMeta = payload.getJSONObject("batch_meta");
        batchId = firstNonBlank(
                batchMeta == null ? null : batchMeta.getString("batch_id"),
                batchMeta == null ? null : batchMeta.getString("batchId"));
        if (batchId != null) {
            return batchId;
        }
        JSONObject scenarioRef = payload.getJSONObject("scenario_ref");
        return firstNonBlank(
                scenarioRef == null ? null : scenarioRef.getString("batch_id"),
                scenarioRef == null ? null : scenarioRef.getString("batchId"));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String safe = trimToNull(value);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    private static Object parseJsonSafely(String raw) {
        String txt = trimToNull(raw);
        if (txt == null) {
            return null;
        }
        try {
            return JSON.parse(txt);
        } catch (Exception ignore) {
            return txt;
        }
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

    private static JobStatus toJobStatus(String status) {
        if (status == null) {
            return JobStatus.FAILED;
        }
        try {
            return JobStatus.valueOf(status);
        } catch (Exception ignore) {
            return JobStatus.FAILED;
        }
    }

    private static boolean isTerminal(String status) {
        return SUCCESS.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    /**
     * 异步线程命名工厂。
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final boolean daemon;
        private final AtomicLong seq = new AtomicLong(1L);

        private NamedThreadFactory(String prefix) {
            this(prefix, false);
        }

        private NamedThreadFactory(String prefix, boolean daemon) {
            this.prefix = prefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread t = new Thread(runnable);
            t.setName(prefix + seq.getAndIncrement());
            t.setDaemon(daemon);
            return t;
        }
    }
}
