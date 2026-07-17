package com.zcyh.mr.calc.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.Loader;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalcScenarioInputFileReaderTest {

    @Test
    void readAndParse_whenGeneratedScenarioCsv_shouldBuildScenarioEntry() throws URISyntaxException {
        Path path = Paths.get(getClass().getResource("/scenario/generated-scenario.csv").toURI());
        CalcScenarioInputFileReader reader = new CalcScenarioInputFileReader();

        List<Loader.ScenarioEntry> entries = reader.readScenarioEntries(
                List.of(path), LocalDate.of(2026, 3, 31));

        assertEquals(1, entries.size());
        Loader.ScenarioEntry entry = entries.get(0);
        assertEquals("S_IR", entry.scenarioId);
        assertEquals("IR_UP", entry.subScenarioId);
        assertTrue(entry.impactKeys.contains("IR_SPOT:CNY_IR"));
        assertEquals(0.025, entry.marketData.irSpot.get("CNY_IR").curveData.get(365));
    }

    @Test
    void parseScenarioList_whenInlineArrayProvided_shouldBuildSameStructure() {
        CalcScenarioInputFileReader reader = new CalcScenarioInputFileReader();
        JSONObject row = new JSONObject();
        row.put("SCENARIO_ID", "S_IR");
        row.put("SUBSCENARIO_ID", "IR_UP");
        row.put("SCENARIO_NAME", "利率上行");
        row.put("SCENARIO_TYPE", "HISTORICAL");
        row.put("CURVE_TYPE", "IR_SPOT");
        row.put("CURVE_CODE", "CNY_IR");
        row.put("TERM_DAYS", "365");
        row.put("CHANGED_RATE", "0.025");

        List<Loader.ScenarioEntry> entries = reader.parseScenarioList(
                new JSONArray(List.of(row)), LocalDate.of(2026, 3, 31));

        assertEquals(1, entries.size());
        assertEquals("S_IR", entries.get(0).scenarioId);
        assertEquals(0.025, entries.get(0).marketData.irSpot.get("CNY_IR").curveData.get(365));
    }

    @Test
    void parseScenarioList_whenMarketKeysProvided_shouldKeepWholeMatchedCurve() {
        CalcScenarioInputFileReader reader = new CalcScenarioInputFileReader();
        JSONArray rows = new JSONArray();
        rows.add(row("IR_SPOT", "CNY_IR", 30, 0.021));
        rows.add(row("IR_SPOT", "CNY_IR", 365, 0.025));
        rows.add(row("IR_SPOT", "USD_IR", 365, 0.035));

        List<Loader.ScenarioEntry> entries = reader.parseScenarioList(
                rows,
                LocalDate.of(2026, 3, 31),
                Set.of("IR_SPOT:CNY_IR"));

        assertEquals(1, entries.size());
        assertEquals(2, entries.get(0).marketData.irSpot.get("CNY_IR").curveData.size());
        assertTrue(entries.get(0).marketData.irSpot.get("USD_IR") == null);
        assertEquals(Set.of("IR_SPOT:CNY_IR"), entries.get(0).impactKeys);
    }

    @Test
    void parseScenarioList_whenFxSpotTypeSelected_shouldKeepAllCurrencyPairs() {
        CalcScenarioInputFileReader reader = new CalcScenarioInputFileReader();
        JSONArray rows = new JSONArray();
        rows.add(row("FX_SPOT", "USD/CNY", null, 7.10));
        rows.add(row("FX_SPOT", "EUR/CNY", null, 7.80));

        List<Loader.ScenarioEntry> entries = reader.parseScenarioList(
                rows,
                LocalDate.of(2026, 3, 31),
                Set.of("FX_SPOT"));

        assertEquals(2, entries.get(0).marketData.fxSpot.curveData.size());
    }

    @Test
    void parseScenarioLoadResult_shouldCountRawAndRetainedPointsAfterMarketKeyFilter() {
        CalcScenarioInputFileReader reader = new CalcScenarioInputFileReader();
        JSONArray rows = new JSONArray();
        rows.add(row("IR_SPOT", "CNY_IR", 30, 0.021));
        rows.add(row("IR_SPOT", "USD_IR", 30, 0.031));
        rows.add(row("IR_SPOT", "CNY_IR", 365, 0.025));

        CalcScenarioInputFileReader.ScenarioLoadResult result = reader.parseScenarioLoadResult(
                rows,
                LocalDate.of(2026, 3, 31),
                Set.of("IR_SPOT:CNY_IR"),
                2L);

        assertEquals(3L, result.rawPoints);
        assertEquals(2L, result.retainedPoints);
        assertEquals(2, result.entries.get(0).marketData.irSpot.get("CNY_IR").curveData.size());
    }

    @Test
    void parseScenarioLoadResult_whenRetainedPointsExceedLimit_shouldFail() {
        CalcScenarioInputFileReader reader = new CalcScenarioInputFileReader();
        JSONArray rows = new JSONArray();
        rows.add(row("IR_SPOT", "CNY_IR", 30, 0.021));
        rows.add(row("IR_SPOT", "CNY_IR", 365, 0.025));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> reader.parseScenarioLoadResult(
                        rows,
                        LocalDate.of(2026, 3, 31),
                        Set.of("IR_SPOT:CNY_IR"),
                        1L));

        assertTrue(error.getMessage().contains("剪裁后情景点数超过缓存上限"));
    }

    private static JSONObject row(String curveType, String curveCode, Integer termDays, double changedRate) {
        JSONObject row = new JSONObject();
        row.put("SCENARIO_ID", "S_TEST");
        row.put("SUBSCENARIO_ID", "S_TEST_1");
        row.put("SCENARIO_NAME", "测试情景");
        row.put("SCENARIO_TYPE", "HISTORICAL");
        row.put("CURVE_TYPE", curveType);
        row.put("CURVE_CODE", curveCode);
        if (termDays != null) {
            row.put("TERM_DAYS", termDays);
        }
        row.put("CHANGED_RATE", changedRate);
        return row;
    }
}
