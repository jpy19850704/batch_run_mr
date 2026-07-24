package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.zcyh.mr.frtbima.model.ImaNmrfResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * IMA 不可建模中间结果落库服务。
 */
@Service
public class ImaNmrfResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(ImaNmrfResultPersistService.class);
    private static final String TARGET_TABLE = "TB_OUT_IMA_NMRF_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,GROUP_ORDER,SES,"
                    + "IDIO_CREDIT_SUM_SQ,IDIO_EQUITY_SUM_SQ,OTHER_CORR_TERM,OTHER_IDIO_TERM,NMRF_COUNT,CREATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public ImaNmrfResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void deleteByBatchAndDataDate(String batchId, String dataDate) {
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("IMA NMRF 中间结果清理缺少 BATCH_ID");
        }
        if (dataDate == null || dataDate.trim().isEmpty()) {
            throw new IllegalArgumentException("IMA NMRF 中间结果清理缺少 DATA_DATE");
        }
        int deleted = jdbcTemplate.update("DELETE FROM " + TARGET_TABLE + " WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate));
        log.info("清理 IMA NMRF 中间历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
    }

    public void deleteByBatchDataDateAndRuleIds(String batchId, String dataDate, List<String> ruleIds) {
        int deleted = RuleScopedDeleteSupport.deleteByRuleIds(
                jdbcTemplate, TARGET_TABLE, batchId, dataDate, ruleIds);
        log.info("按规则清理 IMA NMRF 中间历史结果: batchId={}, dataDate={}, ruleIds={}, deleted={}",
                batchId, dataDate, ruleIds, deleted);
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(List<ImaNmrfResult> results) {
        if (results == null || results.isEmpty()) {
            log.warn("IMA NMRF 中间结果为空，跳过落库");
            return;
        }
        String batchId = requireBatchId(results.get(0));
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "ima_nmrf_result_" + batchId,
                1000);
        for (ImaNmrfResult result : results) {
            String resultBatchId = requireBatchId(result);
            if (!batchId.equals(resultBatchId)) {
                throw new IllegalArgumentException("IMA NMRF 中间结果批次不一致: " + resultBatchId);
            }
            buffer.appendRow(
                    resultBatchId,
                    result.getDataDate(),
                    result.getRuleId(),
                    result.getGroupType(),
                    result.getGroupValue(),
                    result.getGroupOrder(),
                    DorisCsvStreamLoadBuffer.decimalText(result.getSes()),
                    DorisCsvStreamLoadBuffer.decimalText(result.getIdioCreditSumSq()),
                    DorisCsvStreamLoadBuffer.decimalText(result.getIdioEquitySumSq()),
                    DorisCsvStreamLoadBuffer.decimalText(result.getOtherCorrTerm()),
                    DorisCsvStreamLoadBuffer.decimalText(result.getOtherIdioTerm()),
                    result.getNmrfCount(),
                    now);
        }
        buffer.flush();

        log.info("IMA NMRF 中间结果落库完成: batchId={}, rows={}", batchId, results.size());
    }

    public void persist(ImaNmrfResult result) {
        persist(result == null ? Collections.<ImaNmrfResult>emptyList() : Collections.singletonList(result));
    }

    private static String requireBatchId(ImaNmrfResult result) {
        if (result == null || result.getBatchId() == null || result.getBatchId().trim().isEmpty()) {
            throw new IllegalArgumentException("IMA NMRF 中间结果缺少 BATCH_ID");
        }
        return result.getBatchId();
    }
}
