package com.zcyh.mr.springboot.measurement.frtb;

import com.zcyh.mr.springboot.measurement.aggregation.DimensionAggregationService;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.frtbsa.rrao.FrtbRraoCalculator;
import com.zcyh.mr.springboot.measurement.frtb.FrtbRraoInputRepository;
import com.zcyh.mr.springboot.input.db.RuleDefinitionRepository;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.springboot.measurement.RuleSummaryRequest;
import com.zcyh.mr.springboot.output.db.FrtbRraoResultPersistService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.parseRuleJson;

/**
 * FRTB RRAO 结果服务。
 */
@Service
public class FrtbRraoResultService {
    private static final String RULE_TYPE_RRAO = "FRTB_RRAO";

    private final RuleDefinitionRepository ruleDefinitionRepository;
    private final FrtbRraoInputRepository inputRepository;
    private final FrtbRraoResultPersistService resultPersistService;
    private final DimensionAggregationService dimensionAggregationService;
    private final FrtbRraoCalculator rraoCalculator = new FrtbRraoCalculator();

    public FrtbRraoResultService(RuleDefinitionRepository ruleDefinitionRepository,
                                 FrtbRraoInputRepository inputRepository,
                                 FrtbRraoResultPersistService resultPersistService,
                                 DimensionAggregationService dimensionAggregationService) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
        this.inputRepository = inputRepository;
        this.resultPersistService = resultPersistService;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    public JSONObject summarize(RuleSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = request.getBatchId();
        String dataDate = request.getDataDate();
        boolean persistResult = request.isPersistResult();

        JSONArray results = new JSONArray();
        List<FrtbRraoResultPersistService.RuleResult> persistResults =
                new ArrayList<FrtbRraoResultPersistService.RuleResult>();
        for (String ruleId : request.getRuleIds()) {
            JSONObject ruleJson = loadRuleSnapshot(ruleId);
            JSONArray summary = executeOne(batchId, dataDate, ruleId, ruleJson);
            persistResults.add(new FrtbRraoResultPersistService.RuleResult(ruleId, summary, ruleJson));

            JSONObject resultItem = new JSONObject();
            resultItem.put("rule_id", ruleId);
            resultItem.put("source_type", "db");
            resultItem.put("summary", summary);
            results.add(resultItem);
        }
        if (persistResult) {
            resultPersistService.replaceResults(
                    batchId, dataDate, request.getCleanupMode(), request.getRuleIds(), persistResults);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", results);
        return response;
    }

    private JSONArray executeOne(String batchId, String dataDate, String ruleId, JSONObject ruleJson) {
        AggregationRule rule = parseRule(ruleId, ruleJson);
        JSONArray output = new JSONArray();
        List<FrtbRraoCalculator.Input> inputs = inputRepository.queryInputs(batchId, dataDate, rule);
        List<FrtbRraoCalculator.Result> calculated = rraoCalculator.calculate(inputs);
        for (FrtbRraoCalculator.Result result : calculated) {
            output.add(toJson(batchId, dataDate, ruleId, result));
        }
        return output;
    }

    private JSONObject loadRuleSnapshot(String ruleId) {
        String ruleLabel = "FRTB RRAO 汇总规则";
        return parseRuleJson(ruleDefinitionRepository.findRequired(RULE_TYPE_RRAO, ruleId, ruleLabel), ruleLabel);
    }

    private AggregationRule parseRule(String ruleId, JSONObject ruleJson) {
        if (ruleJson == null) {
            throw new IllegalArgumentException("FRTB RRAO 汇总规则不能为空: " + ruleId);
        }
        AggregationRule rule = JSON.parseObject(ruleJson.toJSONString(), AggregationRule.class);
        if (rule == null) {
            throw new IllegalArgumentException("FRTB RRAO 汇总规则解析失败: " + ruleId);
        }
        rule.setRuleId(ruleId);
        rule.setRuleType(RULE_TYPE_RRAO);
        List<String> buildOrder = dimensionAggregationService.normalizeBuildOrder(rule.getBuildOrder());
        if (buildOrder.isEmpty()) {
            throw new IllegalArgumentException("FRTB RRAO 汇总规则必须配置 build_order: " + ruleId);
        }
        rule.setBuildOrder(buildOrder);
        return rule;
    }

    private static JSONObject toJson(String batchId,
                                     String dataDate,
                                     String ruleId,
                                     FrtbRraoCalculator.Result result) {
        JSONObject json = new JSONObject();
        json.put("BATCH_ID", batchId);
        json.put("DATA_DATE", dataDate);
        json.put("RULE_ID", ruleId);
        json.put("GROUP_TYPE", result.getGroupType());
        json.put("GROUP_VALUE", result.getGroupValue());
        json.put("RRAO_TYPE", result.getRraoType());
        json.put("TRADE_COUNT", result.getTradeCount());
        json.put("RRAO_NOTIONAL", result.getNotional());
        json.put("RRAO_CAPITAL", result.getCapital());
        return json;
    }

}
