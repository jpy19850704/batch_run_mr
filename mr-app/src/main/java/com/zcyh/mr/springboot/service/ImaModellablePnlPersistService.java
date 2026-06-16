package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * IMA 可建模情景 PnL 结果落库服务。
 * 将 SubsetPnlRecord 列表批量写入 TB_OUT_IMA_MODELLABLE_SCENARIO_PNL（Doris）。
 */
@Service
public class ImaModellablePnlPersistService {

    private static final Logger log = LoggerFactory.getLogger(ImaModellablePnlPersistService.class);
    private static final int DEFAULT_BATCH_SIZE = 10000;
    private static final String TARGET_TABLE = "TB_OUT_IMA_MODELLABLE_SCENARIO_PNL";
    private static final String STREAM_LOAD_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,SEQ_NO,DATA_DATE,OP_CODE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,"
                    + "INSTRUMENT_ID,PRODUCT_CODE,LH_DAYS,BASE_VALUATION_CNY,IR_VALUATION,IR_PNL,CS_VALUATION,CS_PNL,FX_VALUATION,FX_PNL,"
                    + "EQ_VALUATION,EQ_PNL,COMM_VALUATION,COMM_PNL,ALL_VALUATION,ALL_PNL,CREATED_AT,UPDATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public ImaModellablePnlPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    /**
     * 批量写入可建模情景 PnL 结果。
     *
     * @param records  SubsetScenarioRunner 输出列表
     * @param opCode   操作码（来自请求上下文）
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(List<SubsetPnlRecord> records, String opCode) {
        if (records == null || records.isEmpty()) {
            log.warn("IMA 可建模 PnL 结果为空，跳过落库");
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "ima_modellable_" + records.get(0).getBatchId(),
                DEFAULT_BATCH_SIZE);

        for (SubsetPnlRecord r : records) {
            String createdAt = r.getCreatedAt() > 0 ? ResultPersistTime.formatEpochMillis(r.getCreatedAt()) : now;
            buffer.appendRow(
                    r.getRequestId(), r.getJobId(), r.getBatchId(),
                    r.getSeqNo(), r.getDataDate(), opCode,
                    r.getScenarioId(), r.getSubscenarioId(), r.getScenarioName(),
                    r.getScenarioType(),
                    r.getInstrumentId(), r.getProductCode(), r.getLhDays(),
                    DorisCsvStreamLoadBuffer.decimalText(r.getBaseValuationCny()),
                    DorisCsvStreamLoadBuffer.decimalText(r.getIrValuation()), DorisCsvStreamLoadBuffer.decimalText(r.getIrPnl()),
                    DorisCsvStreamLoadBuffer.decimalText(r.getCsValuation()), DorisCsvStreamLoadBuffer.decimalText(r.getCsPnl()),
                    DorisCsvStreamLoadBuffer.decimalText(r.getFxValuation()), DorisCsvStreamLoadBuffer.decimalText(r.getFxPnl()),
                    DorisCsvStreamLoadBuffer.decimalText(r.getEqValuation()), DorisCsvStreamLoadBuffer.decimalText(r.getEqPnl()),
                    DorisCsvStreamLoadBuffer.decimalText(r.getCommValuation()), DorisCsvStreamLoadBuffer.decimalText(r.getCommPnl()),
                    DorisCsvStreamLoadBuffer.decimalText(r.getAllValuation()), DorisCsvStreamLoadBuffer.decimalText(r.getAllPnl()),
                    createdAt, now);
        }
        buffer.flush();

        log.info("IMA 可建模 PnL 落库完成: batchId={}, rows={}",
                records.isEmpty() ? null : records.get(0).getBatchId(), records.size());
    }
}
