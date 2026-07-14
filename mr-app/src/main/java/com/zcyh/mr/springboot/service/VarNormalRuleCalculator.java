package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.var.VarDimensionGroup;
import com.zcyh.mr.var.VarDimensionMeasureResult;
import com.zcyh.mr.var.VarMeasure;
import com.zcyh.mr.var.VarNonDecompCalculator;
import com.zcyh.mr.var.VarPickMethod;
import com.zcyh.mr.var.VarRiskClassMeasureResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 普通 VaR 规则计算器。
 */
final class VarNormalRuleCalculator {
    private final ExecutorService batchExecutor;
    private final VarCalculationResultMapper resultMapper;
    private final VarNonDecompCalculator calculator = new VarNonDecompCalculator();

    VarNormalRuleCalculator(ExecutorService batchExecutor,
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
        List<Future<NormalRiskClassResults>> futures = submitTasks(
                ruleConfig,
                scenarioDimensionGroups,
                quantiles,
                measures);
        Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> riskResultsByQuantile =
                initRiskResultBuckets(quantiles, scenarioDimensionGroups);
        mergeTaskResults(futures, riskResultsByQuantile);
        return buildQuantileResults(
                ruleConfig,
                scenarioDimensionGroups,
                quantiles,
                includeDetail,
                requestId,
                detailCacheCount,
                riskResultsByQuantile);
    }

    private List<Future<NormalRiskClassResults>> submitTasks(
            VarRuleConfig ruleConfig,
            Map<String, List<VarDimensionGroup>> scenarioDimensionGroups,
            List<BigDecimal> quantiles,
            List<VarMeasure> measures) {
        List<Future<NormalRiskClassResults>> futures = new ArrayList<>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            final int quantileIndex = qIndex;
            final BigDecimal quantile = quantiles.get(qIndex);
            for (String riskClass : ruleConfig.riskClasses) {
                final String currentRiskClass = riskClass;
                futures.add(batchExecutor.submit(() -> calculateRiskClass(
                        quantileIndex,
                        quantile,
                        currentRiskClass,
                        ruleConfig.pickMethod,
                        measures,
                        scenarioDimensionGroups)));
            }
        }
        return futures;
    }

    private NormalRiskClassResults calculateRiskClass(
            int quantileIndex,
            BigDecimal quantile,
            String riskClass,
            VarPickMethod pickMethod,
            List<VarMeasure> measures,
            Map<String, List<VarDimensionGroup>> scenarioDimensionGroups) {
        List<ScenarioDimensionMeasureResults> scenarioResults = new ArrayList<>();
        for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
            List<VarDimensionGroup> dimensionGroups = entry.getValue();
            if (dimensionGroups == null || dimensionGroups.isEmpty()) {
                continue;
            }
            scenarioResults.add(new ScenarioDimensionMeasureResults(
                    entry.getKey(),
                    calculator.calculateRiskClass(
                            dimensionGroups,
                            riskClass,
                            quantile,
                            pickMethod,
                            measures)));
        }
        return new NormalRiskClassResults(quantileIndex, scenarioResults);
    }

    private void mergeTaskResults(
            List<Future<NormalRiskClassResults>> futures,
            Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> riskResultsByQuantile) {
        for (Future<NormalRiskClassResults> future : futures) {
            NormalRiskClassResults taskResult = await(future);
            Map<String, Map<String, List<VarRiskClassMeasureResult>>> riskResultsByScenario =
                    riskResultsByQuantile.get(taskResult.quantileIndex);
            for (ScenarioDimensionMeasureResults scenarioResult : taskResult.scenarioResults) {
                Map<String, List<VarRiskClassMeasureResult>> riskResultsByGroup =
                        riskResultsByScenario.get(scenarioResult.scenarioId);
                if (riskResultsByGroup == null) {
                    throw new IllegalStateException("VaR 情景结果不在输入情景中: " + scenarioResult.scenarioId);
                }
                for (VarDimensionMeasureResult dimensionResult : scenarioResult.dimensionResults) {
                    String key = VarCalculationResultMapper.dimensionKey(
                            dimensionResult.getGroupType(),
                            dimensionResult.getGroupValue());
                    List<VarRiskClassMeasureResult> riskResults = riskResultsByGroup.get(key);
                    if (riskResults == null) {
                        throw new IllegalStateException("VaR 维度结果不在输入维度中: " + key);
                    }
                    riskResults.addAll(dimensionResult.getRiskClassResults());
                }
            }
        }
    }

    private List<VarQuantileRuleResults> buildQuantileResults(
            VarRuleConfig ruleConfig,
            Map<String, List<VarDimensionGroup>> scenarioDimensionGroups,
            List<BigDecimal> quantiles,
            boolean includeDetail,
            String requestId,
            AtomicInteger detailCacheCount,
            Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> riskResultsByQuantile) {
        List<VarQuantileRuleResults> quantileResults = new ArrayList<>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            BigDecimal quantile = quantiles.get(qIndex);
            List<JSONObject> ruleResults = new ArrayList<>();
            Map<String, Map<String, List<VarRiskClassMeasureResult>>> riskResultsByScenario =
                    riskResultsByQuantile.get(qIndex);
            for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
                String scenarioId = entry.getKey();
                List<VarDimensionGroup> dimensionGroups = entry.getValue();
                if (dimensionGroups == null || dimensionGroups.isEmpty()) {
                    continue;
                }
                List<VarDimensionMeasureResult> measureResults = buildDimensionMeasureResults(
                        dimensionGroups,
                        riskResultsByScenario.get(scenarioId));
                JSONObject ruleResult = resultMapper.buildRuleResultHeader(ruleConfig, scenarioId, dimensionGroups);
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
            quantileResults.add(new VarQuantileRuleResults(qIndex, ruleResults));
        }
        return quantileResults;
    }

    private static Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> initRiskResultBuckets(
            List<BigDecimal> quantiles,
            Map<String, List<VarDimensionGroup>> scenarioDimensionGroups) {
        Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> riskResultsByQuantile =
                new LinkedHashMap<>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            Map<String, Map<String, List<VarRiskClassMeasureResult>>> riskResultsByScenario =
                    new LinkedHashMap<>();
            for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
                Map<String, List<VarRiskClassMeasureResult>> riskResultsByGroup = new LinkedHashMap<>();
                List<VarDimensionGroup> dimensionGroups = entry.getValue();
                if (dimensionGroups != null) {
                    for (VarDimensionGroup dimensionGroup : dimensionGroups) {
                        riskResultsByGroup.put(
                                VarCalculationResultMapper.dimensionKey(
                                        dimensionGroup.getGroupType(),
                                        dimensionGroup.getGroupValue()),
                                new ArrayList<>());
                    }
                }
                riskResultsByScenario.put(entry.getKey(), riskResultsByGroup);
            }
            riskResultsByQuantile.put(qIndex, riskResultsByScenario);
        }
        return riskResultsByQuantile;
    }

    private static List<VarDimensionMeasureResult> buildDimensionMeasureResults(
            List<VarDimensionGroup> dimensionGroups,
            Map<String, List<VarRiskClassMeasureResult>> riskResultsByGroup) {
        List<VarDimensionMeasureResult> results = new ArrayList<>();
        for (VarDimensionGroup dimensionGroup : dimensionGroups) {
            String key = VarCalculationResultMapper.dimensionKey(
                    dimensionGroup.getGroupType(),
                    dimensionGroup.getGroupValue());
            results.add(new VarDimensionMeasureResult(
                    dimensionGroup.getGroupType(),
                    dimensionGroup.getGroupValue(),
                    dimensionGroup.getBaseValuationCny(),
                    riskResultsByGroup.get(key)));
        }
        return results;
    }

    private static NormalRiskClassResults await(Future<NormalRiskClassResults> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VaR 风险大类并行计算被中断", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("VaR 风险大类并行计算失败", ex.getCause());
        }
    }

    private static final class NormalRiskClassResults {
        private final int quantileIndex;
        private final List<ScenarioDimensionMeasureResults> scenarioResults;

        private NormalRiskClassResults(int quantileIndex,
                                       List<ScenarioDimensionMeasureResults> scenarioResults) {
            this.quantileIndex = quantileIndex;
            this.scenarioResults = scenarioResults == null ? new ArrayList<>() : scenarioResults;
        }
    }

    private static final class ScenarioDimensionMeasureResults {
        private final String scenarioId;
        private final List<VarDimensionMeasureResult> dimensionResults;

        private ScenarioDimensionMeasureResults(String scenarioId,
                                                List<VarDimensionMeasureResult> dimensionResults) {
            this.scenarioId = scenarioId;
            this.dimensionResults = dimensionResults == null ? new ArrayList<>() : dimensionResults;
        }
    }
}
