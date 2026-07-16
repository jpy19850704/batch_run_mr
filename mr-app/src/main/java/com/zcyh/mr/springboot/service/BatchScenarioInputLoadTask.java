package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.CalcScenarioInputCache;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.springboot.out.file.ScenarioSetPathResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在计算子任务提交前统一加载本批次情景缓存。
 */
@Component
public class BatchScenarioInputLoadTask implements BatchRunTask {
    private static final String[] SCENARIO_REF_FIELDS = {
            ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST,
            ScenarioProcessConstants.VAR_SCENARIO_REF_LIST,
            ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST,
            ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST
    };

    private final ScenarioSetPathResolver pathResolver;

    public BatchScenarioInputLoadTask(ScenarioSetPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isScenarioMode()) {
            return;
        }
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        Set<String> loadedCacheKeys = new LinkedHashSet<String>();
        for (BatchJobPayload jobPayload : context.getJobPayloads()) {
            if (jobPayload == null || jobPayload.isFailed() || jobPayload.getPayload() == null) {
                continue;
            }
            injectCacheKeys(
                    jobPayload.getPayload(),
                    context.getBatchId(),
                    dataDate,
                    context.getScenarioMarketKeys(),
                    loadedCacheKeys);
        }
    }

    private void injectCacheKeys(
            JSONObject payload,
            String batchId,
            LocalDate dataDate,
            Set<String> scenarioMarketKeys,
            Set<String> loadedCacheKeys) {
        for (String fieldName : SCENARIO_REF_FIELDS) {
            JSONArray items = payload.getJSONArray(fieldName);
            if (items == null || items.isEmpty()) {
                continue;
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) {
                    throw new IllegalArgumentException(fieldName + "[" + i + "] 必须是 JSON 对象");
                }
                String scenarioIdList = requireText(
                        item.getString("scenario_set_id"),
                        fieldName + "[" + i + "].scenario_set_id 必填");
                String cacheKey = buildCacheKey(fieldName, dataDate, batchId, scenarioIdList);
                if (loadedCacheKeys.add(cacheKey)) {
                    CalcScenarioInputCache.loadFromFiles(
                            cacheKey,
                            resolveScenarioPaths(scenarioIdList, dataDate, batchId),
                            dataDate,
                            scenarioMarketKeys);
                }
                item.put("cache_key", cacheKey);
            }
        }
    }

    private List<String> resolveScenarioPaths(String scenarioIdList, LocalDate dataDate, String batchId) {
        List<String> paths = new ArrayList<String>();
        for (String scenarioId : parseScenarioIds(scenarioIdList)) {
            Path path = pathResolver.resolveScenarioFile(
                    dataDate.format(DateTimeFormatter.BASIC_ISO_DATE), batchId, scenarioId);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("scenario 文件不存在: " + path);
            }
            paths.add(path.toString());
        }
        return paths;
    }

    private static Set<String> parseScenarioIds(String scenarioIdList) {
        Set<String> result = new LinkedHashSet<String>();
        for (String part : scenarioIdList.split(",")) {
            String scenarioId = trimToNull(part);
            if (scenarioId != null) {
                result.add(scenarioId);
            }
        }
        return result;
    }

    private static String buildCacheKey(
            String fieldName,
            LocalDate dataDate,
            String batchId,
            String scenarioIdList) {
        return fieldName + ":" + dataDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + ":" + batchId + ":" + scenarioIdList;
    }

    private static String requireText(String value, String message) {
        String safe = trimToNull(value);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String safe = value.trim();
        return safe.isEmpty() ? null : safe;
    }
}
