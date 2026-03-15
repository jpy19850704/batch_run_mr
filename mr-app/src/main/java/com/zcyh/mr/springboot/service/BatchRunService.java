package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.outer.engine.ScenarioEngineAdapter;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.BatchDetailResult;
import com.zcyh.mr.springboot.model.BatchRunRequest;
import com.zcyh.mr.springboot.model.BatchRunResult;
import com.zcyh.mr.springboot.model.BatchSubmitRequest;
import com.zcyh.mr.springboot.model.BatchSubmitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 批次总编排服务。
 */
@Service
public class BatchRunService {
    private static final String DEFAULT_USER = "outer_service";
    private static final String DEFAULT_TREE_ID = "Batch";

    private final ScenarioEngineAdapter scenarioEngineAdapter;
    private final BatchJobService batchJobService;
    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final long waitPollIntervalMs;
    private final long waitTimeoutMs;
    private final String scenarioSetRootDir;

    public BatchRunService(
            ScenarioEngineAdapter scenarioEngineAdapter,
            BatchJobService batchJobService,
            FrtbSbaDbRunnerService frtbSbaDbRunnerService,
            @Value("${mr.batch.run.wait-poll-interval-ms:1000}") long waitPollIntervalMs,
            @Value("${mr.batch.run.wait-timeout-ms:7200000}") long waitTimeoutMs,
            @Value("${mr.calc.scenario-set.root-dir:}") String scenarioSetRootDir) {
        this.scenarioEngineAdapter = scenarioEngineAdapter;
        this.batchJobService = batchJobService;
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
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

        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode("MR_CALC");

        int scenarioCount = 0;
        if (scenarioMode) {
            scenarioCount = generateScenarios(batchId, dataDate, user, scenarioIdList);
        }

        BatchSubmitResult submitResult = submitBatch(batchId, dataDate, scenarioMode, scenarioIdList);
        BatchDetailResult batchDetail = waitBatchFinished(batchId);
        if (!batchDetail.isSuccess()) {
            throw new IllegalStateException("批量任务执行失败，batchId=" + batchId + ", status=" + batchDetail.getStatus());
        }

        Object frtbSummary = runFrtbSummary(dataDate);

        BatchRunResult result = new BatchRunResult();
        result.setBatchId(batchId);
        result.setDataDate(dataDate);
        result.setUser(user);
        result.setMode(scenarioMode ? "SCENARIO" : "PRICING");
        result.setScenarioGenerated(scenarioMode);
        result.setScenarioCount(scenarioCount);
        result.setBatchDetail(batchDetail);
        result.setFrtbSummary(frtbSummary);
        return result;
    }

    private int generateScenarios(String batchId, String dataDate, String user, String scenarioIdList) {
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
        return data.size();
    }

    private BatchSubmitResult submitBatch(String batchId, String dataDate, boolean scenarioMode, String scenarioIdList) {
        BatchSubmitRequest request = new BatchSubmitRequest();
        request.setBatchId(batchId);
        request.setRequestId(batchId);
        request.setDataDate(dataDate);
        request.setOpCode(scenarioMode ? "SCENARIO" : "PRICING");
        if (scenarioMode) {
            return batchJobService.submit(request, scenarioIdList);
        }
        return batchJobService.submit(request);
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

    private Object runFrtbSummary(String dataDate) {
        JSONObject query = new JSONObject();
        JSONArray treeIds = new JSONArray();
        treeIds.add(DEFAULT_TREE_ID);
        query.put("tree_id_list", treeIds);

        JSONObject payload = new JSONObject();
        payload.put("source_type", "db");
        payload.put("data_date", dataDate);
        payload.put("need_decompose", true);
        payload.put("query", query);

        String raw = frtbSbaDbRunnerService.calculate(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        return JSON.parse(raw);
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
