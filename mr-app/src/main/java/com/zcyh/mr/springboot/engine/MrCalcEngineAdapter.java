package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
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
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
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
        if (!hasScenarioRefList(payload)) {
            return payload;
        }

        String dataDate = requiredPayloadField(payload, "data_date");
        JSONObject batchMeta = payload.getJSONObject("batch_meta");
        String batchId = batchMeta == null ? null : trimToNull(batchMeta.getString("batch_id"));
        if (batchId == null) {
            throw new IllegalArgumentException("batch_meta.batch_id 必填");
        }
        LocalDate date = LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        injectScenarioRefList(payload, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST, batchId, date);
        injectScenarioRefList(payload, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST, batchId, date);
        injectScenarioRefList(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST, batchId, date);
        injectScenarioRefList(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST, batchId, date);
        return payload;
    }

    private void injectScenarioRefList(JSONObject payload,
                                       String fieldName,
                                       String batchId,
                                       LocalDate date) {
        JSONArray items = payload.getJSONArray(fieldName);
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException(fieldName + "[" + i + "] 必须是 JSON 对象");
            }
            String existingCacheKey = trimToNull(item.getString("cache_key"));
            if (existingCacheKey != null && ScenarioCache.contains(existingCacheKey)) {
                continue;
            }
            String scenarioSetId = trimToNull(item.getString("scenario_set_id"));
            if (scenarioSetId == null) {
                throw new IllegalArgumentException(fieldName + "[" + i + "].scenario_set_id 必填");
            }
            String cacheKey = existingCacheKey == null ? buildCacheKey(fieldName, batchId, scenarioSetId) : existingCacheKey;
            ScenarioCache.loadFromFiles(cacheKey, resolveScenarioPaths(scenarioSetId, batchId), date);
            item.put("cache_key", cacheKey);
        }
    }

    private static boolean hasScenarioRefList(JSONObject payload) {
        return hasArray(payload, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST);
    }

    private static boolean hasArray(JSONObject payload, String fieldName) {
        JSONArray arr = payload == null ? null : payload.getJSONArray(fieldName);
        return arr != null && !arr.isEmpty();
    }

    private static String requiredPayloadField(JSONObject payload, String key) {
        String val = payload.getString(key);
        if (val == null || val.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " 必填");
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
                throw new IllegalArgumentException("scenario 文件不存在: " + path);
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
