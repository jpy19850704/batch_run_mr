package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.CalcScenarioInputCache;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.out.file.ScenarioSetPathResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobPayloadBuilderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearScenarioInputCache() {
        CalcScenarioInputCache.clear();
    }

    @Test
    void buildPayloadRejectsInvalidTradeJson() {
        JobPayloadBuilder builder = new JobPayloadBuilder();
        BatchTradeDataLoader.TradeRow trade = trade("T_BAD", "{bad-json");

        PayloadJsonParseException ex = assertThrows(PayloadJsonParseException.class, () ->
                builder.buildPayload(
                        "PRICING",
                        LocalDate.of(2026, 7, 9),
                        Collections.singletonList(trade),
                        Collections.<MrMarketDataSliceService.CurveSliceSource>emptyList(),
                        Collections.<String, java.util.Set<String>>emptyMap(),
                        "BATCH_TEST",
                        1,
                        null,
                        null,
                        true,
                        false));

        assertTrue(ex.getMessage().contains("instrumentId=T_BAD"));
    }

    @Test
    void payloadBuildTaskMarksOnlyInvalidChunkFailed() {
        BatchPayloadBuildTask task = new BatchPayloadBuildTask(
                new MrMarketDataSliceService(),
                new JobPayloadBuilder());
        BatchRunWorkflowContext context = new BatchRunWorkflowContext();
        context.setBatchId("BATCH_TEST");
        context.setDataDate(LocalDate.of(2026, 7, 9).format(DateTimeFormatter.BASIC_ISO_DATE));

        List<List<BatchTradeDataLoader.TradeRow>> chunks = new ArrayList<List<BatchTradeDataLoader.TradeRow>>();
        chunks.add(Collections.singletonList(trade("T_BAD", "{bad-json")));
        chunks.add(Collections.singletonList(trade("T_OK", "{\"INSTRUMENT_ID\":\"T_OK\"}")));
        context.setTradeChunks(chunks);

        task.execute(context);

        assertEquals(2, context.getJobPayloads().size());
        assertTrue(context.getJobPayloads().get(0).isFailed());
        assertEquals(BatchJobService.PAYLOAD_JSON_PARSE_ERROR, context.getJobPayloads().get(0).getErrorCode());
        assertTrue(context.getJobPayloads().get(0).getErrorMessage().contains("instrumentId=T_BAD"));
        assertFalse(context.getJobPayloads().get(1).isFailed());
        assertNotNull(context.getJobPayloads().get(1).getPayload());
    }

    @Test
    void payloadBuildTaskCollectsMarketKeysFromAllChunks() {
        MrMarketDataSliceService sliceService = new MrMarketDataSliceService() {
            @Override
            public SliceResult sliceCurvesWithTradeKeys(
                    List<TradeSliceSource> trades,
                    List<CurveSliceSource> curves) {
                String instrumentId = trades.get(0).getInstrumentId();
                Set<String> keys = new LinkedHashSet<String>();
                keys.add("T_IR".equals(instrumentId) ? "IR_SPOT:CNY_IR" : "EQ_SPOT:000001");
                Map<String, Set<String>> perTrade = new LinkedHashMap<String, Set<String>>();
                perTrade.put(instrumentId, keys);
                return new SliceResult(Collections.<CurveSliceSource>emptyList(), perTrade);
            }
        };
        BatchPayloadBuildTask task = new BatchPayloadBuildTask(sliceService, new JobPayloadBuilder());
        BatchRunWorkflowContext context = new BatchRunWorkflowContext();
        context.setBatchId("BATCH_MARKET_KEYS");
        context.setDataDate(LocalDate.of(2026, 7, 9).format(DateTimeFormatter.BASIC_ISO_DATE));
        context.setTradeChunks(List.of(
                Collections.singletonList(trade("T_IR", "{\"INSTRUMENT_ID\":\"T_IR\"}")),
                Collections.singletonList(trade("T_EQ", "{\"INSTRUMENT_ID\":\"T_EQ\"}"))));

        task.execute(context);

        assertEquals(Set.of("IR_SPOT:CNY_IR", "EQ_SPOT:000001"), context.getScenarioMarketKeys());
        assertFalse(context.getJobPayloads().get(0).getPayload().containsKey("scenarioMarketKeys"));
        assertFalse(context.getJobPayloads().get(1).getPayload().containsKey("scenarioMarketKeys"));
    }

    @Test
    void scenarioCacheLoadTaskLoadsFilteredCacheOnceAndInjectsSameKey() throws Exception {
        String dataDate = "20260331";
        String batchId = "BATCH_SCENARIO_CACHE";
        Path scenarioFile = tempDir.resolve(dataDate).resolve(batchId).resolve("S_VAR.csv.gz");
        writeScenarioFile(scenarioFile);

        BatchRunWorkflowContext context = new BatchRunWorkflowContext();
        context.setScenarioMode(true);
        context.setDataDate(dataDate);
        context.setBatchId(batchId);
        context.setScenarioMarketKeys(Set.of("IR_SPOT:CNY_IR"));
        context.setJobPayloads(List.of(scenarioJobPayload(), scenarioJobPayload()));

        BatchScenarioInputLoadTask task = new BatchScenarioInputLoadTask(
                new ScenarioSetPathResolver(tempDir.toString()));
        task.execute(context);

        String firstCacheKey = cacheKey(context.getJobPayloads().get(0));
        String secondCacheKey = cacheKey(context.getJobPayloads().get(1));
        assertEquals(firstCacheKey, secondCacheKey);
        assertEquals(1, CalcScenarioInputCache.size());
        List<Loader.ScenarioEntry> entries = CalcScenarioInputCache.get(firstCacheKey);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).marketData.irSpot.containsKey("CNY_IR"));
        assertFalse(entries.get(0).marketData.irSpot.containsKey("USD_IR"));
        assertEquals(2, entries.get(0).marketData.irSpot.get("CNY_IR").curveData.size());
    }

    @Test
    void calcAdapterRejectsScenarioReferenceWithoutPreparedCache() {
        MrCalcEngineAdapter adapter = new MrCalcEngineAdapter(null);
        JSONObject item = new JSONObject();
        item.put("scenario_set_id", "S_VAR");
        JSONObject payload = new JSONObject();
        payload.put("data_date", "20260331");
        payload.put(ScenarioProcessConstants.VAR_SCENARIO_REF_LIST, new JSONArray(List.of(item)));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> adapter.calculate(payload.toJSONString()));

        assertEquals("var_scenario_ref_list[0].cache_key 必填", ex.getMessage());
    }

    private static BatchJobPayload scenarioJobPayload() {
        JSONObject item = new JSONObject();
        item.put("scenario_set_id", "S_VAR");
        JSONObject payload = new JSONObject();
        payload.put(ScenarioProcessConstants.VAR_SCENARIO_REF_LIST, new JSONArray(List.of(item)));
        BatchJobPayload jobPayload = new BatchJobPayload();
        jobPayload.setPayload(payload);
        return jobPayload;
    }

    private static String cacheKey(BatchJobPayload jobPayload) {
        return jobPayload.getPayload()
                .getJSONArray(ScenarioProcessConstants.VAR_SCENARIO_REF_LIST)
                .getJSONObject(0)
                .getString("cache_key");
    }

    private static void writeScenarioFile(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
            writer.write("SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,CURVE_TYPE,CURVE_CODE,TERM_DAYS,DIMENSION2,CHANGED_RATE");
            writer.newLine();
            writer.write("S_VAR,S_VAR_1,VAR情景,HISTORICAL,IR_SPOT,CNY_IR,30,,0.021");
            writer.newLine();
            writer.write("S_VAR,S_VAR_1,VAR情景,HISTORICAL,IR_SPOT,CNY_IR,365,,0.025");
            writer.newLine();
            writer.write("S_VAR,S_VAR_1,VAR情景,HISTORICAL,IR_SPOT,USD_IR,365,,0.035");
            writer.newLine();
        }
    }

    private static BatchTradeDataLoader.TradeRow trade(String instrumentId, String contentText) {
        BatchTradeDataLoader.TradeRow trade = new BatchTradeDataLoader.TradeRow();
        trade.instrumentId = instrumentId;
        trade.productCode = "TEST_PRODUCT";
        trade.tradeContentText = contentText;
        return trade;
    }
}
