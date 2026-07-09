package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;
import static com.zcyh.mr.springboot.support.RequestParseSupport.readRequiredString;
import static com.zcyh.mr.springboot.support.RequestParseSupport.readString;
import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;
import static com.zcyh.mr.springboot.prepare.rule.AggregationRuleSupport.collectFilterFields;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.rrao.FrtbRraoCalculator;
import com.zcyh.mr.springboot.out.db.CalcRuleMetaPersistService;
import com.zcyh.mr.springboot.input.rule.AggregationRuleProvider;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.prepare.filter.AggregationFilterSqlBuilder;
import com.zcyh.mr.springboot.prepare.mapping.RuleColumnSqlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FRTB RRAO 结果服务。
 */
@Service
public class FrtbRraoResultService {
    private static final Logger log = LoggerFactory.getLogger(FrtbRraoResultService.class);
    private static final String RULE_TYPE_RRAO = "FRTB_RRAO";
    private static final String CALC_TYPE_RRAO = "RRAO";
    private static final String TARGET_TABLE = "TB_OUT_TRADE_RRAO_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,RRAO_TYPE,TRADE_COUNT,RRAO_NOTIONAL,RRAO_CAPITAL,CREATED_AT";
    private static final int DEFAULT_BATCH_SIZE = 5000;

    private final AggregationRuleProvider aggregationRuleProvider;
    private final JdbcTemplate engineResultDbJdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;
    private final DimensionAggregationService dimensionAggregationService;
    private final FrtbRraoCalculator rraoCalculator = new FrtbRraoCalculator();

    public FrtbRraoResultService(AggregationRuleProvider aggregationRuleProvider,
                                 @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate,
                                 DorisStreamLoadService dorisStreamLoadService,
                                 CalcRuleMetaPersistService calcRuleMetaPersistService,
                                 DimensionAggregationService dimensionAggregationService) {
        this.aggregationRuleProvider = aggregationRuleProvider;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    public JSONObject summarize(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = readRequiredString(request, "batch_id");
        String dataDate = readRequiredString(request, "data_date");
        boolean persistResult = readBoolean(request, true, "persist_result");
        JSONArray ruleList = resolveRuleList(request);

        if (persistResult) {
            deleteByBatchAndDataDate(batchId, dataDate);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_RRAO);
        }

        JSONArray results = new JSONArray();
        for (int i = 0; i < ruleList.size(); i++) {
            JSONObject ruleItem = ruleList.getJSONObject(i);
            if (ruleItem == null) {
                throw new IllegalArgumentException("rule_list[" + i + "] 不能为空对象");
            }
            RuleExecution execution = resolveRuleExecution(ruleItem);
            if ("db".equals(execution.sourceType)) {
                execution.ruleJson = loadRuleSnapshot(execution.ruleId);
            }
            JSONArray summary = executeOne(batchId, dataDate, execution.ruleId, execution.ruleJson);
            if (persistResult) {
                persist(batchId, dataDate, execution.ruleId, summary);
                persistRuleMeta(batchId, dataDate, execution);
            }

            JSONObject resultItem = new JSONObject();
            resultItem.put("rule_id", execution.ruleId);
            resultItem.put("source_type", execution.sourceType);
            resultItem.put("summary", summary);
            results.add(resultItem);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", results);
        return response;
    }

    private JSONArray executeOne(String batchId, String dataDate, String ruleId, JSONObject ruleJson) {
        AggregationRule rule = parseRule(ruleId, ruleJson);
        List<Map<String, Object>> detailRows = queryRraoRows(batchId, dataDate, rule);
        JSONArray output = new JSONArray();
        List<FrtbRraoCalculator.Input> inputs = buildRraoInputs(detailRows, rule.getBuildOrder());
        List<FrtbRraoCalculator.Result> calculated = rraoCalculator.calculate(inputs);
        for (FrtbRraoCalculator.Result result : calculated) {
            output.add(toJson(batchId, dataDate, ruleId, result));
        }
        return output;
    }

    private JSONObject loadRuleSnapshot(String ruleId) {
        return aggregationRuleProvider.loadRuleJson(RULE_TYPE_RRAO, ruleId, "FRTB RRAO 汇总规则");
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
        for (String level : buildOrder) {
            normalizeGroupType(level);
        }
        rule.setBuildOrder(buildOrder);
        return rule;
    }

    private List<Map<String, Object>> queryRraoRows(String batchId, String dataDate, AggregationRule rule) {
        List<Object> params = new ArrayList<Object>();
        Set<String> selectedFields = new LinkedHashSet<String>();
        for (String level : rule.getBuildOrder()) {
            String groupType = normalizeGroupType(level);
            if (!"TOTAL".equals(groupType)) {
                selectedFields.add(groupType);
            }
        }
        selectedFields.addAll(collectFilterFields(rule));
        boolean usePortfolioFlatView = RuleColumnSqlResolver.requiresPortfolioFlatView(selectedFields);

        StringBuilder sql = new StringBuilder()
                .append("SELECT r.INSTRUMENT_ID, r.RRAO_TYPE, r.RRAO_NOTIONAL");
        for (String field : selectedFields) {
            String column = resolveRuleColumn(field);
            if (column == null) {
                throw new IllegalArgumentException("RRAO 规则字段无法映射到交易结果明细: " + field);
            }
            sql.append(", ").append(column).append(" AS ").append(field);
        }
        sql.append(" FROM TB_OUT_TRADE_RESULT_DETAIL r ");
        if (usePortfolioFlatView) {
            sql.append("LEFT JOIN ").append(RuleColumnSqlResolver.PORTFOLIO_FLAT_VIEW)
                    .append(" p ON p.BATCH_ID = r.BATCH_ID AND p.DATA_DATE = r.DATA_DATE AND p.PORTFOLIO_CODE = r.PORTFOLIO ");
        }
        sql.append("WHERE r.BATCH_ID = ? AND r.DATA_DATE = ? ")
                .append("AND r.RRAO_TYPE IS NOT NULL AND r.RRAO_NOTIONAL IS NOT NULL");
        params.add(batchId);
        params.add(dataDate);
        AggregationFilterSqlBuilder.appendWhereClause(sql, params, rule, new AggregationFilterSqlBuilder.ColumnResolver() {
            @Override
            public String resolve(String field) {
                return resolveRuleColumn(field);
            }
        });
        sql.append(" ORDER BY r.INSTRUMENT_ID");

        try {
            return engineResultDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 RRAO 汇总底层明细失败，请确认交易结果明细已生成且可访问: " + ex.getMessage(), ex);
        }
    }

    private List<FrtbRraoCalculator.Input> buildRraoInputs(List<Map<String, Object>> rows, List<String> buildOrder) {
        List<FrtbRraoCalculator.Input> inputs = new ArrayList<FrtbRraoCalculator.Input>();
        if (rows == null || rows.isEmpty()) {
            return inputs;
        }
        for (Map<String, Object> row : rows) {
            String rraoType = trimToNull(stringValue(row.get("RRAO_TYPE")));
            BigDecimal notional = toBigDecimal(row.get("RRAO_NOTIONAL"));
            if (rraoType == null || notional == null) {
                continue;
            }
            List<String> pathValues = new ArrayList<String>();
            for (String level : buildOrder) {
                String groupType;
                String groupValue;
                if ("TOTAL".equals(level)) {
                    groupType = "TOTAL";
                    groupValue = "TOTAL";
                } else {
                    String levelValue = dimensionAggregationService.normalizeDimensionValue(row.get(level));
                    pathValues.add(levelValue);
                    groupType = level;
                    groupValue = dimensionAggregationService.buildGroupValue(pathValues);
                }
                inputs.add(new FrtbRraoCalculator.Input(groupType, groupValue, rraoType, notional));
            }
        }
        return inputs;
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

    private void deleteByBatchAndDataDate(String batchId, String dataDate) {
        int deleted = engineResultDbJdbcTemplate.update(
                "DELETE FROM TB_OUT_TRADE_RRAO_RESULT WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, dataDate);
        if (deleted > 0) {
            log.info("清理 RRAO 汇总历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
        }
    }

    private void persist(String batchId, String dataDate, String ruleId, JSONArray summary) {
        if (summary == null || summary.isEmpty()) {
            log.info("RRAO 汇总结果为空: batchId={}, dataDate={}, ruleId={}", batchId, dataDate, ruleId);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "frtb_rrao_" + batchId + "_" + dataDate + "_" + ruleId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < summary.size(); i++) {
            JSONObject row = summary.getJSONObject(i);
            buffer.appendRow(
                    row.getString("BATCH_ID"),
                    row.getString("DATA_DATE"),
                    row.getString("RULE_ID"),
                    row.getString("GROUP_TYPE"),
                    row.getString("GROUP_VALUE"),
                    row.getString("RRAO_TYPE"),
                    row.getLong("TRADE_COUNT"),
                    DorisCsvStreamLoadBuffer.decimalText(row.getBigDecimal("RRAO_NOTIONAL")),
                    DorisCsvStreamLoadBuffer.decimalText(row.getBigDecimal("RRAO_CAPITAL")),
                    now
            );
        }
        buffer.flush();
        log.info("RRAO 汇总结果落库完成: batchId={}, dataDate={}, ruleId={}, rows={}", batchId, dataDate, ruleId, summary.size());
    }

    private void persistRuleMeta(String batchId, String dataDate, RuleExecution execution) {
        String ruleJsonStr = execution.ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_RRAO, execution.ruleId, ruleJsonStr);
    }

    private static JSONArray resolveRuleList(JSONObject request) {
        JSONArray ruleList = request.getJSONArray("rule_list");
        if (ruleList != null && !ruleList.isEmpty()) {
            return ruleList;
        }
        JSONArray single = new JSONArray();
        JSONObject item = new JSONObject();
        String ruleId = readString(request, "rule_id");
        JSONObject rule = request.getJSONObject("rule");
        if (ruleId != null) {
            item.put("rule_id", ruleId);
            single.add(item);
            return single;
        }
        if (rule != null) {
            item.put("rule", rule);
            single.add(item);
            return single;
        }
        throw new IllegalArgumentException("FRTB RRAO 汇总必须显式提供 rule_id、rule 或 rule_list");
    }

    private static RuleExecution resolveRuleExecution(JSONObject ruleItem) {
        JSONObject rule = ruleItem.getJSONObject("rule");
        String ruleId = readString(ruleItem, "rule_id");
        if (rule == null) {
            if (ruleId == null) {
                throw new IllegalArgumentException("rule_list 项必须提供 rule_id 或 rule");
            }
            return RuleExecution.db(ruleId);
        }
        if (ruleId == null) {
            ruleId = readString(rule, "rule_id");
        }
        if (ruleId == null) {
            throw new IllegalArgumentException("FRTB RRAO inline rule 必须显式提供 rule_id");
        }
        rule.put("rule_id", ruleId);
        rule.put("rule_type", RULE_TYPE_RRAO);
        return RuleExecution.inline(ruleId, rule);
    }

    private static String resolveRuleColumn(String field) {
        String safeField = RuleColumnSqlResolver.normalizeField(field);
        if (safeField == null) {
            return null;
        }
        if (RuleColumnSqlResolver.isPortfolioFlatField(safeField)) {
            return "p." + safeField;
        }
        if ("BATCH_ID".equals(safeField)) {
            return "r.BATCH_ID";
        }
        if ("DATA_DATE".equals(safeField)) {
            return "r.DATA_DATE";
        }
        if ("INSTRUMENT_ID".equals(safeField)) {
            return "r.INSTRUMENT_ID";
        }
        if ("PRODUCT_CODE".equals(safeField)) {
            return "r.PRODUCT_CODE";
        }
        if ("PORTFOLIO".equals(safeField)) {
            return "r.PORTFOLIO";
        }
        if ("DESK".equals(safeField)) {
            return "r.DESK";
        }
        if ("TRADER".equals(safeField)) {
            return "r.TRADER";
        }
        if ("VALUATION_CCY".equals(safeField)) {
            return "r.VALUATION_CCY";
        }
        if ("RRAO_TYPE".equals(safeField)) {
            return "r.RRAO_TYPE";
        }
        if ("RRAO_NOTIONAL".equals(safeField)) {
            return "r.RRAO_NOTIONAL";
        }
        return null;
    }

    private static String normalizeGroupType(String value) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            throw new IllegalArgumentException("RRAO build_order 不能包含空值");
        }
        String groupType = safeValue.toUpperCase(Locale.ROOT);
        if (resolveRuleColumn(groupType) == null && !"TOTAL".equals(groupType)) {
            throw new IllegalArgumentException("RRAO build_order 字段不支持: " + value);
        }
        return groupType;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        String text = trimToNull(String.valueOf(value));
        return text == null ? null : new BigDecimal(text);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static class RuleExecution {
        private String ruleId;
        private String sourceType;
        private JSONObject ruleJson;

        static RuleExecution db(String ruleId) {
            RuleExecution execution = new RuleExecution();
            execution.ruleId = ruleId;
            execution.sourceType = "db";
            return execution;
        }

        static RuleExecution inline(String ruleId, JSONObject ruleJson) {
            RuleExecution execution = new RuleExecution();
            execution.ruleId = ruleId;
            execution.sourceType = "db_inline";
            execution.ruleJson = ruleJson;
            return execution;
        }
    }
}
