package com.zcyh.mr.calc.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.zcyh.mr.loader.Loader;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioFileReaderTest {

    @Test
    void readAndParse_whenGeneratedScenarioCsv_shouldBuildScenarioEntry() throws URISyntaxException {
        Path path = Paths.get(getClass().getResource("/scenario/generated-scenario.csv").toURI());
        ScenarioFileReader reader = new ScenarioFileReader();

        JSONArray records = reader.read(path);
        List<Loader.ScenarioEntry> entries = reader.parseScenarioList(records, LocalDate.of(2026, 3, 31));

        assertEquals(1, entries.size());
        Loader.ScenarioEntry entry = entries.get(0);
        assertEquals("S_IR", entry.scenarioId);
        assertEquals("IR_UP", entry.subScenarioId);
        assertTrue(entry.impactKeys.contains("IR_SPOT:CNY_IR"));
        assertEquals(0.025, entry.marketData.irSpot.get("CNY_IR").curveData.get(365));
    }
}
