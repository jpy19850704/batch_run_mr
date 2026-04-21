package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;
import com.zcyh.mr.springboot.service.ScenarioGeneratedPersistService;
import com.zcyh.mr.springboot.service.ScenarioResultCacheService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Scenario 引擎适配器。
 * 只保留正式场景生成入口。
 */
public class ScenarioEngineAdapter implements EngineAdapter {
    public static final String CODE = "scenario";
    private final ScenarioGenerationEngine scenarioGenerationEngine;
    private final ScenarioRequestAssembler scenarioRequestAssembler;
    private final ScenarioGeneratedPersistService scenarioGeneratedPersistService;
    private final ScenarioResultCacheService scenarioResultCacheService;

    public ScenarioEngineAdapter(
            ScenarioGenerationEngine scenarioGenerationEngine,
            ScenarioRequestAssembler scenarioRequestAssembler,
            ScenarioGeneratedPersistService scenarioGeneratedPersistService,
            ScenarioResultCacheService scenarioResultCacheService) {
        this.scenarioGenerationEngine = scenarioGenerationEngine;
        this.scenarioRequestAssembler = scenarioRequestAssembler;
        this.scenarioGeneratedPersistService = scenarioGeneratedPersistService;
        this.scenarioResultCacheService = scenarioResultCacheService;
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
        String scenarioIdList = requiredString(req, "scenario_id_list");
        String dataDate = requiredString(req, "data_date");
        String batchId = trimToNull(firstNonBlank(req.getString("batch_id"), req.getString("batchId")));
        List<ScenarioGeneratedRecord> records = generateRecords(req);
        String runId = resolveRunId(batchId, scenarioIdList, dataDate);
        JSONObject summary = buildSummary(runId, scenarioIdList, dataDate, batchId, records);
        return JSON.toJSONString(summary, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    public List<ScenarioGeneratedRecord> generateRecords(JSONObject req) {
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
        String batchId = trimToNull(firstNonBlank(req.getString("batch_id"), req.getString("batchId")));
        Boolean persistScenario = readBoolean(req, "persist_scenario", "persistScenario");

        ScenarioGenerationRequest request = scenarioRequestAssembler.build(
                scenarioIdList,
                LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE),
                user,
                "mr-app");
        List<ScenarioGeneratedRecord> result = scenarioGenerationEngine.generate(request);
        if (scenarioGeneratedPersistService != null) {
            scenarioGeneratedPersistService.persist(batchId, dataDate, persistScenario, result);
        }
        return result;
    }

    private static String requiredString(JSONObject obj, String key) {
        String value = obj.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private static Boolean readBoolean(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object raw = obj.get(key);
            if (raw == null) {
                continue;
            }
            if (raw instanceof Boolean) {
                return (Boolean) raw;
            }
            String text = trimToNull(String.valueOf(raw));
            if (text == null) {
                continue;
            }
            if ("true".equalsIgnoreCase(text) || "1".equals(text) || "y".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text) || "n".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String safe = trimToNull(value);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    private JSONObject buildSummary(String runId,
                                    String scenarioIdList,
                                    String dataDate,
                                    String batchId,
                                    List<ScenarioGeneratedRecord> records) {
        JSONObject summary;
        if (scenarioResultCacheService != null) {
            summary = scenarioResultCacheService.cacheRunResult(runId, scenarioIdList, dataDate, records);
        } else {
            summary = new JSONObject();
            summary.put("run_id", runId);
            summary.put("scenario_id_list", scenarioIdList);
            summary.put("data_date", dataDate);
            summary.put("record_count", records == null ? 0 : records.size());
            summary.put("cache_ready", false);
            summary.put("scenario_ids", new JSONArray());
            summary.put("sub_scenario_groups", new JSONArray());
        }
        summary.put("engine_code", CODE);
        summary.put("batch_id", batchId);
        summary.put("redis_query_required", true);
        summary.put("query_dimensions", JSON.parseArray("[\"scenario_id\",\"sub_scenario_id\",\"curve_type\",\"curve_id\"]"));
        summary.put("run_mode", "REDIS_QUERY");
        return summary;
    }

    private static String resolveRunId(String batchId, String scenarioIdList, String dataDate) {
        String safeBatchId = trimToNull(batchId);
        if (safeBatchId != null) {
            return safeBatchId;
        }
        String scenarioToken = trimToNull(scenarioIdList);
        if (scenarioToken == null) {
            scenarioToken = "scenario";
        }
        scenarioToken = scenarioToken.replace(',', '_').replace(' ', '_');
        if (scenarioToken.length() > 40) {
            scenarioToken = scenarioToken.substring(0, 40);
        }
        return String.format(Locale.ROOT,
                "scenario_%s_%s_%s",
                trimToNull(dataDate) == null ? "unknown" : dataDate,
                scenarioToken,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSS")));
    }
}
