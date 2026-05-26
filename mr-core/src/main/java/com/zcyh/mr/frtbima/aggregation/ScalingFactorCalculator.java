package com.zcyh.mr.frtbima.aggregation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * IMCC 缩放因子计算器（MAR33.5）。
 *
 * <p>间接法公式：
 * <pre>
 *   scalingFactor = max(1, ES_{F,C} / ES_{R,C})
 *   constrainedES = ES_{R,S} × scalingFactor
 * </pre>
 *
 * <p>其中：
 * <ul>
 *   <li>ES_{F,C} = 当期全因子集 ES（Full, Current）</li>
 *   <li>ES_{R,C} = 当期缩减集 ES（Reduced, Current）</li>
 *   <li>ES_{R,S} = 压力期缩减集 ES（Reduced, Stress）</li>
 * </ul>
 *
 * <p>缩放因子反映"当期下全量因子相对缩减因子多出多少风险"，
 * 然后将该比例应用到压力期的缩减集 ES 上，以近似压力期全量风险。
 *
 * <p>MAR33.5 限制：scalingFactor 不得低于 1.0。
 */
public class ScalingFactorCalculator {

    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * 计算缩放因子（MAR33.5）。
     *
     * <p>公式：scalingFactor = max(1, ES_{F,C} / ES_{R,C})
     *
     * @param esCurrFull    当期全因子集流动性调整 ES（ES_{F,C}）
     * @param esCurrReduced 当期缩减集流动性调整 ES（ES_{R,C}）
     * @return 缩放因子（>= 1.0）
     */
    public BigDecimal compute(BigDecimal esCurrFull, BigDecimal esCurrReduced) {
        if (esCurrReduced == null || esCurrReduced.compareTo(BigDecimal.ZERO) <= 0) {
            return ONE;
        }
        BigDecimal factor = esCurrFull.divide(esCurrReduced, 10, RoundingMode.HALF_UP);
        return factor.max(ONE);
    }

    /**
     * 计算约束 ES（MAR33.5 间接法）。
     *
     * <p>公式：constrainedES = ES_{R,S} × scalingFactor
     *
     * @param esStressReduced 压力期缩减集流动性调整 ES（ES_{R,S}）
     * @param scalingFactor   缩放因子（由 compute() 计算）
     * @return 约束 ES
     */
    public BigDecimal computeConstrainedEs(BigDecimal esStressReduced,
                                            BigDecimal scalingFactor) {
        if (esStressReduced == null) return BigDecimal.ZERO;
        return esStressReduced.multiply(scalingFactor)
                .setScale(10, RoundingMode.HALF_UP);
    }
}
