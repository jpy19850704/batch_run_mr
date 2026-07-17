package com.zcyh.mr.springboot.measurement.var;

import com.zcyh.mr.springboot.measurement.aggregation.DimensionAggregationService;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.measurement.var.VarInputQueryService;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.springboot.measurement.var.VarCalculation;
import com.zcyh.mr.var.VarPickMethod;
import com.zcyh.mr.var.VarPnlColumns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.addUniqueIgnoreCase;
import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.normalizeUpperFieldName;
import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.toFilterExpression;
import static com.zcyh.mr.springboot.measurement.var.VarRequestParser.nullSafe;
import static com.zcyh.mr.springboot.measurement.var.VarRequestParser.readInteger;
import static com.zcyh.mr.springboot.measurement.var.VarRequestParser.readString;
import static com.zcyh.mr.springboot.measurement.var.VarRequestParser.readStringList;
import static com.zcyh.mr.springboot.measurement.var.VarRequestParser.requireString;
import static com.zcyh.mr.springboot.measurement.var.VarRequestParser.trimToNull;
import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;

/**
 * VaR 规则加载、规范化与运行配置解析器。
 */
final class VarRuleResolver {
    private static final String TOTAL = "TOTAL";
    private static final String VAR_RULE_TYPE = "VAR";
    private static final String DEFAULT_SUM_FIELD = VarPnlColumns.ALL_PNL;

    private final VarInputQueryService inputQueryService;
    private final DimensionAggregationService dimensionAggregationService;

    VarRuleResolver(VarInputQueryService inputQueryService,
                    DimensionAggregationService dimensionAggregationService) {
        this.inputQueryService = inputQueryService;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    List<VarResolvedCalculation> loadConfiguredCalculations(List<VarCalculation> calculations) {
        if (calculations == null || calculations.isEmpty()) {
            return Collections.emptyList();
        }
        List<VarResolvedCalculation> resolved = new ArrayList<VarResolvedCalculation>();
        for (int i = 0; i < calculations.size(); i++) {
            VarCalculation calculation = calculations.get(i);
            JSONObject ruleSnapshot = inputQueryService.loadVarRuleJson(calculation.getRuleId());
            resolved.add(new VarResolvedCalculation(
                    parseSingleRule(ruleSnapshot, i), calculation.getScenarioId()));
        }
        sortCalculations(resolved);
        return resolved;
    }

    List<VarResolvedCalculation> parseExplicitCalculations(List<VarCalculation> calculations) {
        if (calculations == null || calculations.isEmpty()) {
            return Collections.emptyList();
        }
        List<VarResolvedCalculation> resolved = new ArrayList<VarResolvedCalculation>();
        for (int i = 0; i < calculations.size(); i++) {
            VarCalculation calculation = calculations.get(i);
            JSONObject ruleDefinition = calculation.getRuleDefinition();
            if (ruleDefinition == null) {
                throw new IllegalArgumentException("calculations[" + i + "].rule 不能为空");
            }
            resolved.add(new VarResolvedCalculation(
                    parseSingleRule(ruleDefinition, i), calculation.getScenarioId()));
        }
        sortCalculations(resolved);
        return resolved;
    }

    private static void sortCalculations(List<VarResolvedCalculation> calculations) {
        calculations.sort(Comparator
                .comparingInt((VarResolvedCalculation calculation) -> calculation.ruleConfig.outputOrder)
                .thenComparing(calculation -> nullSafe(calculation.ruleConfig.rule.getRuleId()))
                .thenComparing(calculation -> nullSafe(calculation.scenarioId)));
    }

    List<JSONObject> loadRuleSnapshots(List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<JSONObject> ruleSnapshots = new ArrayList<JSONObject>(ruleIds.size());
        for (String ruleId : ruleIds) {
            ruleSnapshots.add(inputQueryService.loadVarRuleJson(ruleId));
        }
        return ruleSnapshots;
    }

    private VarRuleConfig parseSingleRule(JSONObject ruleJson, int index) {
        AggregationRule rule = new AggregationRule();
        rule.setRuleId(requireString(ruleJson, "rule_id"));
        rule.setRuleName(readString(ruleJson, "rule_name"));
        rule.setRuleType(readString(ruleJson, "rule_type"));
        rule.setBuildOrder(readStringList(ruleJson, "build_order"));
        rule.setSumFields(readStringList(ruleJson, "sum_fields"));
        rule.setFilterTree(toFilterExpression(ruleJson.get("filterTree")));
        applyVarRuleDefaults(rule);
        dimensionAggregationService.validateRule(rule);

        JSONObject calcJson = ruleJson.getJSONObject("calc");
        String decompType = VarRequestParser.parseDecompType(readString(calcJson, "decomp_type"));
        String riskClassRaw = readString(calcJson, "risk_class");
        boolean decompMode = "risk_class".equals(decompType);
        List<String> riskClasses = VarRequestParser.parseRiskClassesRequired(riskClassRaw);

        boolean enabled = readBoolean(ruleJson, true, "enabled");
        Integer outputOrder = readInteger(ruleJson, "output_order");
        return new VarRuleConfig(
                rule,
                decompMode,
                riskClasses,
                VarPickMethod.parse(VarRequestParser.parseVarPick(readString(calcJson, "var_pick"))),
                VarRequestParser.parseQuantiles(ruleJson.get("quantiles")),
                VarRequestParser.parseMeasures(ruleJson.get("measure")),
                enabled,
                outputOrder == null ? index + 1 : outputOrder);
    }

    private static void applyVarRuleDefaults(AggregationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("AggregationRule 不能为空");
        }
        String ruleType = trimToNull(rule.getRuleType());
        if (!VAR_RULE_TYPE.equalsIgnoreCase(ruleType)) {
            throw new IllegalArgumentException("VaR 汇总规则 rule_type 必须为 VAR");
        }
        rule.setRuleType(VAR_RULE_TYPE);

        List<String> buildOrder = new ArrayList<>();
        addUniqueIgnoreCase(buildOrder, TOTAL);
        for (String level : rule.getBuildOrder()) {
            addUniqueIgnoreCase(buildOrder, normalizeUpperFieldName(level));
        }
        rule.setBuildOrder(buildOrder);


        List<String> sumFields = new ArrayList<>();
        sumFields.add(DEFAULT_SUM_FIELD);
        rule.setSumFields(sumFields);
    }

}
