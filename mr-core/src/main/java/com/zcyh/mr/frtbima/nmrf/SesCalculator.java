package com.zcyh.mr.frtbima.nmrf;

import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.es.LiquidityAdjustedEs;
import com.zcyh.mr.frtbima.model.NmrfStressResult;
import com.zcyh.mr.frtbima.model.SesResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * SES（Stress Expected Shortfall for NMRF）计算器（MAR33.17）。
 *
 * <p>公式：
 * <pre>
 * SES = sqrt(
 *     Σ_idio_credit(S_b^2)          // 特异信用：独立加总
 *   + Σ_idio_equity(S_b^2)          // 特异权益：独立加总
 *   + (ρ × Σ_other(S_b))^2          // 其他 NMRF：相关项
 *   + (1 - ρ^2) × Σ_other(S_b^2)   // 其他 NMRF：特异项
 * )
 * ρ = 0.6（MAR33.17）
 * </pre>
 *
 * <p>其中 S_b = stressLoss（来自 NmrfStressAggregator）。
 */
public class SesCalculator {

    private static final BigDecimal RHO = ImaConstants.RHO_SES; // 0.6
    private static final BigDecimal RHO_SQ = RHO.multiply(RHO);
    private static final BigDecimal ONE_MINUS_RHO_SQ =
            BigDecimal.ONE.subtract(RHO_SQ).setScale(10, RoundingMode.HALF_UP);

    /**
     * 计算全行 SES。
     *
     * @param stressResults 每个 NMRF 桶的压力损失列表（来自 NmrfStressAggregator）
     * @return SesResult（含各项中间值和最终 SES）
     */
    public SesResult calculate(List<NmrfStressResult> stressResults) {
        SesResult result = new SesResult();

        BigDecimal idioCreditSumSq = BigDecimal.ZERO;
        BigDecimal idioEquitySumSq = BigDecimal.ZERO;
        BigDecimal otherSum = BigDecimal.ZERO;
        BigDecimal otherSumSq = BigDecimal.ZERO;

        for (NmrfStressResult r : stressResults) {
            if (r == null || r.getStressLoss() == null) continue;
            BigDecimal s = r.getStressLoss();
            BigDecimal sSq = s.pow(2);

            switch (r.getNmrfType()) {
                case ImaConstants.NMRF_TYPE_IDIO_CREDIT:
                    idioCreditSumSq = idioCreditSumSq.add(sSq);
                    break;
                case ImaConstants.NMRF_TYPE_IDIO_EQUITY:
                    idioEquitySumSq = idioEquitySumSq.add(sSq);
                    break;
                default: // OTHER
                    otherSum = otherSum.add(s);
                    otherSumSq = otherSumSq.add(sSq);
                    break;
            }
        }

        // (ρ × Σ_other)^2
        BigDecimal otherCorrTerm = RHO.multiply(otherSum).pow(2);
        // (1-ρ^2) × Σ_other(S^2)
        BigDecimal otherIdioTerm = ONE_MINUS_RHO_SQ.multiply(otherSumSq)
                .setScale(10, RoundingMode.HALF_UP);

        BigDecimal sesSquared = idioCreditSumSq
                .add(idioEquitySumSq)
                .add(otherCorrTerm)
                .add(otherIdioTerm);

        result.setIdioCreditSumSq(idioCreditSumSq);
        result.setIdioEquitySumSq(idioEquitySumSq);
        result.setOtherCorrTerm(otherCorrTerm);
        result.setOtherIdioTerm(otherIdioTerm);
        result.setSes(LiquidityAdjustedEs.sqrt(sesSquared));
        return result;
    }
}
