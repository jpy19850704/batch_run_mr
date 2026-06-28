package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * FRTB SBA Class 级资本汇总结果落库服务。
 * 将 FRTBClassResult 按 NONADDITIVE / ADDITIVE 两种 CAPITAL_TYPE 写入 engine_result_db 结果表。
 */
@Service
public class FrtbSbaResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(FrtbSbaResultPersistService.class);
    private static final int DEFAULT_BATCH_SIZE = 5000;
    private static final String TARGET_TABLE = "TB_OUT_FRTB_SBA_CLASS_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,"
                    + "RISK_FACTOR_CLASS,MAX_SIGN,CAPITAL_TYPE,RISK_CHARGE,"
                    + "NORMAL_DELTA,HIGH_DELTA,LOW_DELTA,"
                    + "NORMAL_VEGA,HIGH_VEGA,LOW_VEGA,"
                    + "NORMAL_CURVATURE,HIGH_CURVATURE,LOW_CURVATURE,"
                    + "CREATED_AT,UPDATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public FrtbSbaResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    /**
     * 批量写入 FRTB SBA Class 级结果。
     * 每个 FRTBClassResult 写入两行：NONADDITIVE（独立计算资本）和 ADDITIVE（分摊资本）。
     *
     * @param classResults 计算结果列表
     * @param batchId      批次 ID
     * @param dataDate     估值日期
     * @param ruleId       规则 ID
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(List<FRTBClassResult> classResults, String batchId, String dataDate, String ruleId) {
        if (classResults == null || classResults.isEmpty()) {
            log.warn("FRTB SBA Class 结果为空，跳过落库: batchId={}", batchId);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "frtb_sba_" + batchId,
                DEFAULT_BATCH_SIZE);

        for (FRTBClassResult cr : classResults) {
            // NONADDITIVE：SBA 独立计算资本（含相关性矩阵）
            buffer.appendRow(
                    batchId, dataDate, ruleId,
                    cr.getGroupType(), cr.getGroupValue(),
                    cr.getRiskFactorClass(), cr.getMaxSign(),
                    "NONADDITIVE", DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getRiskCharge())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getNormalDelta())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getHighDelta())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getLowDelta())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getNormalVega())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getHighVega())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getLowVega())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getNormalCurvature())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getHighCurvature())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getLowCurvature())),
                    now, now);

            // ADDITIVE：Euler 分摊资本（按 sensType × scenario）
            BigDecimal allocCharge = computeAllocRiskCharge(cr);
            buffer.appendRow(
                    batchId, dataDate, ruleId,
                    cr.getGroupType(), cr.getGroupValue(),
                    cr.getRiskFactorClass(), cr.getMaxSign(),
                    "ADDITIVE", DorisCsvStreamLoadBuffer.decimalText(decVal(allocCharge)),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocDeltaNormal())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocDeltaHigh())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocDeltaLow())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocVegaNormal())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocVegaHigh())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocVegaLow())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocCurvatureNormal())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocCurvatureHigh())),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(cr.getAllocCurvatureLow())),
                    now, now);
        }
        buffer.flush();

        log.info("FRTB SBA Class 结果落库完成: batchId={}, classCount={}, rows={}",
                batchId, classResults.size(), classResults.size() * 2);
    }

    /**
     * 删除指定批次的历史结果（重跑前清理）。
     */
    public void deleteByBatchAndDataDate(String batchId, String dataDate) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM TB_OUT_FRTB_SBA_CLASS_RESULT WHERE BATCH_ID = ? AND DATA_DATE = ?",
                batchId, dataDate);
        if (deleted > 0) {
            log.info("清理 FRTB SBA 历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
        }
    }

    /**
     * 计算 ADDITIVE 行的 RISK_CHARGE：alloc 三场景各 sensType 之和取 max。
     */
    private static BigDecimal computeAllocRiskCharge(FRTBClassResult cr) {
        double normal = safeDouble(cr.getAllocDeltaNormal())
                + safeDouble(cr.getAllocVegaNormal())
                + safeDouble(cr.getAllocCurvatureNormal());
        double high = safeDouble(cr.getAllocDeltaHigh())
                + safeDouble(cr.getAllocVegaHigh())
                + safeDouble(cr.getAllocCurvatureHigh());
        double low = safeDouble(cr.getAllocDeltaLow())
                + safeDouble(cr.getAllocVegaLow())
                + safeDouble(cr.getAllocCurvatureLow());
        double max = Math.max(Math.max(normal, high), low);
        return BigDecimal.valueOf(max);
    }

    private static BigDecimal decVal(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static double safeDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
