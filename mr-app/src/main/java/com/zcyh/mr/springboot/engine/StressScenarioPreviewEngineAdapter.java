package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.service.BatchTradeDataLoader;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class StressScenarioPreviewEngineAdapter implements EngineAdapter {
    public static final String CODE = "stress_pnl_preview";

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

        JSONArray tradeData = buildTradeData(trades);
        JSONArray marketData = buildMarketData(curves);

        JSONObject calcPayload = new JSONObject();
        calcPayload.put("oper_code", "SCENARIO");
        calcPayload.put("data_date", dataDate);
        calcPayload.put("trade_data", tradeData);
        calcPayload.put("market_data", marketData);
        calcPayload.put("scenario_data", scenarioData);

        String raw = mrCalcEngineAdapter.calculate(
                calcPayload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        JSONObject calcJson = JSON.parseObject(raw);
        JSONObject calcData = calcJson == null ? null : calcJson.getJSONObject("data");
        if (calcData == null) {
            throw new IllegalStateException("MR 计量返回结构异常，缺少 data 节点");
        }

        JSONObject out = new JSONObject();
        out.put("data_date", dataDate);
        out.put("trade_count", tradeData.size());
        out.put("market_curve_count", marketData.size());
        out.put("scenario_count", scenarioData.size());
        out.put("scenario_result", calcData.getJSONArray("scenario_result"));
        out.put("log_data", calcData.getJSONArray("log_data"));
        return JSON.toJSONString(out, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static JSONArray buildTradeData(List<BatchTradeDataLoader.TradeRow> trades) {
        JSONArray result = new JSONArray();
        for (BatchTradeDataLoader.TradeRow trade : trades) {
            Object parsed = parseJsonSafely(trade.tradeContentText);
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

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
