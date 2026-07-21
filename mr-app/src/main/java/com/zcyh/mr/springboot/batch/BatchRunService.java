package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.runtime.AlertService;

import com.zcyh.mr.springboot.runtime.ExecutionContextHolder;
import com.zcyh.mr.calc.scenario.CalcScenarioInputCache;
import com.zcyh.mr.springboot.batch.model.BatchDetailResult;
import com.zcyh.mr.springboot.batch.model.BatchExecutionResult;
import com.zcyh.mr.springboot.batch.model.BatchPatchRequest;
import com.zcyh.mr.springboot.batch.model.BatchRunRequest;
import com.zcyh.mr.springboot.batch.model.BatchRunResult;
import com.zcyh.mr.springboot.output.file.BatchResultStageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 批次总编排服务。
 * 仅负责串行编排批量工作流任务，不再承载具体计量细节。
 */
@Service
public class BatchRunService {
    private static final Logger log = LoggerFactory.getLogger(BatchRunService.class);
    private static final String DEFAULT_USER = "outer_service";
    private static final String RUN_MODE_WHATIF = "WHATIF";
    private static final Pattern DATE_8_PATTERN = Pattern.compile("^\\d{8}$");
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final DateTimeFormatter GENERATED_BATCH_TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmssSSS");
    private static final String ENGINE_CODE = "MR_CALC";
    private final AtomicReference<String> runningBatchId = new AtomicReference<String>();

    private final List<BatchRunTask> batchPrepareTasks;
    private final List<BatchRunTask> scenarioTasks;
    private final List<BatchRunTask> payloadTasks;
    private final List<BatchRunTask> calcTasks;
    private final BatchJobService batchJobService;
    private final AlertService alertService;
    private final TradeFilterResolver tradeFilterResolver;
    private final BatchResultStageService batchResultStageService;
    private final ExecutorService batchRunWorkflowExecutor;

    public BatchRunService(
            BatchMrCalcDetailCleanupTask detailCleanupTask,
            BatchPrepareTask prepareTask,
            BatchScenarioGenerateTask scenarioGenerateTask,
            BatchScenarioFileTask scenarioFileTask,
            BatchTradeLoadTask tradeLoadTask,
            BatchMarketDataLoadTask marketDataLoadTask,
            BatchMarketDataPersistTask marketDataPersistTask,
            BatchChunkBuildTask chunkBuildTask,
            BatchPayloadBuildTask payloadBuildTask,
            BatchScenarioInputLoadTask scenarioInputLoadTask,
            BatchCalcSubmitTask calcSubmitTask,
            BatchCalcWaitTask calcWaitTask,
            BatchDorisResultWriteTask resultWriteTask,
            BatchJobService batchJobService,
            AlertService alertService,
            TradeFilterResolver tradeFilterResolver,
            BatchResultStageService batchResultStageService,
            @Qualifier("batchRunWorkflowExecutor") ExecutorService batchRunWorkflowExecutor) {
        this.batchPrepareTasks = Arrays.<BatchRunTask>asList(detailCleanupTask, prepareTask);
        this.scenarioTasks = Arrays.<BatchRunTask>asList(
                scenarioGenerateTask,
                scenarioFileTask);
        this.payloadTasks = Arrays.<BatchRunTask>asList(
                tradeLoadTask,
                marketDataLoadTask,
                marketDataPersistTask,
                chunkBuildTask,
                payloadBuildTask,
                scenarioInputLoadTask);
        this.calcTasks = Arrays.<BatchRunTask>asList(
                calcSubmitTask,
                calcWaitTask,
                resultWriteTask);
        this.batchJobService = batchJobService;
        this.alertService = alertService;
        this.tradeFilterResolver = tradeFilterResolver;
        this.batchResultStageService = batchResultStageService;
        this.batchRunWorkflowExecutor = batchRunWorkflowExecutor;
    }

    public BatchRunResult run(BatchRunRequest request) {
        BatchRunWorkflowContext context = buildContext(request, false);
        submitWorkflow(context);
        return buildAcceptedResult(context);
    }

    public BatchExecutionResult patch(BatchPatchRequest request) {
        BatchRunWorkflowContext context = buildContext(request, true);
        submitWorkflow(context);
        return buildAcceptedPatchResult(context);
    }

    private void submitWorkflow(BatchRunWorkflowContext context) {
        claimBatchRunSlot(context);
        boolean submitted = false;
        try {
            initializeWorkflow(context);
            batchRunWorkflowExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runWorkflow(context);
                }
            });
            submitted = true;
        } catch (RejectedExecutionException ex) {
            batchJobService.markWorkflowFailed(context.getBatchId(), "批次工作流提交失败: 执行队列已满");
            alertService.error("BATCH_RUN_REJECTED", "批次工作流提交失败，batchId=" + context.getBatchId(), ex);
            throw new IllegalStateException("批次工作流提交失败，执行队列已满，请稍后重试");
        } finally {
            if (!submitted) {
                releaseBatchRunSlot(context);
            }
        }
    }

    private BatchRunWorkflowContext buildContext(BatchRunRequest request, boolean localRerun) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String user = trimToNull(request.getUser());
        String regularScenarioIdList = trimToNull(request.getRegularScenarioIdList());
        String varScenarioIdList = trimToNull(request.getVarScenarioIdList());
        String normalFullScenarioIdList = trimToNull(request.getNormalFullScenarioIdList());
        String normalReducedScenarioIdList = trimToNull(request.getNormalReducedScenarioIdList());
        String stressReducedScenarioIdList = trimToNull(request.getStressReducedScenarioIdList());
        String nmrfScenarioIdList = trimToNull(request.getNmrfScenarioIdList());
        String runMode = normalizeRunMode(request.getRunMode());
        String dataDate = normalizeDataDate(request.getDataDate());
        String externalBatchId = trimToNull(request.getBatchId());
        if (localRerun && externalBatchId == null) {
            throw new IllegalArgumentException("局部重跑 batchId 不能为空");
        }
        if (localRerun && Boolean.TRUE.equals(request.getPersistScenario())) {
            throw new IllegalArgumentException("局部重跑不允许 persistScenario=true");
        }
        String batchId = externalBatchId == null ? buildGeneratedBatchId(dataDate) : externalBatchId;
        validateBatchId(batchId);
        boolean persistResult = request.getPersistResult() == null
                ? externalBatchId != null
                : Boolean.TRUE.equals(request.getPersistResult());
        boolean frtbDisabled = Boolean.TRUE.equals(request.getFrtbDisable());

        BatchRunWorkflowContext context = new BatchRunWorkflowContext();
        context.setRequest(request);
        context.setBatchId(batchId);
        context.setDataDate(dataDate);
        context.setUser(user == null ? DEFAULT_USER : user);
        context.setRegularScenarioIdList(regularScenarioIdList);
        context.setVarScenarioIdList(varScenarioIdList);
        context.setNormalFullScenarioIdList(normalFullScenarioIdList);
        context.setNormalReducedScenarioIdList(normalReducedScenarioIdList);
        context.setStressReducedScenarioIdList(stressReducedScenarioIdList);
        context.setNmrfScenarioIdList(nmrfScenarioIdList);
        context.setScenarioMode(regularScenarioIdList != null
                || varScenarioIdList != null
                || normalFullScenarioIdList != null
                || normalReducedScenarioIdList != null
                || stressReducedScenarioIdList != null
                || nmrfScenarioIdList != null);
        context.setRunMode(runMode);
        context.setWhatifMode(RUN_MODE_WHATIF.equals(runMode));
        context.setExternalBatchIdProvided(externalBatchId != null);
        context.setPersistResult(persistResult);
        context.setCacheScenarioResult(Boolean.TRUE.equals(request.getCacheScenarioResult()));
        context.setFrtbDisabled(frtbDisabled);
        context.setLocalRerun(localRerun);
        context.setExecutionType(localRerun
                ? BatchResultStageService.EXECUTION_TYPE_PATCH
                : BatchResultStageService.EXECUTION_TYPE_BATCH);
        context.setExecutionId(localRerun
                ? buildPatchExecutionId()
                : BatchResultStageService.BATCH_EXECUTION_ID);
        if (localRerun) {
            List<String> instrumentIds = normalizeInstrumentIds(
                    ((BatchPatchRequest) request).getInstrumentIdList());
            if (instrumentIds.isEmpty()) {
                throw new IllegalArgumentException("instrumentIdList 不能为空");
            }
            context.setInstrumentIds(instrumentIds);
        }
        context.setTradeFilter(tradeFilterResolver.resolve(request.getTradeFilter()));
        return context;
    }

    private static List<String> normalizeInstrumentIds(List<String> instrumentIdList) {
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        if (instrumentIdList != null) {
            for (String instrumentId : instrumentIdList) {
                String safeInstrumentId = trimToNull(instrumentId);
                if (safeInstrumentId != null) {
                    normalized.add(safeInstrumentId);
                }
            }
        }
        return new ArrayList<String>(normalized);
    }

    private BatchRunResult buildAcceptedResult(BatchRunWorkflowContext context) {
        BatchRunResult result = new BatchRunResult();
        result.setBatchId(context.getBatchId());
        result.setDataDate(context.getDataDate());
        result.setUser(context.getUser());
        result.setMode(context.isScenarioMode() ? "SCENARIO" : "PRICING");
        result.setRunMode(context.getRunMode());
        result.setPersistResult(context.isPersistResult());
        result.setScenarioGenerated(false);
        result.setScenarioCount(0);
        result.setScenarioData(null);
        result.setBatchDetail(batchJobService.getDetail(context.getBatchId()));
        return result;
    }

    private BatchExecutionResult buildAcceptedPatchResult(BatchRunWorkflowContext context) {
        BatchExecutionResult result = new BatchExecutionResult();
        result.setBatchId(context.getBatchId());
        result.setExecutionId(context.getExecutionId());
        result.setRequestId(context.getBatchId());
        result.setEngineCode(ENGINE_CODE);
        result.setOpCode(context.isScenarioMode() ? "SCENARIO" : "PRICING");
        result.setDataDate(LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE).toString());
        result.setStatus("ACCEPTED");
        result.setTotalTrades(context.getInstrumentIds().size());
        result.setTotalJobs(0);
        result.setWeightBudget(batchJobService.getWeightBudget());
        result.setSubmittedAt(System.currentTimeMillis());
        result.setPollAfterMs(batchJobService.getPollAfterMs());
        result.setDetailUrl(batchJobService.getDetailUrl(context.getBatchId()));
        result.setMessage("批次局部重跑工作流已启动");
        return result;
    }

    private void initializeWorkflow(BatchRunWorkflowContext context) {
        ExecutionContextHolder.setBatchId(context.getBatchId());
        ExecutionContextHolder.setEngineCode(ENGINE_CODE);
        CalcScenarioInputCache.evictByBatchId(context.getBatchId());
        if (context.isLocalRerun()) {
            int firstJobSeqNo = batchJobService.prepareLocalRerun(
                    context.getBatchId(),
                    LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE));
            context.setFirstJobSeqNo(firstJobSeqNo);
            batchJobService.markWorkflowRunning(context.getBatchId(), "批次局部重跑工作流已启动");
            return;
        }
        batchJobService.initializeWorkflowBatch(
                context.getBatchId(),
                context.getBatchId(),
                ENGINE_CODE,
                context.isScenarioMode() ? "SCENARIO" : "PRICING",
                LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE),
                null,
                null,
                System.currentTimeMillis(),
                "批次工作流已启动"
        );
        batchResultStageService.resetBatch(context.getBatchId());
    }

    private void runWorkflow(BatchRunWorkflowContext context) {
        long workflowStart = System.nanoTime();
        ExecutionContextHolder.setBatchId(context.getBatchId());
        ExecutionContextHolder.setEngineCode(ENGINE_CODE);
        try {
            executeTaskGroup("PREPARE_RUNNING", batchPrepareTasks, context);
            if (!context.isLocalRerun()) {
                executeTaskGroup("SCENARIO_RUNNING", scenarioTasks, context);
            } else if (context.isScenarioMode()) {
                log.info("局部重跑复用既有情景文件，跳过情景生成与文件写入，batchId={}, dataDate={}",
                        context.getBatchId(), context.getDataDate());
            }
            executeTaskGroup("PAYLOAD_BUILDING", payloadTasks, context);
            executeTaskGroup("CALC_RUNNING", calcTasks, context);
            BatchStatusCalculator.BatchStatusSnapshot status = context.getBatchStatusSnapshot();
            batchJobService.completeWorkflow(context.getBatchId(), status, "批次工作流执行完成");
            BatchDetailResult batchDetail = batchJobService.getDetail(context.getBatchId());
            context.setBatchDetail(batchDetail);
            log.info("批次性能统计: batchId={}, phase=WORKFLOW_TOTAL, status={}, elapsedMs={}",
                    context.getBatchId(), batchDetail.getStatus(), elapsedMs(workflowStart));
        } catch (Throwable ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            batchJobService.markWorkflowFailed(context.getBatchId(), "批次工作流执行失败: " + message);
            alertService.error("BATCH_RUN_FAILED", "批次工作流异步执行失败，batchId=" + context.getBatchId(), ex);
        } finally {
            CalcScenarioInputCache.evictByBatchId(context.getBatchId());
            releaseBatchRunSlot(context);
            ExecutionContextHolder.clear();
        }
    }

    private void claimBatchRunSlot(BatchRunWorkflowContext context) {
        String batchId = context.getBatchId();
        if (!runningBatchId.compareAndSet(null, batchId)) {
            String activeBatchId = runningBatchId.get();
            throw new IllegalStateException("已有批次工作流正在运行，当前运行批次=" + activeBatchId
                    + "，请等待完成后再提交");
        }
        log.info("批次工作流运行槽已占用，batchId={}", batchId);
    }

    private void releaseBatchRunSlot(BatchRunWorkflowContext context) {
        String batchId = context == null ? null : context.getBatchId();
        if (batchId != null && runningBatchId.compareAndSet(batchId, null)) {
            log.info("批次工作流运行槽已释放，batchId={}", batchId);
        }
    }

    private void executeTaskGroup(String stageMessage, List<BatchRunTask> tasks, BatchRunWorkflowContext context) {
        if (tasks.isEmpty()) {
            return;
        }
        batchJobService.markWorkflowRunning(context.getBatchId(), stageMessage);
        long stageStart = System.nanoTime();
        for (BatchRunTask task : tasks) {
            long taskStart = System.nanoTime();
            task.execute(context);
            log.info("批次性能统计: batchId={}, phase={}, task={}, elapsedMs={}",
                    context.getBatchId(), stageMessage, task.getClass().getSimpleName(), elapsedMs(taskStart));
        }
        log.info("批次性能统计: batchId={}, phase={}, elapsedMs={}",
                context.getBatchId(), stageMessage, elapsedMs(stageStart));
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0d;
    }

    private static String normalizeRunMode(String runMode) {
        String value = trimToNull(runMode);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!RUN_MODE_WHATIF.equals(value)) {
            throw new IllegalArgumentException("runMode 仅支持 WHATIF 或空值，实际: " + runMode);
        }
        return value;
    }

    private static String requireNonBlank(String txt, String message) {
        String safe = trimToNull(txt);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String normalizeDataDate(String txt) {
        String safe = requireNonBlank(txt, "dataDate 不能为空");
        if (!DATE_8_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException("dataDate 格式错误，仅支持 yyyyMMdd");
        }
        try {
            LocalDate.parse(safe, DateTimeFormatter.BASIC_ISO_DATE);
            return safe;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("dataDate 格式错误，仅支持 yyyyMMdd");
        }
    }

    private static String buildGeneratedBatchId(String dataDate) {
        String random = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
        return "batch_" + dataDate + "_" + LocalDateTime.now().format(GENERATED_BATCH_TIME_FORMATTER) + "_" + random;
    }

    private static String buildPatchExecutionId() {
        return "patch_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static void validateBatchId(String batchId) {
        String safe = requireNonBlank(batchId, "batchId 不能为空");
        if (!BATCH_ID_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException("batchId 格式非法，仅支持字母、数字、点、下划线、中划线");
        }
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
