package com.zcyh.mr.var;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * VaR 分位点计算器。
 */
public class VarCalculator {
    private static final BigDecimal TWO = BigDecimal.valueOf(2L);
    private static final int DEFAULT_SCALE = 10;

    public List<VarQuantileResult> calculate(List<VarScenarioPnl> scenarioPnls,
                                             List<BigDecimal> quantiles,
                                             VarPickMethod pickMethod) {
        if (scenarioPnls == null || scenarioPnls.isEmpty()) {
            throw new IllegalArgumentException("VaR 样本为空，无法计算");
        }
        if (quantiles == null || quantiles.isEmpty()) {
            throw new IllegalArgumentException("quantiles 不能为空");
        }
        VarPickMethod safePickMethod = pickMethod == null ? VarPickMethod.AVERAGE : pickMethod;

        List<VarScenarioPnl> sorted = new ArrayList<VarScenarioPnl>(scenarioPnls);
        sorted.sort(Comparator
                .comparing((VarScenarioPnl row) -> safePnl(row.getPnl()))
                .thenComparing(row -> nullToEmpty(row.getScenarioId()))
                .thenComparing(row -> nullToEmpty(row.getSubScenarioId()))
                .thenComparing(row -> nullToEmpty(row.getScenarioName())));

        List<VarQuantileResult> results = new ArrayList<VarQuantileResult>();
        for (BigDecimal quantile : quantiles) {
            validateQuantile(quantile);
            results.add(calculateSingle(sorted, quantile, safePickMethod));
        }
        return results;
    }

    /**
     * 基于 out 口径计算 ES（尾部均值）。
     */
    public BigDecimal calculateEsByOut(List<VarScenarioPnl> scenarioPnls, BigDecimal quantile) {
        if (scenarioPnls == null || scenarioPnls.isEmpty() || quantile == null) {
            return BigDecimal.ZERO;
        }
        validateQuantile(quantile);

        List<BigDecimal> values = new ArrayList<BigDecimal>();
        for (VarScenarioPnl row : scenarioPnls) {
            values.add(safePnl(row == null ? null : row.getPnl()));
        }
        values.sort(Comparator.naturalOrder());

        int n = values.size();
        BigDecimal tail = BigDecimal.ONE.subtract(quantile);
        int idxOut = clampIndex(tail.multiply(BigDecimal.valueOf(n - 1L)).setScale(0, RoundingMode.FLOOR).intValue(), n);
        int count = idxOut + 1;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            sum = sum.add(values.get(i));
        }
        return sum.divide(BigDecimal.valueOf(count), DEFAULT_SCALE, RoundingMode.HALF_UP);
    }

    private VarQuantileResult calculateSingle(List<VarScenarioPnl> rows,
                                              BigDecimal quantile,
                                              VarPickMethod pickMethod) {
        int n = rows.size();
        if (n == 1) {
            VarScenarioPnl only = rows.get(0);
            BigDecimal onlyPnl = safePnl(only.getPnl());
            BigDecimal onlyVar = toVarValue(onlyPnl);
            return new VarQuantileResult(
                    quantile,
                    1,
                    1,
                    only,
                    only,
                    onlyPnl,
                    onlyVar,
                    onlyPnl,
                    onlyVar,
                    pickMethod,
                    only,
                    onlyPnl,
                    onlyVar,
                    true);
        }

        BigDecimal tail = BigDecimal.ONE.subtract(quantile);
        BigDecimal pos = tail.multiply(BigDecimal.valueOf(n - 1L));
        int idxOut = clampIndex(pos.setScale(0, RoundingMode.FLOOR).intValue(), n);
        int idxIn = clampIndex(pos.setScale(0, RoundingMode.CEILING).intValue(), n);

        VarScenarioPnl outRow = rows.get(idxOut);
        VarScenarioPnl inRow = rows.get(idxIn);
        BigDecimal pnlOut = safePnl(outRow.getPnl());
        BigDecimal pnlIn = safePnl(inRow.getPnl());
        BigDecimal varOut = toVarValue(pnlOut);
        BigDecimal varIn = toVarValue(pnlIn);
        BigDecimal avgPnl = pnlOut.add(pnlIn).divide(TWO, DEFAULT_SCALE, RoundingMode.HALF_UP);
        BigDecimal avgVar = varOut.add(varIn).divide(TWO, DEFAULT_SCALE, RoundingMode.HALF_UP);

        VarScenarioPnl selectedScenario = null;
        BigDecimal selectedPnl;
        BigDecimal selectedVar;
        if (pickMethod == VarPickMethod.IN) {
            selectedScenario = inRow;
            selectedPnl = pnlIn;
            selectedVar = varIn;
        } else if (pickMethod == VarPickMethod.OUT) {
            selectedScenario = outRow;
            selectedPnl = pnlOut;
            selectedVar = varOut;
        } else {
            selectedPnl = avgPnl;
            selectedVar = avgVar;
        }

        return new VarQuantileResult(
                quantile,
                idxIn + 1,
                idxOut + 1,
                inRow,
                outRow,
                pnlIn,
                varIn,
                pnlOut,
                varOut,
                pickMethod,
                selectedScenario,
                selectedPnl,
                selectedVar,
                false);
    }

    private static int clampIndex(int idx, int size) {
        if (idx < 0) {
            return 0;
        }
        int max = size - 1;
        return Math.min(idx, max);
    }

    private static BigDecimal safePnl(BigDecimal pnl) {
        return pnl == null ? BigDecimal.ZERO : pnl;
    }

    private static BigDecimal toVarValue(BigDecimal pnl) {
        BigDecimal risk = safePnl(pnl).negate();
        if (risk.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return risk;
    }

    private static void validateQuantile(BigDecimal quantile) {
        if (quantile == null) {
            throw new IllegalArgumentException("quantile 不能为空");
        }
        if (quantile.compareTo(BigDecimal.ZERO) <= 0 || quantile.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("quantile 必须在 (0,1) 区间: " + quantile);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
