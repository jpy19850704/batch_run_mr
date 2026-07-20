package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.STATUS_ERROR;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.STATUS_SUCCESS;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.appendLogs;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.normalizeDataDate;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.resolveLogs;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.resolveStatus;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.toBigDecimal;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.toJsonString;
import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.trimToNull;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.frtbima.common.ImaConstants;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class ImaScenarioPnlWriter {
    private static final String MODELLABLE_TABLE = "TB_OUT_IMA_MODELLABLE_SCENARIO_PNL";
    private static final String MODELLABLE_COLUMNS =
            "BATCH_ID,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,"
                    + "INSTRUMENT_ID,PRODUCT_CODE,LH_DAYS,BASE_VALUATION_CNY,IR_PNL,CS_PNL,FX_PNL,"
                    + "EQ_PNL,COMM_PNL,ALL_PNL,STATUS,LOGS_JSON,CREATED_AT";
    private static final String NMRF_TABLE = "TB_OUT_IMA_NMRF_SCENARIO_PNL";
    private static final String NMRF_COLUMNS =
            "BATCH_ID,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,"
                    + "INSTRUMENT_ID,PRODUCT_CODE,RISK_FACTOR_ID,NMRF_TYPE,BASE_VALUATION_CNY,PNL,STATUS,LOGS_JSON,CREATED_AT";

    private final DorisStreamLoadService dorisStreamLoadService;

    ImaScenarioPnlWriter(DorisStreamLoadService dorisStreamLoadService) {
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    void writeModellableFromScenarioResults(CalcPersistContext context,
                                            List<JSONObject> scenarios,
                                            Map<String, JSONObject> baseTradeIndex) {
        writeModellableRows(context, buildModellableRows(context, scenarios, baseTradeIndex));
    }

    void writeNmrfFromScenarioResults(CalcPersistContext context,
                                      List<JSONObject> scenarios,
                                      Map<String, JSONObject> baseTradeIndex) {
        writeNmrfRows(context, buildNmrfRows(context, scenarios, baseTradeIndex));
    }

    void writeModellableRows(CalcPersistContext context, JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                MODELLABLE_TABLE,
                MODELLABLE_COLUMNS,
                "ima_modellable_" + context.batchId + "_" + context.jobId,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            buffer.appendRow(
                    context.batchId,
                    normalizeDataDate(context.dataDate),
                    trimToNull(row.getString("SCENARIO_ID")),
                    trimToNull(row.getString("SUBSCENARIO_ID")),
                    trimToNull(row.getString("SCENARIO_NAME")),
                    trimToNull(row.getString("SCENARIO_TYPE")),
                    trimToNull(row.getString("INSTRUMENT_ID")),
                    trimToNull(row.getString("PRODUCT_CODE")),
                    row.get("LH_DAYS"),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("BASE_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("IR_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("CS_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("FX_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("EQ_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("COMM_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("ALL_PNL"))),
                    resolveStatus(row),
                    toJsonString(row.get("LOGS_JSON")),
                    resolveCreatedAt(row, now));
        }
        buffer.flush();
    }

    private void writeNmrfRows(CalcPersistContext context, JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                NMRF_TABLE,
                NMRF_COLUMNS,
                "ima_nmrf_" + context.batchId + "_" + context.jobId,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            buffer.appendRow(
                    context.batchId,
                    normalizeDataDate(context.dataDate),
                    trimToNull(row.getString("SCENARIO_ID")),
                    trimToNull(row.getString("SUBSCENARIO_ID")),
                    trimToNull(row.getString("SCENARIO_NAME")),
                    trimToNull(row.getString("INSTRUMENT_ID")),
                    trimToNull(row.getString("PRODUCT_CODE")),
                    trimToNull(row.getString("RISK_FACTOR_ID")),
                    trimToNull(row.getString("NMRF_TYPE")),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("BASE_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("PNL"))),
                    resolveStatus(row),
                    toJsonString(row.get("LOGS_JSON")),
                    resolveCreatedAt(row, now));
        }
        buffer.flush();
    }

    private JSONArray buildModellableRows(CalcPersistContext context,
                                          List<JSONObject> scenarios,
                                          Map<String, JSONObject> baseTradeIndex) {
        JSONArray result = new JSONArray();
        if (scenarios == null || scenarios.isEmpty()) {
            return result;
        }
        LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<String, JSONObject>();
        for (JSONObject scenario : scenarios) {
            JSONObject tag = scenario.getJSONObject("SCENARIO_TAG");
            Integer lhDays = tag == null ? null : tag.getInteger(ScenarioProcessConstants.TAG_LH);
            if (lhDays == null) {
                throw new IllegalStateException("IMA 可建模 scenario_result 缺少 SCENARIO_TAG.lh");
            }
            String imaRiskClass = trimToNull(tag.getString(ScenarioProcessConstants.TAG_IMA_RISK_CLASS));
            String prefix = mapImaRiskClassToColumnPrefix(imaRiskClass);
            JSONArray tradeData = scenario.getJSONArray("trade_data");
            if (tradeData == null || tradeData.isEmpty()) {
                continue;
            }
            for (int i = 0; i < tradeData.size(); i++) {
                JSONObject trade = tradeData.getJSONObject(i);
                if (trade == null) {
                    continue;
                }
                String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
                if (instrumentId == null) {
                    continue;
                }
                JSONObject baseTrade = baseTradeIndex.get(instrumentId);
                String rowKey = TradeScenarioVarResultWriter.scenarioRowKey(scenario, instrumentId);
                JSONObject row = rows.get(rowKey);
                if (row == null) {
                    row = initModellableRow(context, scenario, trade, baseTrade, instrumentId, lhDays);
                    rows.put(rowKey, row);
                }
                if (TradeScenarioVarResultWriter.isScenarioErrorTrade(trade)) {
                    markRowError(row, trade, imaRiskClass + " 情景估值错误");
                    continue;
                }
                row.put(prefix + "_PNL", toBigDecimal(trade.get("PNL")));
            }
        }
        result.addAll(rows.values());
        return result;
    }

    private JSONArray buildNmrfRows(CalcPersistContext context,
                                    List<JSONObject> scenarios,
                                    Map<String, JSONObject> baseTradeIndex) {
        JSONArray result = new JSONArray();
        if (scenarios == null || scenarios.isEmpty()) {
            return result;
        }
        for (JSONObject scenario : scenarios) {
            String riskFactorId = trimToNull(scenario == null ? null : scenario.getString("RISK_FACTOR_ID"));
            if (riskFactorId == null) {
                throw new IllegalStateException("IMA NMRF scenario_result 缺少 RISK_FACTOR_ID");
            }
            String nmrfType = trimToNull(scenario.getString("NMRF_TYPE"));
            if (nmrfType == null) {
                throw new IllegalStateException("IMA NMRF scenario_result 缺少 NMRF_TYPE");
            }
            JSONArray tradeData = scenario.getJSONArray("trade_data");
            if (tradeData == null || tradeData.isEmpty()) {
                continue;
            }
            for (int i = 0; i < tradeData.size(); i++) {
                JSONObject trade = tradeData.getJSONObject(i);
                if (trade == null) {
                    continue;
                }
                String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
                if (instrumentId == null) {
                    continue;
                }
                boolean errorTrade = TradeScenarioVarResultWriter.isScenarioErrorTrade(trade);
                BigDecimal baseValuation = errorTrade
                        ? toBigDecimal(trade.get("BASE_VALUATION_CNY"))
                        : requireDecimal(trade, "BASE_VALUATION_CNY", "IMA NMRF scenario_result");
                BigDecimal pnl = errorTrade
                        ? toBigDecimal(trade.get("PNL"))
                        : requireDecimal(trade, "PNL", "IMA NMRF scenario_result");
                JSONObject baseTrade = baseTradeIndex.get(instrumentId);
                JSONObject row = new JSONObject();
                row.put("SCENARIO_ID", trimToNull(scenario.getString("SCENARIO_ID")));
                row.put("SUBSCENARIO_ID", trimToNull(scenario.getString("SUBSCENARIO_ID")));
                row.put("SCENARIO_NAME", trimToNull(scenario.getString("SCENARIO_NAME")));
                row.put("INSTRUMENT_ID", instrumentId);
                row.put("PRODUCT_CODE", baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")));
                row.put("RISK_FACTOR_ID", riskFactorId);
                row.put("NMRF_TYPE", nmrfType);
                row.put("BASE_VALUATION_CNY", baseValuation);
                row.put("PNL", pnl);
                row.put("STATUS", resolveStatus(trade));
                row.put("LOGS_JSON", resolveLogs(trade, "IMA NMRF 情景估值错误"));
                result.add(row);
            }
        }
        return result;
    }

    private static JSONObject initModellableRow(CalcPersistContext context,
                                                JSONObject scenario,
                                                JSONObject trade,
                                                JSONObject baseTrade,
                                                String instrumentId,
                                                Integer lhDays) {
        BigDecimal baseValuation = toBigDecimal(trade == null ? null : trade.get("BASE_VALUATION_CNY"));
        if (baseValuation == null) {
            baseValuation = BigDecimal.ZERO;
        }
        JSONObject row = new JSONObject();
        row.put("SCENARIO_ID", trimToNull(scenario.getString("SCENARIO_ID")));
        row.put("SUBSCENARIO_ID", trimToNull(scenario.getString("SUBSCENARIO_ID")));
        row.put("SCENARIO_NAME", trimToNull(scenario.getString("SCENARIO_NAME")));
        row.put("SCENARIO_TYPE", requireImaScenarioType(scenario));
        row.put("INSTRUMENT_ID", instrumentId);
        row.put("PRODUCT_CODE", baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")));
        row.put("LH_DAYS", lhDays);
        row.put("BASE_VALUATION_CNY", baseValuation);
        row.put("IR_PNL", BigDecimal.ZERO);
        row.put("CS_PNL", BigDecimal.ZERO);
        row.put("FX_PNL", BigDecimal.ZERO);
        row.put("EQ_PNL", BigDecimal.ZERO);
        row.put("COMM_PNL", BigDecimal.ZERO);
        row.put("ALL_PNL", BigDecimal.ZERO);
        row.put("STATUS", STATUS_SUCCESS);
        return row;
    }

    private static void markRowError(JSONObject row, JSONObject trade, String defaultMessage) {
        row.put("STATUS", STATUS_ERROR);
        appendLogs(row, resolveLogs(trade, defaultMessage));
    }

    private static String mapImaRiskClassToColumnPrefix(String riskClass) {
        String upper = TradeScenarioVarResultWriter.normalizeRiskClass(riskClass);
        if ("GIRR".equals(upper)) {
            return "IR";
        }
        if ("CSR".equals(upper)) {
            return "CS";
        }
        if ("FX".equals(upper) || "EQ".equals(upper) || "COMM".equals(upper) || "ALL".equals(upper)) {
            return upper;
        }
        throw new IllegalStateException("IMA 可建模不支持的风险类别: " + riskClass);
    }

    private static String requireImaScenarioType(JSONObject scenario) {
        JSONObject tag = scenario == null ? null : scenario.getJSONObject("SCENARIO_TAG");
        String value = trimToNull(tag == null
                ? null
                : tag.getString(ScenarioProcessConstants.TAG_IMA_SCENARIO_TYPE));
        if (ImaConstants.SCENARIO_TYPE_NORMAL_FULL.equals(value)
                || ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED.equals(value)
                || ImaConstants.SCENARIO_TYPE_STRESS_REDUCED.equals(value)) {
            return value;
        }
        throw new IllegalStateException("IMA 可建模 scenario_result 缺少合法 SCENARIO_TAG."
                + ScenarioProcessConstants.TAG_IMA_SCENARIO_TYPE);
    }

    private static BigDecimal requireDecimal(JSONObject row, String field, String source) {
        BigDecimal value = toBigDecimal(row == null ? null : row.get(field));
        if (value == null) {
            throw new IllegalStateException(source + " 缺少 " + field);
        }
        return value;
    }

    private static String resolveCreatedAt(JSONObject row, String defaultText) {
        if (row == null || row.get("CREATED_AT") == null) {
            return defaultText;
        }
        Long epochMillis = toLong(row.get("CREATED_AT"));
        if (epochMillis != null && epochMillis > 0) {
            return ResultPersistTime.formatEpochMillis(epochMillis);
        }
        return defaultText;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Long) {
                return (Long) value;
            }
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.valueOf(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }
}
