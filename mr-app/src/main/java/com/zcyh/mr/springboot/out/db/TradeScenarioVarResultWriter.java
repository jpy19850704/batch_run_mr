package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;

import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.STATUS_ERROR;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.STATUS_SUCCESS;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.isErrorStatus;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.normalizeDataDate;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.resolveLogs;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.resolveStatus;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.toBigDecimal;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.toJsonString;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.trimToNull;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class TradeScenarioVarResultWriter {
    private static final String TABLE_NAME = "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL";
    private static final List<String> COLUMN_LIST = Collections.unmodifiableList(Arrays.asList(
            "REQUEST_ID",
            "JOB_ID",
            "BATCH_ID",
            "SEQ_NO",
            "DATA_DATE",
            "SCENARIO_ID",
            "SUBSCENARIO_ID",
            "SCENARIO_NAME",
            "INSTRUMENT_ID",
            "PRODUCT_CODE",
            "BASE_VALUATION_CNY",
            "IR_VALUATION",
            "IR_PNL",
            "FX_VALUATION",
            "FX_PNL",
            "EQ_VALUATION",
            "EQ_PNL",
            "COMM_VALUATION",
            "COMM_PNL",
            "ALL_VALUATION",
            "ALL_PNL",
            "STATUS",
            "LOGS_JSON",
            "CREATED_AT",
            "UPDATED_AT"
    ));
    private static final String COLUMNS = String.join(",", COLUMN_LIST);

    private final DorisStreamLoadService dorisStreamLoadService;

    TradeScenarioVarResultWriter(DorisStreamLoadService dorisStreamLoadService) {
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    String tableName() {
        return TABLE_NAME;
    }

    List<String> writeColumns() {
        return COLUMN_LIST;
    }

    void writeProcessed(CalcPersistContext context,
                        List<JSONObject> scenarios,
                        Map<String, JSONObject> baseTradeIndex) {
        if (scenarios == null || scenarios.isEmpty()) {
            return;
        }
        LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<String, JSONObject>();
        for (JSONObject scenario : scenarios) {
            JSONObject tag = scenario.getJSONObject("SCENARIO_TAG");
            String riskClass = trimToNull(tag == null ? null : tag.getString(ScenarioProcessConstants.TAG_RISK_CLASS));
            String prefix = mapVarRiskClassToColumnPrefix(riskClass);
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
                String rowKey = scenarioRowKey(scenario, instrumentId);
                JSONObject row = rows.get(rowKey);
                if (row == null) {
                    row = initVarPersistRow(scenario, trade, baseTrade, instrumentId);
                    rows.put(rowKey, row);
                }
                if (isScenarioErrorTrade(trade)) {
                    appendPersistVarLog(row, riskClass, trade.getJSONArray("LOGS"));
                    continue;
                }
                row.put(prefix + "_VALUATION", toBigDecimal(trade.get("SCENARIO_VALUATION_CNY")));
                row.put(prefix + "_PNL", toBigDecimal(trade.get("PNL")));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = newBuffer("scenario_var_" + context.batchId + "_" + context.jobId + "_processed");
        for (JSONObject row : rows.values()) {
            appendVarRow(context, buffer, row);
        }
        buffer.flush();
    }

    void writeDirect(CalcPersistContext context, JSONObject scenario, Map<String, JSONObject> baseTradeIndex) {
        JSONArray tradeData = scenario.getJSONArray("trade_data");
        if (tradeData == null || tradeData.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = newBuffer("scenario_var_" + context.batchId + "_" + context.jobId);
        for (int i = 0; i < tradeData.size(); i++) {
            JSONObject trade = tradeData.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            JSONObject baseTrade = instrumentId == null ? null : baseTradeIndex.get(instrumentId);
            JSONObject row = new JSONObject();
            row.put("SCENARIO_ID", trimToNull(scenario.getString("SCENARIO_ID")));
            row.put("SUBSCENARIO_ID", trimToNull(scenario.getString("SUBSCENARIO_ID")));
            row.put("SCENARIO_NAME", trimToNull(scenario.getString("SCENARIO_NAME")));
            row.put("INSTRUMENT_ID", instrumentId);
            row.put("PRODUCT_CODE", baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")));
            row.put("BASE_VALUATION_CNY", trade.get("BASE_VALUATION_CNY"));
            row.put("IR_VALUATION", trade.get("IR_VALUATION"));
            row.put("IR_PNL", trade.get("IR_PNL"));
            row.put("FX_VALUATION", trade.get("FX_VALUATION"));
            row.put("FX_PNL", trade.get("FX_PNL"));
            row.put("EQ_VALUATION", trade.get("EQ_VALUATION"));
            row.put("EQ_PNL", trade.get("EQ_PNL"));
            row.put("COMM_VALUATION", trade.get("COMM_VALUATION"));
            row.put("COMM_PNL", trade.get("COMM_PNL"));
            row.put("ALL_VALUATION", trade.get("ALL_VALUATION"));
            row.put("ALL_PNL", trade.get("ALL_PNL"));
            row.put("STATUS", resolveStatus(trade));
            row.put("LOGS", resolveLogs(trade, "情景估值错误"));
            appendVarRow(context, buffer, row);
        }
        buffer.flush();
    }

    private DorisCsvStreamLoadBuffer newBuffer(String labelPrefix) {
        return new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TABLE_NAME,
                COLUMNS,
                labelPrefix,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
    }

    private static void appendVarRow(CalcPersistContext context,
                                      DorisCsvStreamLoadBuffer buffer,
                                      JSONObject row) {
        buffer.appendRow(
                context.requestId,
                context.jobId,
                context.batchId,
                context.seqNo,
                normalizeDataDate(context.dataDate),
                trimToNull(row.getString("SCENARIO_ID")),
                trimToNull(row.getString("SUBSCENARIO_ID")),
                trimToNull(row.getString("SCENARIO_NAME")),
                trimToNull(row.getString("INSTRUMENT_ID")),
                trimToNull(row.getString("PRODUCT_CODE")),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("BASE_VALUATION_CNY"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("IR_VALUATION"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("IR_PNL"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("FX_VALUATION"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("FX_PNL"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("EQ_VALUATION"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("EQ_PNL"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("COMM_VALUATION"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("COMM_PNL"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("ALL_VALUATION"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("ALL_PNL"))),
                resolveStatus(row),
                toJsonString(row.get("LOGS")),
                context.createdAt,
                context.updatedAt
        );
    }

    private static JSONObject initVarPersistRow(JSONObject scenario,
                                                JSONObject trade,
                                                JSONObject baseTrade,
                                                String instrumentId) {
        BigDecimal baseValuation = toBigDecimal(trade == null ? null : trade.get("BASE_VALUATION_CNY"));
        if (baseValuation == null) {
            baseValuation = BigDecimal.ZERO;
        }
        JSONObject row = new JSONObject();
        row.put("SCENARIO_ID", trimToNull(scenario.getString("SCENARIO_ID")));
        row.put("SUBSCENARIO_ID", trimToNull(scenario.getString("SUBSCENARIO_ID")));
        row.put("SCENARIO_NAME", trimToNull(scenario.getString("SCENARIO_NAME")));
        row.put("SCENARIO_ENTRY_KEY", trimToNull(scenario.getString("SCENARIO_ENTRY_KEY")));
        row.put("SCENARIO_TAG", scenario.get("SCENARIO_TAG"));
        row.put("INSTRUMENT_ID", instrumentId);
        row.put("PRODUCT_CODE", baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")));
        row.put("BASE_VALUATION_CNY", baseValuation);
        row.put("IR_VALUATION", baseValuation);
        row.put("IR_PNL", BigDecimal.ZERO);
        row.put("FX_VALUATION", baseValuation);
        row.put("FX_PNL", BigDecimal.ZERO);
        row.put("EQ_VALUATION", baseValuation);
        row.put("EQ_PNL", BigDecimal.ZERO);
        row.put("COMM_VALUATION", baseValuation);
        row.put("COMM_PNL", BigDecimal.ZERO);
        row.put("ALL_VALUATION", baseValuation);
        row.put("ALL_PNL", BigDecimal.ZERO);
        row.put("STATUS", STATUS_SUCCESS);
        return row;
    }

    static String scenarioRowKey(JSONObject scenario, String instrumentId) {
        String entryKey = trimToNull(scenario == null ? null : scenario.getString("SCENARIO_ENTRY_KEY"));
        if (entryKey != null) {
            return entryKey + "|" + instrumentId;
        }
        return trimToNull(scenario == null ? null : scenario.getString("SCENARIO_ID"))
                + "|" + trimToNull(scenario == null ? null : scenario.getString("SUBSCENARIO_ID"))
                + "|" + instrumentId;
    }

    private static String mapVarRiskClassToColumnPrefix(String riskClass) {
        String upper = normalizeRiskClass(riskClass);
        if ("IR".equals(upper) || "FX".equals(upper) || "EQ".equals(upper)
                || "COMM".equals(upper) || "ALL".equals(upper)) {
            return upper;
        }
        throw new IllegalStateException("VaR 不支持的风险类别: " + riskClass);
    }

    static String normalizeRiskClass(String riskClass) {
        String safe = trimToNull(riskClass);
        if (safe == null) {
            throw new IllegalStateException("scenario_result 缺少风险类别 tag");
        }
        return safe.toUpperCase(Locale.ROOT);
    }

    static boolean isScenarioErrorTrade(JSONObject trade) {
        return isErrorStatus(trade);
    }

    private static void appendPersistVarLog(JSONObject row, String riskClass, JSONArray sourceLogs) {
        row.put("STATUS", STATUS_ERROR);
        JSONArray logs = row.getJSONArray("LOGS");
        if (logs == null) {
            logs = new JSONArray();
            row.put("LOGS", logs);
        }
        String prefix = trimToNull(riskClass) == null ? "UNKNOWN" : riskClass.trim();
        if (sourceLogs == null || sourceLogs.isEmpty()) {
            JSONObject logItem = new JSONObject();
            logItem.put("level", STATUS_ERROR);
            logItem.put("message", prefix + ": VaR 风险类别计算异常");
            logs.add(logItem);
            return;
        }
        for (int i = 0; i < sourceLogs.size(); i++) {
            JSONObject source = sourceLogs.getJSONObject(i);
            if (source == null) {
                continue;
            }
            JSONObject logItem = new JSONObject();
            logItem.put("level", String.valueOf(source.getOrDefault("level", STATUS_ERROR)));
            logItem.put("message", prefix + ": " + String.valueOf(source.getOrDefault("message", "")));
            logs.add(logItem);
        }
    }
}
