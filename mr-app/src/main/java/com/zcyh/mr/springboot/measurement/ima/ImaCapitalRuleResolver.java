package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.springboot.input.db.InputFilterExpression;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.db.RuleDefinitionRepository;
import com.zcyh.mr.springboot.input.db.RuleDefinitionRow;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import org.springframework.stereotype.Service;

/**
 * IMA 资本计算规则解析器。
 */
@Service
public class ImaCapitalRuleResolver {
    private static final String RULE_TYPE_IMA = "IMA";
    private static final String RULE_TYPE_TRADE = "TRADE";

    private final RuleDefinitionRepository ruleDefinitionRepository;

    public ImaCapitalRuleResolver(RuleDefinitionRepository ruleDefinitionRepository) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
    }

    public LoadedRule loadImaRule(String ruleId) {
        String safeRuleId = requireText(ruleId, "IMA ruleId 不能为空");
        RuleDefinitionRow row = ruleDefinitionRepository.findRequired(
                RULE_TYPE_IMA, safeRuleId, "IMA 汇总规则");
        AggregationRule rule = JSON.parseObject(row.getRuleJson(), AggregationRule.class);
        if (rule == null) {
            throw new IllegalArgumentException("IMA 汇总规则解析失败: " + safeRuleId);
        }
        rule.setRuleId(row.getRuleId());
        rule.setRuleType(row.getRuleType());
        rule.setRuleName(row.getRuleName());
        return new LoadedRule(rule, row.getRuleJson());
    }

    public InputFilterExpression loadTradeFilter(String ruleId) {
        String safeRuleId = requireText(ruleId, "filter_rule_id 不能为空");
        RuleDefinitionRow row = ruleDefinitionRepository.findRequired(
                RULE_TYPE_TRADE, safeRuleId, "TRADE 过滤规则");
        JSONObject root = JSON.parseObject(row.getRuleJson());
        Object filterTree = root == null ? null : root.get("filterTree");
        if (filterTree == null) {
            throw new IllegalArgumentException("TRADE过滤规则缺少filterTree: " + safeRuleId);
        }
        InputFilterExpression expression = JSON.parseObject(
                JSON.toJSONString(filterTree),
                InputFilterExpression.class);
        if (expression == null) {
            throw new IllegalArgumentException("TRADE过滤规则filterTree解析失败: " + safeRuleId);
        }
        return expression;
    }

    private static String requireText(String value, String message) {
        String safe = trimToNull(value);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class LoadedRule {
        private final AggregationRule rule;
        private final String ruleJson;

        private LoadedRule(AggregationRule rule, String ruleJson) {
            this.rule = rule;
            this.ruleJson = ruleJson;
        }

        public AggregationRule getRule() {
            return rule;
        }

        public String getRuleJson() {
            return ruleJson;
        }
    }
}
