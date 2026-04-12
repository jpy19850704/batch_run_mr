package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.engine.ScenarioEngineAdapter;
import com.zcyh.mr.springboot.model.BatchDetailResult;
import com.zcyh.mr.springboot.model.BatchRunRequest;
import com.zcyh.mr.springboot.model.BatchRunResult;
import com.zcyh.mr.springboot.model.BatchSubmitRequest;
import com.zcyh.mr.springboot.model.BatchSubmitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 批次总编排服务。
 */
@Service
public class BatchRunService {
    private static final Logger log = LoggerFactory.getLogger(BatchRunService.class);
    private static final String DEFAULT_USER = "outer_service";
    private static final String DEFAULT_TREE_ID = "Batch";
    private static final String DEFAULT_RULE_ID = "BATCH_FRTB_DEFAULT";
    private static final String DEFAULT_VAR_RULE_ID = "BATCH_VAR_DEFAULT";
    private static final String DEFAULT_VAR_QUANTILES = "0.95,0.99";
    private static final String RUN_MODE_WHATIF = "WHATIF";

    private final ScenarioEngineAdapter scenarioEngineAdapter;
    private final CalendarFileBootstrapService calendarFileBootstrapService;
    private final BatchJobService batchJobService;
    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final FrtbSbaResultPersistService frtbSbaResultPersistService;
    private final FrtbDrcDbRunnerService frtbDrcDbRunnerService;
    private final FrtbDrcResultPersistService frtbDrcResultPersistService;
    private final VarDbRunnerService varDbRunnerService;
    private final VarResultPersistService varResultPersistService;
    private final FrtbAggregator frtbAggregator;
    private final long waitPollIntervalMs;
    private final long waitTimeoutMs;
    private final String scenarioSetRootDir;

    public BatchRunService(
            ScenarioEngineAdapter scenarioEngineAdapter,
            CalendarFileBootstrapService calendarFileBootstrapService,
            BatchJobService batchJobService,
            FrtbSbaDbRunnerService frtbSbaDbRunnerService,
            FrtbSbaResultPersistService frtbSbaResultPersistService,
            FrtbDrcDbRunnerService frtbDrcDbRunnerService,
            FrtbDrcResultPersistService frtbDrcResultPersistService,
            VarDbRunnerService varDbRunnerService,
            VarResultPersistService varResultPersistService,
            FrtbAggregator frtbAggregator,
            @Value("${mr.batch.run.wait-poll-interval-ms:1000}") long waitPollIntervalMs,
            @Value("${mr.batch.run.wait-timeout-ms:7200000}") long waitTimeoutMs,
            @Value("${mr.calc.scenario-set.root-dir:}") String scenarioSetRootDir) {
        this.scenarioEngineAdapter = scenarioEngineAdapter;
        this.calendarFileBootstrapService = calendarFileBootstrapService;
        this.batchJobService = batchJobService;
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
        this.frtbSbaResultPersistService = frtbSbaResultPersistService;
        this.frtbDrcDbRunnerService = frtbDrcDbRunnerService;
        this.frtbDrcResultPersistService = frtbDrcResultPersistService;
        this.varDbRunnerService = varDbRunnerService;
        this.varResultPersistService = varResultPersistService;
        this.frtbAggregator = frtbAggregator;
        this.waitPollIntervalMs = Math.max(200L, waitPollIntervalMs);
        this.waitTimeoutMs = Math.max(1000L, waitTimeoutMs);
        this.scenarioSetRootDir = scenarioSetRootDir == null ? "" : scenarioSetRootDir.trim();
    }

    public BatchRunResult run(BatchRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }

        String batchId = requireNonBlank(request.getBatchId(), "batchId 不能为空");
        String dataDate = requireNonBlank(request.getDataDate(), "dataDate 不能为空");
        String user = trimToNull(request.getUser());
        if (user == null) {
            user = DEFAULT_USER;
        }
        String scenarioIdList = trimToNull(request.getScenarioIdList());
        boolean scenarioMode = scenarioIdList != null;
        String runMode = normalizeRunMode(request.getRunMode());
        boolean whatifMode = RUN_MODE_WHATIF.equals(runMode);

        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode("MR_CALC");

        calendarFileBootstrapService.refreshForBatch(batchId);

        JSONArray scenarioData = new JSONArray();
        if (scenarioMode) {
            scenarioData = generateScenarios(batchId, dataDate, user, scenarioIdList);
        }

        BatchSubmitResult submitResult = submitBatch(batchId, dataDate, scenarioMode, scenarioIdList, runMode);
        BatchDetailResult batchDetail = waitBatchFinished(batchId);
        if (!batchDetail.isSuccess()) {
            throw new IllegalStateException("批量任务执行失败，batchId=" + batchId + ", status=" + batchDetail.getStatus());
        }

        Object frtbSummary = whatifMode ? null : runFrtbSummary(batchId, dataDate);
        Object drcSummary = whatifMode ? null : runDrcSummary(batchId, dataDate);
        Object varSummary = whatifMode ? null : runVarSummary(batchId, dataDate);

        BatchRunResult result = new BatchRunResult();
        result.setBatchId(batchId);
        result.setDataDate(dataDate);
        result.setUser(user);
        result.setMode(scenarioMode ? "SCENARIO" : "PRICING");
        result.setRunMode(runMode);
        result.setScenarioGenerated(scenarioMode);
        result.setScenarioCount(scenarioData.size());
        result.setScenarioData(scenarioData);
        result.setBatchDetail(batchDetail);
        result.setFrtbSummary(frtbSummary);
        result.setDrcSummary(drcSummary);
        result.setVarSummary(varSummary);
        return result;
    }

    private JSONArray generateScenarios(String batchId, String dataDate, String user, String scenarioIdList) {
        if (trimToNull(scenarioSetRootDir) == null) {
            throw new IllegalStateException("SCENARIO 模式要求已配置 mr.calc.scenario-set.root-dir，用于生成并落地情景文件");
        }
        JSONObject payload = new JSONObject();
        payload.put("mode", "service");
        payload.put("scenario_id_list", scenarioIdList);
        payload.put("data_date", dataDate);
        payload.put("user", user);
        payload.put("batch_id", batchId);

        String raw = scenarioEngineAdapter.calculate(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        JSONArray data = JSON.parseArray(raw);
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("情景生成结果为空，batchId=" + batchId + ", scenario_id_list=" + scenarioIdList);
        }

        // 将情景数据写入文件，命名格式与 MrCalcEngineAdapter.resolveScenarioPath 对齐
        try {
            java.nio.file.Path rootDir = java.nio.file.Paths.get(scenarioSetRootDir);
            java.nio.file.Files.createDirectories(rootDir);

            // 主场景文件
            String mainFileName = scenarioIdList + "_" + dataDate + "_" + batchId + ".json";
            java.nio.file.Path mainFilePath = rootDir.resolve(mainFileName);
            java.nio.file.Files.writeString(mainFilePath, raw, java.nio.charset.StandardCharsets.UTF_8);
            log.info("主场景文件已写入: {}, 记录数={}", mainFilePath, data.size());

            // Risk Class Decomp 场景文件（内容与主场景相同，Calc 引擎内部按 IR/FX/EQ/COMM/ALL 动态切片）
            String decompFileName = "DECOMP_" + scenarioIdList + "_" + dataDate + "_" + batchId + ".json";
            java.nio.file.Path decompFilePath = rootDir.resolve(decompFileName);
            java.nio.file.Files.writeString(decompFilePath, raw, java.nio.charset.StandardCharsets.UTF_8);
            log.info("Decomp 场景文件已写入: {}", decompFilePath);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("写入情景文件失败: " + e.getMessage(), e);
        }

        return data;
    }

    private BatchSubmitResult submitBatch(String batchId, String dataDate, boolean scenarioMode, String scenarioIdList, String runMode) {
        BatchSubmitRequest request = new BatchSubmitRequest();
        request.setBatchId(batchId);
        request.setRequestId(batchId);
        request.setDataDate(dataDate);
        request.setOpCode(scenarioMode ? "SCENARIO" : "PRICING");
        request.setRunMode(runMode);
        if (scenarioMode) {
            return batchJobService.submit(request, scenarioIdList);
        }
        return batchJobService.submit(request);
    }

    private static String normalizeRunMode(String runMode) {
        String value = trimToNull(runMode);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(java.util.Locale.ROOT);
        if (!RUN_MODE_WHATIF.equals(value)) {
            throw new IllegalArgumentException("runMode 仅支持 WHATIF 或空值，实际: " + runMode);
        }
        return value;
    }

    private BatchDetailResult waitBatchFinished(String batchId) {
        long deadline = System.currentTimeMillis() + waitTimeoutMs;
        BatchDetailResult last = null;
        while (System.currentTimeMillis() <= deadline) {
            last = batchJobService.getDetail(batchId);
            if (last != null && last.isDone()) {
                return last;
            }
            sleepQuietly(waitPollIntervalMs);
        }
        throw new IllegalStateException("批量任务等待超时，batchId=" + batchId + ", timeoutMs=" + waitTimeoutMs);
    }

    @SuppressWarnings("unchecked")
    private Object runFrtbSummary(String batchId, String dataDate) {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);
        payload.put("rule_id", DEFAULT_RULE_ID);
        payload.put("need_decompose", true);

        String raw = frtbSbaDbRunnerService.calculateByRule(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        Object parsed = JSON.parse(raw);

        // 将原始 Map 结果转为 POJO 并落库
        try {
            Map<String, Map<String, Object>> batchResult = JSON.parseObject(raw, Map.class);
            if (batchResult != null && !batchResult.isEmpty()) {
                // 清理旧数据
                frtbSbaResultPersistService.deleteByBatch(batchId);
                // 遍历每个维度组的结果并落库
                for (Map.Entry<String, Map<String, Object>> entry : batchResult.entrySet()) {
                    String groupKey = entry.getKey();
                    // groupKey 格式: treeId|groupValue
                    String[] parts = groupKey.split("\\|", 2);
                    String treeId = parts.length > 0 ? parts[0] : null;
                    String groupValue = parts.length > 1 ? parts[1] : null;
                    // 从 buildOrder 推断 groupType
                    String groupType = inferGroupType(groupValue);

                    Map<String, Object> mapResult = entry.getValue();

                    Map<String, List<?>> pojoResult = frtbAggregator.buildResults(
                            mapResult, treeId, groupType, groupValue);
                    List<?> classResults = pojoResult.get("classResults");
                    if (classResults != null) {

                        frtbSbaResultPersistService.persist(
                                (List<FRTBClassResult>) classResults, batchId, dataDate, DEFAULT_RULE_ID);
                    }
                }
            }
        } catch (Exception ex) {
            // 落库失败不影响主流程返回
            log.warn("FRTB SBA 结果落库异常，不影响批量返回: batchId={}, error={}", batchId, ex.getMessage());
        }

        return parsed;
    }

    private Object runDrcSummary(String batchId, String dataDate) {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);

        String raw = frtbDrcDbRunnerService.calculateByBatch(
                payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        Object parsed = JSON.parse(raw);

        try {
            JSONObject resultJson = JSON.parseObject(raw);
            frtbDrcResultPersistService.persist(
                    batchId,
                    null,
                    batchId,
                    dataDate,
                    resultJson);
        } catch (Exception ex) {
            // 落库失败不影响主流程返回
            log.warn("DRC 结果落库异常，不影响批量返回: batchId={}, error={}", batchId, ex.getMessage());
        }

        return parsed;
    }

    private Object runVarSummary(String batchId, String dataDate) {
        JSONObject payload = new JSONObject();
        payload.put("source_type", "db_inline");
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);
        payload.put("quantiles", DEFAULT_VAR_QUANTILES);

        JSONObject rule = new JSONObject();
        rule.put("rule_id", DEFAULT_VAR_RULE_ID);
        rule.put("rule_name", "默认 VaR 批次汇总规则");
        rule.put("rule_type", "VAR");
        rule.put("build_order", JSON.parseArray("[\"TRADER\",\"DESK\",\"PORTFOLIO\",\"TOTAL\"]"));

        JSONObject dimensions = new JSONObject();
        dimensions.put("TRADER", "TRADER");
        dimensions.put("DESK", "DESK");
        dimensions.put("PORTFOLIO", "PORTFOLIO");
        rule.put("dimensions", dimensions);
        rule.put("group_by_fields", JSON.parseArray("[\"TRADER\",\"DESK\",\"PORTFOLIO\"]"));
        rule.put("sum_fields", JSON.parseArray("[\"ALL_PNL\"]"));
        rule.put("filters", new JSONArray());

        JSONObject calc = new JSONObject();
        calc.put("risk_class", "ALL");
        calc.put("var_pick", "average");
        rule.put("calc", calc);

        JSONArray rules = new JSONArray();
        rules.add(rule);
        payload.put("rules", rules);

        String raw = varDbRunnerService.calculateByInline(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        Object parsed = JSON.parse(raw);

        try {
            JSONObject resultJson = JSON.parseObject(raw);
            varResultPersistService.persist(batchId, dataDate, resultJson);
        } catch (Exception ex) {
            // 落库失败不影响主流程返回
            log.warn("VaR 结果落库异常，不影响批量返回: batchId={}, error={}", batchId, ex.getMessage());
        }

        return parsed;
    }

    /**
     * 根据 groupValue 推断 groupType。
     * TOTAL 值对应 TOTAL 类型，其余根据 buildOrder 默认为 PORTFOLIO。
     */
    private static String inferGroupType(String groupValue) {
        if (groupValue == null || "TOTAL".equalsIgnoreCase(groupValue)
                || "__EMPTY_GROUP__".equals(groupValue)) {
            return "TOTAL";
        }
        return "PORTFOLIO";
    }

    private static void sleepQuietly(long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待批量任务完成时被中断", ex);
        }
    }

    private static String requireNonBlank(String txt, String message) {
        String safe = trimToNull(txt);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
