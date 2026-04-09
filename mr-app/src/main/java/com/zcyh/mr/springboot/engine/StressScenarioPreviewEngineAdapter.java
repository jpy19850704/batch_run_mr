package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.ScenarioCache;
import com.zcyh.mr.springboot.service.BatchTradeDataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StressScenarioPreviewEngineAdapter implements EngineAdapter {
    public static final String CODE = "stress_pnl_preview";
    private static final Logger log = LoggerFactory.getLogger(StressScenarioPreviewEngineAdapter.class);

    private final BatchTradeDataLoader batchTradeDataLoader;
    private final MrCalcEngineAdapter mrCalcEngineAdapter;

    public StressScenarioPreviewEngineAdapter(BatchTradeDataLoader batchTradeDataLoader,
                                              MrCalcEngineAdapter mrCalcEngineAdapter) {
        this.batchTradeDataLoader = batchTradeDataLoader;
        this.mrCalcEngineAdapter = mrCalcEngineAdapter;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Stress scenario pnl preview adapter (scenario_data inline, no persistence)";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        String dataDate = requiredString(req, "data_date");
        JSONArray scenarioData = req.getJSONArray("scenario_data");
        if (scenarioData == null || scenarioData.isEmpty()) {
            throw new IllegalArgumentException("scenario_data is required");
        }

        LocalDate date = parseDataDate(dataDate);
        String portfolio = trimToNull(req.getString("portfolio"));
        String desk = trimToNull(req.getString("desk"));

        List<BatchTradeDataLoader.TradeRow> trades = batchTradeDataLoader.loadTradeRows(date, portfolio, desk);
        if (trades == null || trades.isEmpty()) {
            throw new IllegalArgumentException("未查询到交易数据，请检查 data_date/portfolio/desk 条件");
        }
        List<BatchTradeDataLoader.CurveRow> curves = batchTradeDataLoader.loadCurveRows(date);
        if (curves == null || curves.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 MR_MARKET_CURVE_INPUT");
        }

        TradeBuildBundle tradeBuildBundle = buildTradeDataAndMetadata(trades);
        JSONArray tradeData = tradeBuildBundle.tradeData;
        JSONArray marketData = buildMarketData(curves);
        Map<String, BatchTradeDataLoader.PortfolioFlatRow> portfolioFlatMap = loadPortfolioFlatMap(tradeBuildBundle.portfolioCodes);

        JSONObject calcPayload = new JSONObject();
        calcPayload.put("oper_code", "SCENARIO");
        calcPayload.put("data_date", dataDate);
        calcPayload.put("trade_data", tradeData);
        calcPayload.put("market_data", marketData);
        ScenarioPayloadBinding scenarioBinding = bindScenarioPayload(scenarioData, date);
        if (scenarioBinding.inlineScenarioData != null) {
            calcPayload.put("scenario_data", scenarioBinding.inlineScenarioData);
        }
        if (scenarioBinding.cacheKey != null) {
            JSONObject scenarioRef = new JSONObject();
            scenarioRef.put("cache_key", scenarioBinding.cacheKey);
            calcPayload.put("scenario_ref", scenarioRef);
        }

        String raw = mrCalcEngineAdapter.calculate(
                calcPayload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        JSONObject calcJson = JSON.parseObject(raw);
        JSONObject calcData = calcJson == null ? null : calcJson.getJSONObject("data");
        if (calcData == null) {
            throw new IllegalStateException("MR 计量返回结构异常，缺少 data 节点");
        }
        JSONArray scenarioResult = calcData.getJSONArray("scenario_result");
        enrichScenarioResultTradeData(scenarioResult, tradeBuildBundle.instrumentMetadata, portfolioFlatMap);

        JSONObject out = new JSONObject();
        out.put("data_date", dataDate);
        out.put("trade_count", tradeData.size());
        out.put("market_curve_count", marketData.size());
        out.put("scenario_count", scenarioData.size());
        out.put("scenario_result", scenarioResult);
        out.put("log_data", calcData.getJSONArray("log_data"));
        return JSON.toJSONString(out, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    /**
     * 统一绑定场景输入：
     * 1) 已是标准 scenario_data（包含 market_data）时直接透传；
     * 2) 扁平记录（runscenario/Redis 格式）时先归一化并写入 ScenarioCache，再通过 cache_key 传递给 Calc。
     */
    private static ScenarioPayloadBinding bindScenarioPayload(JSONArray scenarioData, LocalDate dataDate) {
        if (containsInlineScenarioData(scenarioData)) {
            return ScenarioPayloadBinding.withInline(scenarioData);
        }

        JSONArray normalizedFlatRows = normalizeFlatScenarioRows(scenarioData);
        if (normalizedFlatRows.isEmpty()) {
            throw new IllegalArgumentException("scenario_data 不包含可识别的情景记录");
        }

        String cacheKey = "stress_preview_" + dataDate + "_" + UUID.randomUUID().toString().replace("-", "");
        ScenarioCache.loadFromArray(cacheKey, normalizedFlatRows, dataDate);
        return ScenarioPayloadBinding.withCacheKey(cacheKey);
    }

    /**
     * 判断是否为标准 scenario_data（每个情景包含 market_data）。
     */
    private static boolean containsInlineScenarioData(JSONArray scenarioData) {
        if (scenarioData == null || scenarioData.isEmpty()) {
            return false;
        }
        for (int i = 0; i < scenarioData.size(); i++) {
            JSONObject row = asJsonObject(scenarioData.get(i));
            if (row == null) {
                continue;
            }
            Object marketData = firstNonNull(row,
                    "market_data",
                    "MARKET_DATA");
            if (marketData instanceof JSONArray) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 runscenario 返回的扁平记录归一化为 ScenarioCache 可解析的字段结构。
     * 统一只使用 SCENARIO_ID / SUBSCENARIO_ID，不再输出旧字段。
     */
    private static JSONArray normalizeFlatScenarioRows(JSONArray scenarioData) {
        JSONArray result = new JSONArray();
        if (scenarioData == null || scenarioData.isEmpty()) {
            return result;
        }
        for (int i = 0; i < scenarioData.size(); i++) {
            JSONObject raw = asJsonObject(scenarioData.get(i));
            if (raw == null) {
                continue;
            }
            JSONObject normalized = normalizeFlatScenarioRow(raw);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static JSONObject normalizeFlatScenarioRow(JSONObject raw) {
        String curveType = readText(raw, "CURVE_TYPE", "curveType", "curve_type");
        String curveCode = readText(raw, "CURVE_CODE", "curveCode", "curve_code");
        Object changedRate = firstNonNull(raw,
                "CHANGED_RATE",
                "changedRate",
                "changed_rate",
                "CHANGED_VALUE",
                "changedValue",
                "changed_value");
        if (curveType == null || curveCode == null || changedRate == null) {
            return null;
        }

        String scenarioId = readText(raw, "SCENARIO_ID", "scenarioId", "scenario_id");
        String subScenarioId = readText(raw,
                "SUBSCENARIO_ID",
                "subScenarioId",
                "subscenarioId",
                "subscenario_id");
        String scenarioName = readText(raw, "SCENARIO_NAME", "scenarioName", "scenario_name");
        String scenarioType = readText(raw, "SCENARIO_TYPE", "scenarioType", "scenario_type");
        String termCode = readText(raw, "TERM_CODE", "termCode", "term_code");
        Integer termDays = readInteger(raw, "TERM_DAYS", "termDays", "term_days");

        if (scenarioId == null) {
            return null;
        }
        String safeSubScenarioId = subScenarioId == null ? scenarioId : subScenarioId;

        JSONObject normalized = new JSONObject();
        normalized.put("CURVE_TYPE", curveType);
        normalized.put("CURVE_CODE", curveCode);
        normalized.put("CHANGED_RATE", changedRate);
        normalized.put("SCENARIO_ID", scenarioId);
        normalized.put("SUBSCENARIO_ID", safeSubScenarioId);
        if (scenarioName != null) {
            normalized.put("SCENARIO_NAME", scenarioName);
        }
        if (scenarioType != null) {
            normalized.put("SCENARIO_TYPE", scenarioType);
        }
        if (termCode != null) {
            normalized.put("TERM_CODE", termCode);
        }
        if (termDays != null) {
            normalized.put("TERM_DAYS", termDays);
        }
        return normalized;
    }

    private static Integer readInteger(JSONObject obj, String... keys) {
        Object value = firstNonNull(obj, keys);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Object firstNonNull(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object value = obj.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static JSONObject asJsonObject(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        return null;
    }

    /**
     * 组装计量输入 trade_data，同时抽取逐笔交易维度元信息。
     */
    private static TradeBuildBundle buildTradeDataAndMetadata(List<BatchTradeDataLoader.TradeRow> trades) {
        JSONArray tradeData = new JSONArray();
        Map<String, JSONObject> instrumentMetadata = new LinkedHashMap<String, JSONObject>();
        Set<String> portfolioCodes = new LinkedHashSet<String>();
        for (BatchTradeDataLoader.TradeRow trade : trades) {
            Object parsed = parseJsonSafely(trade.tradeContentText);
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.size(); i++) {
                    appendTradeItem(arr.get(i), trade, tradeData, instrumentMetadata, portfolioCodes);
                }
            } else if (parsed != null) {
                appendTradeItem(parsed, trade, tradeData, instrumentMetadata, portfolioCodes);
            }
        }
        return new TradeBuildBundle(tradeData, instrumentMetadata, portfolioCodes);
    }

    private static void appendTradeItem(Object item,
                                        BatchTradeDataLoader.TradeRow sourceRow,
                                        JSONArray tradeData,
                                        Map<String, JSONObject> instrumentMetadata,
                                        Set<String> portfolioCodes) {
        if (item == null) {
            return;
        }
        tradeData.add(item);
        if (!(item instanceof JSONObject)) {
            return;
        }

        JSONObject tradeJson = (JSONObject) item;
        String instrumentId = readText(tradeJson, "INSTRUMENT_ID", "instrumentId");
        if (instrumentId == null) {
            return;
        }

        JSONObject metadata = instrumentMetadata.get(instrumentId);
        if (metadata == null) {
            metadata = new JSONObject();
            metadata.put("instrumentId", instrumentId);
            metadata.put("inputDimensions", new JSONObject());
            instrumentMetadata.put(instrumentId, metadata);
        }

        String tradeId = firstNonBlank(
                sourceRow == null ? null : sourceRow.tradeId,
                readText(tradeJson, "TRADE_ID", "tradeId"));
        String productType = firstNonBlank(
                sourceRow == null ? null : sourceRow.productType,
                readText(tradeJson, "PRODUCT_TYPE", "productType", "PRODUCT_CODE", "productCode"));
        String portfolio = firstNonBlank(
                sourceRow == null ? null : sourceRow.portfolio,
                readText(tradeJson, "PORTFOLIO", "portfolio"));
        String desk = firstNonBlank(
                sourceRow == null ? null : sourceRow.desk,
                readText(tradeJson, "DESK", "desk"));
        String trader = firstNonBlank(
                sourceRow == null ? null : sourceRow.trader,
                readText(tradeJson, "TRADER", "trader"));

        putIfAbsentText(metadata, "tradeId", tradeId);
        putIfAbsentText(metadata, "productType", productType);
        putIfAbsentText(metadata, "portfolio", portfolio);
        putIfAbsentText(metadata, "desk", desk);
        putIfAbsentText(metadata, "trader", trader);

        if (portfolio != null) {
            portfolioCodes.add(portfolio);
        }

        JSONObject inputDimensions = metadata.getJSONObject("inputDimensions");
        mergeInputDimensions(inputDimensions, tradeJson);
    }

    /**
     * 将输入交易的顶层标量字段统一透传到 inputDimensions。
     */
    private static void mergeInputDimensions(JSONObject target, JSONObject tradeJson) {
        if (target == null || tradeJson == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : tradeJson.entrySet()) {
            String key = trimToNull(entry.getKey());
            if (key == null || target.containsKey(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof JSONObject || value instanceof JSONArray) {
                continue;
            }
            target.put(key, value);
        }
    }

    private Map<String, BatchTradeDataLoader.PortfolioFlatRow> loadPortfolioFlatMap(Set<String> portfolioCodes) {
        if (portfolioCodes == null || portfolioCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return batchTradeDataLoader.loadPortfolioFlatByCodes(new ArrayList<String>(portfolioCodes));
        } catch (Exception ex) {
            log.warn("读取投组平铺视图失败，压力情景结果降级为不附加投组层级字段: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 将 input 维度与投组层级维度拼接到每条情景损益结果中。
     */
    private static void enrichScenarioResultTradeData(JSONArray scenarioResult,
                                                      Map<String, JSONObject> instrumentMetadata,
                                                      Map<String, BatchTradeDataLoader.PortfolioFlatRow> portfolioFlatMap) {
        if (scenarioResult == null || scenarioResult.isEmpty() || instrumentMetadata == null || instrumentMetadata.isEmpty()) {
            return;
        }
        for (int i = 0; i < scenarioResult.size(); i++) {
            JSONObject scenarioItem = scenarioResult.getJSONObject(i);
            if (scenarioItem == null) {
                continue;
            }
            JSONArray tradeData = scenarioItem.getJSONArray("trade_data");
            if (tradeData == null || tradeData.isEmpty()) {
                continue;
            }
            for (int j = 0; j < tradeData.size(); j++) {
                JSONObject trade = tradeData.getJSONObject(j);
                if (trade == null) {
                    continue;
                }
                String instrumentId = readText(trade, "INSTRUMENT_ID", "instrumentId");
                if (instrumentId == null) {
                    continue;
                }

                JSONObject metadata = instrumentMetadata.get(instrumentId);
                if (metadata == null) {
                    continue;
                }
                putIfAbsentText(trade, "TRADE_ID", metadata.getString("tradeId"));
                putIfAbsentText(trade, "PRODUCT_TYPE", metadata.getString("productType"));
                putIfAbsentText(trade, "PORTFOLIO", metadata.getString("portfolio"));
                putIfAbsentText(trade, "DESK", metadata.getString("desk"));
                putIfAbsentText(trade, "TRADER", metadata.getString("trader"));

                JSONObject inputDimensions = metadata.getJSONObject("inputDimensions");
                if (inputDimensions != null) {
                    trade.put("INPUT_DIMENSIONS", inputDimensions);
                }

                String portfolio = trimToNull(metadata.getString("portfolio"));
                if (portfolio != null && portfolioFlatMap != null && !portfolioFlatMap.isEmpty()) {
                    BatchTradeDataLoader.PortfolioFlatRow flatRow = portfolioFlatMap.get(portfolio);
                    applyPortfolioFlat(trade, flatRow);
                }
            }
        }
    }

    private static void applyPortfolioFlat(JSONObject trade, BatchTradeDataLoader.PortfolioFlatRow row) {
        if (trade == null || row == null) {
            return;
        }
        putIfAbsentText(trade, "PORTFOLIO_CODE_1", row.portfolioCode1);
        putIfAbsentText(trade, "PORTFOLIO_CODE_2", row.portfolioCode2);
        putIfAbsentText(trade, "PORTFOLIO_CODE_3", row.portfolioCode3);
        putIfAbsentText(trade, "PORTFOLIO_CODE_4", row.portfolioCode4);
        putIfAbsentText(trade, "PORTFOLIO_CODE_5", row.portfolioCode5);
        putIfAbsentText(trade, "PORTFOLIO_CODE_6", row.portfolioCode6);
        putIfAbsentText(trade, "PORTFOLIO_CODE_7", row.portfolioCode7);
        putIfAbsentText(trade, "PORTFOLIO_NAME_1", row.portfolioName1);
        putIfAbsentText(trade, "PORTFOLIO_NAME_2", row.portfolioName2);
        putIfAbsentText(trade, "PORTFOLIO_NAME_3", row.portfolioName3);
        putIfAbsentText(trade, "PORTFOLIO_NAME_4", row.portfolioName4);
        putIfAbsentText(trade, "PORTFOLIO_NAME_5", row.portfolioName5);
        putIfAbsentText(trade, "PORTFOLIO_NAME_6", row.portfolioName6);
        putIfAbsentText(trade, "PORTFOLIO_NAME_7", row.portfolioName7);
    }

    private static JSONArray buildMarketData(List<BatchTradeDataLoader.CurveRow> curves) {
        JSONArray result = new JSONArray();
        for (BatchTradeDataLoader.CurveRow curve : curves) {
            Object parsed = parseJsonSafely(curve.curveContentText);
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.size(); i++) {
                    result.add(arr.get(i));
                }
            } else if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private static Object parseJsonSafely(String text) {
        String safe = trimToNull(text);
        if (safe == null) {
            return null;
        }
        try {
            return JSON.parse(safe);
        } catch (Exception ignore) {
            return safe;
        }
    }

    private static LocalDate parseDataDate(String dataDate) {
        try {
            return LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("data_date 格式错误，必须为 yyyyMMdd: " + dataDate);
        }
    }

    private static String requiredString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String readText(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = trimToNull(obj.getString(key));
            if (value != null) {
                return value;
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

    private static void putIfAbsentText(JSONObject obj, String key, String value) {
        if (obj == null) {
            return;
        }
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            return;
        }
        String existing = trimToNull(obj.getString(key));
        if (existing == null) {
            obj.put(key, safeValue);
        }
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    private static final class TradeBuildBundle {
        private final JSONArray tradeData;
        private final Map<String, JSONObject> instrumentMetadata;
        private final Set<String> portfolioCodes;

        private TradeBuildBundle(JSONArray tradeData, Map<String, JSONObject> instrumentMetadata, Set<String> portfolioCodes) {
            this.tradeData = tradeData;
            this.instrumentMetadata = instrumentMetadata;
            this.portfolioCodes = portfolioCodes;
        }
    }

    private static final class ScenarioPayloadBinding {
        private final JSONArray inlineScenarioData;
        private final String cacheKey;

        private ScenarioPayloadBinding(JSONArray inlineScenarioData, String cacheKey) {
            this.inlineScenarioData = inlineScenarioData;
            this.cacheKey = cacheKey;
        }

        private static ScenarioPayloadBinding withInline(JSONArray scenarioData) {
            return new ScenarioPayloadBinding(scenarioData, null);
        }

        private static ScenarioPayloadBinding withCacheKey(String cacheKey) {
            return new ScenarioPayloadBinding(null, cacheKey);
        }
    }
}
