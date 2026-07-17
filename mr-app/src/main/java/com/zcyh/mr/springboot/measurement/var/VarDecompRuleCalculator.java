package com.zcyh.mr.springboot.measurement.var;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.var.VarDimensionGroup;
import com.zcyh.mr.var.VarDimensionMeasureResult;
import com.zcyh.mr.var.VarMeasure;
import com.zcyh.mr.var.VarRiskClassDecompCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VaR 风险大类分解规则计算器。
 */
final class VarDecompRuleCalculator {
    private final ExecutorService batchExecutor;
    private final VarCalculationResultMapper resultMapper;
    private final VarRiskClassDecompCalculator calculator = new VarRiskClassDecompCalculator();

    VarDecompRuleCalculator(ExecutorService batchExecutor,
                            VarCalculationResultMapper resultMapper) {
        this.batchExecutor = batchExecutor;
        this.resultMapper = resultMapper;
    }

    List<VarQuantileRuleResults> calculate(VarRuleConfig ruleConfig,
                                           Map<String, List<VarDimensionGroup>> scenarioDimensionGroups,
                                           List<BigDecimal> quantiles,
                                           List<VarMeasure> measures,
                                           boolean includeDetail,
                                           String requestId,
                                           AtomicInteger detailCacheCount) {
        List<Future<VarQuantileRuleResults>> futures = new ArrayList<>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            final BigDecimal quantile = quantiles.get(qIndex);
            futures.add(batchExecutor.submit(() -> new VarQuantileRuleResults(
                    quantile,
                    calculateQuantile(
                            ruleConfig,
                            scenarioDimensionGroups,
                            quantile,
                            measures,
                            includeDetail,
                            requestId,
                            detailCacheCount))));
        }
        List<VarQuantileRuleResults> results = new ArrayList<>();
        for (Future<VarQuantileRuleResults> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("VaR 分位点并行计算被中断", ex);
            } catch (ExecutionException ex) {
                throw new IllegalStateException("VaR 分位点并行计算失败", ex.getCause());
            }
        }
        results.sort(Comparator.comparing(item -> item.quantile));
        return results;
    }

    private List<JSONObject> calculateQuantile(VarRuleConfig ruleConfig,
                                               Map<String, List<VarDimensionGroup>> scenarioDimensionGroups,
                                               BigDecimal quantile,
                                               List<VarMeasure> measures,
                                               boolean includeDetail,
                                               String requestId,
                                               AtomicInteger detailCacheCount) {
        List<JSONObject> ruleResults = new ArrayList<>();
        for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
            String scenarioId = entry.getKey();
            List<VarDimensionGroup> dimensionGroups = entry.getValue();
            if (dimensionGroups == null || dimensionGroups.isEmpty()) {
                continue;
            }
            JSONObject ruleResult = resultMapper.buildRuleResultHeader(ruleConfig, scenarioId, dimensionGroups);
            List<VarDimensionMeasureResult> measureResults = calculator.calculate(
                    dimensionGroups,
                    ruleConfig.riskClasses,
                    quantile,
                    ruleConfig.pickMethod,
                    measures);
            ruleResult.put("dimension_results", resultMapper.toDimensionResultArray(
                    measureResults,
                    dimensionGroups,
                    includeDetail,
                    requestId,
                    quantile,
                    ruleConfig.rule.getRuleId(),
                    scenarioId,
                    detailCacheCount));
            ruleResults.add(ruleResult);
        }
        return ruleResults;
    }
}
