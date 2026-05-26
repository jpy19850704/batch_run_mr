package com.zcyh.mr.var;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 风险大类分解 VaR 计量器。
 */
public class VarRiskClassDecompCalculator extends VarMeasureCalculatorSupport {

    public List<VarDimensionMeasureResult> calculate(List<VarDimensionGroup> dimensionGroups,
                                                     List<String> riskClasses,
                                                     BigDecimal quantile,
                                                     VarPickMethod pickMethod,
                                                     List<VarMeasure> measures) {
        validateInputs(dimensionGroups, riskClasses, quantile, measures);
        VarDimensionGroup totalGroup = requireTotalGroup(dimensionGroups);
        VarQuantileResult totalAllQuantile = calcSingleQuantile(
                toScenarioRows(totalGroup.getScenarioPnls(), VarPnlColumns.ALL_PNL),
                quantile,
                pickMethod);
        VarTailScenarioContext totalAllTailContext = measures.contains(VarMeasure.ES)
                ? buildTailScenarioContext(totalGroup, quantile, VarPnlColumns.ALL_PNL)
                : null;

        List<VarDimensionMeasureResult> dimensionResults = new ArrayList<VarDimensionMeasureResult>();
        for (VarDimensionGroup dimensionGroup : dimensionGroups) {
            List<VarRiskClassMeasureResult> riskClassResults = new ArrayList<VarRiskClassMeasureResult>();
            for (String riskClass : riskClasses) {
                String pnlColumn = VarPnlColumns.riskClassToPnlColumn(riskClass);
                BigDecimal es = measures.contains(VarMeasure.ES)
                        ? calculateEsByBaseOut(totalAllTailContext, dimensionGroup, pnlColumn)
                        : BigDecimal.ZERO;
                VarRiskClassMeasureResult result = buildTotalSortedRiskClassResult(
                        dimensionGroup,
                        totalAllQuantile,
                        riskClass,
                        es,
                        pickMethod,
                        measures);
                fillMeasureValues(result, totalGroup, dimensionGroup, totalAllQuantile, quantile, pnlColumn, pickMethod, measures);
                riskClassResults.add(result);
            }
            dimensionResults.add(new VarDimensionMeasureResult(
                    dimensionGroup.getGroupType(),
                    dimensionGroup.getGroupValue(),
                    dimensionGroup.getBaseValuationCny(),
                    riskClassResults));
        }
        return dimensionResults;
    }

    private void fillMeasureValues(VarRiskClassMeasureResult result,
                                   VarDimensionGroup totalGroup,
                                   VarDimensionGroup currentGroup,
                                   VarQuantileResult totalAllQuantile,
                                   BigDecimal quantile,
                                   String adjustmentPnlColumn,
                                   VarPickMethod pickMethod,
                                   List<VarMeasure> measures) {
        if (measures.contains(VarMeasure.COMPONENT_VAR)) {
            result.setComponentVar(calculateComponentVar(totalAllQuantile, currentGroup, adjustmentPnlColumn, pickMethod));
        }
        if (measures.contains(VarMeasure.MARGINAL_VAR)) {
            result.setMarginalVar(calculateMarginalVar(totalGroup, currentGroup, totalAllQuantile, quantile,
                    VarPnlColumns.ALL_PNL, adjustmentPnlColumn, pickMethod));
        }
        if (measures.contains(VarMeasure.INCREMENTAL_VAR)) {
            result.setIncrementalVar(calculateIncrementalVar(totalGroup, currentGroup, totalAllQuantile, quantile,
                    VarPnlColumns.ALL_PNL, adjustmentPnlColumn, pickMethod));
        }
        applySelectedMeasures(result, measures);
    }
}
