package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Scenario 引擎适配器。
 * 只保留正式场景生成入口。
 */
public class ScenarioEngineAdapter implements EngineAdapter {
    public static final String CODE = "scenario";
    private final ScenarioGenerationEngine scenarioGenerationEngine;
    private final ScenarioRequestAssembler scenarioRequestAssembler;

    public ScenarioEngineAdapter(
            ScenarioGenerationEngine scenarioGenerationEngine,
            ScenarioRequestAssembler scenarioRequestAssembler) {
        this.scenarioGenerationEngine = scenarioGenerationEngine;
        this.scenarioRequestAssembler = scenarioRequestAssembler;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Scenario engine adapter for standardized scenario generation requests";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        return runService(req);
    }

    private String runService(JSONObject req) {
        if (scenarioGenerationEngine == null) {
            throw new IllegalStateException("scenario generation engine 未启用，请配置 mr.scenario.service.enabled=true 并提供业务库连接");
        }
        if (scenarioRequestAssembler == null) {
            throw new IllegalStateException("scenario request assembler 未启用，请检查情景请求装配配置");
        }

        String scenarioIdList = requiredString(req, "scenario_id_list");
        String dataDate = requiredString(req, "data_date");
        String user = req.getString("user");
        if (user == null || user.trim().isEmpty()) {
            user = "outer_service";
        }

        ScenarioGenerationRequest request = scenarioRequestAssembler.build(
                scenarioIdList,
                LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE),
                user,
                "mr-app");
        List<ScenarioGeneratedRecord> result = scenarioGenerationEngine.generate(request);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static String requiredString(JSONObject obj, String key) {
        String value = obj.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }
}
