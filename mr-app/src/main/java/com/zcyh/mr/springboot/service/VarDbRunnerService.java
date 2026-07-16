package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.out.cache.VarDetailCacheService;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.db.VarInputQueryService;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.model.VarCalculation;
import com.zcyh.mr.springboot.model.VarSummaryRequest;
import com.zcyh.mr.var.VarDimensionGroup;
import com.zcyh.mr.var.VarMeasure;
import com.zcyh.mr.var.VarPnlColumns;
import com.zcyh.mr.var.VarScenarioKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zcyh.mr.springboot.service.VarRequestParser.trimToNull;

/**
 * VaR 数据库输入执行服务。
 */
@Service
public class VarDbRunnerService {
    private static final String TOTAL = "TOTAL";

    private final VarInputQueryService inputQueryService;
    private final DimensionAggregationService dimensionAggregationService;
    private final VarRuleResolver ruleResolver;
    private final VarDetailWriter detailWriter;
    private final VarNormalRuleCalculator normalRuleCalculator;
    private final VarDecompRuleCalculator decompRuleCalculator;

    public VarDbRunnerService(VarInputQueryService inputQueryService,
                              DimensionAggregationService dimensionAggregationService,
                              ObjectProvider<VarDetailCacheService> varDetailCacheServiceProvider,
                              @Qualifier("frtbBatchExecutor") ExecutorService batchExecutor) {
        this.inputQueryService = inputQueryService;
        this.dimensionAggregationService = dimensionAggregationService;
        this.ruleResolver = new VarRuleResolver(inputQueryService, dimensionAggregationService);
        VarDetailCacheService cacheService = varDetailCacheServiceProvider == null
                ? null
                : varDetailCacheServiceProvider.getIfAvailable();
        this.detailWriter = new VarDetailWriter(cacheService);
        VarCalculationResultMapper resultMapper = new VarCalculationResultMapper(detailWriter);
        this.normalRuleCalculator = new VarNormalRuleCalculator(batchExecutor, resultMapper);
        this.decompRuleCalculator = new VarDecompRuleCalculator(batchExecutor, resultMapper);
    }

    public JSONObject calculateConfigured(VarSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        return calculate(
                request.getBatchId(),
                request.getDataDate(),
                ruleResolver.loadConfiguredCalculations(request.getCalculations()),
                false,
                null);
    }

    public JSONObject calculateTrial(String batchId,
                                     String dataDate,
                                     List<VarCalculation> calculations,
                                     boolean includeDetail,
                                     String requestId) {
        return calculate(
                batchId,
                dataDate,
                ruleResolver.parseExplicitCalculations(calculations),
                includeDetail,
                requestId);
    }

    private JSONObject calculate(String batchId,
                                 String dataDate,
                                 List<VarResolvedCalculation> calculations,
                                 boolean includeDetail,
                                 String requestId) {
        boolean includeDetailRequested = includeDetail;
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        if (calculations == null || calculations.isEmpty()) {
            throw new IllegalArgumentException("calculations 不能为空");
        }
        if (includeDetail && !detailWriter.isAvailable()) {
            throw new IllegalStateException("include_detail=true 但 Redis 缓存服务未启用");
        }

        AtomicInteger detailCacheCount = new AtomicInteger(0);

        List<BigDecimal> quantiles = collectQuantiles(calculations);
        List<VarMeasure> measures = collectMeasures(calculations);
        List<JSONObject> quantileGroups = VarResultAssembler.initQuantileGroups(quantiles);
        Map<String, JSONObject> quantileGroupByValue = indexQuantileGroups(quantileGroups);
        for (VarResolvedCalculation calculation : calculations) {
            VarRuleConfig ruleConfig = calculation.ruleConfig;
            if (!ruleConfig.enabled) {
                continue;
            }

            List<VarInputQueryService.RuleScenarioPnlRow> rows =
                    inputQueryService.queryRuleScenarioPnlRows(
                            batchId, dataDate, calculation.scenarioId, ruleConfig.rule);
            Map<String, List<VarDimensionGroup>> scenarioDimensionGroups = buildScenarioDimensionGroups(
                    ruleConfig.rule, calculation.scenarioId, rows);
            if (scenarioDimensionGroups.isEmpty()) {
                continue;
            }

            List<VarQuantileRuleResults> ruleResultsByQuantile = ruleConfig.decompMode
                    ? decompRuleCalculator.calculate(
                            ruleConfig,
                            scenarioDimensionGroups,
                            ruleConfig.quantiles,
                            ruleConfig.measures,
                            includeDetail,
                            requestId,
                            detailCacheCount)
                    : normalRuleCalculator.calculate(
                            ruleConfig,
                            scenarioDimensionGroups,
                            ruleConfig.quantiles,
                            ruleConfig.measures,
                            includeDetail,
                            requestId,
                            detailCacheCount);
            for (VarQuantileRuleResults quantileResult : ruleResultsByQuantile) {
                JSONObject quantileGroup = quantileGroupByValue.get(quantileKey(quantileResult.quantile));
                if (quantileGroup == null) {
                    throw new IllegalStateException("VaR 分位点结果不在规则配置中: " + quantileResult.quantile);
                }
                JSONArray ruleResults = quantileGroup.getJSONArray("rule_results");
                for (JSONObject item : quantileResult.ruleResults) {
                    ruleResults.add(item);
                }
            }
        }

        Long detailCacheTtlSeconds = detailWriter.getTtlSeconds();
        return JSON.parseObject(VarResultAssembler.assemble(
                batchId,
                dataDate,
                quantiles,
                measures,
                quantileGroups,
                includeDetail,
                includeDetailRequested,
                requestId,
                detailCacheCount.get(),
                detailCacheTtlSeconds));
    }

    private Map<String, List<VarDimensionGroup>> buildScenarioDimensionGroups(
            AggregationRule rule,
            String expectedScenarioId,
            List<VarInputQueryService.RuleScenarioPnlRow> rows) {
        Map<String, Map<String, VarDimensionGroup>> groupedByScenario = new LinkedHashMap<String, Map<String, VarDimensionGroup>>();
        for (VarInputQueryService.RuleScenarioPnlRow row : rows) {
            String scenarioId = trimToNull(row.getScenarioId());
            if (scenarioId == null) {
                throw new IllegalStateException("VaR 情景损益明细缺少 SCENARIO_ID");
            }
            if (!scenarioId.equals(expectedScenarioId)) {
                throw new IllegalStateException("VaR 情景损益明细与请求不一致: expected="
                        + expectedScenarioId + ", actual=" + scenarioId);
            }
            Map<String, VarDimensionGroup> groups = groupedByScenario.get(scenarioId);
            if (groups == null) {
                groups = new LinkedHashMap<String, VarDimensionGroup>();
                groupedByScenario.put(scenarioId, groups);
            }
            List<String> pathValues = new ArrayList<String>();
            for (String level : rule.getBuildOrder()) {
                String groupType;
                String groupValue;
                if (TOTAL.equalsIgnoreCase(level)) {
                    groupType = TOTAL;
                    groupValue = TOTAL;
                } else {
                    String levelValue = dimensionAggregationService.normalizeDimensionValue(row.getGroupValues().get(level));
                    pathValues.add(levelValue);
                    groupType = level;
                    groupValue = dimensionAggregationService.buildGroupValue(pathValues);
                }

                String key = groupType + "|" + groupValue;
                VarDimensionGroup groupData = groups.get(key);
                if (groupData == null) {
                    groupData = new VarDimensionGroup(groupType, groupValue);
                    groups.put(key, groupData);
                }
                groupData.accumulate(
                        new VarScenarioKey(row.getScenarioId(), row.getSubScenarioId(), row.getScenarioName()),
                        row.getAllPnl(),
                        row.getIrPnl(),
                        row.getFxPnl(),
                        row.getEqPnl(),
                        row.getCommPnl(),
                        row.getBaseValuationCny());
            }
        }
        Map<String, List<VarDimensionGroup>> result = new LinkedHashMap<String, List<VarDimensionGroup>>();
        for (Map.Entry<String, Map<String, VarDimensionGroup>> entry : groupedByScenario.entrySet()) {
            result.put(entry.getKey(), new ArrayList<VarDimensionGroup>(entry.getValue().values()));
        }
        return result;
    }

    private static List<BigDecimal> collectQuantiles(List<VarResolvedCalculation> calculations) {
        Map<String, BigDecimal> values = new LinkedHashMap<String, BigDecimal>();
        for (VarResolvedCalculation calculation : calculations) {
            for (BigDecimal quantile : calculation.ruleConfig.quantiles) {
                values.putIfAbsent(quantileKey(quantile), quantile);
            }
        }
        return new ArrayList<BigDecimal>(values.values());
    }

    private static List<VarMeasure> collectMeasures(List<VarResolvedCalculation> calculations) {
        Set<VarMeasure> values = new LinkedHashSet<VarMeasure>();
        for (VarResolvedCalculation calculation : calculations) {
            values.addAll(calculation.ruleConfig.measures);
        }
        return new ArrayList<VarMeasure>(values);
    }

    private static Map<String, JSONObject> indexQuantileGroups(List<JSONObject> quantileGroups) {
        Map<String, JSONObject> indexed = new LinkedHashMap<String, JSONObject>();
        for (JSONObject quantileGroup : quantileGroups) {
            indexed.put(quantileGroup.getString("quantile"), quantileGroup);
        }
        return indexed;
    }

    private static String quantileKey(BigDecimal quantile) {
        return quantile.stripTrailingZeros().toPlainString();
    }

    /**
     * 返回本次 VaR 请求解析后的规则快照，用于结果落库后按规则还原下钻范围。
     */
    public JSONArray resolveRuleSnapshots(List<String> ruleIds) {
        JSONArray snapshots = new JSONArray();
        snapshots.addAll(ruleResolver.loadRuleSnapshots(ruleIds));
        return snapshots;
    }
    static boolean isRiskClassDecomp(String decompType, String riskClass) {
        return VarRequestParser.isRiskClassDecomp(decompType, riskClass);
    }

    static List<BigDecimal> parseQuantiles(Object value) {
        return VarRequestParser.parseQuantiles(value);
    }

    static List<VarMeasure> parseMeasures(Object value) {
        return VarRequestParser.parseMeasures(value);
    }

    static String parseVarPick(String value) {
        return VarRequestParser.parseVarPick(value);
    }

    static List<String> parseRiskClasses(String riskClassRaw) {
        String safe = trimToNull(riskClassRaw);
        if (safe == null) {
            throw new IllegalArgumentException("risk_class 不能为空");
        }
        List<String> riskClasses = VarRequestParser.parseRiskClassesOptional(safe);
        if (riskClasses.isEmpty()) {
            throw new IllegalArgumentException("risk_class 不能为空");
        }
        return riskClasses;
    }

    static String riskClassToPnlColumn(String riskClass) {
        return VarPnlColumns.riskClassToPnlColumn(riskClass);
    }

}
