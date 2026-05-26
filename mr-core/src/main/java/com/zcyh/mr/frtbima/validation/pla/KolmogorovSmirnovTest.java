package com.zcyh.mr.frtbima.validation.pla;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kolmogorov-Smirnov 检验统计量计算器。
 * 用于 PLA 测试中比较假设PnL与风险理论PnL的分布差异。
 * 规则依据：MAR32.40
 *
 * 两样本 KS 统计量：D = max|F1(x) - F2(x)|
 * 其中 F1、F2 分别为两组样本的经验分布函数（ECDF）
 */
public class KolmogorovSmirnovTest {

    private static final int SCALE = 10;

    /**
     * 计算两样本 KS 统计量。
     *
     * @param hypothetical      假设PnL序列
     * @param riskTheoretical   风险理论PnL序列
     * @return KS 统计量（越小越好，表示两分布越接近）
     */
    public BigDecimal compute(List<BigDecimal> hypothetical, List<BigDecimal> riskTheoretical) {
        if (hypothetical == null || riskTheoretical == null
                || hypothetical.isEmpty() || riskTheoretical.isEmpty()) {
            throw new IllegalArgumentException("输入序列不能为空");
        }

        List<BigDecimal> sorted1 = new ArrayList<>(hypothetical);
        List<BigDecimal> sorted2 = new ArrayList<>(riskTheoretical);
        Collections.sort(sorted1);
        Collections.sort(sorted2);

        int n1 = sorted1.size();
        int n2 = sorted2.size();

        // 合并两个有序序列的所有点，遍历计算 ECDF 差的最大值
        double maxDiff = 0.0;
        int i = 0;
        int j = 0;

        while (i < n1 || j < n2) {
            double x;
            if (i >= n1) {
                x = sorted2.get(j).doubleValue();
            } else if (j >= n2) {
                x = sorted1.get(i).doubleValue();
            } else {
                x = Math.min(sorted1.get(i).doubleValue(), sorted2.get(j).doubleValue());
            }

            // 推进 i 到超过 x 的位置
            while (i < n1 && sorted1.get(i).doubleValue() <= x) i++;
            // 推进 j 到超过 x 的位置
            while (j < n2 && sorted2.get(j).doubleValue() <= x) j++;

            double f1 = (double) i / n1;
            double f2 = (double) j / n2;
            double diff = Math.abs(f1 - f2);
            if (diff > maxDiff) {
                maxDiff = diff;
            }
        }

        return BigDecimal.valueOf(maxDiff).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
