package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.outer.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.EngineRunResult;
import org.springframework.dao.DataAccessException;
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
    private static final String[] REQUIRED_TABLES = new String[]{
            "TB_OUT_TRADE_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
            "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
            "TB_OUT_TRADE_DRC_DETAIL"
    };
    private static final String DECOMP_TABLE = "TB_OUT_TRADE_SCENARIO_DECOMP_DETAIL";

    private final JdbcTemplate jdbcTemplate;

    public PricingResultPersistService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按任务覆盖写入结果明细。
     * 【Doris 备注】Doris 2.0+ 支持单表事务（enable_unique_key_merge_on_write=true），
     * 但不支持跨表事务。切换后建议移除 @Transactional 注解，改为单表独立写入。
     * 写入失败不影响任务状态，仅记录日志。
     */
    @Transactional
    public void persistJobResult(String requestId, String jobId, String payloadJson, EngineRunResult runResult) {
        if (runResult == null || !runResult.isSuccess()) {
            return;
        }
        if (!MrCalcEngineAdapter.CODE.equalsIgnoreCase(trimToNull(runResult.getEngineCode()))) {
            return;
        }
        if (!allRequiredTablesExist()) {
            return;
        }
        boolean decompTableExists = tableExists(DECOMP_TABLE);

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

        insertTradeResults(context, baseTrades);
        insertDrcDetails(context, baseTrades);
        insertFrtbSensitivityDetails(context, baseTrades);
        insertScenarioResults(context, scenarioResults, baseTradeIndex, decompTableExists);
    }

    /**
     * 检查所有输出表是否存在。
     * 当前实现使用 H2 的 INFORMATION_SCHEMA，切换 Doris 时需替换为下方注释的实现。
     */
    private boolean allRequiredTablesExist() {
        try {
            for (String tableName : REQUIRED_TABLES) {
                if (!tableExists(tableName)) {
                    return false;
                }
            }
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)=?",
                    Integer.class,
                    tableName
            );
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            return false;
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

    private void insertTradeResults(PersistContext context, JSONArray trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO TB_OUT_TRADE_RESULT_DETAIL ("
                + "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
                + "INSTRUMENT_ID, PRODUCT_CODE, PORTFOLIO, DESK, TRADER, "
                + "POSITION, VALUATION_UNIT, VALUATION, VALUATION_CCY, VALUATION_CNY, "
                + "PV01, DELTA, GAMMA, VEGA, THETA, RHO, STATUS, ERROR, DETAIL, ERRORS_JSON, CASHFLOW_JSON, RESULT_JSON, "
                + "CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            jdbcTemplate.update(
                    sql,
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    context.seqNo,
                    normalizeDataDate(firstNonBlank(trade.getString("DATA_DATE"), context.dataDate)),
                    context.opCode,
                    trimToNull(trade.getString("INSTRUMENT_ID")),
                    trimToNull(trade.getString("PRODUCT_CODE")),
                    resolveDimensionField(context.tradeDimension, trimToNull(trade.getString("INSTRUMENT_ID")), "PORTFOLIO", trade, "PORTFOLIO", "PORTFOLIO_CODE"),
                    resolveDimensionField(context.tradeDimension, trimToNull(trade.getString("INSTRUMENT_ID")), "DESK", trade, "DESK", "DESK_CODE"),
                    resolveDimensionField(context.tradeDimension, trimToNull(trade.getString("INSTRUMENT_ID")), "TRADER", trade, "TRADER", "TRADER_CODE"),
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
                + "RISK_WEIGHT, JTD, INSTRUMENT_VALUE, FRTB_LGD, NOTIONAL, DETAIL_JSON, CREATED_AT, UPDATED_AT) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
