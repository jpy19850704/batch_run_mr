package com.zcyh.mr.frtbima.es;

import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.model.EsResult;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流动性调整 ES 计算（MAR33.4 公式）。
 *
 * <p>公式（单风险类别）：
 * <pre>
 * ES_adj(riskClass) = sqrt(
 *     ES(LH_1)^2
 *   + ES(LH_2)^2 × (LH_2 - LH_1) / LH_1
 *   + ES(LH_3)^2 × (LH_3 - LH_2) / LH_1
 *   + ES(LH_4)^2 × (LH_4 - LH_3) / LH_1
 *   + ES(LH_5)^2 × (LH_5 - LH_4) / LH_1
 * )
 * </pre>
 *
 * <p>其中 LH_1=10, LH_2=20, LH_3=40, LH_4=60, LH_5=120（天）。
 * ES(LH_j) = 该 lhDays 子集的 97.5% ES（来自 EsCalculator 输出）。
 */
public class LiquidityAdjustedEs {

    /** 牛顿迭代收敛精度：1e-12 */
    private static final BigDecimal SQRT_EPSILON = new BigDecimal("1e-12");

    /**
     * 计算指定 scenarioType 下每个 riskClass 的流动性调整 ES。
     *
     * @param esResults EsCalculator 输出（可包含多个 scenarioType）
     * @param scenarioType 目标情景类型（STRESS_REDUCED / NORMAL_FULL / NORMAL_REDUCED）
     * @return riskClass → 流动性调整后的 ES 值
     */
    public Map<String, BigDecimal> compute(List<EsResult> esResults, String scenarioType) {
        // 收集 (riskClass, lhDays) → esValue
        Map<String, Map<Integer, BigDecimal>> esMap = new HashMap<>();
        for (EsResult r : esResults) {
            if (!scenarioType.equals(r.getScenarioType())) continue;
            esMap.computeIfAbsent(r.getRiskClass(), k -> new HashMap<>())
                 .put(r.getLhDays(), r.getEsValue());
        }

        Map<String, BigDecimal> result = new HashMap<>();
        for (Map.Entry<String, Map<Integer, BigDecimal>> entry : esMap.entrySet()) {
            result.put(entry.getKey(), applyLhFormula(entry.getValue()));
        }
        return result;
    }

    /**
     * 对一个 riskClass 的 {lhDays → ES} 映射应用流动性调整公式。
     */
    private BigDecimal applyLhFormula(Map<Integer, BigDecimal> esByLh) {
        // LH_DAYS_ARRAY = {10, 20, 40, 60, 120}
        int[] lhArr = ImaConstants.LH_DAYS_ARRAY;
        int lh1 = ImaConstants.LH_BASE; // 10

        BigDecimal sumSq = BigDecimal.ZERO;
        for (int j = 0; j < lhArr.length; j++) {
            int lhJ = lhArr[j];
            BigDecimal esJ = esByLh.getOrDefault(lhJ, BigDecimal.ZERO);

            // 权重 = (LH_j - LH_{j-1}) / LH_1；j=0 时权重 = LH_1/LH_1 = 1
            int prevLh = (j == 0) ? 0 : lhArr[j - 1];
            BigDecimal weight = BigDecimal.valueOf((long) (lhJ - prevLh))
                    .divide(BigDecimal.valueOf(lh1), 10, RoundingMode.HALF_UP);

            sumSq = sumSq.add(esJ.pow(2).multiply(weight));
        }
        return sqrt(sumSq);
    }

    /**
     * BigDecimal 平方根（牛顿迭代，精度 15 位）。
     */
    public static BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        MathContext mc = new MathContext(15, RoundingMode.HALF_UP);
        BigDecimal x = value;
        BigDecimal prev;
        do {
            prev = x;
            x = x.add(value.divide(x, mc)).divide(BigDecimal.valueOf(2), mc);
        } while (x.subtract(prev).abs().compareTo(SQRT_EPSILON) > 0);
        return x.setScale(10, RoundingMode.HALF_UP);
    }
}
