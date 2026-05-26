package com.zcyh.mr.frtbima.aggregation;

import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.model.ImccResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * IMCC 聚合器（MAR33.15）。
 *
 * <p>采用间接法计算（MAR33.5）：
 * <pre>
 *   scalingFactor = max(1, ES_{F,C}(ALL) / ES_{R,C}(ALL))  // 当期全量/缩减比值
 *   IMCC(C)       = ES_{R,S}(ALL) × scalingFactor           // 压力期缩减集×缩放因子
 *   IMCC(Ci)      = ES_{R,S}(Ci)  × scalingFactor           // 各类别同理
 * </pre>
 *
 * <p>最终聚合公式（MAR33.15）：
 * <pre>
 *   IMCC = ρ × IMCC(C) + (1-ρ) × Σ_i IMCC(Ci)，ρ=0.5
 * </pre>
 */
public class ImccAggregator {

    private static final BigDecimal RHO = ImaConstants.RHO_IMCC; // 0.5
    private static final BigDecimal ONE_MINUS_RHO = BigDecimal.ONE.subtract(RHO);
    private static final String[] RISK_CLASSES = {"IR", "FX", "EQ", "COMM"};

    private final ScalingFactorCalculator scalingCalc = new ScalingFactorCalculator();

    /**
     * 聚合计算 IMCC（间接法，MAR33.5）。
     *
     * @param esCurrentByRiskClass        当期全因子集流动性调整 ES（riskClass → ES_{F,C}）
     * @param esStressReducedByRiskClass  压力期缩减集流动性调整 ES（riskClass → ES_{R,S}）
     * @param esCurrentReducedByRiskClass 当期缩减集流动性调整 ES（riskClass → ES_{R,C}）
     * @return ImccResult
     */
    public ImccResult aggregate(Map<String, BigDecimal> esCurrentByRiskClass,
                                 Map<String, BigDecimal> esStressReducedByRiskClass,
                                 Map<String, BigDecimal> esCurrentReducedByRiskClass) {
        ImccResult result = new ImccResult();

        // MAR33.5：缩放因子 = max(1, ES_{F,C}(ALL) / ES_{R,C}(ALL))
        BigDecimal esCFull = getOrZero(esCurrentByRiskClass, "ALL");
        BigDecimal esCReduced = getOrZero(esCurrentReducedByRiskClass, "ALL");
        BigDecimal scalingFactor = scalingCalc.compute(esCFull, esCReduced);

        // IMCC(C) = ES_{R,S}(ALL) × scalingFactor
        BigDecimal esSReduced = getOrZero(esStressReducedByRiskClass, "ALL");
        BigDecimal imccC = scalingCalc.computeConstrainedEs(esSReduced, scalingFactor);
        result.setImccConstrained(imccC);

        // 各风险类别 IMCC(Ci) = ES_{R,S}(Ci) × scalingFactor
        Map<String, BigDecimal> riskClassEs = new HashMap<>();
        BigDecimal sumImccCi = BigDecimal.ZERO;
        for (String rc : RISK_CLASSES) {
            BigDecimal esSReducedRc = getOrZero(esStressReducedByRiskClass, rc);
            BigDecimal imccCi = scalingCalc.computeConstrainedEs(esSReducedRc, scalingFactor);
            riskClassEs.put(rc, imccCi);
            sumImccCi = sumImccCi.add(imccCi);
        }
        result.setRiskClassEs(riskClassEs);
        result.setImccUnconstrainedSum(sumImccCi);

        // IMCC = ρ × IMCC(C) + (1-ρ) × Σ IMCC(Ci)（MAR33.15，ρ=0.5）
        BigDecimal imcc = RHO.multiply(imccC)
                .add(ONE_MINUS_RHO.multiply(sumImccCi))
                .setScale(10, RoundingMode.HALF_UP);
        result.setImcc(imcc);

        return result;
    }

    private BigDecimal getOrZero(Map<String, BigDecimal> map, String key) {
        if (map == null) return BigDecimal.ZERO;
        BigDecimal val = map.get(key);
        return val != null ? val : BigDecimal.ZERO;
    }
}
