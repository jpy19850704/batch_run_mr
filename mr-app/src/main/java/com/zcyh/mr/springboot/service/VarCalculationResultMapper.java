package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.var.VarDimensionGroup;
import com.zcyh.mr.var.VarDimensionMeasureResult;
import com.zcyh.mr.var.VarRiskClassMeasureResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zcyh.mr.springboot.service.VarRequestParser.nullSafe;

/**
 * VaR 计算结果到接口结构的转换器。
 */
final class VarCalculationResultMapper {
    private final VarDetailWriter detailWriter;

    VarCalculationResultMapper(VarDetailWriter detailWriter) {
        this.detailWriter = detailWriter;
    }

    JSONObject buildRuleResultHeader(VarRuleConfig ruleConfig,
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

    JSONArray toDimensionResultArray(List<VarDimensionMeasureResult> measureResults,
                                     List<VarDimensionGroup> dimensionGroups,
                                     boolean includeDetail,
                                     String requestId,
                                     BigDecimal quantile,
                                     String ruleId,
                                     String scenarioId,
                                     AtomicInteger detailCacheCount) {
        Map<String, VarDimensionGroup> dimensionGroupByKey = new LinkedHashMap<>();
        for (VarDimensionGroup dimensionGroup : dimensionGroups) {
            dimensionGroupByKey.put(
                    dimensionKey(dimensionGroup.getGroupType(), dimensionGroup.getGroupValue()),
                    dimensionGroup);
        }

        JSONArray dimensionResults = new JSONArray();
        for (VarDimensionMeasureResult measureResult : measureResults) {
            String key = dimensionKey(measureResult.getGroupType(), measureResult.getGroupValue());
            VarDimensionGroup dimensionGroup = dimensionGroupByKey.get(key);
            if (dimensionGroup == null) {
                throw new IllegalStateException("VaR 维度结果不在输入维度中: " + key);
            }
            if (includeDetail && detailWriter.write(requestId, quantile, ruleId, scenarioId, dimensionGroup)) {
                detailCacheCount.incrementAndGet();
            }
            dimensionResults.add(toDimensionResultJson(measureResult));
        }
        return dimensionResults;
    }

    static String dimensionKey(String groupType, String groupValue) {
        return nullSafe(groupType) + "|" + nullSafe(groupValue);
    }

    private static int deriveSampleSize(List<VarDimensionGroup> dimensionGroups) {
        int max = 0;
        for (VarDimensionGroup group : dimensionGroups) {
            if (group != null && group.getScenarioPnls() != null) {
                max = Math.max(max, group.getScenarioPnls().size());
            }
        }
        return max;
    }

    private static JSONObject toDimensionResultJson(VarDimensionMeasureResult measureResult) {
        JSONObject dimensionResult = new JSONObject();
        dimensionResult.put("group_type", measureResult.getGroupType());
        dimensionResult.put("group_value", measureResult.getGroupValue());
        dimensionResult.put(
                "base_valuation_cny",
                measureResult.getBaseValuationCny().stripTrailingZeros().toPlainString());

        JSONArray riskClassResults = new JSONArray();
        for (VarRiskClassMeasureResult riskClassResult : measureResult.getRiskClassResults()) {
            riskClassResults.add(toRiskClassResultJson(riskClassResult));
        }
        dimensionResult.put("risk_class_results", riskClassResults);
        return dimensionResult;
    }

    private static JSONObject toRiskClassResultJson(VarRiskClassMeasureResult result) {
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
}
