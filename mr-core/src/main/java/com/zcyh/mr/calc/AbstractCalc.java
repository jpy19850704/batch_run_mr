package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.JsonNumberUtils;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * 计算器抽象基类。
 * 封装所有 Calc 共用的字段声明、基准计量、情景计量和错误处理等样板逻辑。
 * 子类只需实现产品特定的构建和估值方法。
 */
public abstract class AbstractCalc implements ProductCalculator {

    protected List<HashMap<String, Object>> trades;
    protected MarketData marketData;
    protected LocalDate dataDate;
    protected String operCode;
    protected Calendar calendar;

    protected JSONObject result = new JSONObject();
    protected JSONArray trade = new JSONArray();
    protected JSONArray log = new JSONArray();

    /**
     * 标准构造（无 Calendar）
     */
    protected AbstractCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        this(operCode, dataDate, trades, marketData, null);
    }

    /**
     * 完整构造（含 Calendar）
     */
    protected AbstractCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData,
            Calendar calendar) {
        this.operCode = operCode;
        this.dataDate = dataDate;
        this.trades = trades;
        this.marketData = marketData;
        this.calendar = calendar;
    }

    /**
     * 执行基准估值并封装结果 JSON
     */
    public String calc() {
        calculateTrades();
        this.result.put("data", new JSONObject());
        ((JSONObject) this.result.get("data")).put("trade_data", trade);
        ((JSONObject) this.result.get("data")).put("log_data", log);
        JsonNumberUtils.normalizeNumbersInPlace(this.result);
        return JSON.toJSONString(this.result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    /**
     * 遍历交易列表执行基准估值，统一处理异常
     */
    private void calculateTrades() {
        if (Constants.CALC_MODE.PRICING.equalsIgnoreCase(operCode)) {
            for (HashMap<String, Object> t : trades) {
                try {
                    calcTrade(t);
                } catch (Exception e) {
                    handleError(t, e);
                }
            }
        }
    }

    /**
     * 场景估值（全量重估），返回 trade_data 结果数组。
     * JSON 封装由 Calc.java 统一处理。
     */
    @Override
    public JSONArray calcScenario(MarketData scenarioMd) {
        return calcScenario(scenarioMd, null);
    }

    /**
     * 场景估值（按 affectedIds 筛选），返回 trade_data 结果数组。
     * 子类实现 runScenarioLoop 填充结果。
     */
    @Override
    public JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        JSONArray scenarioRst = new JSONArray();
        runScenarioLoop(scenarioMd, affectedIds, scenarioRst);
        return scenarioRst;
    }

    // ===== 子类必须实现的方法 =====

    /** 单笔交易的基准估值逻辑 */
    protected abstract void calcTrade(HashMap<String, Object> tradeData);

    /** 场景估值循环体，将结果追加到 scenarioRst */
    protected abstract void runScenarioLoop(MarketData scenarioMd,
            Set<String> affectedIds, JSONArray scenarioRst);

    // ===== 公共工具方法 =====

    /** 记录失败日志（成功交易不写 log_data） */
    protected void addLog(String instrumentId, Measure measure) {
        if (!"SUCCESS".equals(measure.status)) {
            JSONObject logInfo = new JSONObject();
            logInfo.put("INSTRUMENT_ID", instrumentId);
            logInfo.put("info", "校验失败");
            log.add(logInfo);
        }
    }

    /** 统一异常处理：生成错误 Measure 和错误日志 */
    protected void handleError(HashMap<String, Object> tradeData, Exception e) {
        appendErrorResult(this.trade, this.log, dataDate, tradeData, e);
    }

    /** 将标的债券数组转换为按 BOND_ID 索引的对象。 */
    protected static JSONObject indexUnderlyingDataByBondId(Object rawUnderlyingData) {
        JSONObject result = new JSONObject();
        if (!(rawUnderlyingData instanceof JSONArray)) {
            return result;
        }
        for (Object item : (JSONArray) rawUnderlyingData) {
            JSONObject bond = (JSONObject) item;
            String bondId = bond.getString("BOND_ID");
            if (bondId != null) {
                result.put(bondId, bond);
            }
        }
        return result;
    }

    /** 构造标准错误交易结果。 */
    public static Measure buildErrorMeasure(LocalDate dataDate, String instrumentId, String productCode, Exception e) {
        Measure err = new Measure();
        err.instrumentId = java.util.Objects.toString(instrumentId, "");
        err.productCode = java.util.Objects.toString(productCode, "");
        err.dataDate = dataDate;
        err.status = "ERROR";
        err.logs = Measure.errorLogs("计算异常: " + resolveErrorMessage(e));
        err.cashFlowList = null;
        err.detail = null;
        err.sensitivityList = null;
        return err;
    }

    /** 为手写 Calc 追加标准错误交易结果，避免异常交易只进 log_data 而从 trade_data 消失。 */
    public static void appendErrorResult(JSONArray tradeDataResult, JSONArray logData,
            LocalDate dataDate, HashMap<String, Object> tradeData, Exception e) {
        String instId = java.util.Objects.toString(tradeData.get("INSTRUMENT_ID"), "");
        String prodCode = java.util.Objects.toString(tradeData.get("PRODUCT_CODE"), "");
        Measure err = buildErrorMeasure(dataDate, instId, prodCode, e);
        tradeDataResult.add(err);

        JSONObject errLog = new JSONObject();
        errLog.put("INSTRUMENT_ID", instId);
        errLog.put("info", "计算异常: " + resolveErrorMessage(e));
        logData.add(errLog);
    }

    private static String resolveErrorMessage(Exception e) {
        if (e == null) {
            return "unknown";
        }
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
