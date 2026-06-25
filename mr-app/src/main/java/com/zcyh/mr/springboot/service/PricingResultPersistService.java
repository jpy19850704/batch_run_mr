package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.EngineRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MR 异步结果落库服务。
 * 负责将成功任务的结果拆分写入交易结果、情景结果、FRTB 敏感性和 DRC 明细表。
 */
@Service
public class PricingResultPersistService {
    private static final Logger log = LoggerFactory.getLogger(PricingResultPersistService.class);
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_10_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DEFAULT_BATCH_SIZE = 20000;
    private static final int MAX_INVALID_DRC_LOG = 10;
    private static final String DECOMP_TABLE = "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL";
    private static final String TRADE_RESULT_TABLE = "TB_OUT_TRADE_RESULT_DETAIL";
    private static final List<String> TRADE_RESULT_COLUMN_LIST = Collections.unmodifiableList(Arrays.asList(
            "REQUEST_ID",
            "JOB_ID",
            "BATCH_ID",
            "SEQ_NO",
            "DATA_DATE",
            "OP_CODE",
            "INSTRUMENT_ID",
            "PRODUCT_CODE",
            "PORTFOLIO",
            "DESK",
            "TRADER",
            "POSITION",
            "VALUATION_UNIT",
            "VALUATION",
            "VALUATION_CCY",
            "VALUATION_CNY",
            "RRAO_TYPE",
            "RRAO_NOTIONAL",
            "PV01",
            "DELTA",
            "GAMMA",
            "VEGA",
            "THETA",
            "RHO",
            "STATUS",
            "ERROR",
            "DETAIL",
            "LOGS_JSON",
            "CASHFLOW_JSON",
            "RESULT_JSON",
            "TRADE_INPUT_JSON",
            "MARKET_DATA_KEYS_JSON",
            "CREATED_AT",
            "UPDATED_AT"
    ));
    private static final String TRADE_RESULT_COLUMNS = String.join(",", TRADE_RESULT_COLUMN_LIST);
    private static final Map<String, String> TRADE_RESULT_DIMENSION_SOURCE_COLUMNS = buildTradeResultDimensionSourceColumns();
    private static final String SCENARIO_RESULT_TABLE = "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL";
    private static final String SCENARIO_RESULT_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,OP_CODE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,INSTRUMENT_ID,PRODUCT_CODE,"
                    + "BASE_VALUATION_CNY,SCENARIO_VALUATION_CNY,PNL,ERROR,DETAIL,LOGS_JSON,RESULT_JSON,CREATED_AT,UPDATED_AT";
    private static final String DECOMP_RESULT_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,OP_CODE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,INSTRUMENT_ID,PRODUCT_CODE,"
                    + "BASE_VALUATION_CNY,IR_VALUATION,IR_PNL,FX_VALUATION,FX_PNL,EQ_VALUATION,EQ_PNL,COMM_VALUATION,COMM_PNL,ALL_VALUATION,ALL_PNL,"
                    + "LOGS_JSON,RESULT_JSON,CREATED_AT,UPDATED_AT";
    private static final String FRTB_SENSITIVITY_TABLE = "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL";
    private static final String FRTB_SENSITIVITY_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,OP_CODE,INSTRUMENT_ID,PRODUCT_CODE,RISK_FACTOR_ID,RISK_FACTOR_VERTEX_1,RISK_FACTOR_VERTEX_2,"
                    + "RISK_FACTOR_CLASS,RISK_FACTOR_BUCKET,RISK_FACTOR_TYPE,SENSITIVITY_TYPE,SENSITIVITY_VAL_INST_CURR,INSTRUMENT_CURRENCY,SENSITIVITY_VAL_INST_CURR_CNY,DETAIL_JSON,CREATED_AT,UPDATED_AT";
    private static final String DRC_DETAIL_TABLE = "TB_OUT_TRADE_DRC_DETAIL";
    private static final String DRC_DETAIL_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,OP_CODE,INSTRUMENT_ID,PRODUCT_CODE,PORTFOLIO_CODE,SECURITY_ID,SECURITY_TYPE,LEGAL_ENTITY,"
                    + "DRC_BUCKET,JTD_TYPE,SENIORITY,TERM_TO_MATURITY,MODIFIED_REMAIN_TERM,RISK_WEIGHT,JTD,JTD_CNY,INSTRUMENT_VALUE,FRTB_LGD,NOTIONAL,DETAIL_JSON,CREATED_AT,UPDATED_AT";
    private static final String MARKET_DATA_TABLE = "TB_OUT_MARKET_DATA_DETAIL";
    private static final String MARKET_DATA_COLUMNS =
            "BATCH_ID,DATA_DATE,OP_CODE,CURVE_TYPE,CURVE_ID,CURVE_DATA_JSON,CREATED_AT,UPDATED_AT";
    private static final String RESULT_KIND_SCENARIO = "SCENARIO";
    private static final String RESULT_KIND_RISK_CLASS_DECOMP = "RISK_CLASS_DECOMP";
    private static final String SYNTHETIC_ERROR_TRADE_FLAG = "_SYNTHETIC_ERROR_TRADE";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final Object schemaVerifyLock = new Object();
    private volatile boolean requiredSchemaVerified = false;

    public PricingResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
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
        boolean decompTableExists = true;

        JSONObject root = toJsonObject(runResult.getData());
        if (root == null) {
            return;
        }
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return;
        }

        PersistContext context = buildContext(requestId, jobId, payloadJson);
        JSONArray baseTrades = data.getJSONArray("trade_data");
        JSONArray logData = data.getJSONArray("log_data");
        JSONArray scenarioResults = data.getJSONArray("scenario_result");

        // 从输入侧 payload 提取原始交易数据和市场数据（沿用已有的非计量指标写入模式）
        JSONObject payload = parseObjectSafely(payloadJson);
        Map<String, JSONObject> inputTradeIndex = buildInputTradeIndex(payload);
        JSONArray inputMarketData = payload == null ? null : payload.getJSONArray("market_data");
        JSONArray generatedMarketData = data.getJSONArray("generated_market_data");
        JSONArray effectiveBaseTrades = appendMissingErrorTradesFromLog(baseTrades, logData, inputTradeIndex, context);
        Map<String, JSONObject> baseTradeIndex = buildTradeIndex(effectiveBaseTrades);

        insertTradeResults(context, effectiveBaseTrades, inputTradeIndex);
        // 市场数据优先落库，避免后续敏感性/DRC异常导致 market_data 被一并跳过。
        insertMarketDataDetails(context, inputMarketData, generatedMarketData);
        insertDrcDetails(context, effectiveBaseTrades);
        insertFrtbSensitivityDetails(context, effectiveBaseTrades);
        insertScenarioResults(context, scenarioResults, baseTradeIndex, decompTableExists);
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
            verifyTableColumns(TRADE_RESULT_TABLE, String.join(", ", TRADE_RESULT_COLUMN_LIST));
            verifyTableColumns("TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
                    "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, INSTRUMENT_ID, PRODUCT_CODE, "
                            + "BASE_VALUATION_CNY, SCENARIO_VALUATION_CNY, PNL, ERROR, DETAIL, LOGS_JSON, RESULT_JSON, CREATED_AT, UPDATED_AT");
            verifyTableColumns(DECOMP_TABLE,
                    "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, INSTRUMENT_ID, PRODUCT_CODE, "
                            + "BASE_VALUATION_CNY, IR_VALUATION, IR_PNL, FX_VALUATION, FX_PNL, EQ_VALUATION, EQ_PNL, COMM_VALUATION, COMM_PNL, "
                            + "ALL_VALUATION, ALL_PNL, LOGS_JSON, RESULT_JSON, CREATED_AT, UPDATED_AT");
            verifyTableColumns("TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
                    "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                            + "INSTRUMENT_ID, PRODUCT_CODE, RISK_FACTOR_ID, RISK_FACTOR_VERTEX_1, RISK_FACTOR_VERTEX_2, "
                            + "RISK_FACTOR_CLASS, RISK_FACTOR_BUCKET, RISK_FACTOR_TYPE, SENSITIVITY_TYPE, "
                            + "SENSITIVITY_VAL_INST_CURR, INSTRUMENT_CURRENCY, SENSITIVITY_VAL_INST_CURR_CNY, DETAIL_JSON, CREATED_AT, UPDATED_AT");
            verifyTableColumns("TB_OUT_TRADE_DRC_DETAIL",
                    "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                            + "INSTRUMENT_ID, PRODUCT_CODE, PORTFOLIO_CODE, SECURITY_ID, SECURITY_TYPE, LEGAL_ENTITY, "
                            + "DRC_BUCKET, JTD_TYPE, SENIORITY, TERM_TO_MATURITY, MODIFIED_REMAIN_TERM, "
                            + "RISK_WEIGHT, JTD, JTD_CNY, INSTRUMENT_VALUE, FRTB_LGD, NOTIONAL, DETAIL_JSON, CREATED_AT, UPDATED_AT");
            verifyTableColumns("TB_OUT_MARKET_DATA_DETAIL",
                    "BATCH_ID, DATA_DATE, OP_CODE, CURVE_TYPE, CURVE_ID, CURVE_DATA_JSON, CREATED_AT, UPDATED_AT");
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

    private PersistContext buildContext(String requestId, String jobId, String payloadJson) {
        PersistContext context = new PersistContext();
        context.requestId = trimToNull(requestId);
        context.jobId = trimToNull(jobId);
        context.createdAt = ResultPersistTime.nowText();
        context.updatedAt = context.createdAt;

        JSONObject payload = parseObjectSafely(payloadJson);
        if (payload == null) {
            return context;
        }
        context.dataDate = normalizeDataDate(payload.getString("data_date"));
        context.opCode = trimToNull(payload.getString("oper_code"));

        JSONObject batchMeta = payload.getJSONObject("batch_meta");
        if (batchMeta != null) {
            context.batchId = trimToNull(batchMeta.getString("batch_id"));
            if (batchMeta.get("seq_no") != null) {
                context.seqNo = batchMeta.getLong("seq_no");
            }
        }
        context.tradeDimension = payload.getJSONObject("trade_dimension");
        context.tradeRrao = payload.getJSONObject("trade_rrao");
        return context;
    }

    /**
     * 写入基准估值结果。
     * 计量指标来自引擎输出，非计量指标（原始交易、市场数据依赖）来自输入侧 payload。
     *
     * @param context         落库上下文
     * @param trades          引擎输出的交易结果数据
     * @param inputTradeIndex 输入侧原始交易索引（INSTRUMENT_ID → 原始交易 JSON）
     */
    private void insertTradeResults(PersistContext context, JSONArray trades, Map<String, JSONObject> inputTradeIndex) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TRADE_RESULT_TABLE,
                TRADE_RESULT_COLUMNS,
                "trade_result_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            // 从输入侧 payload 提取原始交易和市场数据依赖。
            JSONObject inputTrade = (instrumentId == null || inputTradeIndex == null)
                    ? null : inputTradeIndex.get(instrumentId);
            JSONObject inputRrao = (instrumentId == null || context.tradeRrao == null)
                    ? null : context.tradeRrao.getJSONObject(instrumentId);
            buffer.appendRow(buildTradeResultRow(context, trade, instrumentId, inputTrade, inputRrao));
        }
        buffer.flush();
    }

    private Object[] buildTradeResultRow(PersistContext context, JSONObject trade, String instrumentId,
                                         JSONObject inputTrade, JSONObject inputRrao) {
        return new Object[]{
                context.requestId,
                context.jobId,
                context.batchId,
                context.seqNo,
                normalizeDataDate(context.dataDate),
                context.opCode,
                instrumentId,
                trimToNull(trade.getString("PRODUCT_CODE")),
                resolveTradeResultDimensionField(context.tradeDimension, instrumentId, "PORTFOLIO"),
                resolveTradeResultDimensionField(context.tradeDimension, instrumentId, "DESK"),
                resolveTradeResultDimensionField(context.tradeDimension, instrumentId, "TRADER"),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("POSITION"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("VALUATION_UNIT"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("VALUATION"))),
                trimToNull(trade.getString("VALUATION_CCY")),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("VALUATION_CNY"))),
                inputRrao == null ? null : trimToNull(inputRrao.getString("RRAO_TYPE")),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(inputRrao == null ? null : inputRrao.get("RRAO_NOTIONAL"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("PV01"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("DELTA"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("GAMMA"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("VEGA"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("THETA"))),
                DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(trade.get("RHO"))),
                trimToNull(trade.getString("STATUS")),
                null,
                toTextValue(trade.get("DETAIL")),
                toJsonString(trade.get("LOGS")),
                toJsonString(trade.get("CASH_FLOW")),
                isSyntheticErrorTrade(trade) ? null : toJsonString(trade),
                toJsonString(inputTrade),
                inputTrade == null ? null : toJsonString(inputTrade.get("_MARKET_DATA_KEYS")),
                context.createdAt,
                context.updatedAt
        };
    }

    private void insertScenarioResults(PersistContext context, JSONArray scenarioResults, Map<String, JSONObject> baseTradeIndex, boolean decompTableExists) {
        if (scenarioResults == null || scenarioResults.isEmpty()) {
            return;
        }
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
            if (isDecompScenarioResult(scenario)) {
                if (decompTableExists) {
                    insertScenarioDecompResults(context, scenario, baseTradeIndex);
                }
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
                        context.opCode,
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

    private void insertScenarioDecompResults(PersistContext context, JSONObject scenario, Map<String, JSONObject> baseTradeIndex) {
        JSONArray tradeData = scenario.getJSONArray("trade_data");
        if (tradeData == null || tradeData.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                DECOMP_TABLE,
                DECOMP_RESULT_COLUMNS,
                "scenario_decomp_" + context.batchId + "_" + context.jobId,
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
                    context.opCode,
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


    private void insertFrtbSensitivityDetails(PersistContext context, JSONArray trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                FRTB_SENSITIVITY_TABLE,
                FRTB_SENSITIVITY_COLUMNS,
                "frtb_sensitivity_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            JSONArray sensitivityList = trade.getJSONArray("FRTB_SENSITIVITY");
            if (sensitivityList == null || sensitivityList.isEmpty()) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            String productCode = trimToNull(trade.getString("PRODUCT_CODE"));
            for (int j = 0; j < sensitivityList.size(); j++) {
                JSONObject sensitivity = sensitivityList.getJSONObject(j);
                if (sensitivity == null) {
                    continue;
                }
                buffer.appendRow(
                        context.requestId,
                        context.jobId,
                        context.batchId,
                        context.seqNo,
                        normalizeDataDate(context.dataDate),
                        context.opCode,
                        instrumentId,
                        productCode,
                        trimToNull(sensitivity.getString("RISK_FACTOR_ID")),
                        trimToNull(sensitivity.getString("RISK_FACTOR_VERTEX_1")),
                        trimToNull(sensitivity.getString("RISK_FACTOR_VERTEX_2")),
                        trimToNull(sensitivity.getString("RISK_FACTOR_CLASS")),
                        trimToNull(sensitivity.getString("RISK_FACTOR_BUCKET")),
                        trimToNull(sensitivity.getString("RISK_FACTOR_TYPE")),
                        trimToNull(sensitivity.getString("SENSITIVITY_TYPE")),
                        DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(sensitivity.get("SENSITIVITY_VAL_INST_CURR"))),
                        trimToNull(sensitivity.getString("INSTRUMENT_CURRENCY")),
                        DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(sensitivity.get("SENSITIVITY_VAL_INST_CURR_CNY"))),
                        null,
                        context.createdAt,
                        context.updatedAt
                );
            }
        }
        buffer.flush();
    }

    private void insertDrcDetails(PersistContext context, JSONArray trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        int skippedNullJtdCny = 0;
        int logged = 0;
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                DRC_DETAIL_TABLE,
                DRC_DETAIL_COLUMNS,
                "drc_detail_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            JSONObject drc = trade.getJSONObject("DRC");
            if (drc == null || drc.isEmpty()) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            String productCode = trimToNull(trade.getString("PRODUCT_CODE"));
            BigDecimal jtd = toBigDecimal(drc.get("JTD"));
            BigDecimal jtdCny = toBigDecimal(drc.get("JTD_CNY"));
            // DRC计量口径已切换为JTD_CNY主导，缺失时直接跳过落库并记录问题。
            if (jtdCny == null) {
                skippedNullJtdCny++;
                if (logged < MAX_INVALID_DRC_LOG) {
                    log.warn("DRC明细缺少JTD_CNY，已跳过落库: batchId={}, instrumentId={}, productCode={}, drcSecurityType={}",
                            context.batchId,
                            instrumentId,
                            productCode,
                            trimToNull(drc.getString("SECURITY_TYPE")));
                    logged++;
                }
                continue;
            }
            buffer.appendRow(
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    context.seqNo,
                    normalizeDataDate(context.dataDate),
                    context.opCode,
                    instrumentId,
                    productCode,
                    trimToNull(drc.getString("PORTFOLIO_CODE")),
                    trimToNull(drc.getString("SECURITY_ID")),
                    trimToNull(drc.getString("SECURITY_TYPE")),
                    trimToNull(drc.getString("LEGAL_ENTITY")),
                    trimToNull(drc.getString("DRC_BUCKET")),
                    trimToNull(drc.getString("JTD_TYPE")),
                    toInteger(drc.get("SENIORITY")),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(drc.get("TERM_TO_MATURITY"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(drc.get("MODIFIED_REMAIN_TERM"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(drc.get("RISK_WEIGHT"))),
                    DorisCsvStreamLoadBuffer.decimalText(jtd),
                    DorisCsvStreamLoadBuffer.decimalText(jtdCny),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(drc.get("INSTRUMENT_VALUE"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(drc.get("FRTB_LGD"))),
                    DorisCsvStreamLoadBuffer.decimalText(toBigDecimal(drc.get("NOTIONAL"))),
                    null,
                    context.createdAt,
                    context.updatedAt
            );
        }
        buffer.flush();
        if (skippedNullJtdCny > 0) {
            log.warn("DRC明细落库跳过记录: batchId={}, skippedNullJtdCny={}, loggedRows={}",
                    context.batchId, skippedNullJtdCny, Math.min(skippedNullJtdCny, MAX_INVALID_DRC_LOG));
        }
    }

    /**
     * 从 log_data 补齐缺失的异常交易结果。
     * 仅在 trade_data 中没有该 INSTRUMENT_ID 时补一行，避免同一交易重复落库。
     */
    private static JSONArray appendMissingErrorTradesFromLog(JSONArray baseTrades, JSONArray logData,
                                                             Map<String, JSONObject> inputTradeIndex,
                                                             PersistContext context) {
        JSONArray result = new JSONArray();
        Set<String> existedInstrumentIds = new LinkedHashSet<String>();
        if (baseTrades != null) {
            for (int i = 0; i < baseTrades.size(); i++) {
                JSONObject trade = baseTrades.getJSONObject(i);
                if (trade == null) {
                    continue;
                }
                result.add(trade);
                String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
                if (instrumentId != null) {
                    existedInstrumentIds.add(instrumentId);
                }
            }
        }
        if ((logData == null || logData.isEmpty()) && (inputTradeIndex == null || inputTradeIndex.isEmpty())) {
            return result;
        }

        LinkedHashMap<String, JSONObject> missingErrorTrades = new LinkedHashMap<String, JSONObject>();
        if (logData != null) {
            for (int i = 0; i < logData.size(); i++) {
                JSONObject logItem = logData.getJSONObject(i);
                if (logItem == null) {
                    continue;
                }
                String instrumentId = trimToNull(logItem.getString("INSTRUMENT_ID"));
                if (instrumentId == null || existedInstrumentIds.contains(instrumentId)) {
                    continue;
                }
                String message = resolveLogMessage(logItem);
                if (message == null) {
                    message = "计算异常";
                }

                JSONObject errorTrade = missingErrorTrades.get(instrumentId);
                if (errorTrade == null) {
                    JSONObject inputTrade = inputTradeIndex == null ? null : inputTradeIndex.get(instrumentId);
                    errorTrade = buildSyntheticErrorTrade(instrumentId,
                            trimToNull(logItem.getString("PRODUCT_CODE")),
                            inputTrade,
                            context);
                    missingErrorTrades.put(instrumentId, errorTrade);
                }
                appendTradeLog(errorTrade, "ERROR", message);
            }
        }
        if (inputTradeIndex != null) {
            for (Map.Entry<String, JSONObject> entry : inputTradeIndex.entrySet()) {
                String instrumentId = trimToNull(entry.getKey());
                if (instrumentId == null || existedInstrumentIds.contains(instrumentId)
                        || missingErrorTrades.containsKey(instrumentId)) {
                    continue;
                }
                JSONObject errorTrade = buildSyntheticErrorTrade(instrumentId, null, entry.getValue(), context);
                appendTradeLog(errorTrade, "ERROR", "输入交易未生成计量结果");
                missingErrorTrades.put(instrumentId, errorTrade);
            }
        }

        for (JSONObject errorTrade : missingErrorTrades.values()) {
            result.add(errorTrade);
        }
        return result;
    }

    private static JSONObject buildSyntheticErrorTrade(String instrumentId, String productCode,
                                                       JSONObject inputTrade, PersistContext context) {
        JSONObject errorTrade = new JSONObject();
        errorTrade.put("INSTRUMENT_ID", instrumentId);
        errorTrade.put("PRODUCT_CODE", productCode != null ? productCode
                : inputTrade == null ? null : trimToNull(inputTrade.getString("PRODUCT_CODE")));
        errorTrade.put("DATA_DATE", context == null ? null : context.dataDate);
        errorTrade.put("STATUS", "ERROR");
        errorTrade.put(SYNTHETIC_ERROR_TRADE_FLAG, true);
        errorTrade.put("LOGS", new JSONArray());
        return errorTrade;
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

    private static void appendTradeLog(JSONObject errorTrade, String level, String message) {
        if (errorTrade == null) {
            return;
        }
        String safeMessage = trimToNull(message);
        if (safeMessage == null) {
            return;
        }
        JSONArray logs = errorTrade.getJSONArray("LOGS");
        if (logs == null) {
            logs = new JSONArray();
            errorTrade.put("LOGS", logs);
        }
        String safeLevel = trimToNull(level) == null ? "ERROR" : level;
        for (int i = 0; i < logs.size(); i++) {
            JSONObject item = logs.getJSONObject(i);
            if (item != null
                    && safeMessage.equals(String.valueOf(item.get("message")))
                    && safeLevel.equalsIgnoreCase(String.valueOf(item.get("level")))) {
                return;
            }
        }
        JSONObject log = new JSONObject();
        log.put("level", safeLevel);
        log.put("message", safeMessage);
        logs.add(log);
    }

    private static boolean isSyntheticErrorTrade(JSONObject trade) {
        return trade != null && Boolean.TRUE.equals(trade.getBoolean(SYNTHETIC_ERROR_TRADE_FLAG));
    }

    private Map<String, JSONObject> buildTradeIndex(JSONArray trades) {
        Map<String, JSONObject> index = new LinkedHashMap<String, JSONObject>();
        if (trades == null) {
            return index;
        }
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            if (instrumentId != null) {
                index.put(instrumentId, trade);
            }
        }
        return index;
    }

    /**
     * 从输入侧 payload 构建原始交易索引。
     * 用于将原始交易 JSON 和市场数据依赖写入结果表。
     */
    private Map<String, JSONObject> buildInputTradeIndex(JSONObject payload) {
        Map<String, JSONObject> index = new LinkedHashMap<String, JSONObject>();
        if (payload == null) {
            return index;
        }
        JSONArray inputTrades = payload.getJSONArray("trade_data");
        if (inputTrades == null) {
            return index;
        }
        for (int i = 0; i < inputTrades.size(); i++) {
            JSONObject trade = inputTrades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            if (instrumentId != null) {
                index.put(instrumentId, trade);
            }
        }
        return index;
    }

    /**
     * 写入市场数据结果表。
     * 按“外部优先”合并输入曲线与生成曲线后写入 TB_OUT_MARKET_DATA_DETAIL：
     * 1) 先写入输入侧 payload.market_data；
     * 2) 再补入 data.generated_market_data 中外部缺失的曲线；
     * 3) 冲突键（CURVE_TYPE + CURVE_ID）始终保留外部曲线。
     */
    private void insertMarketDataDetails(PersistContext context, JSONArray inputMarketData, JSONArray generatedMarketData) {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<String, JSONObject>();
        appendMarketDataByPriority(merged, inputMarketData, true, "INPUT");
        appendMarketDataByPriority(merged, generatedMarketData, false, "GENERATED");
        if (merged.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                MARKET_DATA_TABLE,
                MARKET_DATA_COLUMNS,
                "market_data_" + context.batchId + "_" + context.jobId,
                DEFAULT_BATCH_SIZE);
        for (JSONObject curve : merged.values()) {
            if (curve == null) {
                continue;
            }
            buffer.appendRow(
                    context.batchId,
                    normalizeDataDate(context.dataDate),
                    context.opCode,
                    resolveCurveType(curve),
                    resolveCurveId(curve),
                    toJsonString(curve),
                    context.createdAt,
                    context.updatedAt
            );
        }
        buffer.flush();
    }

    /**
     * 将市场数据按优先级合并到索引中。
     *
     * @param merged            合并结果（保持插入顺序）
     * @param marketData        待合并的曲线数组
     * @param overrideOnConflict 是否允许覆盖同键
     * @param sourceTag         来源标记（仅用于错误定位）
     */
    private void appendMarketDataByPriority(LinkedHashMap<String, JSONObject> merged, JSONArray marketData,
                                            boolean overrideOnConflict, String sourceTag) {
        if (merged == null || marketData == null || marketData.isEmpty()) {
            return;
        }
        for (int i = 0; i < marketData.size(); i++) {
            JSONObject curve = marketData.getJSONObject(i);
            if (curve == null) {
                continue;
            }
            String key = buildCurveMergeKey(curve, sourceTag, i);
            if (overrideOnConflict || !merged.containsKey(key)) {
                merged.put(key, curve);
            }
        }
    }

    /**
     * 构建曲线合并键，业务键必须完整提供。
     */
    private String buildCurveMergeKey(JSONObject curve, String sourceTag, int index) {
        if (curve == null) {
            throw new IllegalArgumentException("市场数据为空，无法构建合并键: source=" + sourceTag + ", index=" + index);
        }
        String curveType = resolveCurveType(curve);
        String curveId = resolveCurveId(curve);
        if (curveType == null || curveId == null) {
            throw new IllegalArgumentException("市场数据缺少 CURVE_TYPE 或 CURVE_ID/FIXING_ID，无法构建合并键: source="
                    + sourceTag + ", index=" + index);
        }
        return curveType + "|" + curveId;
    }

    private static String resolveCurveType(JSONObject curve) {
        if (curve == null) {
            return null;
        }
        return trimToNull(curve.getString("CURVE_TYPE"));
    }

    /**
     * CURVE_ID 或 FIXING_ID 作为曲线标识。
     */
    private static String resolveCurveId(JSONObject curve) {
        if (curve == null) {
            return null;
        }
        String curveId = trimToNull(curve.getString("CURVE_ID"));
        if (curveId == null) {
            curveId = trimToNull(curve.getString("FIXING_ID"));
        }
        return curveId;
    }

    private static boolean isDecompScenarioResult(JSONObject scenario) {
        if (scenario == null) {
            return false;
        }
        String resultKind = trimToNull(scenario.getString("RESULT_KIND"));
        if (RESULT_KIND_RISK_CLASS_DECOMP.equalsIgnoreCase(resultKind)) {
            return true;
        }
        if (RESULT_KIND_SCENARIO.equalsIgnoreCase(resultKind)) {
            return false;
        }
        throw new IllegalStateException("scenario_result 缺少合法 RESULT_KIND，仅支持 SCENARIO 或 RISK_CLASS_DECOMP");
    }

    private static JSONObject toJsonObject(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof JSONObject) {
            return (JSONObject) obj;
        }
        return parseObjectSafely(JSON.toJSONString(obj, JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    private static JSONObject parseObjectSafely(String text) {
        String safe = trimToNull(text);
        if (safe == null) {
            return null;
        }
        try {
            return JSON.parseObject(safe);
        } catch (Exception ex) {
            return null;
        }
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

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Integer) {
                return (Integer) value;
            }
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
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

    private static Map<String, String> buildTradeResultDimensionSourceColumns() {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        map.put("PORTFOLIO", "portfolio");
        map.put("DESK", "desk");
        map.put("TRADER", "trader");
        return Collections.unmodifiableMap(map);
    }

    /**
     * trade_detail 输出维度字段只读取批次构建阶段生成的 trade_dimension。
     */
    private static String resolveTradeResultDimensionField(JSONObject tradeDimension, String instrumentId,
                                                           String outputColumn) {
        String sourceColumn = TRADE_RESULT_DIMENSION_SOURCE_COLUMNS.get(outputColumn);
        if (sourceColumn == null) {
            throw new IllegalStateException("trade_detail输出字段缺少输入维度映射: " + outputColumn);
        }
        if (tradeDimension != null && instrumentId != null) {
            JSONObject dim = tradeDimension.getJSONObject(instrumentId);
            if (dim != null) {
                return trimToNull(dim.getString(sourceColumn));
            }
        }
        return null;
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

    /**
     * 单任务落库上下文。
     */
    private static final class PersistContext {
        private String requestId;
        private String jobId;
        private String batchId;
        private Long seqNo;
        private String dataDate;
        private String opCode;
        private String createdAt;
        private String updatedAt;
        private JSONObject tradeDimension;
        private JSONObject tradeRrao;
    }
}
