package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.capital.ImaCapitalCalculator;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import com.zcyh.mr.springboot.service.ImaCapitalResultPersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IMA Phase2 引擎适配器。
 *
 * <p>职责：
 * <ol>
 *   <li>从 Doris 读取 Phase1 输出（modellable PnL + NMRF PnL）。</li>
 *   <li>调用 {@link ImaCapitalCalculator} 计算 IMCC、SES、总资本。</li>
 *   <li>将 {@link ImaCapitalResult} 落库。</li>
 * </ol>
 *
 * <p>输入 JSON 关键字段：
 * <pre>
 * {
 *   "batch_id": "...",
 *   "data_date": "20260409",
 *   "sa_by_desk": {"DESK_A": 1000000, "DESK_B": 2000000},  // 标准法资本，用于 Amber 系数
 *   "amber_desks": ["DESK_C"],                              // Amber 区交易台
 *   "green_desks": ["DESK_A", "DESK_B"]                     // Green 区交易台
 * }
 * </pre>
 */
public class ImaCapitalEngineAdapter implements EngineAdapter {

    public static final String CODE = "ima_capital";

    private static final Logger log = LoggerFactory.getLogger(ImaCapitalEngineAdapter.class);

    /** 查询可建模 PnL */
    private static final String QUERY_MODELLABLE =
            "SELECT REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, "
            + "INSTRUMENT_ID, PRODUCT_CODE, LH_DAYS, "
            + "BASE_VALUATION_CNY, IR_VALUATION, IR_PNL, FX_VALUATION, FX_PNL, "
            + "EQ_VALUATION, EQ_PNL, COMM_VALUATION, COMM_PNL, "
            + "ALL_VALUATION, ALL_PNL, CREATED_AT "
            + "FROM TB_OUT_IMA_MODELLABLE_SCENARIO_PNL "
            + "WHERE BATCH_ID = ?";

    /** 查询 NMRF PnL */
    private static final String QUERY_NMRF =
            "SELECT REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, "
            + "INSTRUMENT_ID, PRODUCT_CODE, RISK_FACTOR_ID, NMRF_TYPE, "
            + "BASE_VALUATION_CNY, STRESS_VALUATION_CNY, PNL, CREATED_AT "
            + "FROM TB_OUT_IMA_NMRF_SCENARIO_PNL "
            + "WHERE BATCH_ID = ?";

    private final JdbcTemplate resultDbJdbcTemplate;
    private final ImaCapitalResultPersistService capitalPersistService;
    private final ImaCapitalCalculator capitalCalculator = new ImaCapitalCalculator();

    public ImaCapitalEngineAdapter(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate resultDbJdbcTemplate,
            ImaCapitalResultPersistService capitalPersistService) {
        this.resultDbJdbcTemplate = resultDbJdbcTemplate;
        this.capitalPersistService = capitalPersistService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "IMA Phase2：从 Doris 读取 PnL → 计算 IMCC/SES/总资本 → 落库";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) throw new IllegalArgumentException("IMA Phase2 input 不能为空");

        String batchId  = required(req, "batch_id");
        String dataDate = required(req, "data_date");

        // 解析 Amber/Green 交易台 + 标准法资本
        Map<String, BigDecimal> saByDesk = parseSaByDesk(req);
        Set<String> amberDesks = parseStringSet(req, "amber_desks");
        Set<String> greenDesks = parseStringSet(req, "green_desks");

        // 从 Doris 读取 Phase1 结果
        List<SubsetPnlRecord> subsetPnls = queryModellablePnl(batchId);
        List<NmrfPnlRecord> nmrfPnls = queryNmrfPnl(batchId);

        log.info("IMA Phase2 开始: batchId={}, modellableRows={}, nmrfRows={}",
                batchId, subsetPnls.size(), nmrfPnls.size());

        // 计算资本
        ImaCapitalResult capitalResult = capitalCalculator.calculate(
                subsetPnls, nmrfPnls, saByDesk, amberDesks, greenDesks, dataDate, batchId);

        // 落库
        capitalPersistService.persist(capitalResult);

        log.info("IMA Phase2 完成: batchId={}, IMCC={}, SES={}, ACR={}",
                batchId,
                capitalResult.getImccResult() != null ? capitalResult.getImccResult().getImcc() : null,
                capitalResult.getSesResult() != null ? capitalResult.getSesResult().getSes() : null,
                capitalResult.getAcrTotal());

        return JSON.toJSONString(capitalResult, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    // ==================== 数据查询 ====================

    private List<SubsetPnlRecord> queryModellablePnl(String batchId) {
        return resultDbJdbcTemplate.query(QUERY_MODELLABLE, (rs, i) -> {
            SubsetPnlRecord r = new SubsetPnlRecord();
            r.setRequestId(rs.getString("REQUEST_ID"));
            r.setJobId(rs.getString("JOB_ID"));
            r.setBatchId(rs.getString("BATCH_ID"));
            r.setSeqNo(rs.getLong("SEQ_NO"));
            r.setDataDate(rs.getString("DATA_DATE"));
            r.setOpCode(rs.getString("OP_CODE"));
            r.setScenarioId(rs.getString("SCENARIO_ID"));
            r.setSubscenarioId(rs.getString("SUBSCENARIO_ID"));
            r.setScenarioName(rs.getString("SCENARIO_NAME"));
            r.setScenarioType(rs.getString("SCENARIO_TYPE"));
            r.setInstrumentId(rs.getString("INSTRUMENT_ID"));
            r.setProductCode(rs.getString("PRODUCT_CODE"));
            r.setLhDays(rs.getInt("LH_DAYS"));
            r.setBaseValuationCny(rs.getBigDecimal("BASE_VALUATION_CNY"));
            r.setIrValuation(rs.getBigDecimal("IR_VALUATION"));
            r.setIrPnl(rs.getBigDecimal("IR_PNL"));
            r.setFxValuation(rs.getBigDecimal("FX_VALUATION"));
            r.setFxPnl(rs.getBigDecimal("FX_PNL"));
            r.setEqValuation(rs.getBigDecimal("EQ_VALUATION"));
            r.setEqPnl(rs.getBigDecimal("EQ_PNL"));
            r.setCommValuation(rs.getBigDecimal("COMM_VALUATION"));
            r.setCommPnl(rs.getBigDecimal("COMM_PNL"));
            r.setAllValuation(rs.getBigDecimal("ALL_VALUATION"));
            r.setAllPnl(rs.getBigDecimal("ALL_PNL"));
            return r;
        }, batchId);
    }

    private List<NmrfPnlRecord> queryNmrfPnl(String batchId) {
        return resultDbJdbcTemplate.query(QUERY_NMRF, (rs, i) -> {
            NmrfPnlRecord r = new NmrfPnlRecord();
            r.setRequestId(rs.getString("REQUEST_ID"));
            r.setJobId(rs.getString("JOB_ID"));
            r.setBatchId(rs.getString("BATCH_ID"));
            r.setSeqNo(rs.getLong("SEQ_NO"));
            r.setDataDate(rs.getString("DATA_DATE"));
            r.setOpCode(rs.getString("OP_CODE"));
            r.setScenarioId(rs.getString("SCENARIO_ID"));
            r.setSubscenarioId(rs.getString("SUBSCENARIO_ID"));
            r.setScenarioName(rs.getString("SCENARIO_NAME"));
            r.setInstrumentId(rs.getString("INSTRUMENT_ID"));
            r.setProductCode(rs.getString("PRODUCT_CODE"));
            r.setRiskFactorId(rs.getString("RISK_FACTOR_ID"));
            r.setNmrfType(rs.getString("NMRF_TYPE"));
            r.setBaseValuationCny(rs.getBigDecimal("BASE_VALUATION_CNY"));
            r.setStressValuationCny(rs.getBigDecimal("STRESS_VALUATION_CNY"));
            r.setPnl(rs.getBigDecimal("PNL"));
            return r;
        }, batchId);
    }

    // ==================== JSON 工具 ====================

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> parseSaByDesk(JSONObject req) {
        JSONObject sa = req.getJSONObject("sa_by_desk");
        if (sa == null) return new HashMap<>();
        Map<String, BigDecimal> map = new HashMap<>();
        for (String k : sa.keySet()) {
            map.put(k, sa.getBigDecimal(k));
        }
        return map;
    }

    private Set<String> parseStringSet(JSONObject req, String key) {
        com.alibaba.fastjson2.JSONArray arr = req.getJSONArray(key);
        if (arr == null) return new HashSet<>();
        Set<String> set = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            set.add(arr.getString(i));
        }
        return set;
    }

    private static String required(JSONObject obj, String key) {
        String v = obj.getString(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException(key + " 必填");
        return v.trim();
    }
}
