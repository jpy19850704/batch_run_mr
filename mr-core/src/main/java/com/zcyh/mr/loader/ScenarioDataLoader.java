package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 情景数据加载器。
 * 负责解析 scenario_data，并构造 Loader.ScenarioEntry。
 */
public class ScenarioDataLoader {
    private final MarketDataLoader marketDataLoader;

    public ScenarioDataLoader(MarketDataLoader marketDataLoader) {
        this.marketDataLoader = marketDataLoader;
    }

    /**
     * 加载情景数据。
     */
    public List<Loader.ScenarioEntry> load(JSONArray scenarioDataArray) {
        List<Loader.ScenarioEntry> scenarioEntries = new ArrayList<>();
        if (scenarioDataArray == null) {
            return scenarioEntries;
        }
        for (int i = 0; i < scenarioDataArray.size(); i++) {
            Object scenarioObj = scenarioDataArray.get(i);
            JSONObject scenarioJson = (JSONObject) scenarioObj;
            String scenarioName = readRequiredField(scenarioJson, "SCENARIO_NAME");
            String scenarioId = readRequiredField(scenarioJson, "SCENARIO_ID");
            String subScenarioId = readRequiredField(scenarioJson, "SUBSCENARIO_ID");
            String scenarioType = readRequiredField(scenarioJson, "SCENARIO_TYPE");
            if (scenarioName == null) {
                throw new IllegalArgumentException("scenario_data 缺少 SCENARIO_NAME: index=" + i);
            }
            if (scenarioId == null) {
                throw new IllegalArgumentException("scenario_data 缺少 SCENARIO_ID: index=" + i);
            }
            if (subScenarioId == null) {
                throw new IllegalArgumentException("scenario_data 缺少 SUBSCENARIO_ID: index=" + i);
            }
            if (scenarioType == null) {
                throw new IllegalArgumentException("scenario_data 缺少 SCENARIO_TYPE: index=" + i);
            }
            JSONArray scenarioMarketData = scenarioJson.getJSONArray("market_data");
            Set<String> impactKeys = parseImpactKeys(scenarioJson);
            if (scenarioMarketData == null) {
                throw new IllegalArgumentException("scenario_data 缺少 market_data: subScenarioId=" + subScenarioId);
            }
            MarketData scenarioMarket = marketDataLoader.loadScenarioMarketData(scenarioMarketData);
            scenarioEntries.add(new Loader.ScenarioEntry(
                    scenarioId,
                    subScenarioId,
                    scenarioName,
                    scenarioType,
                    scenarioMarket,
                    impactKeys));
        }
        return scenarioEntries;
    }

    private static String readRequiredField(JSONObject scenarioJson, String fieldName) {
        if (scenarioJson == null || fieldName == null) {
            return null;
        }
        Object raw = scenarioJson.get(fieldName);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private static Set<String> parseImpactKeys(JSONObject scenarioJson) {
        Set<String> keys = new LinkedHashSet<>();
        if (scenarioJson == null) {
            return keys;
        }
        JSONArray impactArray = scenarioJson.getJSONArray("IMPACT_KEYS");
        if (impactArray == null) {
            return keys;
        }
        for (int i = 0; i < impactArray.size(); i++) {
            String raw = impactArray.getString(i);
            if (raw == null) {
                continue;
            }
            String key = raw.trim().toUpperCase();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }
}
