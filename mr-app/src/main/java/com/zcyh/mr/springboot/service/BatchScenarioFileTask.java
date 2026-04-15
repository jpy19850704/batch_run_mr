package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 批量情景文件写出任务。
 */
@Component
public class BatchScenarioFileTask implements BatchRunTask {
    private static final Logger log = LoggerFactory.getLogger(BatchScenarioFileTask.class);

    private final String scenarioSetRootDir;

    public BatchScenarioFileTask(@Value("${mr.calc.scenario-set.root-dir:}") String scenarioSetRootDir) {
        this.scenarioSetRootDir = scenarioSetRootDir == null ? "" : scenarioSetRootDir.trim();
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isScenarioMode()) {
            return;
        }
        if (trimToNull(scenarioSetRootDir) == null) {
            throw new IllegalStateException("SCENARIO 模式要求已配置 mr.calc.scenario-set.root-dir，用于生成并落地情景文件");
        }
        String scenarioJson = trimToNull(context.getScenarioJson());
        if (scenarioJson == null) {
            throw new IllegalStateException("情景文件写出前缺少 scenarioJson，batchId=" + context.getBatchId());
        }
        JSONArray scenarioData = context.getScenarioData();
        try {
            java.nio.file.Path rootDir = java.nio.file.Paths.get(scenarioSetRootDir);
            java.nio.file.Files.createDirectories(rootDir);

            writeScenarioFile(rootDir, context.getRegularScenarioIdList(), context, scenarioData, "主场景");
            writeScenarioFile(rootDir, context.getRiskClassDecompScenarioIdList(), context, scenarioData, "Decomp 场景");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("写入情景文件失败: " + ex.getMessage(), ex);
        }
    }

    private void writeScenarioFile(java.nio.file.Path rootDir,
                                   String scenarioIdList,
                                   BatchRunWorkflowContext context,
                                   JSONArray scenarioData,
                                   String logPrefix) throws java.io.IOException {
        String safeScenarioIdList = trimToNull(scenarioIdList);
        if (safeScenarioIdList == null) {
            return;
        }
        JSONArray filtered = filterScenarioData(scenarioData, safeScenarioIdList);
        String fileName = safeScenarioIdList + "_" + context.getDataDate() + "_" + context.getBatchId() + ".json";
        java.nio.file.Path filePath = rootDir.resolve(fileName);
        java.nio.file.Files.writeString(filePath, JSON.toJSONString(filtered), java.nio.charset.StandardCharsets.UTF_8);
        log.info("{}文件已写入: {}, 记录数={}", logPrefix, filePath, filtered.size());
    }

    private JSONArray filterScenarioData(JSONArray scenarioData, String scenarioIdList) {
        JSONArray result = new JSONArray();
        if (scenarioData == null || scenarioData.isEmpty()) {
            return result;
        }
        Set<String> allowedIds = parseScenarioIds(scenarioIdList);
        for (int i = 0; i < scenarioData.size(); i++) {
            JSONObject item = scenarioData.getJSONObject(i);
            if (item == null) {
                continue;
            }
            // 兼容 camelCase 和 UPPER_SNAKE_CASE 两种序列化格式
            String scenarioId = trimToNull(firstNonBlank(
                    item.getString("SCENARIO_ID"),
                    item.getString("scenarioId")));
            if (scenarioId != null && allowedIds.contains(scenarioId)) {
                result.add(item);
            }
        }
        return result;
    }

    private Set<String> parseScenarioIds(String scenarioIdList) {
        Set<String> result = new LinkedHashSet<String>();
        String safe = trimToNull(scenarioIdList);
        if (safe == null) {
            return result;
        }
        for (String part : safe.split(",")) {
            String scenarioId = trimToNull(part);
            if (scenarioId != null) {
                result.add(scenarioId);
            }
        }
        return result;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            String safe = trimToNull(v);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }
}
