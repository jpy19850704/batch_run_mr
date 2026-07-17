package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.zcyh.mr.frtbima.model.ImaEsResultDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * IMA ES 中间结果落库服务。
 */
@Service
public class ImaEsResultDetailPersistService {

    private static final Logger log = LoggerFactory.getLogger(ImaEsResultDetailPersistService.class);
    private static final String TARGET_TABLE = "TB_OUT_IMA_ES_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,GROUP_ORDER,SCENARIO_TYPE,"
                    + "CONFIDENCE_LEVEL,LIQUIDITY_HORIZON_DAYS,ALL_ES,IR_ES,CS_ES,FX_ES,EQ_ES,COMM_ES,CREATED_AT,UPDATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public ImaEsResultDetailPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void deleteByBatchAndDataDate(String batchId, String dataDate) {
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("IMA ES 中间结果清理缺少 BATCH_ID");
        }
        if (dataDate == null || dataDate.trim().isEmpty()) {
            throw new IllegalArgumentException("IMA ES 中间结果清理缺少 DATA_DATE");
        }
        int deleted = jdbcTemplate.update("DELETE FROM " + TARGET_TABLE + " WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, dataDate);
        log.info("清理 IMA ES 中间历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
    }

    public void deleteByBatchDataDateAndRuleIds(String batchId, String dataDate, List<String> ruleIds) {
        int deleted = RuleScopedDeleteSupport.deleteByRuleIds(
                jdbcTemplate, TARGET_TABLE, batchId, dataDate, ruleIds);
        log.info("按规则清理 IMA ES 中间历史结果: batchId={}, dataDate={}, ruleIds={}, deleted={}",
                batchId, dataDate, ruleIds, deleted);
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(List<ImaEsResultDetail> details) {
        if (details == null || details.isEmpty()) {
            log.warn("IMA ES 中间结果为空，跳过落库");
            return;
        }
        String batchId = requireBatchId(details.get(0));
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "ima_es_result_" + batchId,
                1000);
        for (ImaEsResultDetail detail : details) {
            String detailBatchId = requireBatchId(detail);
            if (!batchId.equals(detailBatchId)) {
                throw new IllegalArgumentException("IMA ES 中间结果批次不一致: " + detailBatchId);
            }
            buffer.appendRow(
                    detailBatchId,
                    detail.getDataDate(),
                    detail.getRuleId(),
                    detail.getGroupType(),
                    detail.getGroupValue(),
                    detail.getGroupOrder(),
                    detail.getScenarioType(),
                    DorisCsvStreamLoadBuffer.decimalText(detail.getConfidenceLevel()),
                    detail.getLiquidityHorizonDays(),
                    DorisCsvStreamLoadBuffer.decimalText(detail.getAllEs()),
                    DorisCsvStreamLoadBuffer.decimalText(detail.getIrEs()),
                    DorisCsvStreamLoadBuffer.decimalText(detail.getCsEs()),
                    DorisCsvStreamLoadBuffer.decimalText(detail.getFxEs()),
                    DorisCsvStreamLoadBuffer.decimalText(detail.getEqEs()),
                    DorisCsvStreamLoadBuffer.decimalText(detail.getCommEs()),
                    now,
                    now);
        }
        buffer.flush();

        log.info("IMA ES 中间结果落库完成: batchId={}, rows={}", batchId, details.size());
    }

    public void persist(ImaEsResultDetail detail) {
        persist(detail == null ? Collections.<ImaEsResultDetail>emptyList() : Collections.singletonList(detail));
    }

    private static String requireBatchId(ImaEsResultDetail detail) {
        if (detail == null || detail.getBatchId() == null || detail.getBatchId().trim().isEmpty()) {
            throw new IllegalArgumentException("IMA ES 中间结果缺少 BATCH_ID");
        }
        return detail.getBatchId();
    }
}
