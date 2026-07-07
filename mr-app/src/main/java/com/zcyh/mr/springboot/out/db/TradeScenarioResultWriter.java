package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;

import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.normalizeDataDate;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.toBigDecimal;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.toJsonString;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.toTextValue;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.trimToNull;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class TradeScenarioResultWriter {
    private static final String TABLE_NAME = "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL";
    private static final String COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,INSTRUMENT_ID,PRODUCT_CODE,"
                    + "BASE_VALUATION_CNY,SCENARIO_VALUATION_CNY,PNL,STATUS,LOGS_JSON,CREATED_AT,UPDATED_AT";
    private static final String RESULT_KIND_SCENARIO = "SCENARIO";
    private static final String RESULT_KIND_VAR = "VAR";

    private final DorisStreamLoadService dorisStreamLoadService;
    private final TradeScenarioVarResultWriter tradeScenarioVarResultWriter;
    private final ImaScenarioPnlWriter imaScenarioPnlWriter;

    TradeScenarioResultWriter(DorisStreamLoadService dorisStreamLoadService,
                              TradeScenarioVarResultWriter tradeScenarioVarResultWriter,
                              ImaScenarioPnlWriter imaScenarioPnlWriter) {
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.tradeScenarioVarResultWriter = tradeScenarioVarResultWriter;
        this.imaScenarioPnlWriter = imaScenarioPnlWriter;
    }

    String tableName() {
        return TABLE_NAME;
    }

    String requiredColumnsForCheck() {
        return "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, "
                + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, INSTRUMENT_ID, PRODUCT_CODE, "
                + "BASE_VALUATION_CNY, SCENARIO_VALUATION_CNY, PNL, STATUS, LOGS_JSON, CREATED_AT, UPDATED_AT";
    }

    void write(CalcPersistContext context,
               JSONArray scenarioResults,
               Map<String, JSONObject> baseTradeIndex,
               boolean varTableExists) {
        if (scenarioResults == null || scenarioResults.isEmpty()) {
            return;
        }
        List<JSONObject> varScenarios = new ArrayList<JSONObject>();
        List<JSONObject> imaModellableScenarios = new ArrayList<JSONObject>();
        List<JSONObject> imaNmrfScenarios = new ArrayList<JSONObject>();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TABLE_NAME,
                COLUMNS,
                "scenario_result_" + context.batchId + "_" + context.jobId,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
        for (int i = 0; i < scenarioResults.size(); i++) {
            JSONObject scenario = scenarioResults.getJSONObject(i);
            if (scenario == null) {
                continue;
            }
            if (isVarScenarioResult(scenario)) {
                if (varTableExists) {
                    tradeScenarioVarResultWriter.writeDirect(context, scenario, baseTradeIndex);
                }
                continue;
            }
            String processType = resolveScenarioProcessType(scenario);
            if (ScenarioProcessConstants.VAR.equals(processType)) {
                varScenarios.add(scenario);
                continue;
            }
            if (ScenarioProcessConstants.IMA_MODELLABLE.equals(processType)) {
                imaModellableScenarios.add(scenario);
                continue;
            }
            if (ScenarioProcessConstants.IMA_NMRF.equals(processType)) {
                imaNmrfScenarios.add(scenario);
                continue;
            }
            writeNormalScenario(context, baseTradeIndex, buffer, scenario);
        }
        buffer.flush();
        if (varTableExists) {
            tradeScenarioVarResultWriter.writeProcessed(context, varScenarios, baseTradeIndex);
        }
        imaScenarioPnlWriter.writeModellableFromScenarioResults(context, imaModellableScenarios, baseTradeIndex);
        imaScenarioPnlWriter.writeNmrfFromScenarioResults(context, imaNmrfScenarios, baseTradeIndex);
    }

    private void writeNormalScenario(CalcPersistContext context,
                                     Map<String, JSONObject> baseTradeIndex,
                                     DorisCsvStreamLoadBuffer buffer,
                                     JSONObject scenario) {
        JSONArray tradeData = scenario.getJSONArray("trade_data");
        if (tradeData == null || tradeData.isEmpty()) {
            return;
        }
        for (int j = 0; j < tradeData.size(); j++) {
            JSONObject trade = tradeData.getJSONObject(j);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            JSONObject baseTrade = instrumentId == null ? null : baseTradeIndex.get(instrumentId);
            buffer.appendRow(
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    context.seqNo,
                    normalizeDataDate(context.dataDate),
                    trimToNull(scenario.getString("SCENARIO_ID")),
                    trimToNull(scenario.getString("SUBSCENARIO_ID")),
                    trimToNull(scenario.getString("SCENARIO_NAME")),
                    trimToNull(scenario.getString("SCENARIO_TYPE")),
                    instrumentId,
                    baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("BASE_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("SCENARIO_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("PNL"))),
                    resolveScenarioStatus(trade),
                    toJsonString(resolveScenarioLogs(trade)),
                    context.createdAt,
                    context.updatedAt
            );
        }
    }

    private static String resolveScenarioProcessType(JSONObject scenario) {
        String processType = trimToNull(scenario == null ? null : scenario.getString("SCENARIO_PROCESS_TYPE"));
        if (processType == null) {
            throw new IllegalStateException("scenario_result 缺少 SCENARIO_PROCESS_TYPE");
        }
        String upper = processType.toUpperCase(Locale.ROOT);
        if (ScenarioProcessConstants.isValidProcessType(upper)) {
            return upper;
        }
        throw new IllegalStateException("scenario_result.SCENARIO_PROCESS_TYPE 无效: " + processType);
    }

    private static boolean isVarScenarioResult(JSONObject scenario) {
        if (scenario == null) {
            return false;
        }
        String resultKind = trimToNull(scenario.getString("RESULT_KIND"));
        if (RESULT_KIND_VAR.equalsIgnoreCase(resultKind)) {
            return true;
        }
        if (RESULT_KIND_SCENARIO.equalsIgnoreCase(resultKind)) {
            return false;
        }
        throw new IllegalStateException("scenario_result 缺少合法 RESULT_KIND，仅支持 SCENARIO 或 VAR");
    }

    private static String resolveScenarioStatus(JSONObject trade) {
        String status = trimToNull(trade == null ? null : trade.getString("STATUS"));
        return "ERROR".equalsIgnoreCase(status) ? "ERROR" : "SUCCESS";
    }

    private static Object resolveScenarioLogs(JSONObject trade) {
        if (trade == null) {
            return null;
        }
        Object logs = trade.get("LOGS");
        if (!"ERROR".equals(resolveScenarioStatus(trade))) {
            return logs;
        }
        if (logs instanceof JSONArray && !((JSONArray) logs).isEmpty()) {
            return logs;
        }
        String message = resolveScenarioErrorMessage(trade);
        JSONArray result = new JSONArray();
        JSONObject logItem = new JSONObject();
        logItem.put("level", "ERROR");
        logItem.put("message", message == null ? "情景估值错误" : message);
        result.add(logItem);
        return result;
    }

    private static String resolveScenarioErrorMessage(JSONObject trade) {
        String error = trimToNull(trade == null ? null : trade.getString("ERROR"));
        if (error != null) {
            return error;
        }
        String detail = toTextValue(trade == null ? null : trade.get("DETAIL"));
        if (detail != null) {
            return detail;
        }
        JSONArray logs = trade == null ? null : trade.getJSONArray("LOGS");
        if (logs == null) {
            return null;
        }
        for (int i = 0; i < logs.size(); i++) {
            JSONObject logItem = logs.getJSONObject(i);
            String message = resolveLogMessage(logItem);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private static String resolveLogMessage(JSONObject logItem) {
        if (logItem == null) {
            return null;
        }
        String message = trimToNull(logItem.getString("info"));
        if (message != null) {
            return message;
        }
        message = trimToNull(logItem.getString("ERROR"));
        if (message != null) {
            return message;
        }
        return trimToNull(logItem.getString("message"));
    }
}
