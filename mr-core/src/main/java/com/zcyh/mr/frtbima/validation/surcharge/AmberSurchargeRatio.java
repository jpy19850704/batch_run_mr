package com.zcyh.mr.frtbima.validation.surcharge;

import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;
import com.zcyh.mr.frtbima.validation.common.ValidationConstants;
import com.zcyh.mr.frtbima.validation.model.ValidationNodeResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Amber 附加资本系数计算器。
 * 规则依据：MAR33.45
 *
 * 公式：k = 0.5 × Σ_{amber节点}(SA_i) / Σ_{green节点+amber节点}(SA_i)
 *
 * 支持任意维度的验证节点（交易台、全行或自定义节点）。
 * k 用于最终资本公式：acrTotal = IMCC + SES + (IMCC + SES) × k
 * 当所有节点均为绿区时，k=0（无附加）
 */
public class AmberSurchargeRatio {

    private static final int SCALE = 10;

    /**
     * 计算 Amber 附加系数 k。
     *
     * @param nodeResults 所有节点的验证结果列表（含 zone 和 saCapital）
     * @return Amber 附加系数 k，范围 [0, 0.5]
     */
    public BigDecimal compute(List<ValidationNodeResult> nodeResults) {
        if (nodeResults == null || nodeResults.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal sumAmber = BigDecimal.ZERO;
        BigDecimal sumGreenAndAmber = BigDecimal.ZERO;

        for (ValidationNodeResult node : nodeResults) {
            TrafficLightZone zone = node.getZone();
            BigDecimal sa = node.getSaCapital();
            if (sa == null || sa.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // 分母：绿区 + 黄区
            if (zone == TrafficLightZone.GREEN || zone == TrafficLightZone.AMBER) {
                sumGreenAndAmber = sumGreenAndAmber.add(sa);
            }
            // 分子：仅黄区
            if (zone == TrafficLightZone.AMBER) {
                sumAmber = sumAmber.add(sa);
            }
        }

        if (sumGreenAndAmber.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // k = 0.5 × Σ_amber / Σ_{G,A}
        return ValidationConstants.AMBER_SURCHARGE_COEFFICIENT
                .multiply(sumAmber)
                .divide(sumGreenAndAmber, SCALE, RoundingMode.HALF_UP);
    }
}
