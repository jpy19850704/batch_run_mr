package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.BatchRunRequest;
import com.zcyh.mr.springboot.model.BatchRunResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 批次总编排服务。
 * 仅负责串行编排批量工作流任务，不再承载具体计量细节。
 */
@Service
public class BatchRunService {
    private static final String DEFAULT_USER = "outer_service";
    private static final String RUN_MODE_WHATIF = "WHATIF";
    private static final Pattern DATE_8_PATTERN = Pattern.compile("^\\d{8}$");

    private final List<BatchRunTask> batchPrepareTasks;
    private final List<BatchRunTask> scenarioTasks;
    private final List<BatchRunTask> payloadTasks;
    private final List<BatchRunTask> calcTasks;
    private final List<BatchRunTask> summaryTasks;

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
            BatchFrtbSummaryTask frtbSummaryTask,
            BatchVarSummaryTask varSummaryTask) {
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
        this.summaryTasks = Arrays.<BatchRunTask>asList(
                frtbSummaryTask,
                varSummaryTask);
    }

    public BatchRunResult run(BatchRunRequest request) {
        BatchRunWorkflowContext context = buildContext(request);
        RequestContextHolder.setBatchId(context.getBatchId());
        RequestContextHolder.setEngineCode("MR_CALC");
        executeTasks(batchPrepareTasks, context);
        executeTasks(scenarioTasks, context);
        executeTasks(payloadTasks, context);
        executeTasks(calcTasks, context);
        executeTasks(summaryTasks, context);
        return buildResult(context);
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
        String scenarioIdList = trimToNull(request.getScenarioIdList());
        String runMode = normalizeRunMode(request.getRunMode());

        BatchRunWorkflowContext context = new BatchRunWorkflowContext();
        context.setRequest(request);
        context.setBatchId(requireNonBlank(request.getBatchId(), "batchId 不能为空"));
        context.setDataDate(normalizeDataDate(request.getDataDate()));
        context.setUser(user == null ? DEFAULT_USER : user);
        context.setScenarioIdList(scenarioIdList);
        context.setScenarioMode(scenarioIdList != null);
        context.setRunMode(runMode);
        context.setWhatifMode(RUN_MODE_WHATIF.equals(runMode));
        return context;
    }

    private BatchRunResult buildResult(BatchRunWorkflowContext context) {
        BatchRunResult result = new BatchRunResult();
        result.setBatchId(context.getBatchId());
        result.setDataDate(context.getDataDate());
        result.setUser(context.getUser());
        result.setMode(context.isScenarioMode() ? "SCENARIO" : "PRICING");
        result.setRunMode(context.getRunMode());
        result.setScenarioGenerated(context.isScenarioMode());
        result.setScenarioCount(context.getScenarioData().size());
        result.setScenarioData(context.getScenarioData());
        result.setBatchDetail(context.getBatchDetail());
        result.setSbaSummary(context.getSbaSummary());
        result.setDrcSummary(context.getDrcSummary());
        result.setVarSummary(context.getVarSummary());
        return result;
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

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
