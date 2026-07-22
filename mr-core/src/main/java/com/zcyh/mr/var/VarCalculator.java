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
        int count = calculateOutRank(n, quantile);
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
                    BigDecimal.ZERO,
                    true);
        }

        int rankOut = calculateOutRank(n, quantile);
        int rankIn = calculateInRank(n, quantile);
        int idxOut = rankOut - 1;
        int idxIn = rankIn - 1;

        VarScenarioPnl outRow = rows.get(idxOut);
        VarScenarioPnl inRow = rows.get(idxIn);
        BigDecimal pnlOut = safePnl(outRow.getPnl());
        BigDecimal pnlIn = safePnl(inRow.getPnl());
        BigDecimal varOut = toVarValue(pnlOut);
        BigDecimal varIn = toVarValue(pnlIn);
        BigDecimal interpolationWeightIn = calculateInterpolationWeightIn(n, quantile);
        BigDecimal interpolatedPnl = interpolate(pnlOut, pnlIn, interpolationWeightIn);
        BigDecimal interpolatedVar = interpolate(varOut, varIn, interpolationWeightIn);

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
            selectedPnl = interpolatedPnl;
            selectedVar = interpolatedVar;
        }

        return new VarQuantileResult(
                quantile,
                rankIn,
                rankOut,
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
                interpolationWeightIn,
                false);
    }

    static int calculateOutRank(int sampleSize, BigDecimal quantile) {
        return calculateRank(sampleSize, quantile, RoundingMode.FLOOR);
    }

    static int calculateInRank(int sampleSize, BigDecimal quantile) {
        return calculateRank(sampleSize, quantile, RoundingMode.CEILING);
    }

    static BigDecimal interpolate(BigDecimal outValue,
                                  BigDecimal inValue,
                                  BigDecimal interpolationWeightIn) {
        BigDecimal safeWeightIn = interpolationWeightIn == null ? BigDecimal.ZERO : interpolationWeightIn;
        BigDecimal weightOut = BigDecimal.ONE.subtract(safeWeightIn);
        return safePnl(outValue).multiply(weightOut)
                .add(safePnl(inValue).multiply(safeWeightIn))
                .setScale(DEFAULT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateInterpolationWeightIn(int sampleSize, BigDecimal quantile) {
        BigDecimal position = calculatePosition(sampleSize, quantile);
        return position.subtract(position.setScale(0, RoundingMode.FLOOR));
    }

    private static int calculateRank(int sampleSize, BigDecimal quantile, RoundingMode roundingMode) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("VaR 样本数必须大于 0");
        }
        BigDecimal position = calculatePosition(sampleSize, quantile);
        int rank = position.setScale(0, roundingMode).intValue();
        return Math.max(1, Math.min(rank, sampleSize));
    }

    private static BigDecimal calculatePosition(int sampleSize, BigDecimal quantile) {
        validateQuantile(quantile);
        return BigDecimal.ONE.subtract(quantile)
                .multiply(BigDecimal.valueOf(sampleSize));
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
