package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.var.VarDimensionGroup;
import com.zcyh.mr.var.VarDimensionMeasureResult;
import com.zcyh.mr.var.VarMeasure;
import com.zcyh.mr.var.VarNonDecompCalculator;
import com.zcyh.mr.var.VarPickMethod;
import com.zcyh.mr.var.VarPnlColumns;
import com.zcyh.mr.var.VarRiskClassDecompCalculator;
import com.zcyh.mr.var.VarRiskClassMeasureResult;
import com.zcyh.mr.var.VarScenarioKey;
import com.zcyh.mr.var.VarScenarioPnlAggregate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VaR 数据库输入执行服务。
 */
@Service
public class VarDbRunnerService {
    private static final String VAR_PICK_IN = "in";
    private static final String VAR_PICK_OUT = "out";
    private static final String VAR_PICK_AVERAGE = "average";
    private static final String TOTAL = "TOTAL";
    private static final String VAR_RULE_TYPE = "VAR";
    private static final String DEFAULT_SUM_FIELD = VarPnlColumns.ALL_PNL;
    private static final String VAR_DETAIL_FETCH_API = "/api/engine/var/detail";

    private final VarInputQueryService inputQueryService;
    private final DimensionAggregationService dimensionAggregationService;
    private final VarDetailCacheService varDetailCacheService;
    private final ExecutorService batchExecutor;
    private final VarNonDecompCalculator nonDecompCalculator = new VarNonDecompCalculator();
    private final VarRiskClassDecompCalculator riskClassDecompCalculator = new VarRiskClassDecompCalculator();

    public VarDbRunnerService(VarInputQueryService inputQueryService,
                              DimensionAggregationService dimensionAggregationService,
                              ObjectProvider<VarDetailCacheService> varDetailCacheServiceProvider,
                              @Qualifier("frtbBatchExecutor") ExecutorService batchExecutor) {
        this.inputQueryService = inputQueryService;
        this.dimensionAggregationService = dimensionAggregationService;
        this.varDetailCacheService = varDetailCacheServiceProvider == null ? null : varDetailCacheServiceProvider.getIfAvailable();
        this.batchExecutor = batchExecutor;
    }

    public String calculateByInline(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        normalizeRuleIdRequest(req);
        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");
        List<BigDecimal> quantiles = parseQuantiles(req.get("quantiles"));
        List<VarMeasure> measures = parseMeasures(req.get("measure"));
        List<VarRuleConfig> rules = parseRules(req);
        boolean includeDetailRequested = readBoolean(req, "include_detail", false);
        boolean includeDetail = includeDetailRequested;
        String requestId = readString(req, "request_id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules is required");
        }
        if (includeDetail && varDetailCacheService == null) {
            throw new IllegalStateException("include_detail=true 但 Redis 缓存服务未启用");
        }

        AtomicInteger detailCacheCount = new AtomicInteger(0);

        List<JSONObject> quantileGroups = initQuantileGroups(quantiles);
        for (VarRuleConfig ruleConfig : rules) {
            if (!ruleConfig.enabled) {
                continue;
            }

            List<VarInputQueryService.RuleScenarioPnlRow> rows =
                    inputQueryService.queryRuleScenarioPnlRows(batchId, dataDate, ruleConfig.rule);
            Map<String, List<VarDimensionGroup>> scenarioDimensionGroups = buildScenarioDimensionGroups(ruleConfig.rule, rows);
            if (scenarioDimensionGroups.isEmpty()) {
                continue;
            }

            List<QuantileRuleResults> ruleResultsByQuantile = ruleConfig.decompMode
                    ? calculateDecompRuleResultsForQuantiles(
                            ruleConfig,
                            scenarioDimensionGroups,
                            quantiles,
                            measures,
                            includeDetail,
                            requestId,
                            detailCacheCount)
                    : calculateNormalRuleResultsForQuantiles(
                            ruleConfig,
                            scenarioDimensionGroups,
                            quantiles,
                            measures,
                            includeDetail,
                            requestId,
                            detailCacheCount);
            for (QuantileRuleResults quantileResult : ruleResultsByQuantile) {
                JSONObject quantileGroup = quantileGroups.get(quantileResult.quantileIndex);
                JSONArray ruleResults = quantileGroup.getJSONArray("rule_results");
                for (JSONObject item : quantileResult.ruleResults) {
                    ruleResults.add(item);
                }
            }
        }

        JSONObject summaryFile = new JSONObject();
        summaryFile.put("batch_id", batchId);
        summaryFile.put("data_date", dataDate);
        summaryFile.put("quantiles", toQuantileArray(quantiles));
        summaryFile.put("measure", toMeasureArray(measures));
        summaryFile.put("quantile_groups", toJsonArray(quantileGroups));

        JSONObject detailFile = new JSONObject();
        detailFile.put("enabled", includeDetail);
        detailFile.put("requested", includeDetailRequested);
        detailFile.put("request_id", requestId);
        detailFile.put("fetch_api", VAR_DETAIL_FETCH_API);
        detailFile.put("cache_entries", detailCacheCount.get());
        if (varDetailCacheService != null) {
            detailFile.put("ttl_seconds", varDetailCacheService.getTtlSeconds());
        }

        JSONObject result = new JSONObject();
        result.put("request_id", requestId);
        result.put("summary_file", summaryFile);
        result.put("detail_file", detailFile);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private List<JSONObject> initQuantileGroups(List<BigDecimal> quantiles) {
        List<JSONObject> groups = new ArrayList<JSONObject>();
        for (BigDecimal quantile : quantiles) {
            JSONObject group = new JSONObject();
            group.put("quantile", quantile.stripTrailingZeros().toPlainString());
            group.put("rule_results", new JSONArray());
            groups.add(group);
        }
        return groups;
    }

    private List<QuantileRuleResults> calculateDecompRuleResultsForQuantiles(VarRuleConfig ruleConfig,
                                                                             Map<String, List<VarDimensionGroup>> scenarioDimensionGroups,
                                                                             List<BigDecimal> quantiles,
                                                                             List<VarMeasure> measures,
                                                                             boolean includeDetail,
                                                                             String requestId,
                                                                             AtomicInteger detailCacheCount) {
        List<Future<QuantileRuleResults>> futures = new ArrayList<Future<QuantileRuleResults>>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            final int quantileIndex = qIndex;
            final BigDecimal quantile = quantiles.get(qIndex);
            futures.add(batchExecutor.submit(() -> new QuantileRuleResults(
                    quantileIndex,
                    buildDecompRuleResultsForQuantile(
                            ruleConfig,
                            scenarioDimensionGroups,
                            quantile,
                            measures,
                            includeDetail,
                            requestId,
                            detailCacheCount))));
        }
        return awaitQuantileRuleResults(futures);
    }

    private List<JSONObject> buildDecompRuleResultsForQuantile(VarRuleConfig ruleConfig,
                                                               Map<String, List<VarDimensionGroup>> scenarioDimensionGroups,
                                                               BigDecimal quantile,
                                                               List<VarMeasure> measures,
                                                               boolean includeDetail,
                                                               String requestId,
                                                               AtomicInteger detailCacheCount) {
        List<JSONObject> ruleResults = new ArrayList<JSONObject>();
        for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
            String scenarioId = entry.getKey();
            List<VarDimensionGroup> dimensionGroups = entry.getValue();
            if (dimensionGroups == null || dimensionGroups.isEmpty()) {
                continue;
            }

            JSONObject ruleResult = new JSONObject();
            ruleResult.put("rule_id", ruleConfig.rule.getRuleId());
            ruleResult.put("rule_name", ruleConfig.rule.getRuleName());
            ruleResult.put("mode", ruleConfig.decompMode ? "decomp_risk_class" : "normal");
            ruleResult.put("scenario_id", scenarioId);
            ruleResult.put("selected_method", ruleConfig.pickMethod.code());
            ruleResult.put("sample_size", deriveSampleSize(dimensionGroups));

            List<VarDimensionMeasureResult> measureResults = riskClassDecompCalculator.calculate(
                    dimensionGroups,
                    ruleConfig.riskClasses,
                    quantile,
                    ruleConfig.pickMethod,
                    measures);
            JSONArray dimensionResults = toDimensionResultArray(
                    measureResults,
                    dimensionGroups,
                    includeDetail,
                    requestId,
                    quantile,
                    ruleConfig.rule.getRuleId(),
                    scenarioId,
                    detailCacheCount);
            ruleResult.put("dimension_results", dimensionResults);
            ruleResults.add(ruleResult);
        }
        return ruleResults;
    }

    private List<QuantileRuleResults> calculateNormalRuleResultsForQuantiles(VarRuleConfig ruleConfig,
                                                                             Map<String, List<VarDimensionGroup>> scenarioDimensionGroups,
                                                                             List<BigDecimal> quantiles,
                                                                             List<VarMeasure> measures,
                                                                             boolean includeDetail,
                                                                             String requestId,
                                                                             AtomicInteger detailCacheCount) {
        List<Future<NormalRiskClassResults>> futures = new ArrayList<Future<NormalRiskClassResults>>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            final int quantileIndex = qIndex;
            final BigDecimal quantile = quantiles.get(qIndex);
            for (String riskClass : ruleConfig.riskClasses) {
                final String currentRiskClass = riskClass;
                futures.add(batchExecutor.submit(() -> calculateNormalRiskClassResults(
                        quantileIndex,
                        quantile,
                        currentRiskClass,
                        ruleConfig.pickMethod,
                        measures,
                        scenarioDimensionGroups)));
            }
        }

        Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> riskResultsByQuantile =
                initNormalRiskResultBuckets(quantiles, scenarioDimensionGroups);
        for (Future<NormalRiskClassResults> future : futures) {
            NormalRiskClassResults taskResult = awaitNormalRiskClassResults(future);
            Map<String, Map<String, List<VarRiskClassMeasureResult>>> riskResultsByScenario =
                    riskResultsByQuantile.get(taskResult.quantileIndex);
            for (ScenarioDimensionMeasureResults scenarioResult : taskResult.scenarioResults) {
                Map<String, List<VarRiskClassMeasureResult>> riskResultsByGroup =
                        riskResultsByScenario.get(scenarioResult.scenarioId);
                if (riskResultsByGroup == null) {
                    throw new IllegalStateException("VaR 情景结果不在输入情景中: " + scenarioResult.scenarioId);
                }
                for (VarDimensionMeasureResult dimensionResult : scenarioResult.dimensionResults) {
                    String key = dimensionKey(dimensionResult.getGroupType(), dimensionResult.getGroupValue());
                    List<VarRiskClassMeasureResult> riskResults = riskResultsByGroup.get(key);
                    if (riskResults == null) {
                        throw new IllegalStateException("VaR 维度结果不在输入维度中: " + key);
                    }
                    riskResults.addAll(dimensionResult.getRiskClassResults());
                }
            }
        }

        List<QuantileRuleResults> quantileResults = new ArrayList<QuantileRuleResults>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            BigDecimal quantile = quantiles.get(qIndex);
            List<JSONObject> ruleResults = new ArrayList<JSONObject>();
            Map<String, Map<String, List<VarRiskClassMeasureResult>>> riskResultsByScenario =
                    riskResultsByQuantile.get(qIndex);
            for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
                String scenarioId = entry.getKey();
                List<VarDimensionGroup> dimensionGroups = entry.getValue();
                if (dimensionGroups == null || dimensionGroups.isEmpty()) {
                    continue;
                }
                List<VarDimensionMeasureResult> measureResults = buildNormalDimensionMeasureResults(
                        dimensionGroups,
                        riskResultsByScenario.get(scenarioId));
                JSONObject ruleResult = buildRuleResultHeader(ruleConfig, scenarioId, dimensionGroups);
                ruleResult.put("dimension_results", toDimensionResultArray(
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
            quantileResults.add(new QuantileRuleResults(qIndex, ruleResults));
        }
        return quantileResults;
    }

    private NormalRiskClassResults calculateNormalRiskClassResults(int quantileIndex,
                                                                   BigDecimal quantile,
                                                                   String riskClass,
                                                                   VarPickMethod pickMethod,
                                                                   List<VarMeasure> measures,
                                                                   Map<String, List<VarDimensionGroup>> scenarioDimensionGroups) {
        List<ScenarioDimensionMeasureResults> scenarioResults = new ArrayList<ScenarioDimensionMeasureResults>();
        for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
            List<VarDimensionGroup> dimensionGroups = entry.getValue();
            if (dimensionGroups == null || dimensionGroups.isEmpty()) {
                continue;
            }
            scenarioResults.add(new ScenarioDimensionMeasureResults(
                    entry.getKey(),
                    nonDecompCalculator.calculateRiskClass(
                            dimensionGroups,
                            riskClass,
                            quantile,
                            pickMethod,
                            measures)));
        }
        return new NormalRiskClassResults(quantileIndex, scenarioResults);
    }

    private Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> initNormalRiskResultBuckets(
            List<BigDecimal> quantiles,
            Map<String, List<VarDimensionGroup>> scenarioDimensionGroups) {
        Map<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>> riskResultsByQuantile =
                new LinkedHashMap<Integer, Map<String, Map<String, List<VarRiskClassMeasureResult>>>>();
        for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
            Map<String, Map<String, List<VarRiskClassMeasureResult>>> riskResultsByScenario =
                    new LinkedHashMap<String, Map<String, List<VarRiskClassMeasureResult>>>();
            for (Map.Entry<String, List<VarDimensionGroup>> entry : scenarioDimensionGroups.entrySet()) {
                Map<String, List<VarRiskClassMeasureResult>> riskResultsByGroup =
                        new LinkedHashMap<String, List<VarRiskClassMeasureResult>>();
                List<VarDimensionGroup> dimensionGroups = entry.getValue();
                if (dimensionGroups != null) {
                    for (VarDimensionGroup dimensionGroup : dimensionGroups) {
                        riskResultsByGroup.put(dimensionKey(dimensionGroup.getGroupType(), dimensionGroup.getGroupValue()),
                                new ArrayList<VarRiskClassMeasureResult>());
                    }
                }
                riskResultsByScenario.put(entry.getKey(), riskResultsByGroup);
            }
            riskResultsByQuantile.put(qIndex, riskResultsByScenario);
        }
        return riskResultsByQuantile;
    }

    private List<VarDimensionMeasureResult> buildNormalDimensionMeasureResults(
            List<VarDimensionGroup> dimensionGroups,
            Map<String, List<VarRiskClassMeasureResult>> riskResultsByGroup) {
        List<VarDimensionMeasureResult> results = new ArrayList<VarDimensionMeasureResult>();
        for (VarDimensionGroup dimensionGroup : dimensionGroups) {
            String key = dimensionKey(dimensionGroup.getGroupType(), dimensionGroup.getGroupValue());
            results.add(new VarDimensionMeasureResult(
                    dimensionGroup.getGroupType(),
                    dimensionGroup.getGroupValue(),
                    dimensionGroup.getBaseValuationCny(),
                    riskResultsByGroup.get(key)));
        }
        return results;
    }

    private List<QuantileRuleResults> awaitQuantileRuleResults(List<Future<QuantileRuleResults>> futures) {
        List<QuantileRuleResults> results = new ArrayList<QuantileRuleResults>();
        for (Future<QuantileRuleResults> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("VaR 分位点并行计算被中断", ex);
            } catch (ExecutionException ex) {
                throw new IllegalStateException("VaR 分位点并行计算失败", ex.getCause());
            }
        }
        results.sort(Comparator.comparingInt(item -> item.quantileIndex));
        return results;
    }

    private NormalRiskClassResults awaitNormalRiskClassResults(Future<NormalRiskClassResults> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VaR 风险大类并行计算被中断", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("VaR 风险大类并行计算失败", ex.getCause());
        }
    }

    private JSONObject buildRuleResultHeader(VarRuleConfig ruleConfig,
                                             String scenarioId,
                                             List<VarDimensionGroup> dimensionGroups) {
        JSONObject ruleResult = new JSONObject();
        ruleResult.put("rule_id", ruleConfig.rule.getRuleId());
        ruleResult.put("rule_name", ruleConfig.rule.getRuleName());
        ruleResult.put("mode", ruleConfig.decompMode ? "decomp_risk_class" : "normal");
        ruleResult.put("scenario_id", scenarioId);
        ruleResult.put("selected_method", ruleConfig.pickMethod.code());
        ruleResult.put("sample_size", deriveSampleSize(dimensionGroups));
        return ruleResult;
    }

    private JSONArray toDimensionResultArray(List<VarDimensionMeasureResult> measureResults,
                                             List<VarDimensionGroup> dimensionGroups,
                                             boolean includeDetail,
                                             String requestId,
                                             BigDecimal quantile,
                                             String ruleId,
                                             String scenarioId,
                                             AtomicInteger detailCacheCount) {
        Map<String, VarDimensionGroup> dimensionGroupByKey = new LinkedHashMap<String, VarDimensionGroup>();
        for (VarDimensionGroup dimensionGroup : dimensionGroups) {
            dimensionGroupByKey.put(dimensionKey(dimensionGroup.getGroupType(), dimensionGroup.getGroupValue()), dimensionGroup);
        }

        JSONArray dimensionResults = new JSONArray();
        for (VarDimensionMeasureResult measureResult : measureResults) {
            String key = dimensionKey(measureResult.getGroupType(), measureResult.getGroupValue());
            VarDimensionGroup dimensionGroup = dimensionGroupByKey.get(key);
            if (dimensionGroup == null) {
                throw new IllegalStateException("VaR 维度结果不在输入维度中: " + key);
            }
            if (includeDetail) {
                JSONObject detailPayload = buildDimensionDetailFile(
                        requestId,
                        quantile,
                        ruleId,
                        scenarioId,
                        dimensionGroup);
                boolean cached = cacheDimensionDetailPayload(
                        requestId,
                        quantile,
                        ruleId,
                        scenarioId,
                        measureResult.getGroupType(),
                        measureResult.getGroupValue(),
                        detailPayload);
                if (cached) {
                    detailCacheCount.incrementAndGet();
                }
            }
            dimensionResults.add(toDimensionResultJson(measureResult));
        }
        return dimensionResults;
    }

    private JSONObject toDimensionResultJson(VarDimensionMeasureResult measureResult) {
        JSONObject dimensionResult = new JSONObject();
        dimensionResult.put("group_type", measureResult.getGroupType());
        dimensionResult.put("group_value", measureResult.getGroupValue());
        dimensionResult.put("base_valuation_cny", measureResult.getBaseValuationCny().stripTrailingZeros().toPlainString());

        JSONArray riskClassResults = new JSONArray();
        for (VarRiskClassMeasureResult riskClassResult : measureResult.getRiskClassResults()) {
            riskClassResults.add(toRiskClassResultJson(riskClassResult));
        }
        dimensionResult.put("risk_class_results", riskClassResults);
        return dimensionResult;
    }

    private JSONObject toRiskClassResultJson(VarRiskClassMeasureResult result) {
        JSONObject item = new JSONObject();
        item.put("risk_class", result.getRiskClass());
        item.put("rank_in", result.getRankIn());
        item.put("rank_out", result.getRankOut());
        item.put("subscenario_id_in", result.getSubScenarioIdIn());
        item.put("pnl_in", result.getPnlIn());
        item.put("var_in", result.getVarIn());
        item.put("subscenario_id_out", result.getSubScenarioIdOut());
        item.put("pnl_out", result.getPnlOut());
        item.put("var_out", result.getVarOut());
        item.put("sort_pnl_field", result.getSortPnlField());
        if (result.isIncludeSelectedScenarioId()) {
            item.put("selected_scenario_id", result.getSelectedScenarioId());
        }
        item.put("var", result.getVar());
        item.put("es", result.getEs());
        item.put("component_var", result.getComponentVar());
        item.put("marginal_var", result.getMarginalVar());
        item.put("incremental_var", result.getIncrementalVar());
        return item;
    }

    private boolean cacheDimensionDetailPayload(String requestId,
                                                BigDecimal quantile,
                                                String ruleId,
                                                String scenarioId,
                                                String groupType,
                                                String groupValue,
                                                JSONObject detail) {
        if (varDetailCacheService == null || detail == null) {
            return false;
        }
        return varDetailCacheService.putDimensionDetail(
                requestId,
                quantile.stripTrailingZeros().toPlainString(),
                ruleId,
                scenarioId,
                groupType,
                groupValue,
                detail);
    }

    private JSONObject buildDimensionDetailFile(String requestId,
                                                BigDecimal quantile,
                                                String ruleId,
                                                String scenarioId,
                                                VarDimensionGroup dimensionGroup) {
        if (dimensionGroup == null || dimensionGroup.getScenarioPnls() == null) {
            throw new IllegalArgumentException("维度明细为空，无法构建 detail_file");
        }
        int totalRows = dimensionGroup.getScenarioPnls().size();

        List<ScenarioDetailRow> detailRows = toScenarioDetailRows(dimensionGroup.getScenarioPnls());
        Map<VarScenarioKey, Integer> rankAll = rankByPnlColumn(detailRows, VarPnlColumns.ALL_PNL);
        Map<VarScenarioKey, Integer> rankIr = rankByPnlColumn(detailRows, VarPnlColumns.IR_PNL);
        Map<VarScenarioKey, Integer> rankFx = rankByPnlColumn(detailRows, VarPnlColumns.FX_PNL);
        Map<VarScenarioKey, Integer> rankEq = rankByPnlColumn(detailRows, VarPnlColumns.EQ_PNL);
        Map<VarScenarioKey, Integer> rankComm = rankByPnlColumn(detailRows, VarPnlColumns.COMM_PNL);

        detailRows.sort(Comparator
                .comparing((ScenarioDetailRow row) -> safePnl(row.aggregate.readByColumn(VarPnlColumns.ALL_PNL)))
                .thenComparing(row -> nullSafe(row.scenarioKey.getScenarioId()))
                .thenComparing(row -> nullSafe(row.scenarioKey.getSubScenarioId()))
                .thenComparing(row -> nullSafe(row.scenarioKey.getScenarioName())));

        JSONArray rows = new JSONArray();
        for (ScenarioDetailRow row : detailRows) {
            JSONObject item = new JSONObject();
            item.put("scenario_id", row.scenarioKey.getScenarioId());
            item.put("subscenario_id", row.scenarioKey.getSubScenarioId());
            item.put("scenario_name", row.scenarioKey.getScenarioName());
            item.put("all_pnl", row.aggregate.readByColumn(VarPnlColumns.ALL_PNL));
            item.put("ir_pnl", row.aggregate.readByColumn(VarPnlColumns.IR_PNL));
            item.put("fx_pnl", row.aggregate.readByColumn(VarPnlColumns.FX_PNL));
            item.put("eq_pnl", row.aggregate.readByColumn(VarPnlColumns.EQ_PNL));
            item.put("comm_pnl", row.aggregate.readByColumn(VarPnlColumns.COMM_PNL));
            item.put("rank_all", rankAll.get(row.scenarioKey));
            item.put("rank_ir", rankIr.get(row.scenarioKey));
            item.put("rank_fx", rankFx.get(row.scenarioKey));
            item.put("rank_eq", rankEq.get(row.scenarioKey));
            item.put("rank_comm", rankComm.get(row.scenarioKey));
            rows.add(item);
        }

        JSONObject detail = new JSONObject();
        detail.put("request_id", requestId);
        detail.put("quantile", quantile.stripTrailingZeros().toPlainString());
        detail.put("rule_id", ruleId);
        detail.put("scenario_id", scenarioId);
        detail.put("group_type", dimensionGroup.getGroupType());
        detail.put("group_value", dimensionGroup.getGroupValue());
        detail.put("total_rows", totalRows);
        detail.put("rows", rows);
        return detail;
    }

    private List<ScenarioDetailRow> toScenarioDetailRows(Map<VarScenarioKey, VarScenarioPnlAggregate> scenarioPnls) {
        List<ScenarioDetailRow> rows = new ArrayList<ScenarioDetailRow>();
        for (Map.Entry<VarScenarioKey, VarScenarioPnlAggregate> entry : scenarioPnls.entrySet()) {
            rows.add(new ScenarioDetailRow(entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    private Map<VarScenarioKey, Integer> rankByPnlColumn(List<ScenarioDetailRow> rows, String pnlColumn) {
        List<ScenarioDetailRow> sorted = new ArrayList<ScenarioDetailRow>(rows);
        sorted.sort(Comparator
                .comparing((ScenarioDetailRow row) -> safePnl(row.aggregate.readByColumn(pnlColumn)))
                .thenComparing(row -> nullSafe(row.scenarioKey.getScenarioId()))
                .thenComparing(row -> nullSafe(row.scenarioKey.getSubScenarioId()))
                .thenComparing(row -> nullSafe(row.scenarioKey.getScenarioName())));
        Map<VarScenarioKey, Integer> rank = new LinkedHashMap<VarScenarioKey, Integer>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.put(sorted.get(i).scenarioKey, i + 1);
        }
        return rank;
    }

    private Map<String, List<VarDimensionGroup>> buildScenarioDimensionGroups(AggregationRule rule,
                                                                              List<VarInputQueryService.RuleScenarioPnlRow> rows) {
        Map<String, Map<String, VarDimensionGroup>> groupedByScenario = new LinkedHashMap<String, Map<String, VarDimensionGroup>>();
        for (VarInputQueryService.RuleScenarioPnlRow row : rows) {
            String scenarioId = trimToNull(row.getScenarioId());
            if (scenarioId == null) {
                scenarioId = "__NULL_SCENARIO__";
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

    private static int deriveSampleSize(List<VarDimensionGroup> dimensionGroups) {
        int max = 0;
        for (VarDimensionGroup group : dimensionGroups) {
            if (group == null || group.getScenarioPnls() == null) {
                continue;
            }
            max = Math.max(max, group.getScenarioPnls().size());
        }
        return max;
    }

    private static String dimensionKey(String groupType, String groupValue) {
        return nullSafe(groupType) + "|" + nullSafe(groupValue);
    }

    private static JSONArray toQuantileArray(List<BigDecimal> quantiles) {
        JSONArray array = new JSONArray();
        for (BigDecimal quantile : quantiles) {
            array.add(quantile.stripTrailingZeros().toPlainString());
        }
        return array;
    }

    private static JSONArray toMeasureArray(List<VarMeasure> values) {
        JSONArray array = new JSONArray();
        if (values == null) {
            return array;
        }
        for (VarMeasure value : values) {
            if (value != null) {
                array.add(value.code());
            }
        }
        return array;
    }

    private static JSONArray toJsonArray(List<JSONObject> jsonObjects) {
        JSONArray array = new JSONArray();
        for (JSONObject jsonObject : jsonObjects) {
            array.add(jsonObject);
        }
        return array;
    }

    private List<VarRuleConfig> parseRules(JSONObject req) {
        JSONArray rules = req.getJSONArray("rules");
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }

        List<VarRuleConfig> configs = new ArrayList<VarRuleConfig>();
        for (int i = 0; i < rules.size(); i++) {
            JSONObject ruleJson = rules.getJSONObject(i);
            if (ruleJson == null) {
                throw new IllegalArgumentException("rules[" + i + "] 不能为空");
            }
            VarRuleConfig config = parseSingleRule(ruleJson, i);
            configs.add(config);
        }
        configs.sort(Comparator
                .comparingInt((VarRuleConfig c) -> c.outputOrder)
                .thenComparing(c -> nullSafe(c.rule.getRuleId())));
        return configs;
    }

    /**
     * 支持通过 rule_id 从 MR_AGG_RULE 读取 VAR 规则，并将规则内的 quantiles / measure 提升为本次运行参数。
     */
    private void normalizeRuleIdRequest(JSONObject req) {
        JSONArray rawRules = req.getJSONArray("rules");
        String topRuleId = readString(req, "rule_id");
        if ((rawRules == null || rawRules.isEmpty()) && topRuleId != null) {
            rawRules = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("rule_id", topRuleId);
            rawRules.add(item);
            req.put("rules", rawRules);
        }
        if (rawRules == null || rawRules.isEmpty()) {
            return;
        }

        JSONArray normalizedRules = new JSONArray();
        Object ruleQuantiles = null;
        Object ruleMeasure = null;
        for (int i = 0; i < rawRules.size(); i++) {
            JSONObject ruleItem = rawRules.getJSONObject(i);
            if (ruleItem == null) {
                throw new IllegalArgumentException("rules[" + i + "] 不能为空");
            }
            JSONObject resolvedRule = resolveVarRuleJson(ruleItem);
            normalizedRules.add(resolvedRule);
            ruleQuantiles = mergeRuntimeField(ruleQuantiles, resolvedRule.get("quantiles"), "quantiles");
            ruleMeasure = mergeRuntimeField(ruleMeasure, resolvedRule.get("measure"), "measure");
        }
        req.put("rules", normalizedRules);
        if (req.get("quantiles") == null && ruleQuantiles != null) {
            req.put("quantiles", ruleQuantiles);
        }
        if (req.get("measure") == null && ruleMeasure != null) {
            req.put("measure", ruleMeasure);
        }
    }

    private JSONObject resolveVarRuleJson(JSONObject ruleItem) {
        String ruleId = readString(ruleItem, "rule_id");
        if (ruleId != null && ruleItem.get("build_order") == null) {
            JSONObject loadedRule = inputQueryService.loadVarRuleJson(ruleId);
            copyOptional(ruleItem, loadedRule, "enabled");
            copyOptional(ruleItem, loadedRule, "output_order");
            return loadedRule;
        }
        return JSON.parseObject(ruleItem.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    private static void copyOptional(JSONObject source, JSONObject target, String key) {
        if (source != null && target != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private static Object mergeRuntimeField(Object current, Object next, String fieldName) {
        if (next == null) {
            return current;
        }
        if (current == null) {
            return next;
        }
        String left = JSON.toJSONString(current, JSONWriter.Feature.WriteBigDecimalAsPlain);
        String right = JSON.toJSONString(next, JSONWriter.Feature.WriteBigDecimalAsPlain);
        if (!left.equals(right)) {
            throw new IllegalArgumentException("多个 VAR rule 的 " + fieldName + " 不一致，不能在同一次请求中混用");
        }
        return current;
    }

    private VarRuleConfig parseSingleRule(JSONObject ruleJson, int index) {
        AggregationRule rule = new AggregationRule();
        rule.setRuleId(requireString(ruleJson, "rule_id"));
        rule.setRuleName(readString(ruleJson, "rule_name"));
        rule.setRuleType(readString(ruleJson, "rule_type"));

        List<String> buildOrder = readStringList(ruleJson, "build_order");
        rule.setBuildOrder(buildOrder);
        rule.setGroupByFields(readStringList(ruleJson, "group_by_fields"));
        rule.setSumFields(readStringList(ruleJson, "sum_fields"));
        rule.setFilterTree(toFilterExpression(ruleJson.get("filter_tree")));
        applyVarRuleDefaults(rule);
        dimensionAggregationService.validateRule(rule);

        JSONObject calcJson = ruleJson.getJSONObject("calc");
        String decompType = readString(calcJson, "decomp_type");
        String riskClassRaw = readString(calcJson, "risk_class");
        String varPick = parseVarPick(readString(calcJson, "var_pick"));

        boolean decompMode = isRiskClassDecomp(decompType, riskClassRaw);
        List<String> riskClasses = parseRiskClassesOptional(riskClassRaw);
        if (decompMode && riskClasses.isEmpty()) {
            decompMode = false;
        }
        if (!decompMode) {
            riskClasses = resolveNonDecompRiskClasses(riskClasses);
        }

        boolean enabled = readBoolean(ruleJson, "enabled", true);
        Integer outputOrder = readInteger(ruleJson, "output_order");
        return new VarRuleConfig(
                rule,
                decompMode,
                riskClasses,
                VarPickMethod.parse(varPick),
                enabled,
                outputOrder == null ? index + 1 : outputOrder);
    }

    /**
     * 校验并规范 VaR 汇总规则，规则类型必须由输入明确给出。
     */
    private static void applyVarRuleDefaults(AggregationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("AggregationRule 不能为空");
        }
        String ruleType = trimToNull(rule.getRuleType());
        if (!VAR_RULE_TYPE.equalsIgnoreCase(ruleType)) {
            throw new IllegalArgumentException("VaR 汇总规则 rule_type 必须为 VAR");
        }
        rule.setRuleType(VAR_RULE_TYPE);

        List<String> buildOrder = new ArrayList<String>();
        addUniqueIgnoreCase(buildOrder, TOTAL);
        for (String level : rule.getBuildOrder()) {
            addUniqueIgnoreCase(buildOrder, normalizeFieldName(level));
        }
        rule.setBuildOrder(buildOrder);

        List<String> groupByFields = normalizeFieldList(rule.getGroupByFields());
        for (String level : buildOrder) {
            if (!TOTAL.equalsIgnoreCase(level)) {
                addUniqueIgnoreCase(groupByFields, normalizeFieldName(level));
            }
        }
        rule.setGroupByFields(groupByFields);

        List<String> sumFields = new ArrayList<String>();
        sumFields.add(DEFAULT_SUM_FIELD);
        rule.setSumFields(sumFields);
    }

    private static AggregationRule.FilterExpression toFilterExpression(Object rawExpression) {
        if (!(rawExpression instanceof Map)) {
            return null;
        }
        Map<?, ?> row = (Map<?, ?>) rawExpression;
        AggregationRule.FilterExpression expression = new AggregationRule.FilterExpression();
        expression.setOp(asTrimmedString(row.get("op")));
        expression.setField(asTrimmedString(row.get("field")));
        String operator = asTrimmedString(row.get("operator"));
        expression.setOperator(operator);
        expression.setValue(normalizeFilterValue(operator, row.get("value")));

        Object rawChildren = row.get("children");
        if (rawChildren instanceof List) {
            List<AggregationRule.FilterExpression> children = new ArrayList<AggregationRule.FilterExpression>();
            for (Object child : (List<?>) rawChildren) {
                AggregationRule.FilterExpression childExpression = toFilterExpression(child);
                if (childExpression != null) {
                    children.add(childExpression);
                }
            }
            expression.setChildren(children);
        }
        return expression;
    }

    /**
     * 过滤值归一化：in/not_in 保留数组，其它操作符按单值处理。
     */
    private static Object normalizeFilterValue(String operator, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof List) {
            List<?> values = (List<?>) rawValue;
            if ("in".equalsIgnoreCase(operator) || "not_in".equalsIgnoreCase(operator)) {
                return new ArrayList<Object>(values);
            }
            for (Object item : values) {
                if (item != null) {
                    return item;
                }
            }
            return null;
        }
        return rawValue;
    }

    private static List<String> normalizeFieldList(List<String> rawList) {
        List<String> result = new ArrayList<String>();
        if (rawList == null || rawList.isEmpty()) {
            return result;
        }
        for (String item : rawList) {
            addUniqueIgnoreCase(result, normalizeFieldName(item));
        }
        return result;
    }

    private static String normalizeFieldName(String value) {
        String safe = trimToNull(value);
        if (safe == null) {
            return null;
        }
        return safe.toUpperCase(Locale.ROOT);
    }

    private static void addUniqueIgnoreCase(List<String> values, String value) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            return;
        }
        for (String item : values) {
            if (safeValue.equalsIgnoreCase(item)) {
                return;
            }
        }
        values.add(safeValue);
    }

    private static List<String> resolveNonDecompRiskClasses(List<String> riskClasses) {
        if (riskClasses != null && !riskClasses.isEmpty()) {
            return riskClasses;
        }
        List<String> resolved = new ArrayList<String>();
        resolved.add("ALL");
        return resolved;
    }

    static boolean isRiskClassDecomp(String decompType, String riskClass) {
        return "risk_class".equalsIgnoreCase(trimToNull(decompType))
                && trimToNull(riskClass) != null;
    }

    static List<BigDecimal> parseQuantiles(Object value) {
        List<BigDecimal> quantiles = new ArrayList<BigDecimal>();
        if (value == null) {
            throw new IllegalArgumentException("quantiles is required, 例如: 0.95,0.99");
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.size(); i++) {
                BigDecimal q = parseSingleQuantile(array.get(i));
                quantiles.add(q);
            }
        } else {
            String txt = trimToNull(String.valueOf(value));
            if (txt == null) {
                throw new IllegalArgumentException("quantiles is required, 例如: 0.95,0.99");
            }
            String[] parts = txt.split(",");
            for (String part : parts) {
                BigDecimal q = parseSingleQuantile(part);
                quantiles.add(q);
            }
        }

        if (quantiles.isEmpty()) {
            throw new IllegalArgumentException("quantiles is required, 例如: 0.95,0.99");
        }

        List<BigDecimal> deduped = new ArrayList<BigDecimal>();
        Set<String> seen = new LinkedHashSet<String>();
        for (BigDecimal quantile : quantiles) {
            String key = quantile.stripTrailingZeros().toPlainString();
            if (seen.add(key)) {
                deduped.add(quantile);
            }
        }
        return deduped;
    }

    static List<VarMeasure> parseMeasures(Object value) {
        List<VarMeasure> measures = new ArrayList<VarMeasure>();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.size(); i++) {
                addMeasure(measures, array.getString(i));
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (Object item : list) {
                addMeasure(measures, item == null ? null : String.valueOf(item));
            }
        } else {
            String text = trimToNull(value == null ? null : String.valueOf(value));
            if (text != null) {
                String[] parts = text.split(",");
                for (String part : parts) {
                    addMeasure(measures, part);
                }
            }
        }
        if (measures.isEmpty()) {
            measures.addAll(VarMeasure.defaultMeasures());
        }
        return measures;
    }

    private static void addMeasure(List<VarMeasure> measures, String rawMeasure) {
        String measureText = trimToNull(rawMeasure);
        if (measureText == null) {
            return;
        }
        VarMeasure measure = VarMeasure.parse(measureText);
        if (!measures.contains(measure)) {
            measures.add(measure);
        }
    }

    private static BigDecimal parseSingleQuantile(Object value) {
        String txt = trimToNull(value == null ? null : String.valueOf(value));
        if (txt == null) {
            throw new IllegalArgumentException("quantiles 中包含空值");
        }
        BigDecimal quantile;
        try {
            quantile = new BigDecimal(txt);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("quantile 格式非法: " + txt);
        }
        if (quantile.compareTo(BigDecimal.ZERO) <= 0 || quantile.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("quantile 必须在 (0,1) 区间: " + txt);
        }
        return quantile;
    }

    static String parseVarPick(String value) {
        String safe = trimToNull(value);
        if (safe == null) {
            return VAR_PICK_AVERAGE;
        }
        String lower = safe.toLowerCase(Locale.ROOT);
        if (VAR_PICK_IN.equals(lower) || VAR_PICK_OUT.equals(lower) || VAR_PICK_AVERAGE.equals(lower)) {
            return lower;
        }
        throw new IllegalArgumentException("var_pick 仅支持 in/out/average，实际: " + value);
    }

    static List<String> parseRiskClasses(String riskClassRaw) {
        String safe = trimToNull(riskClassRaw);
        if (safe == null) {
            throw new IllegalArgumentException("risk_class 不能为空");
        }
        List<String> riskClasses = parseRiskClassesOptional(safe);
        if (riskClasses.isEmpty()) {
            throw new IllegalArgumentException("risk_class 不能为空");
        }
        return riskClasses;
    }

    private static List<String> parseRiskClassesOptional(String riskClassRaw) {
        String safe = trimToNull(riskClassRaw);
        List<String> riskClasses = new ArrayList<String>();
        if (safe == null) {
            return riskClasses;
        }
        String[] parts = safe.split(",");
        Set<String> seen = new LinkedHashSet<String>();
        for (String part : parts) {
            String token = trimToNull(part);
            if (token == null) {
                continue;
            }
            String normalized = VarPnlColumns.normalizeRiskClassToken(token);
            riskClassToPnlColumn(normalized);
            if (seen.add(normalized)) {
                riskClasses.add(normalized);
            }
        }
        return riskClasses;
    }

    static String riskClassToPnlColumn(String riskClass) {
        return VarPnlColumns.riskClassToPnlColumn(riskClass);
    }

    private static String requireTopLevelString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String requireString(JSONObject obj, String key) {
        String value = readString(obj, key);
        if (value == null) {
            throw new IllegalArgumentException((key == null ? "field" : key) + " is required");
        }
        return value;
    }

    private static String readString(JSONObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        return trimToNull(obj.getString(key));
    }

    private static List<String> readStringList(JSONObject obj, String key) {
        List<String> list = new ArrayList<String>();
        if (obj == null || key == null) {
            return list;
        }
        JSONArray array = obj.getJSONArray(key);
        if (array == null) {
            return list;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < array.size(); i++) {
            String item = trimToNull(array.getString(i));
            if (item == null) {
                continue;
            }
            if (seen.add(item)) {
                list.add(item);
            }
        }
        return list;
    }

    private static boolean readBoolean(JSONObject obj, String key, boolean defaultValue) {
        if (obj == null || key == null) {
            return defaultValue;
        }
        Boolean value = obj.getBoolean(key);
        return value == null ? defaultValue : value;
    }

    private static Integer readInteger(JSONObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        return obj.getInteger(key);
    }

    private static BigDecimal safePnl(BigDecimal pnl) {
        return pnl == null ? BigDecimal.ZERO : pnl;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        return trimToNull(String.valueOf(value));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static class VarRuleConfig {
        private final AggregationRule rule;
        private final boolean decompMode;
        private final List<String> riskClasses;
        private final VarPickMethod pickMethod;
        private final boolean enabled;
        private final int outputOrder;

        private VarRuleConfig(AggregationRule rule,
                              boolean decompMode,
                              List<String> riskClasses,
                              VarPickMethod pickMethod,
                              boolean enabled,
                              int outputOrder) {
            this.rule = rule;
            this.decompMode = decompMode;
            this.riskClasses = riskClasses == null ? new ArrayList<String>() : riskClasses;
            this.pickMethod = pickMethod;
            this.enabled = enabled;
            this.outputOrder = outputOrder;
        }
    }

    private static class QuantileRuleResults {
        private final int quantileIndex;
        private final List<JSONObject> ruleResults;

        private QuantileRuleResults(int quantileIndex, List<JSONObject> ruleResults) {
            this.quantileIndex = quantileIndex;
            this.ruleResults = ruleResults == null ? new ArrayList<JSONObject>() : ruleResults;
        }
    }

    private static class NormalRiskClassResults {
        private final int quantileIndex;
        private final List<ScenarioDimensionMeasureResults> scenarioResults;

        private NormalRiskClassResults(int quantileIndex,
                                       List<ScenarioDimensionMeasureResults> scenarioResults) {
            this.quantileIndex = quantileIndex;
            this.scenarioResults = scenarioResults == null
                    ? new ArrayList<ScenarioDimensionMeasureResults>()
                    : scenarioResults;
        }
    }

    private static class ScenarioDimensionMeasureResults {
        private final String scenarioId;
        private final List<VarDimensionMeasureResult> dimensionResults;

        private ScenarioDimensionMeasureResults(String scenarioId,
                                                List<VarDimensionMeasureResult> dimensionResults) {
            this.scenarioId = scenarioId;
            this.dimensionResults = dimensionResults == null
                    ? new ArrayList<VarDimensionMeasureResult>()
                    : dimensionResults;
        }
    }

    private static class ScenarioDetailRow {
        private final VarScenarioKey scenarioKey;
        private final VarScenarioPnlAggregate aggregate;

        private ScenarioDetailRow(VarScenarioKey scenarioKey, VarScenarioPnlAggregate aggregate) {
            this.scenarioKey = scenarioKey;
            this.aggregate = aggregate;
        }
    }
}
