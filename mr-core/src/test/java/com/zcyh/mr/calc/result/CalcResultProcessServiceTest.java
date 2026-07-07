package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalcResultProcessServiceTest {

    @Test
    void baseErrorScenarioPnlUsesSummaryLog() {
        JSONObject baseTrade = new JSONObject();
        baseTrade.put("INSTRUMENT_ID", "T_BASE_ERR");
        baseTrade.put("VALUATION_CNY", 100.0);
        baseTrade.put("STATUS", "ERROR");
        baseTrade.put("LOGS", logs("具体基准错误"));

        JSONObject row = CalcResultProcessService.buildBaseErrorPnlRow(baseTrade);

        assertEquals("ERROR", row.getString("STATUS"));
        assertEquals(0.0, row.getDoubleValue("BASE_VALUATION_CNY"));
        assertEquals(0.0, row.getDoubleValue("SCENARIO_VALUATION_CNY"));
        assertEquals(0.0, row.getDoubleValue("PNL"));
        assertEquals("基准估值错误", row.getJSONArray("LOGS").getJSONObject(0).getString("message"));
    }

    @Test
    void scenarioErrorScenarioPnlKeepsSpecificLog() {
        JSONObject baseTrade = new JSONObject();
        baseTrade.put("INSTRUMENT_ID", "T_SCEN_ERR");
        baseTrade.put("VALUATION_CNY", 100.0);

        JSONObject scenarioTrade = new JSONObject();
        scenarioTrade.put("INSTRUMENT_ID", "T_SCEN_ERR");
        scenarioTrade.put("STATUS", "ERROR");
        scenarioTrade.put("LOGS", logs("情景市场数据异常"));

        JSONObject row = CalcResultProcessService.buildScenarioErrorPnlRow(baseTrade, scenarioTrade);

        assertEquals("ERROR", row.getString("STATUS"));
        assertEquals(100.0, row.getDoubleValue("BASE_VALUATION_CNY"));
        assertEquals(100.0, row.getDoubleValue("SCENARIO_VALUATION_CNY"));
        assertEquals(0.0, row.getDoubleValue("PNL"));
        assertEquals("情景市场数据异常", row.getJSONArray("LOGS").getJSONObject(0).getString("message"));
    }

    @Test
    void unsupportedScenarioProductWritesErrorLogInScenarioRow() {
        JSONObject baseTrade = new JSONObject();
        baseTrade.put("INSTRUMENT_ID", "T_UNSUPPORTED");
        baseTrade.put("PRODUCT_CODE", "PROD_UNSUPPORTED");
        baseTrade.put("VALUATION_CNY", 100.0);
        Map<String, JSONObject> baseIndex = new LinkedHashMap<>();
        baseIndex.put("T_UNSUPPORTED", baseTrade);

        JSONArray rows = CalcResultProcessService.buildPnlResults(
                baseIndex,
                new JSONArray(),
                Collections.singleton("PROD_UNSUPPORTED"));
        JSONObject row = rows.getJSONObject(0);

        assertEquals("ERROR", row.getString("STATUS"));
        assertEquals(0.0, row.getDoubleValue("PNL"));
        assertEquals("产品类型不支持情景: PROD_UNSUPPORTED",
                row.getJSONArray("LOGS").getJSONObject(0).getString("message"));
    }

    private static JSONArray logs(String message) {
        JSONArray logs = new JSONArray();
        JSONObject log = new JSONObject();
        log.put("level", "ERROR");
        log.put("message", message);
        logs.add(log);
        return logs;
    }
}
