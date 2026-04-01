package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.var.VarCalculator;
import com.zcyh.mr.var.VarPickMethod;
import com.zcyh.mr.var.VarQuantileResult;
import com.zcyh.mr.var.VarScenarioPnl;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * VaR 数据库输入执行服务。
 */
@Service
public class VarDbRunnerService {
    private static final Logger log = LoggerFactory.getLogger(VarDbRunnerService.class);
    private static final String VAR_PICK_IN = "in";
    private static final String VAR_PICK_OUT = "out";
    private static final String VAR_PICK_AVERAGE = "average";
    private static final BigDecimal TWO = BigDecimal.valueOf(2L);
    private static final int DEFAULT_SCALE = 10;
    private static final String TOTAL = "TOTAL";
    private static final String VAR_DETAIL_FETCH_API = "/api/v1/engine/var/detail";

    private final VarInputQueryService inputQueryService;
    private final DimensionAggregationService dimensionAggregationService;
    private final VarCalculator varCalculator = new VarCalculator();
    private VarDetailCacheService varDetailCacheService;

    public VarDbRunnerService(VarInputQueryService inputQueryService,
                              DimensionAggregationService dimensionAggregationService) {
        this.inputQueryService = inputQueryService;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    @Autowired(required = false)
    public void setVarDetailCacheService(VarDetailCacheService varDetailCacheService) {
        this.varDetailCacheService = varDetailCacheService;
    }

    public String calculateByInline(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");
        List<BigDecimal> quantiles = parseQuantiles(req.get("quantiles"));
        List<VarRuleConfig> rules = parseRules(req);
        boolean includeDetailRequested = readBoolean(req, "include_detail", "includeDetail");
        boolean includeDetail = includeDetailRequested;
        String requestId = readString(req, "request_id", "requestId");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules is required");
        }
        if (includeDetail && varDetailCacheService == null) {
            includeDetail = false;
            log.warn("include_detail=true 但 Redis 缓存服务未启用，自动降级为 include_detail=false");
        }

        int[] detailCacheCount = new int[]{0};

        List<JSONObject> quantileGroups = initQuantileGroups(quantiles);
        for (VarRuleConfig ruleConfig : rules) {
            if (!ruleConfig.enabled) {
                continue;
            }

            List<VarInputQueryService.RuleScenarioPnlRow> rows =
                    inputQueryService.queryRuleScenarioPnlRows(batchId, dataDate, ruleConfig.rule);
            Map<String, List<DimensionGroupData>> scenarioDimensionGroups = buildScenarioDimensionGroups(ruleConfig.rule, rows);
            if (scenarioDimensionGroups.isEmpty()) {
                continue;
            }

            for (int qIndex = 0; qIndex < quantiles.size(); qIndex++) {
                BigDecimal quantile = quantiles.get(qIndex);
                JSONObject quantileGroup = quantileGroups.get(qIndex);
                JSONArray ruleResults = quantileGroup.getJSONArray("rule_results");
                List<JSONObject> ruleResultItems = buildRuleResultsForQuantile(
                        ruleConfig,
                        scenarioDimensionGroups,
                        quantile,
                        includeDetail,
                        requestId,
                        detailCacheCount);
                for (JSONObject item : ruleResultItems) {
                    ruleResults.add(item);
                }
            }
        }

        JSONObject summaryFile = new JSONObject();
        summaryFile.put("batch_id", batchId);
        summaryFile.put("data_date", dataDate);
        summaryFile.put("quantiles", toQuantileArray(quantiles));
        summaryFile.put("quantile_groups", toJsonArray(quantileGroups));

        JSONObject detailFile = new JSONObject();
        detailFile.put("enabled", includeDetail);
        detailFile.put("requested", includeDetailRequested);
        detailFile.put("request_id", requestId);
        detailFile.put("fetch_api", VAR_DETAIL_FETCH_API);
        detailFile.put("cache_entries", detailCacheCount[0]);
        if (varDetailCacheService != null) {
            detailFile.put("ttl_seconds", varDetailCacheService.getTtlSeconds());
        }

        JSONObject result = new JSONObject();
        // 兼容保留：顶层继续保留原始汇总字段，避免已有调用方受影响
        result.put("batch_id", batchId);
        result.put("data_date", dataDate);
        result.put("quantiles", toQuantileArray(quantiles));
        result.put("quantile_groups", toJsonArray(quantileGroups));
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

    private List<JSONObject> buildRuleResultsForQuantile(VarRuleConfig ruleConfig,
                                                         Map<String, List<DimensionGroupData>> scenarioDimensionGroups,
                                                         BigDecimal quantile,
                                                         boolean includeDetail,
                                                         String requestId,
                                                         int[] detailCacheCount) {
        List<JSONObject> ruleResults = new ArrayList<JSONObject>();
        for (Map.Entry<String, List<DimensionGroupData>> entry : scenarioDimensionGroups.entrySet()) {
            String scenarioId = entry.getKey();
            List<DimensionGroupData> dimensionGroups = entry.getValue();
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

            JSONArray dimensionResults = new JSONArray();
            for (DimensionGroupData dimensionGroup : dimensionGroups) {
                JSONObject dimensionResult = new JSONObject();
                dimensionResult.put("group_type", dimensionGroup.groupType);
                dimensionResult.put("group_value", dimensionGroup.groupValue);
                if (includeDetail) {
                    boolean cached = cacheDimensionDetail(
                            requestId,
                            quantile,
                            ruleConfig.rule.getRuleId(),
                            scenarioId,
                            dimensionGroup);
                    if (cached) {
                        detailCacheCount[0] = detailCacheCount[0] + 1;
                    }
                }

                JSONArray riskClassResults = new JSONArray();
                if (ruleConfig.decompMode) {
                    VarQuantileResult allQuantile = calcSingleQuantile(
                            toScenarioRows(dimensionGroup.scenarioPnls, "ALL_PNL"),
                            quantile,
                            ruleConfig.pickMethod);
                    for (String riskClass : ruleConfig.riskClasses) {
                        riskClassResults.add(buildDecompRiskClassResult(dimensionGroup, allQuantile, riskClass, ruleConfig.pickMethod));
                    }
                } else {
                    for (String riskClass : ruleConfig.riskClasses) {
                        String pnlColumn = riskClassToPnlColumn(riskClass);
                        VarQuantileResult quantileResult = calcSingleQuantile(
                                toScenarioRows(dimensionGroup.scenarioPnls, pnlColumn),
                                quantile,
                                ruleConfig.pickMethod);
                        BigDecimal es = varCalculator.calculateEsByOut(toScenarioRows(dimensionGroup.scenarioPnls, pnlColumn), quantile);
                        riskClassResults.add(buildIndependentRiskClassResult(
                                quantileResult,
                                riskClass,
                                es,
                                pnlColumn));
                    }
                }
                dimensionResult.put("risk_class_results", riskClassResults);
                dimensionResults.add(dimensionResult);
            }
            ruleResult.put("dimension_results", dimensionResults);
            ruleResults.add(ruleResult);
        }
        return ruleResults;
    }

    private JSONObject buildDecompRiskClassResult(DimensionGroupData dimensionGroup,
                                                  VarQuantileResult allQuantile,
                                                  String riskClass,
                                                  VarPickMethod pickMethod) {
        String pnlColumn = riskClassToPnlColumn(riskClass);
        VarScenarioPnl inScenario = allQuantile.getInScenario();
        VarScenarioPnl outScenario = allQuantile.getOutScenario();
        List<VarScenarioPnl> rows = toScenarioRows(dimensionGroup.scenarioPnls, pnlColumn);
        BigDecimal es = varCalculator.calculateEsByOut(rows, allQuantile.getQuantile());
        ScenarioPnlAggregate inAgg = dimensionGroup.scenarioPnls.get(ScenarioKey.fromScenario(inScenario));
        ScenarioPnlAggregate outAgg = dimensionGroup.scenarioPnls.get(ScenarioKey.fromScenario(outScenario));

        BigDecimal pnlIn = readAggregatePnl(inAgg, pnlColumn);
        BigDecimal pnlOut = readAggregatePnl(outAgg, pnlColumn);
        BigDecimal varIn = pnlIn;
        BigDecimal varOut = pnlOut;
        int rankIn = allQuantile.getRankIn();
        int rankOut = allQuantile.getRankOut();
        String subIn = inScenario == null ? null : inScenario.getSubScenarioId();
        String subOut = outScenario == null ? null : outScenario.getSubScenarioId();
        if (sameSubScenario(subIn, subOut)) {
            rankOut = rankIn;
            pnlOut = pnlIn;
            varOut = varIn;
            subOut = subIn;
        }

        BigDecimal selectedPnl;
        BigDecimal selectedVar;
        String selectedScenarioId = null;
        if (pickMethod == VarPickMethod.IN) {
            selectedPnl = pnlIn;
            selectedVar = varIn;
            selectedScenarioId = inScenario == null ? null : inScenario.getScenarioId();
        } else if (pickMethod == VarPickMethod.OUT) {
            selectedPnl = pnlOut;
            selectedVar = varOut;
            selectedScenarioId = outScenario == null ? null : outScenario.getScenarioId();
        } else {
            selectedPnl = pnlOut.add(pnlIn).divide(TWO, DEFAULT_SCALE, RoundingMode.HALF_UP);
            selectedVar = varOut.add(varIn).divide(TWO, DEFAULT_SCALE, RoundingMode.HALF_UP);
        }

        JSONObject item = new JSONObject();
        item.put("risk_class", normalizeRiskClassToken(riskClass));
        item.put("rank_in", rankIn);
        item.put("rank_out", rankOut);
        item.put("subscenario_id_in", subIn);
        item.put("pnl_in", pnlIn);
        item.put("var_in", varIn);
        item.put("subscenario_id_out", subOut);
        item.put("pnl_out", pnlOut);
        item.put("var_out", varOut);
        item.put("sort_pnl_field", "ALL_PNL");
        if (!allQuantile.isSingleSample() && pickMethod != VarPickMethod.AVERAGE) {
            item.put("selected_scenario_id", selectedScenarioId);
        }
        item.put("var", selectedVar);
        item.put("es", es);
        return item;
    }

    private JSONObject buildIndependentRiskClassResult(VarQuantileResult quantileResult,
                                                       String riskClass,
                                                       BigDecimal es,
                                                       String sortPnlField) {
        JSONObject item = toQuantileDetail(quantileResult);
        item.put("risk_class", normalizeRiskClassToken(riskClass));
        item.put("es", es);
        item.put("sort_pnl_field", sortPnlField);
        return item;
    }

    private boolean cacheDimensionDetail(String requestId,
                                         BigDecimal quantile,
                                         String ruleId,
                                         String scenarioId,
                                         DimensionGroupData dimensionGroup) {
        if (varDetailCacheService == null) {
            return false;
        }
        JSONObject detail = buildDimensionDetailFile(
                requestId,
                quantile,
                ruleId,
                scenarioId,
                dimensionGroup);
        return varDetailCacheService.putDimensionDetail(
                requestId,
                quantile.stripTrailingZeros().toPlainString(),
                ruleId,
                scenarioId,
                dimensionGroup.groupType,
                dimensionGroup.groupValue,
                detail);
    }

    private JSONObject buildDimensionDetailFile(String requestId,
                                                BigDecimal quantile,
                                                String ruleId,
                                                String scenarioId,
                                                DimensionGroupData dimensionGroup) {
        if (dimensionGroup == null || dimensionGroup.scenarioPnls == null) {
            throw new IllegalArgumentException("维度明细为空，无法构建 detail_file");
        }
        int totalRows = dimensionGroup.scenarioPnls.size();

        List<ScenarioDetailRow> detailRows = toScenarioDetailRows(dimensionGroup.scenarioPnls);
        Map<ScenarioKey, Integer> rankAll = rankByPnlColumn(detailRows, "ALL_PNL");
        Map<ScenarioKey, Integer> rankIr = rankByPnlColumn(detailRows, "IR_PNL");
        Map<ScenarioKey, Integer> rankFx = rankByPnlColumn(detailRows, "FX_PNL");
        Map<ScenarioKey, Integer> rankEq = rankByPnlColumn(detailRows, "EQ_PNL");
        Map<ScenarioKey, Integer> rankComm = rankByPnlColumn(detailRows, "COMM_PNL");

        detailRows.sort(Comparator
                .comparing((ScenarioDetailRow row) -> safePnl(row.aggregate.readByColumn("ALL_PNL")))
                .thenComparing(row -> nullSafe(row.scenarioKey.scenarioId))
                .thenComparing(row -> nullSafe(row.scenarioKey.subScenarioId))
                .thenComparing(row -> nullSafe(row.scenarioKey.scenarioName)));

        JSONArray rows = new JSONArray();
        for (ScenarioDetailRow row : detailRows) {
            JSONObject item = new JSONObject();
            item.put("scenario_id", row.scenarioKey.scenarioId);
            item.put("subscenario_id", row.scenarioKey.subScenarioId);
            item.put("scenario_name", row.scenarioKey.scenarioName);
            item.put("all_pnl", row.aggregate.readByColumn("ALL_PNL"));
            item.put("ir_pnl", row.aggregate.readByColumn("IR_PNL"));
            item.put("fx_pnl", row.aggregate.readByColumn("FX_PNL"));
            item.put("eq_pnl", row.aggregate.readByColumn("EQ_PNL"));
            item.put("comm_pnl", row.aggregate.readByColumn("COMM_PNL"));
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
        detail.put("group_type", dimensionGroup.groupType);
        detail.put("group_value", dimensionGroup.groupValue);
        detail.put("total_rows", totalRows);
        detail.put("rows", rows);
        return detail;
    }

    private List<ScenarioDetailRow> toScenarioDetailRows(Map<ScenarioKey, ScenarioPnlAggregate> scenarioPnls) {
        List<ScenarioDetailRow> rows = new ArrayList<ScenarioDetailRow>();
        for (Map.Entry<ScenarioKey, ScenarioPnlAggregate> entry : scenarioPnls.entrySet()) {
            rows.add(new ScenarioDetailRow(entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    private Map<ScenarioKey, Integer> rankByPnlColumn(List<ScenarioDetailRow> rows, String pnlColumn) {
        List<ScenarioDetailRow> sorted = new ArrayList<ScenarioDetailRow>(rows);
        sorted.sort(Comparator
                .comparing((ScenarioDetailRow row) -> safePnl(row.aggregate.readByColumn(pnlColumn)))
                .thenComparing(row -> nullSafe(row.scenarioKey.scenarioId))
                .thenComparing(row -> nullSafe(row.scenarioKey.subScenarioId))
                .thenComparing(row -> nullSafe(row.scenarioKey.scenarioName)));
        Map<ScenarioKey, Integer> rank = new LinkedHashMap<ScenarioKey, Integer>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.put(sorted.get(i).scenarioKey, i + 1);
        }
        return rank;
    }

    private static BigDecimal readAggregatePnl(ScenarioPnlAggregate aggregate, String pnlColumn) {
        if (aggregate == null) {
            return BigDecimal.ZERO;
        }
        return aggregate.readByColumn(pnlColumn);
    }

    private VarQuantileResult calcSingleQuantile(List<VarScenarioPnl> rows,
                                                 BigDecimal quantile,
                                                 VarPickMethod pickMethod) {
        List<VarQuantileResult> results = varCalculator.calculate(rows, Collections.singletonList(quantile), pickMethod);
        return results.get(0);
    }

    private Map<String, List<DimensionGroupData>> buildScenarioDimensionGroups(AggregationRule rule,
                                                                               List<VarInputQueryService.RuleScenarioPnlRow> rows) {
        Map<String, Map<String, DimensionGroupData>> groupedByScenario = new LinkedHashMap<String, Map<String, DimensionGroupData>>();
        for (VarInputQueryService.RuleScenarioPnlRow row : rows) {
            String scenarioId = trimToNull(row.getScenarioId());
            if (scenarioId == null) {
                scenarioId = "__NULL_SCENARIO__";
            }
            Map<String, DimensionGroupData> groups = groupedByScenario.get(scenarioId);
            if (groups == null) {
                groups = new LinkedHashMap<String, DimensionGroupData>();
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
                    String fieldName = rule.getDimensions().get(level);
                    String levelValue = dimensionAggregationService.normalizeDimensionValue(row.getDimensions().get(fieldName));
                    pathValues.add(levelValue);
                    groupType = level;
                    groupValue = dimensionAggregationService.buildGroupValue(pathValues);
                }

                String key = groupType + "|" + groupValue;
                DimensionGroupData groupData = groups.get(key);
                if (groupData == null) {
                    groupData = new DimensionGroupData(groupType, groupValue);
                    groups.put(key, groupData);
                }
                groupData.accumulate(row);
            }
        }
        Map<String, List<DimensionGroupData>> result = new LinkedHashMap<String, List<DimensionGroupData>>();
        for (Map.Entry<String, Map<String, DimensionGroupData>> entry : groupedByScenario.entrySet()) {
            result.put(entry.getKey(), new ArrayList<DimensionGroupData>(entry.getValue().values()));
        }
        return result;
    }

    private static int deriveSampleSize(List<DimensionGroupData> dimensionGroups) {
        int max = 0;
        for (DimensionGroupData group : dimensionGroups) {
            if (group == null || group.scenarioPnls == null) {
                continue;
            }
            max = Math.max(max, group.scenarioPnls.size());
        }
        return max;
    }

    private List<VarScenarioPnl> toScenarioRows(Map<ScenarioKey, ScenarioPnlAggregate> scenarioPnls,
                                                String pnlColumn) {
        List<VarScenarioPnl> rows = new ArrayList<VarScenarioPnl>();
        for (Map.Entry<ScenarioKey, ScenarioPnlAggregate> entry : scenarioPnls.entrySet()) {
            ScenarioKey scenarioKey = entry.getKey();
            ScenarioPnlAggregate aggregate = entry.getValue();
            rows.add(new VarScenarioPnl(
                    scenarioKey.scenarioId,
                    scenarioKey.subScenarioId,
                    scenarioKey.scenarioName,
                    aggregate.readByColumn(pnlColumn)));
        }
        return rows;
    }

    private JSONObject toQuantileDetail(VarQuantileResult calcResult) {
        VarScenarioPnl inScenario = calcResult.getInScenario();
        VarScenarioPnl outScenario = calcResult.getOutScenario();
        BigDecimal pnlIn = calcResult.getPnlIn();
        BigDecimal pnlOut = calcResult.getPnlOut();
        BigDecimal varIn = pnlIn;
        BigDecimal varOut = pnlOut;
        int rankIn = calcResult.getRankIn();
        int rankOut = calcResult.getRankOut();
        String subIn = inScenario == null ? null : inScenario.getSubScenarioId();
        String subOut = outScenario == null ? null : outScenario.getSubScenarioId();
        if (sameSubScenario(subIn, subOut)) {
            rankOut = rankIn;
            pnlOut = pnlIn;
            varOut = varIn;
            subOut = subIn;
        }
        JSONObject item = new JSONObject();
        item.put("rank_in", rankIn);
        item.put("rank_out", rankOut);
        item.put("subscenario_id_in", subIn);
        item.put("pnl_in", pnlIn);
        item.put("var_in", varIn);
        item.put("subscenario_id_out", subOut);
        item.put("pnl_out", pnlOut);
        item.put("var_out", varOut);
        if (!calcResult.isSingleSample()) {
            VarScenarioPnl selectedScenario = calcResult.getSelectedScenario();
            item.put("selected_scenario_id", selectedScenario == null ? null : selectedScenario.getScenarioId());
        }
        BigDecimal selectedPnl = calcResult.getSelectedPnl();
        item.put("var", selectedPnl);
        return item;
    }

    private static JSONArray toQuantileArray(List<BigDecimal> quantiles) {
        JSONArray array = new JSONArray();
        for (BigDecimal quantile : quantiles) {
            array.add(quantile.stripTrailingZeros().toPlainString());
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

    private VarRuleConfig parseSingleRule(JSONObject ruleJson, int index) {
        AggregationRule rule = new AggregationRule();
        rule.setRuleId(requireString(ruleJson, "rule_id", "ruleId"));
        rule.setRuleName(readString(ruleJson, "rule_name", "ruleName"));
        rule.setRuleType(readString(ruleJson, "rule_type", "ruleType"));

        List<String> buildOrder = readStringList(ruleJson, "build_order", "buildOrder");
        if (buildOrder.isEmpty()) {
            buildOrder.add(TOTAL);
        }
        rule.setBuildOrder(buildOrder);
        rule.setDimensions(readStringMap(ruleJson.getJSONObject("dimensions")));

        List<String> groupByFields = readStringList(ruleJson, "group_by_fields", "groupByFields");
        if (groupByFields.isEmpty()) {
            for (String field : rule.getDimensions().values()) {
                String safeField = trimToNull(field);
                if (safeField != null && !containsIgnoreCase(groupByFields, safeField)) {
                    groupByFields.add(safeField);
                }
            }
        }
        rule.setGroupByFields(groupByFields);

        List<String> sumFields = readStringList(ruleJson, "sum_fields", "sumFields");
        if (sumFields.isEmpty()) {
            sumFields.add("ALL_PNL");
        }
        rule.setSumFields(sumFields);
        rule.setFilters(readFilters(ruleJson.getJSONArray("filters")));
        dimensionAggregationService.validateRule(rule);

        JSONObject calcJson = ruleJson.getJSONObject("calc");
        String decompType = readString(calcJson, "decomp_type", "decompType");
        String riskClassRaw = readString(calcJson, "risk_class", "riskClass");
        String varPick = parseVarPick(readString(calcJson, "var_pick", "varPick"));

        boolean decompMode = isRiskClassDecomp(decompType, riskClassRaw);
        List<String> riskClasses = parseRiskClassesOptional(riskClassRaw);
        if (decompMode && riskClasses.isEmpty()) {
            decompMode = false;
        }
        if (!decompMode) {
            riskClasses = resolveNonDecompRiskClasses(riskClasses);
        }

        boolean enabled = readBoolean(ruleJson, "enabled", true);
        Integer outputOrder = readInteger(ruleJson, "output_order", "outputOrder");
        return new VarRuleConfig(
                rule,
                decompMode,
                riskClasses,
                VarPickMethod.parse(varPick),
                enabled,
                outputOrder == null ? index + 1 : outputOrder);
    }

    private static List<AggregationRule.FilterCondition> readFilters(JSONArray filters) {
        List<AggregationRule.FilterCondition> list = new ArrayList<AggregationRule.FilterCondition>();
        if (filters == null || filters.isEmpty()) {
            return list;
        }
        for (int i = 0; i < filters.size(); i++) {
            JSONObject item = filters.getJSONObject(i);
            if (item == null) {
                continue;
            }
            AggregationRule.FilterCondition condition = new AggregationRule.FilterCondition();
            condition.setField(readString(item, "field"));
            condition.setOperator(readString(item, "operator"));
            condition.setValue(item.get("value"));
            list.add(condition);
        }
        return list;
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
            String normalized = normalizeRiskClassToken(token);
            riskClassToPnlColumn(normalized);
            if (seen.add(normalized)) {
                riskClasses.add(normalized);
            }
        }
        return riskClasses;
    }

    static String riskClassToPnlColumn(String riskClass) {
        String safe = trimToNull(riskClass);
        if (safe == null) {
            throw new IllegalArgumentException("risk_class 不能为空");
        }
        String upper = safe.toUpperCase(Locale.ROOT);
        if ("IR".equals(upper)) {
            return "IR_PNL";
        }
        if ("FX".equals(upper)) {
            return "FX_PNL";
        }
        if ("EQ".equals(upper)) {
            return "EQ_PNL";
        }
        if ("COMM".equals(upper) || "CMTY".equals(upper)) {
            return "COMM_PNL";
        }
        if ("ALL".equals(upper)) {
            return "ALL_PNL";
        }
        throw new IllegalArgumentException("不支持的 risk_class: " + riskClass + "，仅支持 IR/FX/EQ/COMM/ALL");
    }

    private static String normalizeRiskClassToken(String token) {
        String upper = token.toUpperCase(Locale.ROOT);
        if ("CMTY".equals(upper)) {
            return "COMM";
        }
        return upper;
    }

    private static boolean sameSubScenario(String left, String right) {
        String l = trimToNull(left);
        String r = trimToNull(right);
        return l != null && l.equals(r);
    }

    private static String requireTopLevelString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String requireString(JSONObject obj, String... keys) {
        String value = readString(obj, keys);
        if (value == null) {
            throw new IllegalArgumentException((keys == null || keys.length == 0 ? "field" : keys[0]) + " is required");
        }
        return value;
    }

    private static String readString(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = trimToNull(obj.getString(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static List<String> readStringList(JSONObject obj, String... keys) {
        List<String> list = new ArrayList<String>();
        if (obj == null || keys == null) {
            return list;
        }
        JSONArray array = null;
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            array = obj.getJSONArray(key);
            if (array != null) {
                break;
            }
        }
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

    private static Map<String, String> readStringMap(JSONObject obj) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (obj == null) {
            return map;
        }
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            String key = trimToNull(entry.getKey());
            String value = trimToNull(entry.getValue() == null ? null : String.valueOf(entry.getValue()));
            if (key == null || value == null) {
                continue;
            }
            map.put(key, value);
        }
        return map;
    }

    private static boolean readBoolean(JSONObject obj, String key, boolean defaultValue) {
        if (obj == null || key == null) {
            return defaultValue;
        }
        Boolean value = obj.getBoolean(key);
        return value == null ? defaultValue : value;
    }

    private static boolean readBoolean(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Boolean value = obj.getBoolean(key);
            if (value != null) {
                return value;
            }
        }
        return false;
    }

    private static Integer readInteger(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Integer value = obj.getInteger(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || values.isEmpty() || trimToNull(target) == null) {
            return false;
        }
        for (String value : values) {
            if (target.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
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

    private static class DimensionGroupData {
        private final String groupType;
        private final String groupValue;
        private final Map<ScenarioKey, ScenarioPnlAggregate> scenarioPnls = new LinkedHashMap<ScenarioKey, ScenarioPnlAggregate>();

        private DimensionGroupData(String groupType, String groupValue) {
            this.groupType = groupType;
            this.groupValue = groupValue;
        }

        private void accumulate(VarInputQueryService.RuleScenarioPnlRow row) {
            ScenarioKey scenarioKey = new ScenarioKey(row.getScenarioId(), row.getSubScenarioId(), row.getScenarioName());
            ScenarioPnlAggregate aggregate = scenarioPnls.get(scenarioKey);
            if (aggregate == null) {
                aggregate = new ScenarioPnlAggregate();
                scenarioPnls.put(scenarioKey, aggregate);
            }
            aggregate.allPnl = aggregate.allPnl.add(safePnl(row.getAllPnl()));
            aggregate.irPnl = aggregate.irPnl.add(safePnl(row.getIrPnl()));
            aggregate.fxPnl = aggregate.fxPnl.add(safePnl(row.getFxPnl()));
            aggregate.eqPnl = aggregate.eqPnl.add(safePnl(row.getEqPnl()));
            aggregate.commPnl = aggregate.commPnl.add(safePnl(row.getCommPnl()));
        }
    }

    private static class ScenarioPnlAggregate {
        private BigDecimal allPnl = BigDecimal.ZERO;
        private BigDecimal irPnl = BigDecimal.ZERO;
        private BigDecimal fxPnl = BigDecimal.ZERO;
        private BigDecimal eqPnl = BigDecimal.ZERO;
        private BigDecimal commPnl = BigDecimal.ZERO;

        private BigDecimal readByColumn(String pnlColumn) {
            if ("ALL_PNL".equalsIgnoreCase(pnlColumn)) {
                return allPnl;
            }
            if ("IR_PNL".equalsIgnoreCase(pnlColumn)) {
                return irPnl;
            }
            if ("FX_PNL".equalsIgnoreCase(pnlColumn)) {
                return fxPnl;
            }
            if ("EQ_PNL".equalsIgnoreCase(pnlColumn)) {
                return eqPnl;
            }
            if ("COMM_PNL".equalsIgnoreCase(pnlColumn)) {
                return commPnl;
            }
            return BigDecimal.ZERO;
        }
    }

    private static class ScenarioDetailRow {
        private final ScenarioKey scenarioKey;
        private final ScenarioPnlAggregate aggregate;

        private ScenarioDetailRow(ScenarioKey scenarioKey, ScenarioPnlAggregate aggregate) {
            this.scenarioKey = scenarioKey;
            this.aggregate = aggregate;
        }
    }

    private static class ScenarioKey {
        private final String scenarioId;
        private final String subScenarioId;
        private final String scenarioName;

        private ScenarioKey(String scenarioId, String subScenarioId, String scenarioName) {
            this.scenarioId = scenarioId;
            this.subScenarioId = subScenarioId;
            this.scenarioName = scenarioName;
        }

        private static ScenarioKey fromScenario(VarScenarioPnl scenario) {
            if (scenario == null) {
                return new ScenarioKey(null, null, null);
            }
            return new ScenarioKey(scenario.getScenarioId(), scenario.getSubScenarioId(), scenario.getScenarioName());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ScenarioKey)) {
                return false;
            }
            ScenarioKey that = (ScenarioKey) o;
            return nullSafe(scenarioId).equals(nullSafe(that.scenarioId))
                    && nullSafe(subScenarioId).equals(nullSafe(that.subScenarioId))
                    && nullSafe(scenarioName).equals(nullSafe(that.scenarioName));
        }

        @Override
        public int hashCode() {
            int result = nullSafe(scenarioId).hashCode();
            result = 31 * result + nullSafe(subScenarioId).hashCode();
            result = 31 * result + nullSafe(scenarioName).hashCode();
            return result;
        }
    }
}
