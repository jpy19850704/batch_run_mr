package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;
import java.sql.ResultSet;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 DataSource/JdbcTemplate 的异步任务服务，支持多实例共享任务状态。
 */
@Service
public class AsyncJobService {
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private static final String RESULT_PERSIST_FAILED = "RESULT_PERSIST_FAILED";
    private static final Logger log = LoggerFactory.getLogger(AsyncJobService.class);

    private static final RowMapper<DbJob> DB_JOB_ROW_MAPPER = new RowMapper<DbJob>() {
        @Override
        public DbJob mapRow(ResultSet rs, int rowNum) throws SQLException {
            DbJob job = new DbJob();
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

    private final EngineOrchestratorService orchestratorService;
    private final PricingResultPersistService pricingResultPersistService;
    private final BatchResultFileService batchResultFileService;
    private final AlertService alertService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService dispatcherExecutor;
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

    public AsyncJobService(
            EngineOrchestratorService orchestratorService,
            PricingResultPersistService pricingResultPersistService,
            BatchResultFileService batchResultFileService,
            AlertService alertService,
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
            @Value("${mr.job.dispatcher.enabled:true}") boolean dispatcherEnabled,
            @Value("${mr.job.dispatcher.interval-ms:500}") long dispatchIntervalMs,
            @Value("${mr.job.dispatcher.claim-batch-size:50}") int claimBatchSize,
            @Value("${mr.job.dispatcher.stale-pending-ms:30000}") long stalePendingMs,
            @Value("${mr.job.dispatcher.stale-running-ms:600000}") long staleRunningMs,
            @Value("${mr.job.executor.shutdown-await-seconds:30}") long shutdownAwaitSeconds,
            @Value("${mr.job.engine.retry.max-attempts:2}") int engineRetryMaxAttempts,
            @Value("${mr.job.engine.retry.backoff-ms:200}") long engineRetryBackoffMs,
            @Value("${mr.job.client.poll-after-ms:500}") long pollAfterMs,
            @Value("${mr.job.api.base-path:/api/v1/jobs}") String jobApiBasePath,
            @Value("${mr.alert.pending-job-threshold:200}") int pendingJobAlertThreshold,
            @Value("${mr.alert.executor-queue-threshold:800}") int executorQueueAlertThreshold
    ) {
        this.orchestratorService = orchestratorService;
        this.pricingResultPersistService = pricingResultPersistService;
        this.batchResultFileService = batchResultFileService;
        this.alertService = alertService;
        this.jdbcTemplate = jdbcTemplate;
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
        this.dispatcherExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("mr-job-dispatcher-", true)
        );

        verifyJobSchema();
        if (this.dispatcherEnabled) {
            startDispatcher();
        }
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
            DbJob existed = findByIdempotencyKey(idempotencyKey);
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

        final DbJob create = new DbJob();
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
                DbJob existed = findByIdempotencyKey(create.idempotencyKey);
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

        cleanupIfNeeded();
        return toSubmitResult(create, false, "任务已提交");
    }

    public JobDetailResult getDetail(String jobId) {
        DbJob job = requireJob(jobId);
        return toDetail(job);
    }

    public EngineRunResult getResult(String jobId) {
        DbJob job = requireJob(jobId);
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

        DbJob job = runInTransaction(new Callable<DbJob>() {
            @Override
            public DbJob call() {
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
        boolean dispatcherReady = !dispatcherEnabled || !dispatcherExecutor.isShutdown();

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
                DbJob current = findByJobId(jobId);
                if (current != null && PENDING.equals(current.status) && current.cancelRequested) {
                    markCancelled(jobId, start, PENDING);
                    handleTerminalSideEffects(jobId, current.requestId, current.payloadJson, current.engineCode, null);
                }
                return;
            }

            // 【架构备注】当前从 DB 读取完整 payload_json（大 TEXT 列），后续改为：
            // String payloadJson = redisTemplate.opsForValue().get("job:payload:" + jobId)
            // 拿到的就是 buildPayload() 组装好的完整 JSON，直传 Calc 无需拼接。
            // Worker 执行完成后主动 DEL key 释放 Redis 内存。
            DbJob running = requireJob(jobId);
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
                persistRunResult(jobId, runResult, finish);
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
        try {
            if (runResult != null && runResult.isSuccess() && MrCalcEngineAdapter.CODE.equalsIgnoreCase(defaultEngineCode(engineCode))) {
                pricingResultPersistService.persistJobResult(requestId, jobId, payloadJson, runResult);
            }
        } catch (Exception ex) {
            markResultPersistFailed(jobId, ex);
        }

        try {
            batchResultFileService.tryWriteSnapshotForJob(jobId);
        } catch (Exception ex) {
            log.error("批次结果快照生成失败，jobId={}", jobId, ex);
        }
    }

    private void markResultPersistFailed(String jobId, Exception cause) {
        String message = buildErrorMessage(cause);
        log.error("任务结果明细落库失败，jobId={}", jobId, cause);
        alertService.error("JOB_RESULT_PERSIST_FAILED", "任务结果明细落库失败，jobId=" + jobId, cause);
        try {
            long now = System.currentTimeMillis();
            String sql = "UPDATE MR_ASYNC_JOB SET status=?, success_flag=0, error_code=?, error_message=?, updated_at=? "
                    + "WHERE job_id=? AND status=?";
            withRetry(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return jdbcTemplate.update(sql, FAILED, RESULT_PERSIST_FAILED, message, now, jobId, SUCCESS);
                }
            }, "回写结果落库失败状态");
        } catch (Exception markEx) {
            log.error("任务结果落库失败状态回写失败，jobId={}", jobId, markEx);
            alertService.error("JOB_RESULT_PERSIST_MARK_FAILED", "任务结果落库失败状态回写失败，jobId=" + jobId, markEx);
        }
    }

    /**
     * 启动本地分发器，周期性抢占并执行待处理任务。
     */
    private void startDispatcher() {
        dispatcherExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                runDispatchLoop();
            }
        }, dispatchIntervalMs, dispatchIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 分发循环：处理 PENDING 任务，并可选回收超时 RUNNING 任务。
     */
    private void runDispatchLoop() {
        if (shuttingDown) {
            return;
        }
        try {
            dispatchPendingJobs();
            recoverStaleRunningJobs();
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
        String sql = "SELECT job_id FROM MR_ASYNC_JOB "
                + "WHERE status=? AND cancel_requested=0 "
                + "AND (owner_node=? OR owner_node IS NULL OR owner_node='' OR updated_at<=?) "
                + "ORDER BY created_at FETCH FIRST " + claimLimit + " ROWS ONLY";
        List<String> jobIds = withRetry(new Callable<List<String>>() {
            @Override
            public List<String> call() {
                return jdbcTemplate.queryForList(sql, String.class, PENDING, nodeId, staleCutoff);
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
        String sql = "UPDATE MR_ASYNC_JOB SET owner_node=?, updated_at=? "
                + "WHERE job_id=? AND status=? AND cancel_requested=0 "
                + "AND (owner_node=? OR owner_node IS NULL OR owner_node='' OR updated_at<=?)";
        Integer updated = withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, nodeId, now, jobId, PENDING, nodeId, staleCutoff);
            }
        }, "抢占待执行任务");
        return updated != null && updated > 0;
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
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=CASE WHEN started_at IS NULL THEN 0 ELSE ? - started_at END, "
                + "success_flag=0, error_code='OWNER_TIMEOUT', error_message='任务执行超时未完成，系统自动回收', updated_at=? "
                + "WHERE status=? AND updated_at<=? AND (owner_node IS NULL OR owner_node<>?)";
        Integer recovered = withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, FAILED, now, now, now, RUNNING, cutoff, nodeId);
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
        String sql = "UPDATE MR_ASYNC_JOB SET updated_at=?, owner_node=? WHERE job_id=? AND status=?";
        withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, now, nodeId, jobId, RUNNING);
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
        Integer count = withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.queryForObject("SELECT COUNT(1) FROM MR_ASYNC_JOB WHERE status=?", Integer.class, PENDING);
            }
        }, "统计待处理任务");
        return count == null ? 0 : count.intValue();
    }

    private void checkAlertThresholds(int pendingJobs, int queueSize) {
        if (pendingJobs >= pendingJobAlertThreshold) {
            alertService.warn("JOB_BACKLOG_HIGH", "待处理任务积压过高，pendingJobs=" + pendingJobs + ", threshold=" + pendingJobAlertThreshold);
        }
        if (queueSize >= executorQueueAlertThreshold) {
            alertService.warn("EXECUTOR_QUEUE_HIGH", "执行队列占用过高，queueSize=" + queueSize + ", threshold=" + executorQueueAlertThreshold);
        }
    }

    private void bindJobContext(DbJob job) {
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
                jdbcTemplate.queryForList(
                        "SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at "
                                + "FROM MR_ASYNC_JOB WHERE 1=0");
                return null;
            }
        }, "校验任务表结构");
    }

    // 【架构备注】payload_json 当前直接写入关系型 DB，大批量时成为 I/O 瓶颈。
    // 后续改造：buildPayload() 组装好的完整 payload 直接存 Redis，
    // redisTemplate.opsForValue().set("job:payload:" + jobId, payloadJson, 2, TimeUnit.HOURS)
    // 此处 INSERT 去掉 payload_json 列，只写元数据。
    private void insertJob(DbJob create) {
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

    private boolean markRunning(String jobId, long start) {
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, started_at=?, updated_at=?, owner_node=? "
                + "WHERE job_id=? AND status=? AND cancel_requested=0";
        Integer updated = withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, RUNNING, start, start, nodeId, jobId, PENDING);
            }
        }, "更新运行状态");
        return updated != null && updated > 0;
    }

    private void persistRunResult(String jobId, EngineRunResult runResult, long finish) {
        DbJob running = requireJob(jobId);
        long elapsed = calcElapsed(running.startedAt, finish, runResult.getElapsedMs());
        runResult.setElapsedMs(elapsed);
        String finalStatus = runResult.isSuccess() ? SUCCESS : FAILED;
        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=?, success_flag=?, error_code=?, error_message=?, updated_at=? "
                + "WHERE job_id=? AND status=?";

        withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(
                        sql,
                        finalStatus,
                        finish,
                        elapsed,
                        runResult.isSuccess() ? 1 : 0,
                        runResult.getErrorCode(),
                        runResult.getErrorMessage(),
                        finish,
                        jobId,
                        RUNNING
                );
            }
        }, "持久化任务结果");
    }

    private void persistRunFailure(String jobId, String requestId, String engineCode, String message, long finish) {
        DbJob running = requireJob(jobId);
        long elapsed = calcElapsed(running.startedAt, finish, 0L);

        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=?, success_flag=0, error_code=?, error_message=?, updated_at=? "
                + "WHERE job_id=? AND status=?";
        withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(
                        sql,
                        FAILED,
                        finish,
                        elapsed,
                        "ENGINE_EXECUTION_ERROR",
                        message,
                        finish,
                        jobId,
                        RUNNING
                );
            }
        }, "持久化失败结果");
    }

    private void markRejected(String jobId, String reason) {
        long now = System.currentTimeMillis();

        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=0, success_flag=0, error_code=?, error_message=?, updated_at=? "
                + "WHERE job_id=? AND status=?";
        withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, FAILED, now, "QUEUE_REJECTED", reason, now, jobId, PENDING);
            }
        }, "更新队列拒绝状态");
    }

    private DbJob markCancelRequestedIfNeeded(String jobId, long now) {
        DbJob current = requireJob(jobId);
        if (!PENDING.equals(current.status) && !RUNNING.equals(current.status)) {
            return current;
        }
        String sql = "UPDATE MR_ASYNC_JOB SET cancel_requested=1, updated_at=? WHERE job_id=? AND status IN (?, ?)";
        withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, now, jobId, PENDING, RUNNING);
            }
        }, "更新取消标记");
        return requireJob(jobId);
    }

    private void markCancelled(String jobId, long now, String expectedStatus) {
        DbJob job = requireJob(jobId);
        long elapsed = calcElapsed(job.startedAt, now, 0L);

        String sql = "UPDATE MR_ASYNC_JOB SET status=?, finished_at=?, elapsed_ms=?, success_flag=0, error_code='CANCELLED', error_message='任务已取消', updated_at=? "
                + "WHERE job_id=? AND status=?";
        withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, CANCELLED, now, elapsed, now, jobId, expectedStatus);
            }
        }, "更新取消状态");
    }

    private boolean isCancelRequested(String jobId) {
        DbJob job = findByJobId(jobId);
        return job != null && job.cancelRequested;
    }

    private void deleteOldTerminalJobs(long cutoff) {
        String sql = "DELETE FROM MR_ASYNC_JOB WHERE status IN ('SUCCESS','FAILED','CANCELLED') AND finished_at IS NOT NULL AND finished_at < ?";
        withRetry(new Callable<Integer>() {
            @Override
            public Integer call() {
                return jdbcTemplate.update(sql, cutoff);
            }
        }, "清理历史任务");
    }

    private DbJob requireJob(String rawJobId) {
        String jobId = trimToNull(rawJobId);
        if (jobId == null) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        DbJob job = findByJobId(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        return job;
    }

    private DbJob findByJobId(String jobId) {
        String sql = "SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at "
                + "FROM MR_ASYNC_JOB WHERE job_id=?";
        List<DbJob> rows = withRetry(new Callable<List<DbJob>>() {
            @Override
            public List<DbJob> call() {
                return jdbcTemplate.query(sql, DB_JOB_ROW_MAPPER, jobId);
            }
        }, "查询任务");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    private DbJob findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at "
                + "FROM MR_ASYNC_JOB WHERE idempotency_key=?";
        List<DbJob> rows = withRetry(new Callable<List<DbJob>>() {
            @Override
            public List<DbJob> call() {
                return jdbcTemplate.query(sql, DB_JOB_ROW_MAPPER, idempotencyKey);
            }
        }, "查询幂等任务");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    private JobSubmitResult toSubmitResult(DbJob job, boolean reused, String message) {
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

    private JobDetailResult toDetail(DbJob job) {
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

    private EngineRunResult buildResult(DbJob job) {
        EngineRunResult result = new EngineRunResult();
        result.setRequestId(job.requestId);
        result.setEngineCode(defaultEngineCode(job.engineCode));
        result.setSuccess(SUCCESS.equals(job.status));
        result.setElapsedMs(resolveElapsed(job) == null ? 0L : resolveElapsed(job));
        result.setErrorCode(job.errorCode);
        result.setErrorMessage(job.errorMessage);
        return result;
    }

    private Long resolveElapsed(DbJob job) {
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

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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
            return "/api/v1/jobs";
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
            return message;
        }
        return ex.getClass().getSimpleName();
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
     * 数据库任务行映射对象。
     */
    private static class DbJob {
        private String jobId;
        private String requestId;
        private String engineCode;
        private String payloadJson;
        private String status;
        private long createdAt;
        private Long startedAt;
        private Long finishedAt;
        private Long elapsedMs;
        private Integer successFlag;
        private String errorCode;
        private String errorMessage;
        private String idempotencyKey;
        private String traceId;
        private String clientId;
        private String userId;
        private String userName;
        private String sourceSystem;
        private boolean cancelRequested;
        private String ownerNode;
        private long updatedAt;
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
