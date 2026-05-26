package com.zcyh.mr.var;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

abstract class VarMeasureCalculatorSupport {
    private static final BigDecimal TWO = BigDecimal.valueOf(2L);
    private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");
    private static final int DEFAULT_SCALE = 10;

    protected final VarCalculator varCalculator = new VarCalculator();

    protected void validateInputs(List<VarDimensionGroup> dimensionGroups,
                                  List<String> riskClasses,
                                  BigDecimal quantile,
                                  List<VarMeasure> measures) {
        if (dimensionGroups == null || dimensionGroups.isEmpty()) {
            throw new IllegalArgumentException("dimensionGroups 不能为空");
        }
        if (riskClasses == null || riskClasses.isEmpty()) {
            throw new IllegalArgumentException("riskClasses 不能为空");
        }
        if (quantile == null) {
            throw new IllegalArgumentException("quantile 不能为空");
        }
        if (measures == null || measures.isEmpty()) {
            throw new IllegalArgumentException("measure 不能为空");
        }
    }

    protected VarDimensionGroup requireTotalGroup(List<VarDimensionGroup> dimensionGroups) {
        if (dimensionGroups != null) {
            for (VarDimensionGroup dimensionGroup : dimensionGroups) {
                if (isTotalGroup(dimensionGroup)) {
                    return dimensionGroup;
                }
            }
        }
        throw new IllegalStateException("VaR 程序内部未生成 TOTAL 维度");
    }

    protected boolean isTotalGroup(VarDimensionGroup dimensionGroup) {
        return dimensionGroup != null
                && "TOTAL".equalsIgnoreCase(dimensionGroup.getGroupType())
                && "TOTAL".equalsIgnoreCase(dimensionGroup.getGroupValue());
    }

    protected VarQuantileResult calcSingleQuantile(List<VarScenarioPnl> rows,
                                                   BigDecimal quantile,
                                                   VarPickMethod pickMethod) {
        List<VarQuantileResult> results = varCalculator.calculate(rows, Collections.singletonList(quantile), pickMethod);
        return results.get(0);
    }

    protected List<VarScenarioPnl> toScenarioRows(Map<VarScenarioKey, VarScenarioPnlAggregate> scenarioPnls,
                                                  String pnlColumn) {
        List<VarScenarioPnl> rows = new ArrayList<VarScenarioPnl>();
        for (Map.Entry<VarScenarioKey, VarScenarioPnlAggregate> entry : scenarioPnls.entrySet()) {
            VarScenarioKey scenarioKey = entry.getKey();
            VarScenarioPnlAggregate aggregate = entry.getValue();
            rows.add(new VarScenarioPnl(
                    scenarioKey.getScenarioId(),
                    scenarioKey.getSubScenarioId(),
                    scenarioKey.getScenarioName(),
                    aggregate.readByColumn(pnlColumn)));
        }
        return rows;
    }

    protected VarRiskClassMeasureResult buildIndependentRiskClassResult(VarQuantileResult quantileResult,
                                                                        String riskClass,
                                                                        BigDecimal es,
                                                                        String sortPnlField,
                                                                        List<VarMeasure> measures) {
        VarRiskClassMeasureResult result = toQuantileResult(quantileResult);
        result.setRiskClass(VarPnlColumns.normalizeRiskClassToken(riskClass));
        result.setEs(es);
        result.setSortPnlField(sortPnlField);
        applySelectedMeasures(result, measures);
        return result;
    }

    protected VarRiskClassMeasureResult buildTotalSortedRiskClassResult(VarDimensionGroup currentGroup,
                                                                        VarQuantileResult totalAllQuantile,
                                                                        String riskClass,
                                                                        BigDecimal es,
                                                                        VarPickMethod pickMethod,
                                                                        List<VarMeasure> measures) {
        String pnlColumn = VarPnlColumns.riskClassToPnlColumn(riskClass);
        VarScenarioPnl inScenario = totalAllQuantile.getInScenario();
        VarScenarioPnl outScenario = totalAllQuantile.getOutScenario();
        BigDecimal pnlIn = readGroupScenarioPnl(currentGroup, VarScenarioKey.fromScenario(inScenario), pnlColumn);
        BigDecimal pnlOut = readGroupScenarioPnl(currentGroup, VarScenarioKey.fromScenario(outScenario), pnlColumn);
        int rankIn = totalAllQuantile.getRankIn();
        int rankOut = totalAllQuantile.getRankOut();
        String subIn = inScenario == null ? null : inScenario.getSubScenarioId();
        String subOut = outScenario == null ? null : outScenario.getSubScenarioId();
        if (sameSubScenario(subIn, subOut)) {
            rankOut = rankIn;
            pnlOut = pnlIn;
            subOut = subIn;
        }

        BigDecimal selectedVar;
        String selectedScenarioId = null;
        if (pickMethod == VarPickMethod.IN) {
            selectedVar = pnlIn;
            selectedScenarioId = inScenario == null ? null : inScenario.getScenarioId();
        } else if (pickMethod == VarPickMethod.OUT) {
            selectedVar = pnlOut;
            selectedScenarioId = outScenario == null ? null : outScenario.getScenarioId();
        } else {
            selectedVar = pnlOut.add(pnlIn).divide(TWO, DEFAULT_SCALE, RoundingMode.HALF_UP);
        }

        VarRiskClassMeasureResult result = new VarRiskClassMeasureResult();
        result.setRiskClass(VarPnlColumns.normalizeRiskClassToken(riskClass));
        result.setRankIn(rankIn);
        result.setRankOut(rankOut);
        result.setSubScenarioIdIn(subIn);
        result.setSubScenarioIdOut(subOut);
        result.setPnlIn(pnlIn);
        result.setPnlOut(pnlOut);
        result.setVarIn(pnlIn);
        result.setVarOut(pnlOut);
        result.setSortPnlField(VarPnlColumns.ALL_PNL);
        result.setIncludeSelectedScenarioId(!totalAllQuantile.isSingleSample() && pickMethod != VarPickMethod.AVERAGE);
        result.setSelectedScenarioId(selectedScenarioId);
        result.setVar(selectedVar);
        result.setEs(es);
        applySelectedMeasures(result, measures);
        return result;
    }

    protected void applySelectedMeasures(VarRiskClassMeasureResult result, List<VarMeasure> measures) {
        if (!measures.contains(VarMeasure.VAR)) {
            result.setVar(BigDecimal.ZERO);
        }
        if (!measures.contains(VarMeasure.ES)) {
            result.setEs(BigDecimal.ZERO);
        }
        if (!measures.contains(VarMeasure.COMPONENT_VAR)) {
            result.setComponentVar(BigDecimal.ZERO);
        }
        if (!measures.contains(VarMeasure.MARGINAL_VAR)) {
            result.setMarginalVar(BigDecimal.ZERO);
        }
        if (!measures.contains(VarMeasure.INCREMENTAL_VAR)) {
            result.setIncrementalVar(BigDecimal.ZERO);
        }
    }

    protected BigDecimal calculateComponentVar(VarQuantileResult totalQuantile,
                                               VarDimensionGroup currentGroup,
                                               String pnlColumn,
                                               VarPickMethod pickMethod) {
        BigDecimal pnlIn = readGroupScenarioPnl(currentGroup, VarScenarioKey.fromScenario(totalQuantile.getInScenario()), pnlColumn);
        BigDecimal pnlOut = readGroupScenarioPnl(currentGroup, VarScenarioKey.fromScenario(totalQuantile.getOutScenario()), pnlColumn);
        if (pickMethod == VarPickMethod.IN) {
            return pnlIn;
        }
        if (pickMethod == VarPickMethod.OUT) {
            return pnlOut;
        }
        return pnlIn.add(pnlOut).divide(TWO, DEFAULT_SCALE, RoundingMode.HALF_UP);
    }

    protected BigDecimal calculateMarginalVar(VarDimensionGroup totalGroup,
                                              VarDimensionGroup currentGroup,
                                              VarQuantileResult baseQuantile,
                                              BigDecimal quantile,
                                              String totalPnlColumn,
                                              String adjustmentPnlColumn,
                                              VarPickMethod pickMethod) {
        VarQuantileResult bumpedQuantile = calcSingleQuantile(
                toAdjustedScenarioRows(totalGroup.getScenarioPnls(), currentGroup.getScenarioPnls(),
                        totalPnlColumn, adjustmentPnlColumn, ONE_PERCENT),
                quantile,
                pickMethod);
        return bumpedQuantile.getSelectedPnl().subtract(baseQuantile.getSelectedPnl());
    }

    protected BigDecimal calculateIncrementalVar(VarDimensionGroup totalGroup,
                                                 VarDimensionGroup currentGroup,
                                                 VarQuantileResult baseQuantile,
                                                 BigDecimal quantile,
                                                 String totalPnlColumn,
                                                 String adjustmentPnlColumn,
                                                 VarPickMethod pickMethod) {
        VarQuantileResult withoutCurrent = calcSingleQuantile(
                toAdjustedScenarioRows(totalGroup.getScenarioPnls(), currentGroup.getScenarioPnls(),
                        totalPnlColumn, adjustmentPnlColumn, BigDecimal.ONE.negate()),
                quantile,
                pickMethod);
        return baseQuantile.getSelectedPnl().subtract(withoutCurrent.getSelectedPnl());
    }

    protected VarTailScenarioContext buildTailScenarioContext(VarDimensionGroup baseGroup,
                                                              BigDecimal quantile,
                                                              String basePnlColumn) {
        List<Map.Entry<VarScenarioKey, VarScenarioPnlAggregate>> sorted =
                new ArrayList<Map.Entry<VarScenarioKey, VarScenarioPnlAggregate>>(baseGroup.getScenarioPnls().entrySet());
        sorted.sort(Comparator
                .comparing((Map.Entry<VarScenarioKey, VarScenarioPnlAggregate> row) -> safePnl(row.getValue().readByColumn(basePnlColumn)))
                .thenComparing(row -> nullSafe(row.getKey().getScenarioId()))
                .thenComparing(row -> nullSafe(row.getKey().getSubScenarioId()))
                .thenComparing(row -> nullSafe(row.getKey().getScenarioName())));

        int n = sorted.size();
        if (n == 0) {
            throw new IllegalArgumentException("VaR 样本为空，无法计算 ES");
        }
        BigDecimal tail = BigDecimal.ONE.subtract(quantile);
        int idxOut = clampIndex(tail.multiply(BigDecimal.valueOf(n - 1L)).setScale(0, RoundingMode.FLOOR).intValue(), n);
        int count = idxOut + 1;
        List<VarScenarioKey> tailScenarioKeys = new ArrayList<VarScenarioKey>();
        for (int i = 0; i < count; i++) {
            tailScenarioKeys.add(sorted.get(i).getKey());
        }
        return new VarTailScenarioContext(tailScenarioKeys);
    }

    protected BigDecimal calculateEsByBaseOut(VarTailScenarioContext tailContext,
                                              VarDimensionGroup currentGroup,
                                              String targetPnlColumn) {
        if (tailContext == null || tailContext.tailScenarioKeys.isEmpty()) {
            throw new IllegalArgumentException("VaR ES 尾部情景为空");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (VarScenarioKey scenarioKey : tailContext.tailScenarioKeys) {
            sum = sum.add(readGroupScenarioPnl(currentGroup, scenarioKey, targetPnlColumn));
        }
        return sum.divide(BigDecimal.valueOf(tailContext.tailScenarioKeys.size()), DEFAULT_SCALE, RoundingMode.HALF_UP);
    }

    private VarRiskClassMeasureResult toQuantileResult(VarQuantileResult calcResult) {
        VarScenarioPnl inScenario = calcResult.getInScenario();
        VarScenarioPnl outScenario = calcResult.getOutScenario();
        BigDecimal pnlIn = calcResult.getPnlIn();
        BigDecimal pnlOut = calcResult.getPnlOut();
        int rankIn = calcResult.getRankIn();
        int rankOut = calcResult.getRankOut();
        String subIn = inScenario == null ? null : inScenario.getSubScenarioId();
        String subOut = outScenario == null ? null : outScenario.getSubScenarioId();
        if (sameSubScenario(subIn, subOut)) {
            rankOut = rankIn;
            pnlOut = pnlIn;
            subOut = subIn;
        }

        VarRiskClassMeasureResult result = new VarRiskClassMeasureResult();
        result.setRankIn(rankIn);
        result.setRankOut(rankOut);
        result.setSubScenarioIdIn(subIn);
        result.setSubScenarioIdOut(subOut);
        result.setPnlIn(pnlIn);
        result.setPnlOut(pnlOut);
        result.setVarIn(pnlIn);
        result.setVarOut(pnlOut);
        result.setIncludeSelectedScenarioId(!calcResult.isSingleSample());
        VarScenarioPnl selectedScenario = calcResult.getSelectedScenario();
        result.setSelectedScenarioId(selectedScenario == null ? null : selectedScenario.getScenarioId());
        result.setVar(calcResult.getSelectedPnl());
        return result;
    }

    private List<VarScenarioPnl> toAdjustedScenarioRows(Map<VarScenarioKey, VarScenarioPnlAggregate> totalScenarioPnls,
                                                        Map<VarScenarioKey, VarScenarioPnlAggregate> groupScenarioPnls,
                                                        String totalPnlColumn,
                                                        String adjustmentPnlColumn,
                                                        BigDecimal groupFactorDelta) {
        List<VarScenarioPnl> rows = new ArrayList<VarScenarioPnl>();
        for (Map.Entry<VarScenarioKey, VarScenarioPnlAggregate> entry : totalScenarioPnls.entrySet()) {
            VarScenarioKey scenarioKey = entry.getKey();
            BigDecimal totalValue = entry.getValue().readByColumn(totalPnlColumn);
            BigDecimal groupValue = readAggregatePnl(groupScenarioPnls.get(scenarioKey), adjustmentPnlColumn);
            BigDecimal adjusted = totalValue.add(groupValue.multiply(groupFactorDelta));
            rows.add(new VarScenarioPnl(
                    scenarioKey.getScenarioId(),
                    scenarioKey.getSubScenarioId(),
                    scenarioKey.getScenarioName(),
                    adjusted));
        }
        return rows;
    }

    private static BigDecimal readGroupScenarioPnl(VarDimensionGroup groupData,
                                                   VarScenarioKey scenarioKey,
                                                   String pnlColumn) {
        if (groupData == null || scenarioKey == null) {
            return BigDecimal.ZERO;
        }
        return readAggregatePnl(groupData.getScenarioPnls().get(scenarioKey), pnlColumn);
    }

    private static BigDecimal readAggregatePnl(VarScenarioPnlAggregate aggregate, String pnlColumn) {
        if (aggregate == null) {
            return BigDecimal.ZERO;
        }
        return aggregate.readByColumn(pnlColumn);
    }

    private static int clampIndex(int idx, int size) {
        if (idx < 0) {
            return 0;
        }
        int max = size - 1;
        return Math.min(idx, max);
    }

    private static boolean sameSubScenario(String left, String right) {
        String l = trimToNull(left);
        String r = trimToNull(right);
        return l != null && l.equals(r);
    }

    private static BigDecimal safePnl(BigDecimal pnl) {
        return pnl == null ? BigDecimal.ZERO : pnl;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected static class VarTailScenarioContext {
        private final List<VarScenarioKey> tailScenarioKeys;

        private VarTailScenarioContext(List<VarScenarioKey> tailScenarioKeys) {
            this.tailScenarioKeys = tailScenarioKeys == null
                    ? new ArrayList<VarScenarioKey>()
                    : new ArrayList<VarScenarioKey>(tailScenarioKeys);
        }
    }
}
