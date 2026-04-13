package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * IMA NMRF 情景 PnL 结果落库服务。
 * 将 NmrfPnlRecord 列表批量写入 TB_OUT_IMA_NMRF_SCENARIO_PNL（Doris）。
 */
@Service
public class ImaNmrfPnlPersistService {

    private static final Logger log = LoggerFactory.getLogger(ImaNmrfPnlPersistService.class);

    private static final String INSERT_SQL =
            "INSERT INTO TB_OUT_IMA_NMRF_SCENARIO_PNL ("
            + "REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, OP_CODE, "
            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, "
            + "INSTRUMENT_ID, PRODUCT_CODE, RISK_FACTOR_ID, NMRF_TYPE, "
            + "BASE_VALUATION_CNY, STRESS_VALUATION_CNY, PNL, "
            + "CREATED_AT, UPDATED_AT"
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbcTemplate;

    public ImaNmrfPnlPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量写入 NMRF 情景 PnL 结果。
     *
     * @param records  NmrfScenarioRunner 输出列表
     * @param opCode   操作码
     */
    public void persist(List<NmrfPnlRecord> records, String opCode) {
        if (records == null || records.isEmpty()) {
            log.warn("IMA NMRF PnL 结果为空，跳过落库");
            return;
        }
        long now = System.currentTimeMillis();

        for (NmrfPnlRecord r : records) {
            jdbcTemplate.update(INSERT_SQL,
                    r.getRequestId(), r.getJobId(), r.getBatchId(),
                    r.getSeqNo(), r.getDataDate(), opCode,
                    r.getScenarioId(), r.getSubscenarioId(), r.getScenarioName(),
                    r.getInstrumentId(), r.getProductCode(),
                    r.getRiskFactorId(), r.getNmrfType(),
                    r.getBaseValuationCny(), r.getStressValuationCny(), r.getPnl(),
                    r.getCreatedAt() > 0 ? r.getCreatedAt() : now, now);
        }

        log.info("IMA NMRF PnL 落库完成: batchId={}, rows={}",
                records.isEmpty() ? null : records.get(0).getBatchId(), records.size());
    }
}
