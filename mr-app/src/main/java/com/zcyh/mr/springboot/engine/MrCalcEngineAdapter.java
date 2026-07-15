package com.zcyh.mr.springboot.engine;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.calc.scenario.ScenarioCache;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.springboot.out.file.ScenarioSetPathResolver;
import com.zcyh.mr.springboot.service.ImaRiskFactorConfigService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MR 计量引擎适配器。
 */
public class MrCalcEngineAdapter implements EngineAdapter {
    public static final String CODE = "MR_CALC";
    private final ScenarioSetPathResolver scenarioSetPathResolver;
    private final ImaRiskFactorConfigService imaRiskFactorConfigService;

    public MrCalcEngineAdapter(
            ScenarioSetPathResolver scenarioSetPathResolver,
            ImaRiskFactorConfigService imaRiskFactorConfigService) {
        this.scenarioSetPathResolver = scenarioSetPathResolver;
        this.imaRiskFactorConfigService = imaRiskFactorConfigService;
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
        return runSingle(singlePayload);
    }

    private String runSingle(JSONObject taskPayload) {
        LiquidityHorizonTable imaRiskFactorConfig = loadImaRiskFactorConfigIfNeeded(taskPayload);
        Calc calc = new Calc(
                taskPayload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain),
                null,
                imaRiskFactorConfig);
        return calc.run();
    }

    LiquidityHorizonTable loadImaRiskFactorConfigIfNeeded(JSONObject payload) {
        if (!hasImaScenarioRefList(payload)) {
            return null;
        }
        if (imaRiskFactorConfigService == null) {
            throw new IllegalStateException("IMA 计量缺少风险因子配置读取服务");
        }
        String dataDate = requiredPayloadField(payload, "data_date");
        return imaRiskFactorConfigService.loadImaRiskFactorConfig(
                LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE));
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
            String scenarioIdList = trimToNull(item.getString("scenario_set_id"));
            if (scenarioIdList == null) {
                throw new IllegalArgumentException(fieldName + "[" + i + "].scenario_set_id 必填");
            }
            String cacheKey = existingCacheKey == null
                    ? buildCacheKey(fieldName, date, batchId, scenarioIdList)
                    : existingCacheKey;
            ScenarioCache.loadFromFiles(cacheKey, resolveScenarioPaths(scenarioIdList, date, batchId), date);
            item.put("cache_key", cacheKey);
        }
    }

    private static boolean hasScenarioRefList(JSONObject payload) {
        return hasArray(payload, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST);
    }

    private static boolean hasImaScenarioRefList(JSONObject payload) {
        return hasArray(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST)
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

    private List<String> resolveScenarioPaths(String scenarioIdList, LocalDate dataDate, String batchId) {
        List<String> result = new ArrayList<String>();
        for (String scenarioId : parseScenarioIds(scenarioIdList)) {
            Path path = scenarioSetPathResolver.resolveScenarioFile(
                    dataDate.format(DateTimeFormatter.BASIC_ISO_DATE), batchId, scenarioId);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("scenario 文件不存在: " + path);
            }
            result.add(path.toString());
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

    private String buildCacheKey(String type, LocalDate dataDate, String batchId, String scenarioIdList) {
        return type + ":" + dataDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + ":" + batchId + ":" + scenarioIdList;
    }
}
