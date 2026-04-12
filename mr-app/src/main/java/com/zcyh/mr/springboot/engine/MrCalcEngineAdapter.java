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
        Calc calc = new Calc(taskPayloadJson);
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

        String scenarioSetId = requiredRefField(scenarioRef, "scenario_set_id");
        String dataDate = requiredRefField(scenarioRef, "data_date");
        String batchId = requiredRefField(scenarioRef, "batch_id");

        Path scenarioPath = resolveScenarioPath(scenarioRef, scenarioSetId, dataDate, batchId);

        LocalDate date = LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        String cacheKey = ScenarioCache.loadFromFile(scenarioPath.toString(), date);
        scenarioRef.put("cache_key", cacheKey);

        String decompSetId = scenarioRef.getString("risk_class_decomp_scenario_set_id");
        if (decompSetId != null && !decompSetId.trim().isEmpty()) {
            decompSetId = decompSetId.trim();
            Path decompPath = resolveScenarioPath(scenarioRef, decompSetId, dataDate, batchId);
            String decompCacheKey = ScenarioCache.loadFromFile(decompPath.toString(), date);
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

    private Path resolveScenarioPath(JSONObject ref, String scenarioSetId, String dataDate, String batchId) {
        String filePath = ref.getString("file_path");
        if (filePath != null && !filePath.trim().isEmpty()) {
            Path path = Paths.get(filePath.trim());
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("scenario_ref.file_path not found: " + path);
            }
            return path;
        }

        String root = ref.getString("root_dir");
        if (root == null || root.trim().isEmpty()) {
            root = scenarioSetRootDir;
        }
        if (root == null || root.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 scenario 数据目录，请配置 mr.calc.scenario-set.root-dir 或传入 scenario_ref.file_path/scenario_ref.root_dir");
        }
        if (root.contains("src/main/resources")) {
            throw new IllegalArgumentException("scenario 数据目录不能再指向源码目录，请改为外部目录: " + root);
        }

        Path rootPath = Paths.get(root.trim());
        if (!rootPath.isAbsolute()) {
            rootPath = rootPath.toAbsolutePath();
        }

        Path csvCandidate1 = rootPath.resolve(scenarioSetId + "_" + dataDate + "_" + batchId + ".csv");
        if (Files.exists(csvCandidate1)) {
            return csvCandidate1;
        }

        Path csvCandidate2 = rootPath.resolve(Paths.get(scenarioSetId, dataDate, batchId + ".csv"));
        if (Files.exists(csvCandidate2)) {
            return csvCandidate2;
        }

        Path jsonCandidate1 = rootPath.resolve(scenarioSetId + "_" + dataDate + "_" + batchId + ".json");
        if (Files.exists(jsonCandidate1)) {
            return jsonCandidate1;
        }

        Path jsonCandidate2 = rootPath.resolve(Paths.get(scenarioSetId, dataDate, batchId + ".json"));
        if (Files.exists(jsonCandidate2)) {
            return jsonCandidate2;
        }

        throw new IllegalArgumentException("scenario set file not found under root_dir=" + rootPath + ", expected: "
                + csvCandidate1 + " or " + jsonCandidate1);
    }
}
