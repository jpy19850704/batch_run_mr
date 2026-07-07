package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * FRTB SBA 风险因子单位贡献度落库服务。
 */
@Service
public class FrtbSbaDecompDetailPersistService {
    private static final Logger log = LoggerFactory.getLogger(FrtbSbaDecompDetailPersistService.class);
    private static final int DEFAULT_BATCH_SIZE = 10000;
    private static final String TARGET_TABLE = "TB_OUT_FRTB_SBA_DECOMP_DETAIL";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,"
                    + "RISK_FACTOR_CLASS,RISK_FACTOR_BUCKET,RISK_FACTOR_ID,"
                    + "RISK_FACTOR_VERTEX_1,RISK_FACTOR_VERTEX_2,RISK_FACTOR_TYPE,"
                    + "SENSITIVITY_TYPE,UNIT_CONTRIBUTION,CREATED_AT,UPDATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public FrtbSbaDecompDetailPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                             DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(List<FRTBPosResult> posResults, String batchId, String dataDate, String ruleId) {
        if (posResults == null || posResults.isEmpty()) {
            log.warn("FRTB SBA Decomp 明细为空，跳过落库: batchId={}, ruleId={}", batchId, ruleId);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "frtb_sba_decomp_" + batchId,
                DEFAULT_BATCH_SIZE);

        int rows = 0;
        for (FRTBPosResult pr : posResults) {
            if (pr == null) {
                continue;
            }
            if ("ALL".equalsIgnoreCase(pr.getRiskFactorClass())) {
                continue;
            }
            buffer.appendRow(
                    batchId, dataDate, ruleId,
                    pr.getGroupType(), pr.getGroupValue(),
                    pr.getRiskFactorClass(), pr.getRiskFactorBucket(), pr.getRiskFactorId(),
                    pr.getRiskFactorVertex1(), pr.getRiskFactorVertex2(), pr.getRiskFactorType(),
                    pr.getSensitivityType(),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(pr.getUnitContribution())),
                    now, now);
            rows++;
        }
        buffer.flush();
        log.info("FRTB SBA Decomp 明细落库完成: batchId={}, ruleId={}, rows={}", batchId, ruleId, rows);
    }

    public void deleteByBatchAndDataDate(String batchId, String dataDate) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM TB_OUT_FRTB_SBA_DECOMP_DETAIL WHERE BATCH_ID = ? AND DATA_DATE = ?",
                batchId, dataDate);
        if (deleted > 0) {
            log.info("清理 FRTB SBA Decomp 历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
        }
    }

    private static BigDecimal decVal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
