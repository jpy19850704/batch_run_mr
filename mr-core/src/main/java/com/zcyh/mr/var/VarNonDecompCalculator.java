package com.zcyh.mr.var;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 非风险分解 VaR 计量器。
 */
public class VarNonDecompCalculator extends VarMeasureCalculatorSupport {

    public List<VarDimensionMeasureResult> calculateRiskClass(List<VarDimensionGroup> dimensionGroups,
                                                              String riskClass,
                                                              BigDecimal quantile,
                                                              VarPickMethod pickMethod,
                                                              List<VarMeasure> measures) {
        validateInputs(dimensionGroups, Collections.singletonList(riskClass), quantile, measures);
        VarDimensionGroup totalGroup = requireTotalGroup(dimensionGroups);
        String pnlColumn = VarPnlColumns.riskClassToPnlColumn(riskClass);
        List<VarScenarioPnl> totalRows = toScenarioRows(totalGroup.getScenarioPnls(), pnlColumn);
        VarQuantileResult totalQuantile = calcSingleQuantile(totalRows, quantile, pickMethod);
        BigDecimal totalEs = measures.contains(VarMeasure.ES)
                ? varCalculator.calculateEsByOut(totalRows, quantile)
                : BigDecimal.ZERO;

        List<VarDimensionMeasureResult> dimensionResults = new ArrayList<VarDimensionMeasureResult>();
        for (VarDimensionGroup dimensionGroup : dimensionGroups) {
            VarRiskClassMeasureResult riskClassResult;
            if (isTotalGroup(dimensionGroup)) {
                riskClassResult = buildIndependentRiskClassResult(
                        totalQuantile,
                        riskClass,
                        totalEs,
                        pnlColumn,
                        measures);
            } else {
                List<VarScenarioPnl> scenarioRows = toScenarioRows(dimensionGroup.getScenarioPnls(), pnlColumn);
                VarQuantileResult groupQuantile = calcSingleQuantile(scenarioRows, quantile, pickMethod);
                BigDecimal es = measures.contains(VarMeasure.ES)
                        ? varCalculator.calculateEsByOut(scenarioRows, quantile)
                        : BigDecimal.ZERO;
                riskClassResult = buildIndependentRiskClassResult(
                        groupQuantile,
                        riskClass,
                        es,
                        pnlColumn,
                        measures);
            }
            fillMeasureValues(riskClassResult, totalGroup, dimensionGroup, totalQuantile,
                    quantile, pnlColumn, pnlColumn, pickMethod, measures);
            dimensionResults.add(new VarDimensionMeasureResult(
                    dimensionGroup.getGroupType(),
                    dimensionGroup.getGroupValue(),
                    dimensionGroup.getBaseValuationCny(),
                    Collections.singletonList(riskClassResult)));
        }
        return dimensionResults;
    }

    private void fillMeasureValues(VarRiskClassMeasureResult result,
                                   VarDimensionGroup totalGroup,
                                   VarDimensionGroup currentGroup,
                                   VarQuantileResult totalQuantile,
                                   BigDecimal quantile,
                                   String totalPnlColumn,
                                   String adjustmentPnlColumn,
                                   VarPickMethod pickMethod,
                                   List<VarMeasure> measures) {
        if (measures.contains(VarMeasure.COMPONENT_VAR)) {
            result.setComponentVar(calculateComponentVar(totalQuantile, currentGroup, adjustmentPnlColumn, pickMethod));
        }
        if (measures.contains(VarMeasure.MARGINAL_VAR)) {
            result.setMarginalVar(calculateMarginalVar(totalGroup, currentGroup, totalQuantile, quantile,
                    totalPnlColumn, adjustmentPnlColumn, pickMethod));
        }
        if (measures.contains(VarMeasure.INCREMENTAL_VAR)) {
            result.setIncrementalVar(calculateIncrementalVar(totalGroup, currentGroup, totalQuantile, quantile,
                    totalPnlColumn, adjustmentPnlColumn, pickMethod));
        }
        applySelectedMeasures(result, measures);
    }
}
