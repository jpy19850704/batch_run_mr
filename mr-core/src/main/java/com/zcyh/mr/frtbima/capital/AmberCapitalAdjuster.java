package com.zcyh.mr.frtbima.capital;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Amber 附加系数（Surcharge）计算器（MAR33.45）。
 *
 * <p>公式：
 * <pre>
 *   k = 0.5 × Σ_{amber}(SA_i) / Σ_{green+amber}(SA_i)
 * </pre>
 *
 * <p>其中 SA_i 为各交易台（desk）在标准法下的资本要求，
 * 分子为 Amber 区的交易台合计，分母为 Green + Amber 的合计。
 *
 * <p>Amber 附加资本 = IMA 资本 × k。
 */
public class AmberCapitalAdjuster {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    /**
     * 计算 Amber 附加系数 k。
     *
     * @param saByDesk   各交易台标准法资本（deskId → SA 值）
     * @param amberDesks 处于 Amber 区的交易台 ID 集合
     * @param greenDesks 处于 Green 区的交易台 ID 集合
     * @return k（0 到 0.5 之间）
     */
    public BigDecimal computeSurchargeRatio(Map<String, BigDecimal> saByDesk,
                                             java.util.Set<String> amberDesks,
                                             java.util.Set<String> greenDesks) {
        BigDecimal sumAmber = BigDecimal.ZERO;
        BigDecimal sumGreenAmber = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> e : saByDesk.entrySet()) {
            String desk = e.getKey();
            BigDecimal sa = e.getValue() != null ? e.getValue().abs() : BigDecimal.ZERO;
            boolean isAmber = amberDesks.contains(desk);
            boolean isGreen = greenDesks.contains(desk);

            if (isAmber) sumAmber = sumAmber.add(sa);
            if (isAmber || isGreen) sumGreenAmber = sumGreenAmber.add(sa);
        }

        if (sumGreenAmber.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return HALF.multiply(sumAmber)
                .divide(sumGreenAmber, 10, RoundingMode.HALF_UP);
    }

    /**
     * 计算 Amber 附加资本。
     *
     * @param imaCapital      IMA 资本基数（IMCC + SES 等）
     * @param surchargeRatio  附加系数 k
     * @return 附加资本 = imaCapital × k
     */
    public BigDecimal computeSurcharge(BigDecimal imaCapital, BigDecimal surchargeRatio) {
        if (imaCapital == null || surchargeRatio == null) return BigDecimal.ZERO;
        return imaCapital.multiply(surchargeRatio).setScale(10, RoundingMode.HALF_UP);
    }
}
