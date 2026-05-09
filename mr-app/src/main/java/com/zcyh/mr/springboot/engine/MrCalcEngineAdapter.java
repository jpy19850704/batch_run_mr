package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.scenario.ScenarioCache;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MR 计量引擎适配器。
 */
public class MrCalcEngineAdapter implements EngineAdapter {
    public static final String CODE = "MR_CALC";
    private final String scenarioSetRootDir;

    public MrCalcEngineAdapter(String scenarioSetRootDir) {
        this.scenarioSetRootDir = scenarioSetRootDir == null ? "" : scenarioSetRootDir.trim();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "MR pricing engine adapter based on com.zcyh.mr.calc.Calc";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        if (req.containsKey("batch_tasks")) {
            throw new IllegalArgumentException("mr 不再支持 batch_tasks，请改为单任务调用或走调度层拆批");
        }

        JSONObject singlePayload = injectScenarioDataIfNeeded(req);
        return runSingle(singlePayload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    private String runSingle(String taskPayloadJson) {
        Calc calc = new Calc(taskPayloadJson, null);
        return calc.run();
    }

    private JSONObject injectScenarioDataIfNeeded(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        String operCode = payload.getString("oper_code");
        if (operCode == null || !"SCENARIO".equalsIgnoreCase(operCode.trim())) {
            return payload;
        }
        if (payload.getJSONArray("scenario_data") != null) {
            return payload;
        }

        JSONObject scenarioRef = payload.getJSONObject("scenario_ref");
        if (scenarioRef == null || scenarioRef.isEmpty()) {
            return payload;
        }

        String existingCacheKey = scenarioRef.getString("cache_key");
        if (existingCacheKey != null && !existingCacheKey.trim().isEmpty()
                && ScenarioCache.contains(existingCacheKey.trim())) {
            return payload;
        }

        String dataDate = requiredRefField(scenarioRef, "data_date");
        String batchId = requiredRefField(scenarioRef, "batch_id");

        LocalDate date = LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        String scenarioSetId = trimToNull(scenarioRef.getString("scenario_set_id"));
        if (scenarioSetId != null) {
            String cacheKey = buildCacheKey("scenario", batchId, scenarioSetId);
            ScenarioCache.loadFromFiles(cacheKey, resolveScenarioPaths(scenarioSetId, batchId), date);
            scenarioRef.put("cache_key", cacheKey);
        }

        String decompSetId = scenarioRef.getString("risk_class_decomp_scenario_set_id");
        if (decompSetId != null && !decompSetId.trim().isEmpty()) {
            decompSetId = decompSetId.trim();
            String decompCacheKey = buildCacheKey("decomp", batchId, decompSetId);
            ScenarioCache.loadFromFiles(decompCacheKey, resolveScenarioPaths(decompSetId, batchId), date);
            scenarioRef.put("decomp_cache_key", decompCacheKey);
        }

        return payload;
    }

    private static String requiredRefField(JSONObject ref, String key) {
        String val = ref.getString(key);
        if (val == null || val.trim().isEmpty()) {
            throw new IllegalArgumentException("scenario_ref." + key + " is required");
        }
        return val.trim();
    }

    private List<String> resolveScenarioPaths(String scenarioSetId, String batchId) {
        String root = scenarioSetRootDir;
        if (root == null || root.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 scenario 数据目录，请配置 mr.calc.scenario-set.root-dir");
        }
        if (root.contains("src/main/resources")) {
            throw new IllegalArgumentException("scenario 数据目录不能再指向源码目录，请改为外部目录: " + root);
        }

        Path rootPath = Paths.get(root.trim());
        if (!rootPath.isAbsolute()) {
            rootPath = rootPath.toAbsolutePath();
        }
        rootPath = rootPath.normalize();

        Path batchDir = rootPath.resolve(toSafePathName(batchId, "batch_id")).normalize();
        List<String> result = new ArrayList<String>();
        for (String scenarioId : parseScenarioIds(scenarioSetId)) {
            Path path = batchDir.resolve(toSafePathName(scenarioId, "SCENARIO_ID") + ".csv.gz").normalize();
            if (!path.startsWith(batchDir)) {
                throw new IllegalArgumentException("非法 scenario 文件路径: " + path);
            }
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("scenario set file not found: " + path);
            }
            result.add(path.toString());
        }
        return result;
    }

    private Set<String> parseScenarioIds(String scenarioSetId) {
        Set<String> result = new LinkedHashSet<String>();
        String safe = trimToNull(scenarioSetId);
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

    private String toSafePathName(String value, String fieldName) {
        String safe = trimToNull(value);
        if (safe == null) {
            throw new IllegalArgumentException(fieldName + " 为空，无法定位情景文件");
        }
        if (safe.contains("/") || safe.contains("\\") || safe.contains(":")
                || safe.contains("*") || safe.contains("?") || safe.contains("\"")
                || safe.contains("<") || safe.contains(">") || safe.contains("|")
                || safe.contains("..")) {
            throw new IllegalArgumentException(fieldName + " 包含非法文件名字符: " + safe);
        }
        return safe;
    }

    private String buildCacheKey(String type, String batchId, String scenarioSetId) {
        return type + ":" + batchId + ":" + scenarioSetId;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
