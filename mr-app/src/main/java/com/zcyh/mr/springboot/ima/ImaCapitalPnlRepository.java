package com.zcyh.mr.springboot.ima;

import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * IMA 资本计算 PnL 读取仓储。
 */
@Repository
public class ImaCapitalPnlRepository {
    private static final String QUERY_MODELLABLE =
            "SELECT REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, "
            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, "
            + "INSTRUMENT_ID, PRODUCT_CODE, LH_DAYS, "
            + "BASE_VALUATION_CNY, IR_VALUATION, IR_PNL, CS_VALUATION, CS_PNL, FX_VALUATION, FX_PNL, "
            + "EQ_VALUATION, EQ_PNL, COMM_VALUATION, COMM_PNL, "
            + "ALL_VALUATION, ALL_PNL, CREATED_AT "
            + "FROM TB_OUT_IMA_MODELLABLE_SCENARIO_PNL "
            + "WHERE BATCH_ID = ? AND DATA_DATE = ?";

    private static final String QUERY_NMRF =
            "SELECT REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, "
            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, "
            + "INSTRUMENT_ID, PRODUCT_CODE, RISK_FACTOR_ID, NMRF_TYPE, "
            + "BASE_VALUATION_CNY, STRESS_VALUATION_CNY, PNL, CREATED_AT "
            + "FROM TB_OUT_IMA_NMRF_SCENARIO_PNL "
            + "WHERE BATCH_ID = ? AND DATA_DATE = ?";

    private final JdbcTemplate resultDbJdbcTemplate;

    public ImaCapitalPnlRepository(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate resultDbJdbcTemplate) {
        this.resultDbJdbcTemplate = resultDbJdbcTemplate;
    }

    public List<SubsetPnlRecord> queryModellablePnl(String batchId, String dataDate) {
        return resultDbJdbcTemplate.query(QUERY_MODELLABLE, (rs, i) -> {
            SubsetPnlRecord record = new SubsetPnlRecord();
            record.setRequestId(rs.getString("REQUEST_ID"));
            record.setJobId(rs.getString("JOB_ID"));
            record.setBatchId(rs.getString("BATCH_ID"));
            record.setSeqNo(rs.getLong("SEQ_NO"));
            record.setDataDate(rs.getString("DATA_DATE"));
            record.setScenarioId(rs.getString("SCENARIO_ID"));
            record.setSubscenarioId(rs.getString("SUBSCENARIO_ID"));
            record.setScenarioName(rs.getString("SCENARIO_NAME"));
            record.setScenarioType(rs.getString("SCENARIO_TYPE"));
            record.setInstrumentId(rs.getString("INSTRUMENT_ID"));
            record.setProductCode(rs.getString("PRODUCT_CODE"));
            record.setLhDays(rs.getInt("LH_DAYS"));
            record.setBaseValuationCny(rs.getBigDecimal("BASE_VALUATION_CNY"));
            record.setIrValuation(rs.getBigDecimal("IR_VALUATION"));
            record.setIrPnl(rs.getBigDecimal("IR_PNL"));
            record.setCsValuation(rs.getBigDecimal("CS_VALUATION"));
            record.setCsPnl(rs.getBigDecimal("CS_PNL"));
            record.setFxValuation(rs.getBigDecimal("FX_VALUATION"));
            record.setFxPnl(rs.getBigDecimal("FX_PNL"));
            record.setEqValuation(rs.getBigDecimal("EQ_VALUATION"));
            record.setEqPnl(rs.getBigDecimal("EQ_PNL"));
            record.setCommValuation(rs.getBigDecimal("COMM_VALUATION"));
            record.setCommPnl(rs.getBigDecimal("COMM_PNL"));
            record.setAllValuation(rs.getBigDecimal("ALL_VALUATION"));
            record.setAllPnl(rs.getBigDecimal("ALL_PNL"));
            return record;
        }, batchId, dataDate);
    }

    public List<NmrfPnlRecord> queryNmrfPnl(String batchId, String dataDate) {
        return resultDbJdbcTemplate.query(QUERY_NMRF, (rs, i) -> {
            NmrfPnlRecord record = new NmrfPnlRecord();
            record.setRequestId(rs.getString("REQUEST_ID"));
            record.setJobId(rs.getString("JOB_ID"));
            record.setBatchId(rs.getString("BATCH_ID"));
            record.setSeqNo(rs.getLong("SEQ_NO"));
            record.setDataDate(rs.getString("DATA_DATE"));
            record.setScenarioId(rs.getString("SCENARIO_ID"));
            record.setSubscenarioId(rs.getString("SUBSCENARIO_ID"));
            record.setScenarioName(rs.getString("SCENARIO_NAME"));
            record.setInstrumentId(rs.getString("INSTRUMENT_ID"));
            record.setProductCode(rs.getString("PRODUCT_CODE"));
            record.setRiskFactorId(rs.getString("RISK_FACTOR_ID"));
            record.setNmrfType(rs.getString("NMRF_TYPE"));
            record.setBaseValuationCny(rs.getBigDecimal("BASE_VALUATION_CNY"));
            record.setStressValuationCny(rs.getBigDecimal("STRESS_VALUATION_CNY"));
            record.setPnl(rs.getBigDecimal("PNL"));
            return record;
        }, batchId, dataDate);
    }
}
