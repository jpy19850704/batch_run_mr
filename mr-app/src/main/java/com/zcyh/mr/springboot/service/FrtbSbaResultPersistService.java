package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * FRTB SBA Class 级资本汇总结果落库服务。
 * 将 FRTBClassResult 按 NONADDITIVE / ADDITIVE 两种 CAPITAL_TYPE 写入 H2 表。
 */
@Service
public class FrtbSbaResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(FrtbSbaResultPersistService.class);

    private static final String INSERT_SQL =
            "INSERT INTO TB_OUT_FRTB_SBA_CLASS_RESULT ("
            + "BATCH_ID, DATA_DATE, RULE_ID, TREE_ID, GROUP_TYPE, GROUP_VALUE, "
            + "RISK_FACTOR_CLASS, MAX_SIGN, CAPITAL_TYPE, RISK_CHARGE, "
            + "NORMAL_DELTA, HIGH_DELTA, LOW_DELTA, "
            + "NORMAL_VEGA, HIGH_VEGA, LOW_VEGA, "
            + "NORMAL_CURVATURE, HIGH_CURVATURE, LOW_CURVATURE, "
            + "CREATED_AT, UPDATED_AT"
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbcTemplate;

    public FrtbSbaResultPersistService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
    public void persist(List<FRTBClassResult> classResults, String batchId, String dataDate, String ruleId) {
        if (classResults == null || classResults.isEmpty()) {
            log.warn("FRTB SBA Class 结果为空，跳过落库: batchId={}", batchId);
            return;
        }

        long now = System.currentTimeMillis();

        for (FRTBClassResult cr : classResults) {
            // NONADDITIVE：SBA 独立计算资本（含相关性矩阵）
            jdbcTemplate.update(INSERT_SQL,
                    batchId, dataDate, ruleId,
                    cr.getTreeId(), cr.getGroupType(), cr.getGroupValue(),
                    cr.getRiskFactorClass(), cr.getMaxSign(),
                    "NONADDITIVE", decVal(cr.getRiskCharge()),
                    decVal(cr.getNormalDelta()), decVal(cr.getHighDelta()), decVal(cr.getLowDelta()),
                    decVal(cr.getNormalVega()), decVal(cr.getHighVega()), decVal(cr.getLowVega()),
                    decVal(cr.getNormalCurvature()), decVal(cr.getHighCurvature()), decVal(cr.getLowCurvature()),
                    now, now);

            // ADDITIVE：Euler 分摊资本（按 sensType × scenario）
            BigDecimal allocCharge = computeAllocRiskCharge(cr);
            jdbcTemplate.update(INSERT_SQL,
                    batchId, dataDate, ruleId,
                    cr.getTreeId(), cr.getGroupType(), cr.getGroupValue(),
                    cr.getRiskFactorClass(), cr.getMaxSign(),
                    "ADDITIVE", decVal(allocCharge),
                    decVal(cr.getAllocDeltaNormal()), decVal(cr.getAllocDeltaHigh()), decVal(cr.getAllocDeltaLow()),
                    decVal(cr.getAllocVegaNormal()), decVal(cr.getAllocVegaHigh()), decVal(cr.getAllocVegaLow()),
                    decVal(cr.getAllocCurvatureNormal()), decVal(cr.getAllocCurvatureHigh()), decVal(cr.getAllocCurvatureLow()),
                    now, now);
        }

        log.info("FRTB SBA Class 结果落库完成: batchId={}, classCount={}, rows={}",
                batchId, classResults.size(), classResults.size() * 2);
    }

    /**
     * 删除指定批次的历史结果（重跑前清理）。
     */
    public void deleteByBatch(String batchId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM TB_OUT_FRTB_SBA_CLASS_RESULT WHERE BATCH_ID = ?", batchId);
        if (deleted > 0) {
            log.info("清理 FRTB SBA 历史结果: batchId={}, deleted={}", batchId, deleted);
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
