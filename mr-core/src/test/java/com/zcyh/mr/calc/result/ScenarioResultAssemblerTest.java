package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenarioResultAssemblerTest {
    private final ScenarioResultAssembler assembler = new ScenarioResultAssembler();

    @Test
    void assembleKeepsScenarioMetadataAndResultKind() {
        JSONObject tag = new JSONObject().fluentPut("source", "HISTORICAL");
        Loader.ScenarioEntry entry = new Loader.ScenarioEntry(
                "S1", "SS1", "历史情景", "HISTORICAL",
                "NMRF", tag, "ENTRY-1", new MarketData(), Collections.<String>emptySet());
        entry.processMetadata.nmrfRiskFactorId = "RF1";
        entry.processMetadata.nmrfType = "IR";

        JSONObject result = assembler.assemble(entry, new JSONArray(), "SCENARIO");

        assertEquals("S1", result.getString("SCENARIO_ID"));
        assertEquals("SS1", result.getString("SUBSCENARIO_ID"));
        assertEquals("NMRF", result.getString("SCENARIO_PROCESS_TYPE"));
        assertEquals("RF1", result.getString("RISK_FACTOR_ID"));
        assertEquals("IR", result.getString("NMRF_TYPE"));
        assertEquals("SCENARIO", result.getString("RESULT_KIND"));
        assertEquals("HISTORICAL", result.getJSONObject("SCENARIO_TAG").getString("source"));
    }
}
