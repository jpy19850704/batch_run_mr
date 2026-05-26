package com.zcyh.mr.frtbima.validation.pla;

import com.zcyh.mr.frtbima.validation.common.ValidationConstants;
import com.zcyh.mr.frtbima.validation.model.DailyPnl;
import com.zcyh.mr.frtbima.validation.model.PlaTestResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * PLA（P&L Attribution）测试评估器。
 * 整合 Spearman 相关系数和 KS 检验，判断交易台的 PLA 是否通过。
 * 规则依据：MAR32.20-32.42
 *
 * 判定规则（MAR32.38-32.40）：
 * - 两项均绿区 → 通过（GREEN）
 * - 任一黄区、无红区 → 黄区（AMBER）
 * - 任一红区 → 红区（RED）
 */
public class PlaTestEvaluator {

    private final SpearmanCorrelation spearman = new SpearmanCorrelation();
    private final KolmogorovSmirnovTest ksTest = new KolmogorovSmirnovTest();

    /**
     * 执行 PLA 测试。
     *
     * @param pnlSeries 每日PnL序列（需包含 hypotheticalPnl 和 riskTheoreticalPnl）
     * @return PLA 测试结果
     */
    public PlaTestResult evaluate(List<DailyPnl> pnlSeries) {
        if (pnlSeries == null || pnlSeries.isEmpty()) {
            throw new IllegalArgumentException("pnlSeries 不能为空");
        }

        List<BigDecimal> hypo = new ArrayList<>();
        List<BigDecimal> riskTheory = new ArrayList<>();
        for (DailyPnl daily : pnlSeries) {
            if (daily.getHypotheticalPnl() != null && daily.getRiskTheoreticalPnl() != null) {
                hypo.add(daily.getHypotheticalPnl());
                riskTheory.add(daily.getRiskTheoreticalPnl());
            }
        }

        BigDecimal spearmanVal = spearman.compute(hypo, riskTheory);
        BigDecimal ksVal = ksTest.compute(hypo, riskTheory);

        String spearmanZone = evaluateSpearmanZone(spearmanVal);
        String ksZone = evaluateKsZone(ksVal);

        boolean passed = "GREEN".equals(spearmanZone) && "GREEN".equals(ksZone);

        return new PlaTestResult(spearmanVal, ksVal, passed, spearmanZone, ksZone);
    }

    private String evaluateSpearmanZone(BigDecimal spearmanVal) {
        if (spearmanVal.compareTo(ValidationConstants.PLA_SPEARMAN_GREEN_THRESHOLD) > 0) {
            return "GREEN";
        }
        if (spearmanVal.compareTo(ValidationConstants.PLA_SPEARMAN_AMBER_THRESHOLD) >= 0) {
            return "AMBER";
        }
        return "RED";
    }

    private String evaluateKsZone(BigDecimal ksVal) {
        if (ksVal.compareTo(ValidationConstants.PLA_KS_GREEN_THRESHOLD) < 0) {
            return "GREEN";
        }
        if (ksVal.compareTo(ValidationConstants.PLA_KS_RED_THRESHOLD) <= 0) {
            return "AMBER";
        }
        return "RED";
    }
}
