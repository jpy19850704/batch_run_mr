package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.calendar.SystemCalendarCache;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 解析 json 类。
 * 作为统一入口协调交易、市场、情景和 CurveGeneration 四类数据加载。
 */
public class Loader {
    private List<HashMap<String, Object>> trades = new ArrayList<>();
    private MarketData marketData;
    private String calcMode;
    private LocalDate dataDate;
    private Calendar calendar;
    private JSONObject otherData;
    private List<ScenarioEntry> scenarioDataList = new ArrayList<>();
    private List<CurveGeneration.CurveInput> curveGenerationInputs = new ArrayList<>();
    private JSONArray validationErrors = new JSONArray();

    /**
     * 压力情景条目：情景名称 + 情景下的市场数据。
     */
    public static class ScenarioEntry {
        public String scenarioId;
        public String subScenarioId;
        public String scenarioName;
        public String scenarioType;
        public ScenarioProcessMetadata processMetadata;
        public MarketData marketData;
        public java.util.Set<String> impactKeys;

        public static class ScenarioProcessMetadata {
            public String processType;
            public JSONObject tag;
            public String entryKey;
            public String nmrfRiskFactorId;
            public String nmrfType;

            public ScenarioProcessMetadata(String processType, JSONObject tag, String entryKey) {
                this.processType = processType;
                this.tag = tag;
                this.entryKey = entryKey;
            }
        }

        public ScenarioEntry(String scenarioName, MarketData marketData) {
            this(null, null, scenarioName, marketData, new java.util.LinkedHashSet<>());
        }

        public ScenarioEntry(String scenarioName, MarketData marketData, java.util.Set<String> impactKeys) {
            this(null, null, scenarioName, marketData, impactKeys);
        }

        public ScenarioEntry(String scenarioId, String subScenarioId, String scenarioName, MarketData marketData,
                java.util.Set<String> impactKeys) {
            this(scenarioId, subScenarioId, scenarioName, null, marketData, impactKeys);
        }

        public ScenarioEntry(String scenarioId, String subScenarioId, String scenarioName, String scenarioType,
                MarketData marketData, java.util.Set<String> impactKeys) {
            this(scenarioId, subScenarioId, scenarioName, scenarioType, null, null, null, marketData, impactKeys);
        }

        public ScenarioEntry(String scenarioId, String subScenarioId, String scenarioName, String scenarioType,
                String scenarioProcessType, JSONObject scenarioTag, String scenarioEntryKey,
                MarketData marketData, java.util.Set<String> impactKeys) {
            this.scenarioId = scenarioId;
            this.subScenarioId = subScenarioId;
            this.scenarioName = scenarioName;
            this.scenarioType = scenarioType;
            this.processMetadata = new ScenarioProcessMetadata(scenarioProcessType, scenarioTag, scenarioEntryKey);
            this.marketData = marketData;
            this.impactKeys = impactKeys == null ? new java.util.LinkedHashSet<>() : impactKeys;
        }
    }

    public Loader() {
        this(null, null);
    }

    public Loader(Calendar calendar) {
        trades = new ArrayList<>();
        marketData = new MarketData();
        this.calendar = SystemCalendarCache.resolve(calendar);
    }

    /**
     * 对输入的 json 字符串进行解析，生成对应的市场数据和交易数据。
     *
     * @param data 输入 JSON 字符串
     * @throws IllegalArgumentException 当 JSON 格式错误或必填字段缺失时抛出
     */
    public Loader(String data) {
        this(data, null);
    }

    public Loader(String data, Calendar calendar) {
        trades = new ArrayList<>();
        marketData = new MarketData();
        this.calendar = SystemCalendarCache.resolve(calendar);

        JSONObject payload = parsePayload(data);
        calcMode = payload.getString("calc_mode");
        dataDate = parseDataDate(payload);
        otherData = payload.getJSONObject("other_data");

        TradeDataLoader tradeDataLoader = new TradeDataLoader(validationErrors);
        trades = tradeDataLoader.load(payload.getJSONArray("trade_data"), otherData);

        MarketDataLoader marketDataLoader = new MarketDataLoader(
                dataDate,
                validationErrors,
                resolveDefaultFxSpotBaseCurrency());
        JSONArray marketDataArray = payload.getJSONArray("market_data");
        if (marketDataArray != null) {
            marketData = marketDataLoader.loadBaseMarketData(marketDataArray);
        }

        CurveGenerationLoader curveGenerationLoader = new CurveGenerationLoader();
        curveGenerationInputs = curveGenerationLoader.load(payload.getJSONArray("curve_generation"));

        ScenarioDataLoader scenarioDataLoader = new ScenarioDataLoader(marketDataLoader);
        scenarioDataList = scenarioDataLoader.load(payload.getJSONArray("scenario_data"));
    }

    private JSONObject parsePayload(String data) {
        JSONObject payload;
        try {
            payload = JSON.parseObject(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("输入 JSON 格式错误: " + e.getMessage(), e);
        }
        if (payload == null) {
            throw new IllegalArgumentException("输入 JSON 为空");
        }
        return payload;
    }

    private LocalDate parseDataDate(JSONObject payload) {
        String dateStr = payload.getString("data_date");
        if (dateStr == null || dateStr.isEmpty()) {
            throw new IllegalArgumentException("缺少必填字段: data_date");
        }
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("data_date 格式错误（应为 yyyy-MM-dd）: " + dateStr, e);
        }
    }

    private String resolveDefaultFxSpotBaseCurrency() {
        String value = EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_SPOT_BASE_CODE);
        if (value == null || value.trim().isEmpty()) {
            return "USD";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public String getCalcMode() {
        return calcMode;
    }

    public LocalDate getDataDate() {
        return dataDate;
    }

    public List<HashMap<String, Object>> getTrades() {
        return trades;
    }

    public MarketData getMarketData() {
        return marketData;
    }

    /**
     * 获取日历数据。
     * 日历由外部统一准备；若未注入则默认全部日期视为工作日。
     */
    public Calendar getCalendar() {
        return calendar;
    }

    public JSONObject getOtherData() {
        return otherData;
    }

    public List<ScenarioEntry> getScenarioDataList() {
        return scenarioDataList;
    }

    public List<CurveGeneration.CurveInput> getCurveGenerationInputs() {
        return curveGenerationInputs;
    }

    /**
     * 获取数据校验错误列表。
     */
    public JSONArray getValidationErrors() {
        return validationErrors;
    }
}
