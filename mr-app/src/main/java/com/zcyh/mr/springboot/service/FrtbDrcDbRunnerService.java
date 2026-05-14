package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.drc.DRCModule;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.springboot.model.AggregationRule;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FRTB DRC 数据库输入执行服务。
 * 按 batch_id + data_date 读取明细输入，执行 DRC 计量，并返回核心结果模块。
 */
@Service
public class FrtbDrcDbRunnerService {
    private static final String TOTAL = "TOTAL";
    private static final String CALC_TYPE_DRC = "DRC";

    private final FrtbDrcInputQueryService inputQueryService;
    private final DimensionAggregationService dimensionAggregationService;

    public FrtbDrcDbRunnerService(FrtbDrcInputQueryService inputQueryService,
                                  DimensionAggregationService dimensionAggregationService) {
        this.inputQueryService = inputQueryService;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    /**
     * 入口参数 JSON 示例：
     * {"batch_id":"...","data_date":"yyyyMMdd"}
     */
    public String calculateByBatch(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }
        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");
        JSONObject result = calculateByBatch(batchId, dataDate);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    /**
     * 按批次和估值日执行 DRC 计量，只保留 DRC_VALUE 与 DECOMP_LEGALENTITY 两类结果。
     */
    public JSONObject calculateByBatch(String batchId, String dataDate) {
        List<DrcDetail> inputList = inputQueryService.queryDrcDetails(batchId, dataDate);
        LocalDate valuationDate = parseDataDate(dataDate);
        JSONObject raw = DRCModule.calc(inputList, valuationDate);

        JSONObject result = new JSONObject();
        result.put("DRC_VALUE", raw.getJSONArray("DRC_VALUE"));
        result.put("DECOMP_LEGALENTITY", raw.getJSONArray("DECOMP_LEGALENTITY"));
        return result;
    }

    public JSONObject loadRuleSnapshot(String ruleId) {
        AggregationRule rule = loadExecutableRule(ruleId);
        return JSON.parseObject(JSON.toJSONString(rule, JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    public String calculateByRule(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }
        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");
        String ruleId = requireTopLevelString(req, "rule_id");
        AggregationRule rule = loadExecutableRule(ruleId);
        JSONObject result = calculateByRule(batchId, dataDate, rule);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    public String calculateByInlineRule(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }
        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");
        JSONObject ruleJson = req.getJSONObject("rule");
        if (ruleJson == null) {
            throw new IllegalArgumentException("rule 不能为空，需要包含 rule_id/rule_type/build_order/filter_tree");
        }
        AggregationRule rule = parseInlineRule(ruleJson);
        JSONObject result = calculateByRule(batchId, dataDate, rule);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private JSONObject calculateByRule(String batchId, String dataDate, AggregationRule rule) {
        List<FrtbDrcInputQueryService.RuleDrcDetailRow> rows =
                inputQueryService.queryRuleDetailRows(batchId, dataDate, rule);
        Map<String, DrcGroup> groups = buildGroups(rule, rows);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("DRC 规则未生成有效维度分组: rule_id=" + rule.getRuleId());
        }

        LocalDate valuationDate = parseDataDate(dataDate);
        JSONObject result = new JSONObject();
        JSONArray drcValueRows = new JSONArray();
        JSONArray legalEntityRows = new JSONArray();
        for (DrcGroup group : groups.values()) {
            JSONObject raw = DRCModule.calc(group.details, valuationDate);
            appendDecoratedRows(drcValueRows, raw.getJSONArray("DRC_VALUE"), rule.getRuleId(), group);
            appendDecoratedRows(legalEntityRows, raw.getJSONArray("DECOMP_LEGALENTITY"), rule.getRuleId(), group);
        }
        result.put("DRC_VALUE", drcValueRows);
        result.put("DECOMP_LEGALENTITY", legalEntityRows);
        return result;
    }

    private Map<String, DrcGroup> buildGroups(AggregationRule rule,
                                              List<FrtbDrcInputQueryService.RuleDrcDetailRow> rows) {
        Map<String, DrcGroup> groups = new LinkedHashMap<String, DrcGroup>();
        for (FrtbDrcInputQueryService.RuleDrcDetailRow row : rows) {
            List<String> pathValues = new ArrayList<String>();
            for (String level : rule.getBuildOrder()) {
                if (TOTAL.equalsIgnoreCase(level)) {
                    addGroup(groups, TOTAL, TOTAL, row.getDetail());
                    continue;
                }
                String levelValue = dimensionAggregationService.normalizeDimensionValue(row.getFields().get(level));
                pathValues.add(levelValue);
                String groupValue = dimensionAggregationService.buildGroupValue(pathValues);
                addGroup(groups, level, groupValue, row.getDetail());
            }
        }
        return groups;
    }

    private static void addGroup(Map<String, DrcGroup> groups, String groupType, String groupValue, DrcDetail detail) {
        String key = groupType + "\u0001" + groupValue;
        DrcGroup group = groups.get(key);
        if (group == null) {
            group = new DrcGroup(groupType, groupValue);
            groups.put(key, group);
        }
        group.details.add(detail);
    }

    private static void appendDecoratedRows(JSONArray output, JSONArray rows, String ruleId, DrcGroup group) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            JSONObject item = rows.getJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject copy = JSON.parseObject(item.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
            copy.put("RULE_ID", ruleId);
            copy.put("GROUP_TYPE", group.groupType);
            copy.put("GROUP_VALUE", group.groupValue);
            output.add(copy);
        }
    }

    private AggregationRule loadExecutableRule(String ruleId) {
        AggregationRule rule = inputQueryService.loadAggregationRule(ruleId);
        normalizeExecutableRule(rule);
        return rule;
    }

    private AggregationRule parseInlineRule(JSONObject ruleJson) {
        AggregationRule rule = JSON.parseObject(
                ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain),
                AggregationRule.class);
        if (rule == null) {
            throw new IllegalArgumentException("DRC 规则解析失败");
        }
        normalizeExecutableRule(rule);
        return rule;
    }

    private void normalizeExecutableRule(AggregationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("DRC 规则不能为空");
        }
        String ruleId = trimToNull(rule.getRuleId());
        if (ruleId == null) {
            throw new IllegalArgumentException("DRC 规则 rule_id 不能为空");
        }
        String ruleType = trimToNull(rule.getRuleType());
        if (!CALC_TYPE_DRC.equalsIgnoreCase(ruleType)) {
            throw new IllegalArgumentException("DRC 规则 rule_type 必须为 DRC: rule_id=" + ruleId);
        }
        List<String> buildOrder = dimensionAggregationService.normalizeBuildOrder(rule.getBuildOrder());
        if (buildOrder.isEmpty()) {
            throw new IllegalArgumentException("DRC 规则 build_order 不能为空: rule_id=" + ruleId);
        }
        rule.setRuleId(ruleId);
        rule.setRuleType(CALC_TYPE_DRC);
        rule.setBuildOrder(buildOrder);
    }

    private static LocalDate parseDataDate(String dataDate) {
        String value = trimToNull(dataDate);
        if (value == null) {
            throw new IllegalArgumentException("data_date 必填");
        }
        if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("data_date 格式必须为 yyyyMMdd 或 yyyy-MM-dd");
        }
    }

    private static String requireTopLevelString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " 必填");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class DrcGroup {
        private final String groupType;
        private final String groupValue;
        private final List<DrcDetail> details = new ArrayList<DrcDetail>();

        private DrcGroup(String groupType, String groupValue) {
            this.groupType = groupType;
            this.groupValue = groupValue;
        }
    }
}
