package com.zcyh.mr.springboot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步任务本地执行与多实例分发器。
 */
@Service
class AsyncJobDispatcher {
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String FAILED = "FAILED";
    private static final Logger log = LoggerFactory.getLogger(AsyncJobDispatcher.class);

    private final AsyncJobStateRepository jobStateRepository;
    private final AsyncJobExecutionService executionService;
    private final AlertService alertService;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService dispatcherExecutor;
    private final ConcurrentMap<String, Future<?>> localFutureMap = new ConcurrentHashMap<>();
    private final boolean dispatcherEnabled;
    private final String nodeId;
    private final long dispatchIntervalMs;
    private final int claimBatchSize;
    private final long stalePendingMs;
    private final long staleRunningMs;
    private final long shutdownAwaitSeconds;
    private final int pendingJobAlertThreshold;
    private final int executorQueueAlertThreshold;
    private volatile ScheduledFuture<?> dispatcherFuture;
    private volatile boolean shuttingDown;

    AsyncJobDispatcher(
            AsyncJobStateRepository jobStateRepository,
            AsyncJobExecutionService executionService,
            AlertService alertService,
            @Value("${mr.job.executor.core-size:4}") int coreSize,
            @Value("${mr.job.executor.max-size:16}") int maxSize,
            @Value("${mr.job.executor.queue-capacity:1000}") int queueCapacity,
            @Value("${mr.job.store.node-id:node-default}") String nodeId,
            @Value("${mr.job.dispatcher.enabled:false}") boolean dispatcherEnabled,
            @Value("${mr.job.dispatcher.interval-ms:500}") long dispatchIntervalMs,
            @Value("${mr.job.dispatcher.claim-batch-size:50}") int claimBatchSize,
            @Value("${mr.job.dispatcher.stale-pending-ms:30000}") long stalePendingMs,
            @Value("${mr.job.dispatcher.stale-running-ms:600000}") long staleRunningMs,
            @Value("${mr.job.executor.shutdown-await-seconds:30}") long shutdownAwaitSeconds,
            @Value("${mr.alert.pending-job-threshold:200}") int pendingJobAlertThreshold,
            @Value("${mr.alert.executor-queue-threshold:800}") int executorQueueAlertThreshold) {
        this.jobStateRepository = jobStateRepository;
        this.executionService = executionService;
        this.alertService = alertService;
        this.nodeId = nodeId;
        this.dispatcherEnabled = dispatcherEnabled;
        this.dispatchIntervalMs = Math.max(100L, dispatchIntervalMs);
        this.claimBatchSize = Math.max(1, claimBatchSize);
        this.stalePendingMs = Math.max(1000L, stalePendingMs);
        this.staleRunningMs = Math.max(0L, staleRunningMs);
        this.shutdownAwaitSeconds = Math.max(1L, shutdownAwaitSeconds);
        this.pendingJobAlertThreshold = Math.max(1, pendingJobAlertThreshold);
        this.executorQueueAlertThreshold = Math.max(1, executorQueueAlertThreshold);

        int safeCore = Math.max(1, coreSize);
        int safeMax = Math.max(safeCore, maxSize);
        int safeQueue = Math.max(100, queueCapacity);
        this.executor = new ThreadPoolExecutor(
                safeCore, safeMax, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(safeQueue),
                new NamedThreadFactory("mr-job-worker-", false),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.dispatcherExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("mr-job-dispatcher-", true));
    }

    void submit(String jobId) {
        localFutureMap.computeIfAbsent(jobId, key -> executor.submit(() -> {
            try {
                executionService.execute(key);
            } finally {
                localFutureMap.remove(key);
            }
        }));
    }

    boolean cancelLocal(String jobId) {
        Future<?> future = localFutureMap.get(jobId);
        return future != null && future.cancel(true);
    }

    void ensureRunning() {
        if (!dispatcherEnabled || shuttingDown
                || dispatcherFuture != null && !dispatcherFuture.isDone()) {
            return;
        }
        start();
    }

    Map<String, Object> readinessSnapshot() {
        int pendingJobs = countPendingJobs();
        int queueSize = executor.getQueue().size();
        checkAlertThresholds(pendingJobs, queueSize);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executorReady", !shuttingDown && executor.getQueue().remainingCapacity() > 0);
        data.put("dispatcherReady", !dispatcherExecutor.isShutdown());
        data.put("activeThreads", executor.getActiveCount());
        data.put("maxThreads", executor.getMaximumPoolSize());
        data.put("queueSize", queueSize);
        data.put("queueRemaining", executor.getQueue().remainingCapacity());
        data.put("pendingJobs", pendingJobs);
        data.put("nodeId", nodeId);
        return data;
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        stop();
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

    private synchronized void start() {
        if (shuttingDown || dispatcherFuture != null && !dispatcherFuture.isDone()) {
            return;
        }
        log.info("启动任务分发器，轮询间隔={}ms", dispatchIntervalMs);
        dispatcherFuture = dispatcherExecutor.scheduleWithFixedDelay(
                this::runDispatchLoop, 0, dispatchIntervalMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void stop() {
        if (dispatcherFuture != null && !dispatcherFuture.isDone()) {
            dispatcherFuture.cancel(false);
            log.info("任务分发器已停止（无待处理任务）");
        }
        dispatcherFuture = null;
    }

    private void runDispatchLoop() {
        if (shuttingDown) {
            return;
        }
        try {
            dispatchPendingJobs();
            recoverStaleRunningJobs();
            if (localFutureMap.isEmpty() && countPendingJobs() == 0) {
                stop();
            }
        } catch (Exception ex) {
            alertService.error("JOB_DISPATCH_FAILED", "任务分发线程执行异常", ex);
        }
    }

    void dispatchPendingJobs() {
        int claimLimit = Math.min(claimBatchSize, Math.max(0, executor.getQueue().remainingCapacity()));
        checkAlertThresholds(countPendingJobs(), executor.getQueue().size());
        if (claimLimit <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long staleCutoff = now - stalePendingMs;
        List<String> jobIds = jobStateRepository.listPendingJobs(
                PENDING, nodeId, staleCutoff, claimLimit);
        if (jobIds == null || jobIds.isEmpty()) {
            return;
        }
        for (String jobId : jobIds) {
            if (trimToNull(jobId) == null || localFutureMap.containsKey(jobId)
                    || !jobStateRepository.claimPendingJob(jobId, staleCutoff, now, nodeId, PENDING)) {
                continue;
            }
            try {
                submit(jobId);
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                alertService.error("EXECUTOR_QUEUE_FULL",
                        "任务分发失败，执行队列已满，jobId=" + jobId, ex);
                return;
            }
        }
    }

    private void recoverStaleRunningJobs() {
        if (staleRunningMs <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        int recovered = jobStateRepository.recoverStaleRunningJobs(
                now, now - staleRunningMs, nodeId, RUNNING, FAILED);
        if (recovered > 0) {
            alertService.warn("JOB_OWNER_TIMEOUT", "检测到超时运行任务被系统回收，count=" + recovered);
        }
    }

    private int countPendingJobs() {
        return jobStateRepository.countPendingJobs(PENDING);
    }

    private void checkAlertThresholds(int pendingJobs, int queueSize) {
        if (pendingJobs >= pendingJobAlertThreshold) {
            alertService.warn("JOB_BACKLOG_HIGH",
                    "待处理任务积压过高，pendingJobs=" + pendingJobs + ", threshold=" + pendingJobAlertThreshold);
        }
        if (queueSize >= executorQueueAlertThreshold) {
            alertService.warn("EXECUTOR_QUEUE_HIGH",
                    "执行队列占用过高，queueSize=" + queueSize + ", threshold=" + executorQueueAlertThreshold);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final boolean daemon;
        private final AtomicLong sequence = new AtomicLong(1L);

        private NamedThreadFactory(String prefix, boolean daemon) {
            this.prefix = prefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + sequence.getAndIncrement());
            thread.setDaemon(daemon);
            return thread;
        }
    }
}
