package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.BatchRunRequest;
import com.zcyh.mr.springboot.model.BatchRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
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

    private final List<BatchRunTask> batchPrepareTasks;
    private final List<BatchRunTask> scenarioTasks;
    private final List<BatchRunTask> payloadTasks;
    private final List<BatchRunTask> calcTasks;
    private final BatchJobService batchJobService;
    private final AlertService alertService;
    private final ExecutorService batchRunWorkflowExecutor;

    public BatchRunService(
            BatchPrepareTask prepareTask,
            BatchScenarioGenerateTask scenarioGenerateTask,
            BatchScenarioFileTask scenarioFileTask,
            BatchTradeLoadTask tradeLoadTask,
            BatchMarketDataLoadTask marketDataLoadTask,
            BatchChunkBuildTask chunkBuildTask,
            BatchPayloadBuildTask payloadBuildTask,
            BatchCalcSubmitTask calcSubmitTask,
            BatchCalcWaitTask calcWaitTask,
            BatchJobService batchJobService,
            AlertService alertService,
            @Qualifier("batchRunWorkflowExecutor") ExecutorService batchRunWorkflowExecutor) {
        this.batchPrepareTasks = Arrays.<BatchRunTask>asList(prepareTask);
        this.scenarioTasks = Arrays.<BatchRunTask>asList(
                scenarioGenerateTask,
                scenarioFileTask);
        this.payloadTasks = Arrays.<BatchRunTask>asList(
                tradeLoadTask,
                marketDataLoadTask,
                chunkBuildTask,
                payloadBuildTask);
        this.calcTasks = Arrays.<BatchRunTask>asList(
                calcSubmitTask,
                calcWaitTask);
        this.batchJobService = batchJobService;
        this.alertService = alertService;
        this.batchRunWorkflowExecutor = batchRunWorkflowExecutor;
    }

    public BatchRunResult run(BatchRunRequest request) {
        BatchRunWorkflowContext context = buildContext(request);
        initializeWorkflow(context);
        try {
            batchRunWorkflowExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runWorkflow(context);
                }
            });
        } catch (RejectedExecutionException ex) {
            batchJobService.markWorkflowFailed(context.getBatchId(), "批次工作流提交失败: 执行队列已满");
            alertService.error("BATCH_RUN_REJECTED", "批次工作流提交失败，batchId=" + context.getBatchId(), ex);
            throw new IllegalStateException("批次工作流提交失败，执行队列已满，请稍后重试");
        }
        return buildAcceptedResult(context);
    }

    private static void executeTasks(List<BatchRunTask> tasks, BatchRunWorkflowContext context) {
        for (BatchRunTask task : tasks) {
            task.execute(context);
        }
    }

    private BatchRunWorkflowContext buildContext(BatchRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String user = trimToNull(request.getUser());
        String regularScenarioIdList = trimToNull(request.getRegularScenarioIdList());
        String riskClassDecompScenarioIdList = trimToNull(request.getRiskClassDecompScenarioIdList());
        String runMode = normalizeRunMode(request.getRunMode());
        String dataDate = normalizeDataDate(request.getDataDate());
        String externalBatchId = trimToNull(request.getBatchId());
        String batchId = externalBatchId == null ? buildGeneratedBatchId(dataDate) : externalBatchId;
        validateBatchId(batchId);
        boolean persistResult = request.getPersistResult() == null
                ? externalBatchId != null
                : Boolean.TRUE.equals(request.getPersistResult());

        BatchRunWorkflowContext context = new BatchRunWorkflowContext();
        context.setRequest(request);
        context.setBatchId(batchId);
        context.setDataDate(dataDate);
        context.setUser(user == null ? DEFAULT_USER : user);
        context.setRegularScenarioIdList(regularScenarioIdList);
        context.setRiskClassDecompScenarioIdList(riskClassDecompScenarioIdList);
        context.setScenarioMode(regularScenarioIdList != null || riskClassDecompScenarioIdList != null);
        context.setRunMode(runMode);
        context.setWhatifMode(RUN_MODE_WHATIF.equals(runMode));
        context.setExternalBatchIdProvided(externalBatchId != null);
        context.setPersistResult(persistResult);
        return context;
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
        result.setScenarioData(context.getScenarioData());
        result.setBatchDetail(batchJobService.getDetail(context.getBatchId()));
        return result;
    }

    private void initializeWorkflow(BatchRunWorkflowContext context) {
        RequestContextHolder.setBatchId(context.getBatchId());
        RequestContextHolder.setEngineCode(ENGINE_CODE);
        batchJobService.initializeWorkflowBatch(
                context.getBatchId(),
                context.getBatchId(),
                ENGINE_CODE,
                context.isScenarioMode() ? "SCENARIO" : "PRICING",
                LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE),
                null,
                null,
                System.currentTimeMillis(),
                "批次工作流已启动",
                context.isPersistResult()
        );
    }

    private void runWorkflow(BatchRunWorkflowContext context) {
        RequestContextHolder.setBatchId(context.getBatchId());
        RequestContextHolder.setEngineCode(ENGINE_CODE);
        try {
            executeTaskGroup("PREPARE_RUNNING", batchPrepareTasks, context);
            executeTaskGroup("SCENARIO_RUNNING", scenarioTasks, context);
            executeTaskGroup("PAYLOAD_BUILDING", payloadTasks, context);
            executeTaskGroup("CALC_RUNNING", calcTasks, context);
            batchJobService.markWorkflowSuccess(context.getBatchId(), "批次工作流执行完成");
            log.info("批次工作流异步执行完成，batchId={}", context.getBatchId());
        } catch (Throwable ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            batchJobService.markWorkflowFailed(context.getBatchId(), "批次工作流执行失败: " + message);
            alertService.error("BATCH_RUN_FAILED", "批次工作流异步执行失败，batchId=" + context.getBatchId(), ex);
        } finally {
            RequestContextHolder.clear();
        }
    }

    private void executeTaskGroup(String stageMessage, List<BatchRunTask> tasks, BatchRunWorkflowContext context) {
        if (tasks.isEmpty()) {
            return;
        }
        batchJobService.markWorkflowRunning(context.getBatchId(), stageMessage);
        executeTasks(tasks, context);
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
