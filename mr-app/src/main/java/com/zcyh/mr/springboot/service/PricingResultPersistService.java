package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.EngineRunResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_10_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final String DECOMP_TABLE = "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL";

    private final JdbcTemplate jdbcTemplate;

    public PricingResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按任务覆盖写入结果明细。
     * 【Doris 备注】Doris 2.0+ 支持单表事务（enable_unique_key_merge_on_write=true），
     * 但不支持跨表事务。切换后建议移除 @Transactional 注解，改为单表独立写入。
     * 写入失败不影响任务状态，仅记录日志。
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager")
    public void persistJobResult(String requestId, String jobId, String payloadJson, EngineRunResult runResult) {
        if (runResult == null || !runResult.isSuccess()) {
            return;
        }
        if (!MrCalcEngineAdapter.CODE.equalsIgnoreCase(trimToNull(runResult.getEngineCode()))) {
            return;
        }
        ensureRequiredOutputSchema();
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
        Map<String, JSONObject> baseTradeIndex = buildTradeIndex(baseTrades);
        JSONArray scenarioResults = data.getJSONArray("scenario_result");
        Set<String> instrumentIds = collectInstrumentIds(baseTrades, scenarioResults);
        deleteExistingResultRows(context, jobId, instrumentIds, decompTableExists);

        // 从输入侧 payload 提取原始交易数据和市场数据（沿用已有的非计量指标写入模式）
        JSONObject payload = parseObjectSafely(payloadJson);
        Map<String, JSONObject> inputTradeIndex = buildInputTradeIndex(payload);
        JSONArray inputMarketData = payload == null ? null : payload.getJSONArray("market_data");
        JSONArray generatedMarketData = data.getJSONArray("generated_market_data");

        insertTradeResults(context, baseTrades, inputTradeIndex);
        insertDrcDetails(context, baseTrades);
        insertFrtbSensitivityDetails(context, baseTrades);
        insertScenarioResults(context, scenarioResults, baseTradeIndex, decompTableExists);
        insertMarketDataDetails(context, inputMarketData, generatedMarketData);
    }

    /**
     * 严格校验输出表列契约，缺列/改名时在写入前快速失败。
     */
    private void ensureRequiredOutputSchema() {
        verifyTableColumns("TB_OUT_TRADE_RESULT_DETAIL",
                "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                        + "INSTRUMENT_ID, PRODUCT_CODE, PORTFOLIO, DESK, TRADER, "
                        + "POSITION, VALUATION_UNIT, VALUATION, VALUATION_CCY, VALUATION_CNY, "
                        + "PV01, DELTA, GAMMA, VEGA, THETA, RHO, STATUS, ERROR, DETAIL, ERRORS_JSON, CASHFLOW_JSON, RESULT_JSON, "
                        + "TRADE_INPUT_JSON, MARKET_DATA_KEYS_JSON, CREATED_AT, UPDATED_AT");
        verifyTableColumns("TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
                "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                        + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, INSTRUMENT_ID, PRODUCT_CODE, "
                        + "BASE_VALUATION_CNY, SCENARIO_VALUATION_CNY, PNL, ERROR, DETAIL, RESULT_JSON, CREATED_AT, UPDATED_AT");
        verifyTableColumns(DECOMP_TABLE,
                "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                        + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, INSTRUMENT_ID, PRODUCT_CODE, "
                        + "BASE_VALUATION_CNY, IR_VALUATION, IR_PNL, FX_VALUATION, FX_PNL, EQ_VALUATION, EQ_PNL, COMM_VALUATION, COMM_PNL, "
                        + "ALL_VALUATION, ALL_PNL, RESULT_JSON, CREATED_AT, UPDATED_AT");
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
        context.createdAt = System.currentTimeMillis();
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
        return context;
    }

    private void deleteByJobId(String jobId, boolean decompTableExists) {
        jdbcTemplate.update("DELETE FROM TB_OUT_TRADE_RESULT_DETAIL WHERE JOB_ID=?", jobId);
        jdbcTemplate.update("DELETE FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL WHERE JOB_ID=?", jobId);
        jdbcTemplate.update("DELETE FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL WHERE JOB_ID=?", jobId);
        jdbcTemplate.update("DELETE FROM TB_OUT_TRADE_DRC_DETAIL WHERE JOB_ID=?", jobId);
        if (decompTableExists) {
            jdbcTemplate.update("DELETE FROM " + DECOMP_TABLE + " WHERE JOB_ID=?", jobId);
        }
        // 市场数据表无 JOB_ID 字段，在 deleteExistingResultRows 中按 BATCH_ID 统一清理
    }

    private void deleteExistingResultRows(PersistContext context, String jobId, Set<String> instrumentIds, boolean decompTableExists) {
        deleteByJobId(jobId, decompTableExists);
        if (trimToNull(context.batchId) == null || instrumentIds == null || instrumentIds.isEmpty()) {
            return;
        }
        deleteByBatchAndInstrumentIds("TB_OUT_TRADE_RESULT_DETAIL", context.batchId, instrumentIds);
        deleteByBatchAndInstrumentIds("TB_OUT_TRADE_SCENARIO_RESULT_DETAIL", context.batchId, instrumentIds);
        deleteByBatchAndInstrumentIds("TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL", context.batchId, instrumentIds);
        deleteByBatchAndInstrumentIds("TB_OUT_TRADE_DRC_DETAIL", context.batchId, instrumentIds);
        if (decompTableExists) {
            deleteByBatchAndInstrumentIds(DECOMP_TABLE, context.batchId, instrumentIds);
        }
    }

    private void deleteByBatchAndInstrumentIds(String tableName, String batchId, Set<String> instrumentIds) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("DELETE FROM ").append(tableName).append(" WHERE BATCH_ID=? AND INSTRUMENT_ID IN (");
        params.add(batchId);
        int index = 0;
        for (String instrumentId : instrumentIds) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(instrumentId);
            index++;
        }
        sql.append(")");
        jdbcTemplate.update(sql.toString(), params.toArray());
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
        String sql = "INSERT INTO TB_OUT_TRADE_RESULT_DETAIL ("
                + "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                + "INSTRUMENT_ID, PRODUCT_CODE, PORTFOLIO, DESK, TRADER, "
                + "POSITION, VALUATION_UNIT, VALUATION, VALUATION_CCY, VALUATION_CNY, "
                + "PV01, DELTA, GAMMA, VEGA, THETA, RHO, STATUS, ERROR, DETAIL, ERRORS_JSON, CASHFLOW_JSON, RESULT_JSON, "
                + "TRADE_INPUT_JSON, MARKET_DATA_KEYS_JSON, "
                + "CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            // 从输入侧 payload 提取原始交易和市场数据依赖（沿用 tradeDimension 的设计模式）
            JSONObject inputTrade = (instrumentId == null || inputTradeIndex == null)
                    ? null : inputTradeIndex.get(instrumentId);
            jdbcTemplate.update(
                    sql,
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    context.seqNo,
                    normalizeDataDate(firstNonBlank(trade.getString("DATA_DATE"), context.dataDate)),
                    context.opCode,
                    instrumentId,
                    trimToNull(trade.getString("PRODUCT_CODE")),
                    resolveDimensionField(context.tradeDimension, instrumentId, "PORTFOLIO", trade, "PORTFOLIO", "PORTFOLIO_CODE"),
                    resolveDimensionField(context.tradeDimension, instrumentId, "DESK", trade, "DESK", "DESK_CODE"),
                    resolveDimensionField(context.tradeDimension, instrumentId, "TRADER", trade, "TRADER", "TRADER_CODE"),
                    toBigDecimal(trade.get("POSITION")),
                    toBigDecimal(trade.get("VALUATION_UNIT")),
                    toBigDecimal(trade.get("VALUATION")),
                    trimToNull(trade.getString("VALUATION_CCY")),
                    toBigDecimal(trade.get("VALUATION_CNY")),
                    toBigDecimal(trade.get("PV01")),
                    toBigDecimal(trade.get("DELTA")),
                    toBigDecimal(trade.get("GAMMA")),
                    toBigDecimal(trade.get("VEGA")),
                    toBigDecimal(trade.get("THETA")),
                    toBigDecimal(trade.get("RHO")),
                    trimToNull(trade.getString("STATUS")),
                    resolveErrorText(trade),
                    toTextValue(trade.get("DETAIL")),
                    toJsonString(trade.get("ERRORS")),
                    toJsonString(trade.get("CASH_FLOW")),
                    toJsonString(trade),
                    toJsonString(inputTrade),
                    inputTrade == null ? null : toJsonString(inputTrade.get("_MARKET_DATA_KEYS")),
                    context.createdAt,
                    context.updatedAt
            );
        }
    }

    private void insertScenarioResults(PersistContext context, JSONArray scenarioResults, Map<String, JSONObject> baseTradeIndex, boolean decompTableExists) {
        if (scenarioResults == null || scenarioResults.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO TB_OUT_TRADE_SCENARIO_RESULT_DETAIL ("
                + "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, INSTRUMENT_ID, PRODUCT_CODE, "
                + "BASE_VALUATION_CNY, SCENARIO_VALUATION_CNY, PNL, ERROR, DETAIL, RESULT_JSON, CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        List<Object[]> batchArgs = new ArrayList<Object[]>();
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
                batchArgs.add(new Object[]{
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
                        toBigDecimal(trade.get("BASE_VALUATION_CNY")),
                        toBigDecimal(trade.get("SCENARIO_VALUATION_CNY")),
                        toBigDecimal(trade.get("PNL")),
                        resolveErrorText(trade),
                        toTextValue(trade.get("DETAIL")),
                        toJsonString(trade),
                        context.createdAt,
                        context.updatedAt
                });
                if (batchArgs.size() >= DEFAULT_BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batchArgs);
                    batchArgs.clear();
                }
            }
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batchArgs);
        }
    }

    private void insertScenarioDecompResults(PersistContext context, JSONObject scenario, Map<String, JSONObject> baseTradeIndex) {
        JSONArray tradeData = scenario.getJSONArray("trade_data");
        if (tradeData == null || tradeData.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + DECOMP_TABLE + " ("
                + "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, INSTRUMENT_ID, PRODUCT_CODE, "
                + "BASE_VALUATION_CNY, "
                + "IR_VALUATION, IR_PNL, FX_VALUATION, FX_PNL, EQ_VALUATION, EQ_PNL, COMM_VALUATION, COMM_PNL, ALL_VALUATION, ALL_PNL, "
                + "RESULT_JSON, CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        List<Object[]> batchArgs = new ArrayList<Object[]>();
        for (int i = 0; i < tradeData.size(); i++) {
            JSONObject trade = tradeData.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            JSONObject baseTrade = instrumentId == null ? null : baseTradeIndex.get(instrumentId);
            batchArgs.add(new Object[]{
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
                    firstNonBlank(trimToNull(trade.getString("PRODUCT_CODE")),
                            baseTrade == null ? null : trimToNull(baseTrade.getString("PRODUCT_CODE"))),
                    toBigDecimal(trade.get("BASE_VALUATION_CNY")),
                    toBigDecimal(trade.get("IR_VALUATION")),
                    toBigDecimal(trade.get("IR_PNL")),
                    toBigDecimal(trade.get("FX_VALUATION")),
                    toBigDecimal(trade.get("FX_PNL")),
                    toBigDecimal(trade.get("EQ_VALUATION")),
                    toBigDecimal(trade.get("EQ_PNL")),
                    toBigDecimal(trade.get("COMM_VALUATION")),
                    toBigDecimal(trade.get("COMM_PNL")),
                    toBigDecimal(trade.get("ALL_VALUATION")),
                    toBigDecimal(trade.get("ALL_PNL")),
                    toJsonString(trade),
                    context.createdAt,
                    context.updatedAt
            });
            if (batchArgs.size() >= DEFAULT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batchArgs);
                batchArgs.clear();
            }
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batchArgs);
        }
    }


    private void insertFrtbSensitivityDetails(PersistContext context, JSONArray trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL ("
                + "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                + "INSTRUMENT_ID, PRODUCT_CODE, RISK_FACTOR_ID, RISK_FACTOR_VERTEX_1, RISK_FACTOR_VERTEX_2, "
                + "RISK_FACTOR_CLASS, RISK_FACTOR_BUCKET, RISK_FACTOR_TYPE, SENSITIVITY_TYPE, "
                + "SENSITIVITY_VAL_INST_CURR, INSTRUMENT_CURRENCY, SENSITIVITY_VAL_INST_CURR_CNY, DETAIL_JSON, CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
                jdbcTemplate.update(
                        sql,
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
                        toBigDecimal(sensitivity.get("SENSITIVITY_VAL_INST_CURR")),
                        trimToNull(sensitivity.getString("INSTRUMENT_CURRENCY")),
                        toBigDecimal(sensitivity.get("SENSITIVITY_VAL_INST_CURR_CNY")),
                        toJsonString(sensitivity),
                        context.createdAt,
                        context.updatedAt
                );
            }
        }
    }

    private void insertDrcDetails(PersistContext context, JSONArray trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO TB_OUT_TRADE_DRC_DETAIL ("
                + "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                + "INSTRUMENT_ID, PRODUCT_CODE, PORTFOLIO_CODE, SECURITY_ID, SECURITY_TYPE, LEGAL_ENTITY, "
                + "DRC_BUCKET, JTD_TYPE, SENIORITY, TERM_TO_MATURITY, MODIFIED_REMAIN_TERM, "
                + "RISK_WEIGHT, JTD, JTD_CNY, INSTRUMENT_VALUE, FRTB_LGD, NOTIONAL, DETAIL_JSON, CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            JSONObject drc = trade.getJSONObject("DRC");
            if (drc == null || drc.isEmpty()) {
                continue;
            }
            jdbcTemplate.update(
                    sql,
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    context.seqNo,
                    normalizeDataDate(context.dataDate),
                    context.opCode,
                    trimToNull(trade.getString("INSTRUMENT_ID")),
                    trimToNull(trade.getString("PRODUCT_CODE")),
                    trimToNull(drc.getString("PORTFOLIO_CODE")),
                    trimToNull(drc.getString("SECURITY_ID")),
                    trimToNull(drc.getString("SECURITY_TYPE")),
                    trimToNull(drc.getString("LEGAL_ENTITY")),
                    trimToNull(drc.getString("DRC_BUCKET")),
                    trimToNull(drc.getString("JTD_TYPE")),
                    toInteger(drc.get("SENIORITY")),
                    toBigDecimal(drc.get("TERM_TO_MATURITY")),
                    toBigDecimal(drc.get("MODIFIED_REMAIN_TERM")),
                    toBigDecimal(drc.get("RISK_WEIGHT")),
                    toBigDecimal(drc.get("JTD")),
                    toBigDecimal(drc.get("JTD_CNY")),
                    toBigDecimal(drc.get("INSTRUMENT_VALUE")),
                    toBigDecimal(drc.get("FRTB_LGD")),
                    toBigDecimal(drc.get("NOTIONAL")),
                    toJsonString(drc),
                    context.createdAt,
                    context.updatedAt
            );
        }
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
        String sql = "INSERT INTO TB_OUT_MARKET_DATA_DETAIL ("
                + "BATCH_ID, DATA_DATE, OP_CODE, CURVE_TYPE, CURVE_ID, CURVE_DATA_JSON, "
                + "CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        List<Object[]> batchArgs = new ArrayList<Object[]>();
        for (JSONObject curve : merged.values()) {
            if (curve == null) {
                continue;
            }
            batchArgs.add(new Object[]{
                    context.batchId,
                    normalizeDataDate(context.dataDate),
                    context.opCode,
                    resolveCurveType(curve),
                    resolveCurveId(curve),
                    toJsonString(curve),
                    context.createdAt,
                    context.updatedAt
            });
            if (batchArgs.size() >= DEFAULT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batchArgs);
                batchArgs.clear();
            }
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batchArgs);
        }
    }

    /**
     * 将市场数据按优先级合并到索引中。
     *
     * @param merged            合并结果（保持插入顺序）
     * @param marketData        待合并的曲线数组
     * @param overrideOnConflict 是否允许覆盖同键
     * @param sourceTag         来源标记（仅用于缺少业务键时生成稳定兜底键）
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
     * 构建曲线合并键：
     * 优先使用 CURVE_TYPE + CURVE_ID（或 FIXING_ID）；
     * 若业务键缺失，则退化为来源+序号，避免不同来源无键数据互相覆盖。
     */
    private String buildCurveMergeKey(JSONObject curve, String sourceTag, int index) {
        if (curve == null) {
            return sourceTag + "#" + index;
        }
        String curveType = resolveCurveType(curve);
        String curveId = resolveCurveId(curve);
        if (curveType == null && curveId == null) {
            return sourceTag + "#" + index;
        }
        return firstNonBlank(curveType, "") + "|" + firstNonBlank(curveId, "");
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

    private Set<String> collectInstrumentIds(JSONArray baseTrades, JSONArray scenarioResults) {
        LinkedHashSet<String> instrumentIds = new LinkedHashSet<String>();
        appendInstrumentIds(instrumentIds, baseTrades);
        if (scenarioResults != null) {
            for (int i = 0; i < scenarioResults.size(); i++) {
                JSONObject scenario = scenarioResults.getJSONObject(i);
                if (scenario == null) {
                    continue;
                }
                appendInstrumentIds(instrumentIds, scenario.getJSONArray("trade_data"));
            }
        }
        return instrumentIds;
    }

    private void appendInstrumentIds(Set<String> instrumentIds, JSONArray trades) {
        if (trades == null) {
            return;
        }
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            if (instrumentId != null) {
                instrumentIds.add(instrumentId);
            }
        }
    }

    private static boolean isDecompScenarioResult(JSONObject scenario) {
        if (scenario == null) {
            return false;
        }
        JSONArray tradeData = scenario.getJSONArray("trade_data");
        if (tradeData == null || tradeData.isEmpty()) {
            return false;
        }
        JSONObject firstTrade = tradeData.getJSONObject(0);
        if (firstTrade == null) {
            return false;
        }
        return firstTrade.containsKey("IR_VALUATION")
                || firstTrade.containsKey("FX_VALUATION")
                || firstTrade.containsKey("EQ_VALUATION")
                || firstTrade.containsKey("COMM_VALUATION")
                || firstTrade.containsKey("ALL_VALUATION");
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

    private static String resolveErrorText(JSONObject trade) {
        String explicit = trimToNull(trade.getString("ERROR"));
        if (explicit != null) {
            return explicit;
        }
        JSONArray errors = trade.getJSONArray("ERRORS");
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        List<String> messages = new ArrayList<String>();
        for (int i = 0; i < errors.size(); i++) {
            Object item = errors.get(i);
            String text = item == null ? null : trimToNull(String.valueOf(item));
            if (text != null) {
                messages.add(text);
            }
        }
        if (messages.isEmpty()) {
            return null;
        }
        return String.join(" | ", messages);
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
        return JSON.toJSONString(value, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static String firstNonBlank(String first, String second) {
        String left = trimToNull(first);
        return left != null ? left : trimToNull(second);
    }

    /**
     * 维度优先级解析：dimension 映射表 > trade JSON 主字段 > trade JSON 备选字段。
     */
    private static String resolveDimensionField(JSONObject tradeDimension, String instrumentId,
                                                String dimKey, JSONObject trade,
                                                String tradeKey, String tradeFallbackKey) {
        if (tradeDimension != null && instrumentId != null) {
            JSONObject dim = tradeDimension.getJSONObject(instrumentId);
            if (dim != null) {
                String value = trimToNull(dim.getString(dimKey));
                if (value != null) {
                    return value;
                }
            }
        }
        return firstNonBlank(
                trade == null ? null : trade.getString(tradeKey),
                trade == null ? null : trade.getString(tradeFallbackKey)
        );
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
        private long createdAt;
        private long updatedAt;
        private JSONObject tradeDimension;
    }
}
