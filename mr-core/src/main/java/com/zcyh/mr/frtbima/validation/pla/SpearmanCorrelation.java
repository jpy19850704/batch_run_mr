package com.zcyh.mr.frtbima.validation.pla;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spearman 秩相关系数计算器。
 * 用于 PLA 测试中衡量假设PnL与风险理论PnL的相关性。
 * 规则依据：MAR32.20
 *
 * Spearman 公式：r_s = 1 - 6×Σd²_i / (n×(n²-1))
 * 其中 d_i = rank(x_i) - rank(y_i)
 */
public class SpearmanCorrelation {

    private static final int SCALE = 10;

    /**
     * 计算 Spearman 秩相关系数。
     *
     * @param x 序列X（通常为假设PnL）
     * @param y 序列Y（通常为风险理论PnL）
     * @return Spearman 相关系数，范围 [-1, 1]
     */
    public BigDecimal compute(List<BigDecimal> x, List<BigDecimal> y) {
        if (x == null || y == null || x.size() != y.size() || x.isEmpty()) {
            throw new IllegalArgumentException("输入序列不能为空且长度须相等");
        }
        int n = x.size();

        // 计算秩次
        double[] rankX = computeRanks(x);
        double[] rankY = computeRanks(y);

        // 计算 Σd²
        double sumD2 = 0.0;
        for (int i = 0; i < n; i++) {
            double d = rankX[i] - rankY[i];
            sumD2 += d * d;
        }

        // r_s = 1 - 6×Σd² / (n×(n²-1))
        double denominator = (double) n * ((double) n * n - 1.0);
        if (denominator == 0.0) {
            return BigDecimal.ONE;
        }
        double rs = 1.0 - 6.0 * sumD2 / denominator;
        return BigDecimal.valueOf(rs).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算序列的秩次（支持并列秩：并列值取平均秩次）。
     */
    private double[] computeRanks(List<BigDecimal> values) {
        int n = values.size();
        // 构建索引排序列表
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            indices.add(i);
        }
        indices.sort((a, b) -> {
            BigDecimal va = values.get(a);
            BigDecimal vb = values.get(b);
            if (va == null && vb == null) return 0;
            if (va == null) return -1;
            if (vb == null) return 1;
            return va.compareTo(vb);
        });

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            BigDecimal val = values.get(indices.get(i));
            // 找到连续相等的区间
            while (j < n) {
                BigDecimal cur = values.get(indices.get(j));
                if (cur == null ? val != null : cur.compareTo(val) != 0) break;
                j++;
            }
            // 并列秩取区间均值
            double avgRank = (i + 1 + j) / 2.0;
            for (int k = i; k < j; k++) {
                ranks[indices.get(k)] = avgRank;
            }
            i = j;
        }
        return ranks;
    }
}
