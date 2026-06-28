package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.EngineRunResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MR 异步结果落库服务。
 * 负责将成功任务的结果拆分写入交易结果、情景结果、FRTB 敏感性和 DRC 明细表。
 */
@Service
public class PricingResultPersistService {
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_10_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DEFAULT_BATCH_SIZE = 20000;
    private static final String VAR_TABLE = "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL";
    private static final String SCENARIO_RESULT_TABLE = "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL";
    private static final String SCENARIO_RESULT_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,INSTRUMENT_ID,PRODUCT_CODE,"
                    + "BASE_VALUATION_CNY,SCENARIO_VALUATION_CNY,PNL,ERROR,DETAIL,LOGS_JSON,RESULT_JSON,CREATED_AT,UPDATED_AT";
    private static final String VAR_RESULT_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,INSTRUMENT_ID,PRODUCT_CODE,"
                    + "BASE_VALUATION_CNY,IR_VALUATION,IR_PNL,FX_VALUATION,FX_PNL,EQ_VALUATION,EQ_PNL,COMM_VALUATION,COMM_PNL,ALL_VALUATION,ALL_PNL,"
                    + "LOGS_JSON,RESULT_JSON,CREATED_AT,UPDATED_AT";
    private static final String IMA_MODELLABLE_SCENARIO_TABLE = "TB_OUT_IMA_MODELLABLE_SCENARIO_PNL";
    private static final String IMA_MODELLABLE_SCENARIO_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,"
                    + "INSTRUMENT_ID,PRODUCT_CODE,LH_DAYS,BASE_VALUATION_CNY,IR_VALUATION,IR_PNL,CS_VALUATION,CS_PNL,FX_VALUATION,FX_PNL,"
                    + "EQ_VALUATION,EQ_PNL,COMM_VALUATION,COMM_PNL,ALL_VALUATION,ALL_PNL,CREATED_AT,UPDATED_AT";
    private static final String IMA_NMRF_SCENARIO_TABLE = "TB_OUT_IMA_NMRF_SCENARIO_PNL";
    private static final String IMA_NMRF_SCENARIO_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,"
                    + "INSTRUMENT_ID,PRODUCT_CODE,RISK_FACTOR_ID,NMRF_TYPE,BASE_VALUATION_CNY,STRESS_VALUATION_CNY,PNL,CREATED_AT,UPDATED_AT";
    private static final String RESULT_KIND_SCENARIO = "SCENARIO";
    private static final String RESULT_KIND_VAR = "VAR";
    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final CalcPersistContextFactory contextFactory;
    private final TradeResultWriter tradeResultWriter;
    private final MarketDataResultWriter marketDataResultWriter;
    private final FrtbSensitivityDetailWriter frtbSensitivityDetailWriter;
    private final DrcDetailWriter drcDetailWriter;
    private final Object schemaVerifyLock = new Object();
    private volatile boolean requiredSchemaVerified = false;

    public PricingResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       DorisStreamLoadService dorisStreamLoadService,
                                       CalcPersistContextFactory contextFactory,
                                       TradeResultWriter tradeResultWriter,
                                       MarketDataResultWriter marketDataResultWriter,
                                       FrtbSensitivityDetailWriter frtbSensitivityDetailWriter,
                                       DrcDetailWriter drcDetailWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.contextFactory = contextFactory;
        this.tradeResultWriter = tradeResultWriter;
        this.marketDataResultWriter = marketDataResultWriter;
        this.frtbSensitivityDetailWriter = frtbSensitivityDetailWriter;
        this.drcDetailWriter = drcDetailWriter;
    }

    /**
     * 系统启动后一次性校验结果表结构，运行期不重复触发表字段探测。
     */
    @PostConstruct
    public void verifyRequiredSchemaOnStartup() {
        ensureRequiredOutputSchema();
    }

    /**
     * 按任务覆盖写入结果明细。
     * 写入失败不影响任务状态，仅记录日志。
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persistJobResult(String requestId, String jobId, String payloadJson, EngineRunResult runResult) {
        if (runResult == null || !runResult.isSuccess()) {
            return;
        }
        if (!MrCalcEngineAdapter.CODE.equalsIgnoreCase(trimToNull(runResult.getEngineCode()))) {
            return;
        }
        boolean varTableExists = true;

        CalcPersistContext context = contextFactory.build(requestId, jobId, payloadJson, runResult);
        if (context == null) {
            return;
        }

        tradeResultWriter.write(context);
        // 市场数据优先落库，避免后续敏感性/DRC异常导致 market_data 被一并跳过。
        marketDataResultWriter.write(context);
        drcDetailWriter.write(context);
        frtbSensitivityDetailWriter.write(context);
        insertScenarioResults(context, context.scenarioResults, context.baseTradeIndex, varTableExists);
        insertImaModellableScenarioResults(context, context.imaModellableScenarioResults);
    }

    /**
     * 严格校验输出表列契约，缺列/改名时在写入前快速失败。
     */
    private void ensureRequiredOutputSchema() {
        if (requiredSchemaVerified) {
            return;
        }
        synchronized (schemaVerifyLock) {
            if (requiredSchemaVerified) {
                return;
            }
            verifyTableColumns("TB_OUT_TRADE_RESULT_DETAIL", String.join(", ", tradeResultWriter.requiredColumns()));
            verifyTableColumns("TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
                    "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, "
                            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, INSTRUMENT_ID, PRODUCT_CODE, "
                            + "BASE_VALUATION_CNY, SCENARIO_VALUATION_CNY, PNL, ERROR, DETAIL, LOGS_JSON, RESULT_JSON, CREATED_AT, UPDATED_AT");
            verifyTableColumns(VAR_TABLE,
                    "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, "
                            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, INSTRUMENT_ID, PRODUCT_CODE, "
                            + "BASE_VALUATION_CNY, IR_VALUATION, IR_PNL, FX_VALUATION, FX_PNL, EQ_VALUATION, EQ_PNL, COMM_VALUATION, COMM_PNL, "
                            + "ALL_VALUATION, ALL_PNL, LOGS_JSON, RESULT_JSON, CREATED_AT, UPDATED_AT");
            verifyTableColumns("TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL", String.join(", ", frtbSensitivityDetailWriter.requiredColumns()));
            verifyTableColumns("TB_OUT_TRADE_DRC_DETAIL", String.join(", ", drcDetailWriter.requiredColumns()));
            verifyTableColumns("TB_OUT_MARKET_DATA_DETAIL", String.join(", ", marketDataResultWriter.requiredColumns()));
            requiredSchemaVerified = true;
        }
    }

    private void verifyTableColumns(String tableName, String columns) {
        String sql = "SELECT " + columns + " FROM " + tableName + " WHERE 1=0";
        try {
            jdbcTemplate.queryForList(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("输出结果表结构校验失败: " + tableName + "，原因=" + ex.getMessage(), ex);
        }
    }

    private void insertScenarioResults(CalcPersistContext context, JSONArray scenarioResults, Map<String, JSONObject> baseTradeIndex, boolean varTableExists) {
        if (scenarioResults == null || scenarioResults.isEmpty()) {
            return;
        }
        List<JSONObject> varScenarios = new java.util.ArrayList<JSONObject>();
        List<JSONObject> imaModellableScenarios = new java.util.ArrayList<JSONObject>();
        List<JSONObject> imaNmrfScenarios = new java.util.ArrayList<JSONObject>();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                SCENARIO_RESULT_TABLE,
                SCENARIO_RESULT_COLUMNS,
                "scenario_result_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < scenarioResults.size(); i++) {
            JSONObject scenario = scenarioResults.getJSONObject(i);
            if (scenario == null) {
                continue;
            }
            if (isVarScenarioResult(scenario)) {
                if (varTableExists) {
                    insertScenarioVarResults(context, scenario, baseTradeIndex);
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
            JSONArray tradeData = scenario.getJSONArray("trade_data");
            if (tradeData == null || tradeData.isEmpty()) {
                continue;
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
                        resolveScenarioError(trade),
                        toTextValue(trade.get("DETAIL")),
                        toJsonString(trade.get("LOGS")),
                        toJsonString(trade),
                        context.createdAt,
                        context.updatedAt
                );
            }
        }
        buffer.flush();
        if (varTableExists) {
            insertProcessedScenarioVarResults(context, varScenarios, baseTradeIndex);
        }
        insertImaModellableScenarioResults(context,
                buildImaModellableRowsFromScenarioResults(context, imaModellableScenarios, baseTradeIndex));
        insertImaNmrfScenarioResults(context,
                buildImaNmrfRowsFromScenarioResults(context, imaNmrfScenarios, baseTradeIndex));
    }

    private static String resolveScenarioProcessType(JSONObject scenario) {
        String processType = trimToNull(scenario == null ? null : scenario.getString("SCENARIO_PROCESS_TYPE"));
        if (processType == null) {
            throw new IllegalStateException("scenario_result 缺少 SCENARIO_PROCESS_TYPE");
        }
        String upper = processType.toUpperCase(java.util.Locale.ROOT);
        if (ScenarioProcessConstants.isValidProcessType(upper)) {
            return upper;
        }
        throw new IllegalStateException("scenario_result.SCENARIO_PROCESS_TYPE 无效: " + processType);
    }

    private void insertProcessedScenarioVarResults(CalcPersistContext context,
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
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                VAR_TABLE,
                VAR_RESULT_COLUMNS,
                "scenario_var_" + context.batchId + "_" + context.jobId + "_processed",
                DEFAULT_BATCH_SIZE);
        for (JSONObject row : rows.values()) {
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
                    toJsonString(row.get("LOGS")),
                    toJsonString(row),
                    context.createdAt,
                    context.updatedAt
            );
        }
        buffer.flush();
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
        return row;
    }

    private JSONArray buildImaModellableRowsFromScenarioResults(CalcPersistContext context,
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
                String rowKey = scenarioRowKey(scenario, instrumentId);
                JSONObject row = rows.get(rowKey);
                if (row == null) {
                    row = initImaModellablePersistRow(context, scenario, trade, baseTrade, instrumentId, lhDays);
                    rows.put(rowKey, row);
                }
                if (isScenarioErrorTrade(trade)) {
                    continue;
                }
                row.put(prefix + "_VALUATION", toBigDecimal(trade.get("SCENARIO_VALUATION_CNY")));
                row.put(prefix + "_PNL", toBigDecimal(trade.get("PNL")));
            }
        }
        result.addAll(rows.values());
        return result;
    }

    private JSONArray buildImaNmrfRowsFromScenarioResults(CalcPersistContext context,
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
                BigDecimal baseValuation = requireDecimal(trade, "BASE_VALUATION_CNY", "IMA NMRF scenario_result");
                BigDecimal stressValuation = requireDecimal(trade, "SCENARIO_VALUATION_CNY", "IMA NMRF scenario_result");
                BigDecimal pnl = requireDecimal(trade, "PNL", "IMA NMRF scenario_result");
                JSONObject baseTrade = baseTradeIndex.get(instrumentId);
                JSONObject row = new JSONObject();
                row.put("SEQ_NO", context.seqNo);
                row.put("SCENARIO_ID", trimToNull(scenario.getString("SCENARIO_ID")));
                row.put("SUBSCENARIO_ID", trimToNull(scenario.getString("SUBSCENARIO_ID")));
                row.put("SCENARIO_NAME", trimToNull(scenario.getString("SCENARIO_NAME")));
                row.put("INSTRUMENT_ID", instrumentId);
                row.put("PRODUCT_CODE", baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")));
                row.put("RISK_FACTOR_ID", riskFactorId);
                row.put("NMRF_TYPE", nmrfType);
                row.put("BASE_VALUATION_CNY", baseValuation);
                row.put("STRESS_VALUATION_CNY", stressValuation);
                row.put("PNL", pnl);
                result.add(row);
            }
        }
        return result;
    }

    private static JSONObject initImaModellablePersistRow(CalcPersistContext context,
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
        row.put("SEQ_NO", context.seqNo);
        row.put("SCENARIO_ID", trimToNull(scenario.getString("SCENARIO_ID")));
        row.put("SUBSCENARIO_ID", trimToNull(scenario.getString("SUBSCENARIO_ID")));
        row.put("SCENARIO_NAME", trimToNull(scenario.getString("SCENARIO_NAME")));
        row.put("SCENARIO_TYPE", trimToNull(scenario.getString("SCENARIO_TYPE")));
        row.put("INSTRUMENT_ID", instrumentId);
        row.put("PRODUCT_CODE", baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")));
        row.put("LH_DAYS", lhDays);
        row.put("BASE_VALUATION_CNY", baseValuation);
        row.put("IR_VALUATION", baseValuation);
        row.put("IR_PNL", BigDecimal.ZERO);
        row.put("CS_VALUATION", baseValuation);
        row.put("CS_PNL", BigDecimal.ZERO);
        row.put("FX_VALUATION", baseValuation);
        row.put("FX_PNL", BigDecimal.ZERO);
        row.put("EQ_VALUATION", baseValuation);
        row.put("EQ_PNL", BigDecimal.ZERO);
        row.put("COMM_VALUATION", baseValuation);
        row.put("COMM_PNL", BigDecimal.ZERO);
        row.put("ALL_VALUATION", baseValuation);
        row.put("ALL_PNL", BigDecimal.ZERO);
        return row;
    }

    private static String scenarioRowKey(JSONObject scenario, String instrumentId) {
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

    private static String mapImaRiskClassToColumnPrefix(String riskClass) {
        String upper = normalizeRiskClass(riskClass);
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

    private static String normalizeRiskClass(String riskClass) {
        String safe = trimToNull(riskClass);
        if (safe == null) {
            throw new IllegalStateException("scenario_result 缺少风险类别 tag");
        }
        return safe.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isScenarioErrorTrade(JSONObject trade) {
        return trade != null && "ERROR".equalsIgnoreCase(String.valueOf(trade.get("STATUS")));
    }

    private static void appendPersistVarLog(JSONObject row, String riskClass, JSONArray sourceLogs) {
        JSONArray logs = row.getJSONArray("LOGS");
        if (logs == null) {
            logs = new JSONArray();
            row.put("LOGS", logs);
        }
        String prefix = trimToNull(riskClass) == null ? "UNKNOWN" : riskClass.trim();
        if (sourceLogs == null || sourceLogs.isEmpty()) {
            JSONObject logItem = new JSONObject();
            logItem.put("level", "ERROR");
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
            logItem.put("level", String.valueOf(source.getOrDefault("level", "ERROR")));
            logItem.put("message", prefix + ": " + String.valueOf(source.getOrDefault("message", "")));
            logs.add(logItem);
        }
    }

    private static String resolveScenarioError(JSONObject trade) {
        if (trade == null || !"ERROR".equalsIgnoreCase(String.valueOf(trade.get("STATUS")))) {
            return null;
        }
        String error = trimToNull(trade.getString("ERROR"));
        if (error != null) {
            return error;
        }
        String detail = toTextValue(trade.get("DETAIL"));
        if (detail != null) {
            return detail;
        }
        JSONArray logs = trade.getJSONArray("LOGS");
        if (logs != null) {
            for (int i = 0; i < logs.size(); i++) {
                JSONObject logItem = logs.getJSONObject(i);
                String message = resolveLogMessage(logItem);
                if (message != null) {
                    return message;
                }
            }
        }
        return "情景估值失败";
    }

    private void insertScenarioVarResults(CalcPersistContext context, JSONObject scenario, Map<String, JSONObject> baseTradeIndex) {
        JSONArray tradeData = scenario.getJSONArray("trade_data");
        if (tradeData == null || tradeData.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                VAR_TABLE,
                VAR_RESULT_COLUMNS,
                "scenario_var_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < tradeData.size(); i++) {
            JSONObject trade = tradeData.getJSONObject(i);
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
                    instrumentId,
                    baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE")),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("BASE_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("IR_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("IR_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("FX_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("FX_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("EQ_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("EQ_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("COMM_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("COMM_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("ALL_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("ALL_PNL"))),
                    toJsonString(trade.get("LOGS")),
                    toJsonString(trade),
                    context.createdAt,
                    context.updatedAt
            );
        }
        buffer.flush();
    }

    private void insertImaModellableScenarioResults(CalcPersistContext context, JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                IMA_MODELLABLE_SCENARIO_TABLE,
                IMA_MODELLABLE_SCENARIO_COLUMNS,
                "ima_modellable_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            buffer.appendRow(
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    row.get("SEQ_NO"),
                    normalizeDataDate(context.dataDate),
                    trimToNull(row.getString("SCENARIO_ID")),
                    trimToNull(row.getString("SUBSCENARIO_ID")),
                    trimToNull(row.getString("SCENARIO_NAME")),
                    trimToNull(row.getString("SCENARIO_TYPE")),
                    trimToNull(row.getString("INSTRUMENT_ID")),
                    trimToNull(row.getString("PRODUCT_CODE")),
                    row.get("LH_DAYS"),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("BASE_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("IR_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("IR_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("CS_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("CS_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("FX_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("FX_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("EQ_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("EQ_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("COMM_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("COMM_PNL"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("ALL_VALUATION"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("ALL_PNL"))),
                    resolveCreatedAt(row, now),
                    now);
        }
        buffer.flush();
    }

    private void insertImaNmrfScenarioResults(CalcPersistContext context, JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                IMA_NMRF_SCENARIO_TABLE,
                IMA_NMRF_SCENARIO_COLUMNS,
                "ima_nmrf_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            buffer.appendRow(
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    row.get("SEQ_NO"),
                    normalizeDataDate(context.dataDate),
                    trimToNull(row.getString("SCENARIO_ID")),
                    trimToNull(row.getString("SUBSCENARIO_ID")),
                    trimToNull(row.getString("SCENARIO_NAME")),
                    trimToNull(row.getString("INSTRUMENT_ID")),
                    trimToNull(row.getString("PRODUCT_CODE")),
                    trimToNull(row.getString("RISK_FACTOR_ID")),
                    trimToNull(row.getString("NMRF_TYPE")),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("BASE_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("STRESS_VALUATION_CNY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(row.get("PNL"))),
                    resolveCreatedAt(row, now),
                    now);
        }
        buffer.flush();
    }

    private static String resolveLogMessage(JSONObject logItem) {
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

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private static BigDecimal requireDecimal(JSONObject row, String field, String source) {
        BigDecimal value = toBigDecimal(row == null ? null : row.get(field));
        if (value == null) {
            throw new IllegalStateException(source + " 缺少 " + field);
        }
        return value;
    }

    private static String toTextValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence) {
            return trimToNull(String.valueOf(value));
        }
        return toJsonString(value);
    }

    private static String toJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence) {
            String text = trimToNull(String.valueOf(value));
            if (text == null) {
                return null;
            }
            JSON.parse(text);
            return text;
        }
        if (value instanceof JSONObject && ((JSONObject) value).isEmpty()) {
            return null;
        }
        return JSON.toJSONString(value, JSONWriter.Feature.WriteBigDecimalAsPlain);
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

    private static String normalizeDataDate(String dataDateText) {
        String text = trimToNull(dataDateText);
        if (text == null) {
            return null;
        }
        try {
            if (text.length() == 8) {
                return LocalDate.parse(text, DATE_8_FORMATTER).format(DATE_8_FORMATTER);
            }
            if (text.length() == 10) {
                return LocalDate.parse(text, DATE_10_FORMATTER).format(DATE_8_FORMATTER);
            }
        } catch (DateTimeParseException ex) {
            return text;
        }
        return text;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
