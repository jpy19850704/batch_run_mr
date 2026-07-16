package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.Loader;

/**
 * Calc 情景 PnL 结果外壳组装器。
 */
public final class ScenarioPnlResultAssembler {

    public JSONObject assemble(Loader.ScenarioEntry entry, JSONArray tradeData, String resultKind) {
        JSONObject item = new JSONObject();
        if (entry != null) {
            Loader.ScenarioEntry.ScenarioProcessMetadata metadata = entry.processMetadata;
            if (hasText(entry.scenarioId)) {
                item.put("SCENARIO_ID", entry.scenarioId);
            }
            if (hasText(entry.subScenarioId)) {
                item.put("SUBSCENARIO_ID", entry.subScenarioId);
            }
            item.put("SCENARIO_NAME", entry.scenarioName);
            if (hasText(entry.scenarioType)) {
                item.put("SCENARIO_TYPE", entry.scenarioType);
            }
            if (metadata != null && hasText(metadata.processType)) {
                item.put("SCENARIO_PROCESS_TYPE", metadata.processType);
            }
            item.put("SCENARIO_TAG", metadata == null || metadata.tag == null
                    ? new JSONObject() : metadata.tag);
            if (metadata != null && hasText(metadata.entryKey)) {
                item.put("SCENARIO_ENTRY_KEY", metadata.entryKey);
            }
            if (metadata != null && hasText(metadata.nmrfRiskFactorId)) {
                item.put("RISK_FACTOR_ID", metadata.nmrfRiskFactorId);
            }
            if (metadata != null && hasText(metadata.nmrfType)) {
                item.put("NMRF_TYPE", metadata.nmrfType);
            }
        } else {
            item.put("SCENARIO_NAME", null);
            item.put("SCENARIO_TAG", new JSONObject());
        }
        if (hasText(resultKind)) {
            item.put("RESULT_KIND", resultKind);
        }
        item.put("trade_data", tradeData == null ? new JSONArray() : tradeData);
        return item;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
