package com.zcyh.mr.springboot.engine;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.capital.ImaCapitalCalculator;
import com.zcyh.mr.frtbima.es.EsCalculator;
import com.zcyh.mr.frtbima.model.EsResult;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.frtbima.model.ImaEsResultDetail;
import com.zcyh.mr.frtbima.model.ImaNmrfResult;
import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.SesResult;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.model.RuleSummaryRequest;
import com.zcyh.mr.springboot.service.BatchTradeDataLoader;
import com.zcyh.mr.springboot.out.db.CalcRuleMetaPersistService;
import com.zcyh.mr.springboot.service.DimensionAggregationService;
import com.zcyh.mr.springboot.service.FrtbSbaDbRunnerService;
import com.zcyh.mr.springboot.out.db.ImaCapitalResultPersistService;
import com.zcyh.mr.springboot.out.db.ImaEsResultDetailPersistService;
import com.zcyh.mr.springboot.out.db.ImaNmrfResultPersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * IMA 资本汇总引擎适配器。
 *
 * <p>职责：
 * <ol>
 *   <li>从 Doris 读取情景 PnL 输出（modellable PnL + NMRF PnL）。</li>
 *   <li>调用 {@link ImaCapitalCalculator} 计算 IMCC、SES、总资本。</li>
 *   <li>将 {@link ImaCapitalResult} 落库。</li>
 * </ol>
 *
 * <p>输入 JSON 关键字段：
 * <pre>
 * {
 *   "batch_id": "...",
 *   "data_date": "20260409",
 *   "sa_by_desk": {"DESK_A": 1000000, "DESK_B": 2000000},  // 标准法资本，用于 Amber 系数
 *   "amber_desks": ["DESK_C"],                              // Amber 区交易台
 *   "green_desks": ["DESK_A", "DESK_B"]                     // Green 区交易台
 * }
 * </pre>
 */
public class ImaCapitalEngineAdapter implements EngineAdapter {

    public static final String CODE = "ima_capital";

    private static final Logger log = LoggerFactory.getLogger(ImaCapitalEngineAdapter.class);
    private static final String RULE_TYPE_IMA = "IMA";
    private static final String RULE_TYPE_TRADE = "TRADE";
    private static final String RULE_TYPE_FRTB_SBA = "FRTB_SBA";
    private static final String TREATMENT_IMA_GREEN = "IMA_GREEN";
    private static final String TREATMENT_IMA_AMBER = "IMA_AMBER";
    private static final String TREATMENT_SA = "SA";
    private static final String TOTAL = "TOTAL";

    /** 查询可建模 PnL */
    private static final String QUERY_MODELLABLE =
            "SELECT REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, "
            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, "
            + "INSTRUMENT_ID, PRODUCT_CODE, LH_DAYS, "
            + "BASE_VALUATION_CNY, IR_VALUATION, IR_PNL, CS_VALUATION, CS_PNL, FX_VALUATION, FX_PNL, "
            + "EQ_VALUATION, EQ_PNL, COMM_VALUATION, COMM_PNL, "
            + "ALL_VALUATION, ALL_PNL, CREATED_AT "
            + "FROM TB_OUT_IMA_MODELLABLE_SCENARIO_PNL "
            + "WHERE BATCH_ID = ? AND DATA_DATE = ?";

    /** 查询 NMRF PnL */
    private static final String QUERY_NMRF =
            "SELECT REQUEST_ID, JOB_ID, BATCH_ID, SEQ_NO, DATA_DATE, "
            + "SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, "
            + "INSTRUMENT_ID, PRODUCT_CODE, RISK_FACTOR_ID, NMRF_TYPE, "
            + "BASE_VALUATION_CNY, STRESS_VALUATION_CNY, PNL, CREATED_AT "
            + "FROM TB_OUT_IMA_NMRF_SCENARIO_PNL "
            + "WHERE BATCH_ID = ? AND DATA_DATE = ?";

    private final JdbcTemplate engineDbJdbcTemplate;
    private final JdbcTemplate resultDbJdbcTemplate;
    private final BatchTradeDataLoader tradeDataLoader;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;
    private final DimensionAggregationService dimensionAggregationService;
    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final ImaCapitalResultPersistService capitalPersistService;
    private final ImaEsResultDetailPersistService esResultDetailPersistService;
    private final ImaNmrfResultPersistService nmrfResultPersistService;
    private final EsCalculator esCalculator = new EsCalculator();
    private final ImaCapitalCalculator capitalCalculator = new ImaCapitalCalculator();

    public ImaCapitalEngineAdapter(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate resultDbJdbcTemplate,
            BatchTradeDataLoader tradeDataLoader,
            CalcRuleMetaPersistService calcRuleMetaPersistService,
            DimensionAggregationService dimensionAggregationService,
            FrtbSbaDbRunnerService frtbSbaDbRunnerService,
            ImaCapitalResultPersistService capitalPersistService,
            ImaEsResultDetailPersistService esResultDetailPersistService,
            ImaNmrfResultPersistService nmrfResultPersistService) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.resultDbJdbcTemplate = resultDbJdbcTemplate;
        this.tradeDataLoader = tradeDataLoader;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
        this.dimensionAggregationService = dimensionAggregationService;
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
        this.capitalPersistService = capitalPersistService;
        this.esResultDetailPersistService = esResultDetailPersistService;
        this.nmrfResultPersistService = nmrfResultPersistService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "IMA Phase2：从 Doris 读取 PnL → 计算 IMCC/SES/总资本 → 落库";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) throw new IllegalArgumentException("IMA Phase2 input 不能为空");

        if (readBoolean(req, false, "trial")) {
            return JSON.toJSONString(calculateTrial(req), JSONWriter.Feature.WriteBigDecimalAsPlain);
        }
        throw new IllegalArgumentException("IMA 资本汇总请使用 /api/summary/ima/capital");
    }

    public JSONObject summarize(RuleSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        return calculateSummary(
                request.getBatchId(),
                request.getDataDate(),
                request.getRuleIds(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptySet());
    }

    private JSONObject calculateSummary(String batchId,
                                        String dataDate,
                                        List<String> ruleIds,
                                        Map<String, BigDecimal> saByDesk,
                                        Set<String> amberDesks,
                                        Set<String> greenDesks) {
        // 从 Doris 读取情景 PnL 结果
        List<SubsetPnlRecord> subsetPnls = queryModellablePnl(batchId, dataDate);
        List<NmrfPnlRecord> nmrfPnls = queryNmrfPnl(batchId, dataDate);

        log.info("IMA 资本汇总开始: batchId={}, modellableRows={}, nmrfRows={}",
                batchId, subsetPnls.size(), nmrfPnls.size());

        List<ImaCapitalResult> capitalResults = new ArrayList<ImaCapitalResult>();
        List<ImaEsResultDetail> esResultDetails = new ArrayList<ImaEsResultDetail>();
        List<ImaNmrfResult> nmrfResults = new ArrayList<ImaNmrfResult>();
        List<LoadedRule> loadedRules = new ArrayList<LoadedRule>();
        LocalDate localDataDate = LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        for (String ruleId : ruleIds) {
            LoadedRule loadedRule = loadAggregationRule(ruleId);
            loadedRules.add(loadedRule);
            Map<String, DimensionRow> dimensionsByInstrument = buildDimensionRows(localDataDate, loadedRule.rule);
            RuleCapitalResults ruleResults = calculateByRule(
                    loadedRule.rule,
                    dimensionsByInstrument,
                    subsetPnls,
                    nmrfPnls,
                    saByDesk,
                    amberDesks,
                    greenDesks,
                    dataDate,
                    batchId);
            capitalResults.addAll(ruleResults.capitalResults);
            esResultDetails.addAll(ruleResults.esResultDetails);
            nmrfResults.addAll(ruleResults.nmrfResults);
        }

        // 落库
        capitalPersistService.deleteByBatchAndDataDate(batchId, dataDate);
        esResultDetailPersistService.deleteByBatchAndDataDate(batchId, dataDate);
        nmrfResultPersistService.deleteByBatchAndDataDate(batchId, dataDate);
        capitalPersistService.persist(capitalResults);
        esResultDetailPersistService.persist(esResultDetails);
        nmrfResultPersistService.persist(nmrfResults);
        calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, "IMA");
        for (LoadedRule loadedRule : loadedRules) {
            calcRuleMetaPersistService.persist(batchId, dataDate, "IMA",
                    loadedRule.rule.getRuleId(), loadedRule.ruleJson);
        }

        log.info("IMA Phase2 完成: batchId={}, ruleCount={}, resultRows={}",
                batchId, ruleIds.size(), capitalResults.size());

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", capitalResults);
        return response;
    }

    private JSONObject calculateTrial(JSONObject req) {
        String batchId = required(req, "batch_id");
        String dataDate = required(req, "data_date");
        String imaRuleId = required(req, "ima_rule_id");
        String filterRuleId = required(req, "filter_rule_id");
        LinkedHashMap<String, String> deskTreatments = parseDeskTreatments(req);

        Set<String> greenDesks = desksByTreatment(deskTreatments, TREATMENT_IMA_GREEN);
        Set<String> amberDesks = desksByTreatment(deskTreatments, TREATMENT_IMA_AMBER);
        Set<String> saDesks = desksByTreatment(deskTreatments, TREATMENT_SA);
        LinkedHashSet<String> imaDesks = new LinkedHashSet<String>();
        imaDesks.addAll(greenDesks);
        imaDesks.addAll(amberDesks);

        AggregationRule.FilterExpression tradeFilter = loadTradeFilterRule(filterRuleId);
        SbaCapitalSnapshot allDeskSa = calculateSbaSnapshot(
                batchId,
                dataDate,
                filterRuleId,
                tradeFilter,
                deskTreatments.keySet(),
                "ALL");
        SbaCapitalSnapshot saDeskCapital = saDesks.isEmpty()
                ? SbaCapitalSnapshot.empty()
                : calculateSbaSnapshot(batchId, dataDate, filterRuleId, tradeFilter, saDesks, "SA");

        Map<String, ImaCapitalResult> imaByGroup = new LinkedHashMap<String, ImaCapitalResult>();
        if (!imaDesks.isEmpty()) {
            LoadedRule loadedRule = loadAggregationRule(imaRuleId);
            AggregationRule trialRule = buildTrialImaRule(
                    loadedRule.rule,
                    andFilters(tradeFilter, deskInFilter(imaDesks)));
            LocalDate localDataDate = LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
            RuleCapitalResults ruleResults = calculateByRule(
                    trialRule,
                    buildDimensionRows(localDataDate, trialRule),
                    queryModellablePnl(batchId, dataDate),
                    queryNmrfPnl(batchId, dataDate),
                    allDeskSa.capitalByDesk,
                    amberDesks,
                    greenDesks,
                    dataDate,
                    batchId);
            for (ImaCapitalResult result : ruleResults.capitalResults) {
                imaByGroup.put(result.getGroupType() + "|" + result.getGroupValue(), result);
            }
        }

        List<JSONObject> rows = new ArrayList<JSONObject>();
        ImaCapitalResult totalIma = imaByGroup.get(TOTAL + "|" + TOTAL);
        rows.add(buildTrialRow(
                TOTAL,
                TOTAL,
                "MIXED",
                totalIma,
                saDeskCapital.totalCapital));

        for (Map.Entry<String, String> entry : deskTreatments.entrySet()) {
            String desk = entry.getKey();
            String treatment = entry.getValue();
            ImaCapitalResult imaResult = TREATMENT_SA.equals(treatment)
                    ? null
                    : imaByGroup.get("DESK|" + desk);
            BigDecimal saCapital = TREATMENT_SA.equals(treatment)
                    ? saDeskCapital.capitalByDesk.get(desk)
                    : BigDecimal.ZERO;
            rows.add(buildTrialRow("DESK", desk, treatment, imaResult, zeroIfNull(saCapital)));
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("rule_id", imaRuleId);
        response.put("filter_rule_id", filterRuleId);
        response.put("rows", rows);
        return response;
    }

    private AggregationRule buildTrialImaRule(AggregationRule sourceRule,
                                              AggregationRule.FilterExpression filterTree) {
        AggregationRule rule = new AggregationRule();
        rule.setRuleId(sourceRule.getRuleId());
        rule.setRuleType(RULE_TYPE_IMA);
        rule.setRuleName(sourceRule.getRuleName());
        List<String> buildOrder = new ArrayList<String>();
        buildOrder.add("DESK");
        rule.setBuildOrder(buildOrder);
        rule.setFilterTree(filterTree);
        validateImaRule(rule);
        return rule;
    }

    private SbaCapitalSnapshot calculateSbaSnapshot(String batchId,
                                                    String dataDate,
                                                    String filterRuleId,
                                                    AggregationRule.FilterExpression tradeFilter,
                                                    Set<String> desks,
                                                    String purpose) {
        if (desks == null || desks.isEmpty()) {
            return SbaCapitalSnapshot.empty();
        }
        JSONObject rule = new JSONObject();
        JSONArray buildOrder = new JSONArray();
        buildOrder.add("DESK");
        rule.put("build_order", buildOrder);
        rule.put("filterTree", JSON.toJSON(andFilters(tradeFilter, deskInFilter(desks))));

        AggregationRule ruleDefinition = frtbSbaDbRunnerService.parseRuleDefinition(
                rule,
                buildTrialSbaRuleId(filterRuleId, desks, purpose));
        Map<String, Object> result = frtbSbaDbRunnerService.calculate(
                batchId,
                dataDate,
                java.util.Collections.singletonList(ruleDefinition),
                false,
                0);
        return parseSbaSnapshot(JSON.parseObject(JSON.toJSONString(
                result, JSONWriter.Feature.WriteBigDecimalAsPlain)));
    }

    private SbaCapitalSnapshot parseSbaSnapshot(JSONObject summary) {
        if (summary == null || summary.isEmpty()) {
            throw new IllegalStateException("IMA试算SA汇总结果为空");
        }
        BigDecimal totalCapital = null;
        Map<String, BigDecimal> byDesk = new LinkedHashMap<String, BigDecimal>();
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.startsWith("__")) {
                continue;
            }
            String groupValue = groupPathFromTaskKey(key);
            BigDecimal capital = extractSbaCapital(entry.getValue(), key);
            if (TOTAL.equals(groupValue)) {
                totalCapital = capital;
            } else {
                byDesk.put(groupValue, capital);
            }
        }
        if (totalCapital == null) {
            throw new IllegalStateException("IMA试算SA汇总缺少TOTAL资本");
        }
        return new SbaCapitalSnapshot(totalCapital, byDesk);
    }

    private BigDecimal extractSbaCapital(Object rawResult, String taskKey) {
        JSONObject result = toJsonObject(rawResult);
        JSONObject all = result == null ? null : result.getJSONObject("ALL");
        BigDecimal capital = all == null ? null : all.getBigDecimal("capital");
        if (capital == null) {
            throw new IllegalStateException("IMA试算SA汇总缺少ALL.capital: taskKey=" + taskKey);
        }
        return capital;
    }

    private String groupPathFromTaskKey(String taskKey) {
        String safe = trimToNull(taskKey);
        if (safe == null) {
            throw new IllegalStateException("IMA试算SA汇总任务key为空");
        }
        int index = safe.indexOf('|');
        if (index < 0 || index == safe.length() - 1) {
            return safe;
        }
        return safe.substring(index + 1);
    }

    private JSONObject buildTrialRow(String groupType,
                                     String groupValue,
                                     String treatment,
                                     ImaCapitalResult imaResult,
                                     BigDecimal saCapital) {
        BigDecimal imaCapital = imaCapitalValue(imaResult);
        BigDecimal safeSaCapital = zeroIfNull(saCapital);
        JSONObject row = new JSONObject();
        row.put("group_type", groupType);
        row.put("group_value", groupValue);
        row.put("treatment", treatment);
        row.put("imcc", imccValue(imaResult));
        row.put("ses", sesValue(imaResult));
        row.put("amber_surcharge_ratio", imaResult == null ? BigDecimal.ZERO : zeroIfNull(imaResult.getAmberSurchargeRatio()));
        row.put("ima_capital", imaCapital);
        row.put("sa_capital", safeSaCapital);
        row.put("total_capital", imaCapital.add(safeSaCapital));
        return row;
    }

    private AggregationRule.FilterExpression loadTradeFilterRule(String ruleId) {
        String safeRuleId = trimToNull(ruleId);
        if (safeRuleId == null) {
            throw new IllegalArgumentException("filter_rule_id 不能为空");
        }
        List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                "SELECT RULE_JSON FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
                RULE_TYPE_TRADE,
                safeRuleId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到TRADE过滤规则: " + safeRuleId);
        }
        String ruleJson = trimToNull(stringValue(rows.get(0).get("RULE_JSON")));
        if (ruleJson == null) {
            throw new IllegalArgumentException("TRADE过滤规则内容为空: " + safeRuleId);
        }
        JSONObject root = JSON.parseObject(ruleJson);
        Object filterTree = root == null ? null : root.get("filterTree");
        if (filterTree == null) {
            throw new IllegalArgumentException("TRADE过滤规则缺少filterTree: " + safeRuleId);
        }
        AggregationRule.FilterExpression expression = JSON.parseObject(
                JSON.toJSONString(filterTree),
                AggregationRule.FilterExpression.class);
        if (expression == null) {
            throw new IllegalArgumentException("TRADE过滤规则filterTree解析失败: " + safeRuleId);
        }
        return expression;
    }

    private LinkedHashMap<String, String> parseDeskTreatments(JSONObject req) {
        JSONArray array = req.getJSONArray("desk_treatments");
        if (array == null || array.isEmpty()) {
            throw new IllegalArgumentException("desk_treatments 不能为空");
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("desk_treatments[" + i + "] 不能为空");
            }
            String desk = trimToNull(item.getString("desk"));
            if (desk == null) {
                throw new IllegalArgumentException("desk_treatments[" + i + "].desk 不能为空");
            }
            if (result.containsKey(desk)) {
                throw new IllegalArgumentException("desk_treatments 包含重复DESK: " + desk);
            }
            String treatment = normalizeTreatment(item.getString("treatment"), i);
            result.put(desk, treatment);
        }
        return result;
    }

    private String normalizeTreatment(String value, int index) {
        String treatment = trimToNull(value);
        if (treatment == null) {
            throw new IllegalArgumentException("desk_treatments[" + index + "].treatment 不能为空");
        }
        treatment = treatment.toUpperCase(Locale.ROOT);
        if (!TREATMENT_IMA_GREEN.equals(treatment)
                && !TREATMENT_IMA_AMBER.equals(treatment)
                && !TREATMENT_SA.equals(treatment)) {
            throw new IllegalArgumentException("desk_treatments[" + index + "].treatment 仅支持 IMA_GREEN/IMA_AMBER/SA");
        }
        return treatment;
    }

    private Set<String> desksByTreatment(Map<String, String> deskTreatments, String treatment) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (Map.Entry<String, String> entry : deskTreatments.entrySet()) {
            if (treatment.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private AggregationRule.FilterExpression andFilters(AggregationRule.FilterExpression first,
                                                        AggregationRule.FilterExpression second) {
        List<AggregationRule.FilterExpression> children = new ArrayList<AggregationRule.FilterExpression>();
        if (first != null) {
            children.add(first);
        }
        if (second != null) {
            children.add(second);
        }
        if (children.isEmpty()) {
            return null;
        }
        if (children.size() == 1) {
            return children.get(0);
        }
        AggregationRule.FilterExpression root = new AggregationRule.FilterExpression();
        root.setLogic("AND");
        root.setChildren(children);
        return root;
    }

    private AggregationRule.FilterExpression deskInFilter(Set<String> desks) {
        if (desks == null || desks.isEmpty()) {
            throw new IllegalArgumentException("DESK过滤列表不能为空");
        }
        AggregationRule.FilterExpression filter = new AggregationRule.FilterExpression();
        filter.setField("DESK");
        filter.setOperator("IN");
        filter.setValue(new ArrayList<String>(desks));
        return filter;
    }

    private String buildTrialSbaRuleId(String filterRuleId, Set<String> desks, String purpose) {
        String seed = filterRuleId + "|" + purpose + "|" + String.join(",", desks);
        return "IMA_TRIAL_SA_" + purpose + "_" + Integer.toHexString(seed.hashCode()).toUpperCase(Locale.ROOT);
    }

    private static JSONObject toJsonObject(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value == null) {
            return null;
        }
        return JSON.parseObject(JSON.toJSONString(value));
    }

    private static BigDecimal imaCapitalValue(ImaCapitalResult result) {
        return result == null ? BigDecimal.ZERO : zeroIfNull(result.getAcrTotal());
    }

    private static BigDecimal imccValue(ImaCapitalResult result) {
        if (result == null || result.getImccResult() == null) {
            return BigDecimal.ZERO;
        }
        return zeroIfNull(result.getImccResult().getImcc());
    }

    private static BigDecimal sesValue(ImaCapitalResult result) {
        if (result == null || result.getSesResult() == null) {
            return BigDecimal.ZERO;
        }
        return zeroIfNull(result.getSesResult().getSes());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private RuleCapitalResults calculateByRule(AggregationRule rule,
                                               Map<String, DimensionRow> dimensionsByInstrument,
                                               List<SubsetPnlRecord> subsetPnls,
                                               List<NmrfPnlRecord> nmrfPnls,
                                               Map<String, BigDecimal> saByDesk,
                                               Set<String> amberDesks,
                                               Set<String> greenDesks,
                                               String dataDate,
                                               String batchId) {
        if (dimensionsByInstrument.isEmpty()) {
            throw new IllegalStateException("IMA 汇总规则未匹配到交易: ruleId=" + rule.getRuleId());
        }
        Map<String, GroupedCapitalInput> grouped = new LinkedHashMap<String, GroupedCapitalInput>();
        for (SubsetPnlRecord record : subsetPnls) {
            if (record == null) {
                continue;
            }
            DimensionRow row = dimensionsByInstrument.get(record.getInstrumentId());
            if (row != null) {
                appendGroupedRecord(rule, row, grouped, record, null);
            }
        }
        for (NmrfPnlRecord record : nmrfPnls) {
            if (record == null) {
                continue;
            }
            DimensionRow row = dimensionsByInstrument.get(record.getInstrumentId());
            if (row != null) {
                appendGroupedRecord(rule, row, grouped, null, record);
            }
        }
        if (grouped.isEmpty()) {
            throw new IllegalStateException("IMA 汇总规则未匹配到情景 PnL 结果: ruleId=" + rule.getRuleId());
        }

        RuleCapitalResults results = new RuleCapitalResults();
        for (GroupedCapitalInput input : grouped.values()) {
            List<EsResult> esResults = esCalculator.calculate(input.subsetPnls);
            ImaCapitalResult result = capitalCalculator.calculateFromEsResults(
                    esResults,
                    input.nmrfPnls,
                    saByDesk,
                    amberDesks,
                    greenDesks,
                    dataDate,
                    batchId);
            result.setRuleId(rule.getRuleId());
            result.setGroupType(input.groupType);
            result.setGroupValue(input.groupValue);
            result.setGroupOrder(input.groupOrder);
            results.capitalResults.add(result);
            results.esResultDetails.addAll(buildEsResultDetails(rule, input, dataDate, batchId, esResults));
            results.nmrfResults.addAll(buildNmrfResults(rule, input, dataDate, batchId, result));
        }
        return results;
    }

    private List<ImaNmrfResult> buildNmrfResults(AggregationRule rule,
                                                 GroupedCapitalInput input,
                                                 String dataDate,
                                                 String batchId,
                                                 ImaCapitalResult capitalResult) {
        LinkedHashSet<String> bucketIds = new LinkedHashSet<String>();
        for (NmrfPnlRecord record : input.nmrfPnls) {
            if (record == null) {
                continue;
            }
            bucketIds.add(resolveNmrfBucketId(record.getSubscenarioId()));
        }
        SesResult sesResult = capitalResult == null ? null : capitalResult.getSesResult();
        ImaNmrfResult result = new ImaNmrfResult();
        result.setBatchId(batchId);
        result.setDataDate(dataDate);
        result.setRuleId(rule.getRuleId());
        result.setGroupType(input.groupType);
        result.setGroupValue(input.groupValue);
        result.setGroupOrder(input.groupOrder);
        result.setSes(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getSes()));
        result.setIdioCreditSumSq(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getIdioCreditSumSq()));
        result.setIdioEquitySumSq(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getIdioEquitySumSq()));
        result.setOtherCorrTerm(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getOtherCorrTerm()));
        result.setOtherIdioTerm(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getOtherIdioTerm()));
        result.setNmrfCount(bucketIds.size());
        List<ImaNmrfResult> results = new ArrayList<ImaNmrfResult>();
        results.add(result);
        return results;
    }

    private List<ImaEsResultDetail> buildEsResultDetails(AggregationRule rule,
                                                         GroupedCapitalInput input,
                                                         String dataDate,
                                                         String batchId,
                                                         List<EsResult> esResults) {
        Map<String, ImaEsResultDetail> details = new LinkedHashMap<String, ImaEsResultDetail>();
        if (esResults == null) {
            return new ArrayList<ImaEsResultDetail>();
        }
        for (EsResult esResult : esResults) {
            if (esResult == null) {
                continue;
            }
            String scenarioType = trimToNull(esResult.getScenarioType());
            if (scenarioType == null) {
                throw new IllegalStateException("IMA ES 明细缺少 SCENARIO_TYPE");
            }
            BigDecimal confidenceLevel = esResult.getConfidenceLevel();
            if (confidenceLevel == null) {
                throw new IllegalStateException("IMA ES 明细缺少 CONFIDENCE_LEVEL");
            }
            int lhDays = esResult.getLhDays();
            String key = scenarioType + "|" + confidenceLevel.toPlainString() + "|" + lhDays;
            ImaEsResultDetail detail = details.get(key);
            if (detail == null) {
                detail = new ImaEsResultDetail();
                detail.setBatchId(batchId);
                detail.setDataDate(dataDate);
                detail.setRuleId(rule.getRuleId());
                detail.setGroupType(input.groupType);
                detail.setGroupValue(input.groupValue);
                detail.setGroupOrder(input.groupOrder);
                detail.setScenarioType(scenarioType);
                detail.setConfidenceLevel(confidenceLevel);
                detail.setLiquidityHorizonDays(lhDays);
                details.put(key, detail);
            }
            assignRiskClassEs(detail, esResult.getRiskClass(), esResult.getEsValue());
        }
        return new ArrayList<ImaEsResultDetail>(details.values());
    }

    private void assignRiskClassEs(ImaEsResultDetail detail, String riskClass, BigDecimal esValue) {
        String safeRiskClass = trimToNull(riskClass);
        if ("ALL".equals(safeRiskClass)) {
            detail.setAllEs(esValue);
        } else if ("IR".equals(safeRiskClass)) {
            detail.setIrEs(esValue);
        } else if ("CS".equals(safeRiskClass)) {
            detail.setCsEs(esValue);
        } else if ("FX".equals(safeRiskClass)) {
            detail.setFxEs(esValue);
        } else if ("EQ".equals(safeRiskClass)) {
            detail.setEqEs(esValue);
        } else if ("COMM".equals(safeRiskClass)) {
            detail.setCommEs(esValue);
        } else {
            throw new IllegalStateException("IMA ES 明细不支持风险类别: " + riskClass);
        }
    }

    private void appendGroupedRecord(AggregationRule rule,
                                     DimensionRow row,
                                     Map<String, GroupedCapitalInput> grouped,
                                     SubsetPnlRecord subsetRecord,
                                     NmrfPnlRecord nmrfRecord) {
        List<String> pathValues = new ArrayList<String>();
        List<String> buildOrder = rule.getBuildOrder();
        for (int i = 0; i < buildOrder.size(); i++) {
            String level = buildOrder.get(i);
            String groupType;
            String groupValue;
            if (TOTAL.equals(level)) {
                groupType = TOTAL;
                groupValue = TOTAL;
            } else {
                pathValues.add(dimensionAggregationService.normalizeDimensionValue(row.get(level)));
                groupType = level;
                groupValue = dimensionAggregationService.buildGroupValue(pathValues);
            }
            String key = rule.getRuleId() + "|" + groupType + "|" + groupValue;
            GroupedCapitalInput input = grouped.get(key);
            if (input == null) {
                input = new GroupedCapitalInput(groupType, groupValue, i);
                grouped.put(key, input);
            }
            if (subsetRecord != null) {
                input.subsetPnls.add(subsetRecord);
            }
            if (nmrfRecord != null) {
                input.nmrfPnls.add(nmrfRecord);
            }
        }
    }

    private Map<String, DimensionRow> buildDimensionRows(LocalDate dataDate, AggregationRule rule) {
        List<BatchTradeDataLoader.TradeRow> trades = tradeDataLoader.loadTradeRows(dataDate, rule.getFilterTree());
        List<String> portfolios = new ArrayList<String>();
        for (BatchTradeDataLoader.TradeRow trade : trades) {
            String portfolio = trade.tradeDimensions.get("portfolio");
            if (trimToNull(portfolio) != null) {
                portfolios.add(portfolio);
            }
        }
        Map<String, BatchTradeDataLoader.PortfolioFlatRow> portfolioFlatRows =
                tradeDataLoader.loadPortfolioFlatByCodes(portfolios);

        Map<String, DimensionRow> rows = new LinkedHashMap<String, DimensionRow>();
        for (BatchTradeDataLoader.TradeRow trade : trades) {
            String instrumentId = trimToNull(trade.instrumentId);
            if (instrumentId == null) {
                continue;
            }
            if (rows.containsKey(instrumentId)) {
                throw new IllegalStateException("IMA 汇总规则匹配到重复交易ID: " + instrumentId);
            }
            DimensionRow row = new DimensionRow();
            row.put("INSTRUMENT_ID", instrumentId);
            row.put("PRODUCT_CODE", trade.productCode);
            row.put("PORTFOLIO", trade.tradeDimensions.get("portfolio"));
            row.put("DESK", trade.tradeDimensions.get("desk"));
            row.put("TRADER", trade.tradeDimensions.get("trader"));
            BatchTradeDataLoader.PortfolioFlatRow flatRow =
                    portfolioFlatRows.get(trade.tradeDimensions.get("portfolio"));
            if (flatRow != null) {
                row.put("PORTFOLIO_CODE_1", flatRow.portfolioCode1);
                row.put("PORTFOLIO_CODE_2", flatRow.portfolioCode2);
                row.put("PORTFOLIO_CODE_3", flatRow.portfolioCode3);
                row.put("PORTFOLIO_CODE_4", flatRow.portfolioCode4);
                row.put("PORTFOLIO_CODE_5", flatRow.portfolioCode5);
                row.put("PORTFOLIO_CODE_6", flatRow.portfolioCode6);
                row.put("PORTFOLIO_CODE_7", flatRow.portfolioCode7);
            }
            rows.put(instrumentId, row);
        }
        return rows;
    }

    private LoadedRule loadAggregationRule(String ruleId) {
        String safeRuleId = trimToNull(ruleId);
        if (safeRuleId == null) {
            throw new IllegalArgumentException("IMA ruleId 不能为空");
        }
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                    "SELECT RULE_ID, RULE_TYPE, RULE_NAME, RULE_JSON FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
                    RULE_TYPE_IMA, safeRuleId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("未找到 IMA 汇总规则: " + safeRuleId);
            }
            Map<String, Object> row = rows.get(0);
            String ruleJson = trimToNull(stringValue(row.get("RULE_JSON")));
            if (ruleJson == null) {
                throw new IllegalArgumentException("IMA 汇总规则内容为空: " + safeRuleId);
            }
            AggregationRule rule = JSON.parseObject(ruleJson, AggregationRule.class);
            if (rule == null) {
                throw new IllegalArgumentException("IMA 汇总规则解析失败: " + safeRuleId);
            }
            rule.setRuleId(safeRuleId);
            rule.setRuleType(trimToNull(stringValue(row.get("RULE_TYPE"))));
            rule.setRuleName(trimToNull(stringValue(row.get("RULE_NAME"))));
            validateImaRule(rule);
            return new LoadedRule(rule, ruleJson);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 MR_AGG_RULE 中 IMA 规则失败，请确认规则表已创建且可访问: "
                    + ex.getMessage(), ex);
        }
    }

    private void validateImaRule(AggregationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("IMA AggregationRule 不能为空");
        }
        if (trimToNull(rule.getRuleId()) == null) {
            throw new IllegalArgumentException("IMA AggregationRule.ruleId 不能为空");
        }
        if (!RULE_TYPE_IMA.equals(rule.getRuleType())) {
            throw new IllegalArgumentException("IMA AggregationRule.ruleType 必须为 IMA");
        }
        List<String> buildOrder = normalizeBuildOrder(rule.getBuildOrder());
        if (buildOrder.size() <= 1) {
            throw new IllegalArgumentException("IMA AggregationRule.buildOrder 必须至少包含一个业务维度");
        }
        rule.setBuildOrder(buildOrder);
    }

    private List<String> normalizeBuildOrder(List<String> buildOrder) {
        List<String> normalizedRuleOrder = dimensionAggregationService.normalizeBuildOrder(buildOrder);
        if (normalizedRuleOrder.isEmpty()) {
            throw new IllegalArgumentException("IMA AggregationRule.buildOrder 不能为空");
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<String> normalized = new ArrayList<String>();
        seen.add(TOTAL);
        normalized.add(TOTAL);
        for (String level : normalizedRuleOrder) {
            if (TOTAL.equals(level)) {
                continue;
            }
            if (!isSupportedDimension(level)) {
                throw new IllegalArgumentException("IMA AggregationRule.buildOrder 不支持维度: " + level);
            }
            if (seen.add(level)) {
                normalized.add(level);
            }
        }
        return normalized;
    }

    private static boolean isSupportedDimension(String field) {
        if ("INSTRUMENT_ID".equals(field)
                || "PRODUCT_CODE".equals(field)
                || "PORTFOLIO".equals(field)
                || "DESK".equals(field)
                || "TRADER".equals(field)) {
            return true;
        }
        for (int i = 1; i <= 7; i++) {
            if (("PORTFOLIO_CODE_" + i).equals(field)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 数据查询 ====================

    private List<SubsetPnlRecord> queryModellablePnl(String batchId, String dataDate) {
        return resultDbJdbcTemplate.query(QUERY_MODELLABLE, (rs, i) -> {
            SubsetPnlRecord r = new SubsetPnlRecord();
            r.setRequestId(rs.getString("REQUEST_ID"));
            r.setJobId(rs.getString("JOB_ID"));
            r.setBatchId(rs.getString("BATCH_ID"));
            r.setSeqNo(rs.getLong("SEQ_NO"));
            r.setDataDate(rs.getString("DATA_DATE"));
            r.setScenarioId(rs.getString("SCENARIO_ID"));
            r.setSubscenarioId(rs.getString("SUBSCENARIO_ID"));
            r.setScenarioName(rs.getString("SCENARIO_NAME"));
            r.setScenarioType(rs.getString("SCENARIO_TYPE"));
            r.setInstrumentId(rs.getString("INSTRUMENT_ID"));
            r.setProductCode(rs.getString("PRODUCT_CODE"));
            r.setLhDays(rs.getInt("LH_DAYS"));
            r.setBaseValuationCny(rs.getBigDecimal("BASE_VALUATION_CNY"));
            r.setIrValuation(rs.getBigDecimal("IR_VALUATION"));
            r.setIrPnl(rs.getBigDecimal("IR_PNL"));
            r.setCsValuation(rs.getBigDecimal("CS_VALUATION"));
            r.setCsPnl(rs.getBigDecimal("CS_PNL"));
            r.setFxValuation(rs.getBigDecimal("FX_VALUATION"));
            r.setFxPnl(rs.getBigDecimal("FX_PNL"));
            r.setEqValuation(rs.getBigDecimal("EQ_VALUATION"));
            r.setEqPnl(rs.getBigDecimal("EQ_PNL"));
            r.setCommValuation(rs.getBigDecimal("COMM_VALUATION"));
            r.setCommPnl(rs.getBigDecimal("COMM_PNL"));
            r.setAllValuation(rs.getBigDecimal("ALL_VALUATION"));
            r.setAllPnl(rs.getBigDecimal("ALL_PNL"));
            return r;
        }, batchId, dataDate);
    }

    private List<NmrfPnlRecord> queryNmrfPnl(String batchId, String dataDate) {
        return resultDbJdbcTemplate.query(QUERY_NMRF, (rs, i) -> {
            NmrfPnlRecord r = new NmrfPnlRecord();
            r.setRequestId(rs.getString("REQUEST_ID"));
            r.setJobId(rs.getString("JOB_ID"));
            r.setBatchId(rs.getString("BATCH_ID"));
            r.setSeqNo(rs.getLong("SEQ_NO"));
            r.setDataDate(rs.getString("DATA_DATE"));
            r.setScenarioId(rs.getString("SCENARIO_ID"));
            r.setSubscenarioId(rs.getString("SUBSCENARIO_ID"));
            r.setScenarioName(rs.getString("SCENARIO_NAME"));
            r.setInstrumentId(rs.getString("INSTRUMENT_ID"));
            r.setProductCode(rs.getString("PRODUCT_CODE"));
            r.setRiskFactorId(rs.getString("RISK_FACTOR_ID"));
            r.setNmrfType(rs.getString("NMRF_TYPE"));
            r.setBaseValuationCny(rs.getBigDecimal("BASE_VALUATION_CNY"));
            r.setStressValuationCny(rs.getBigDecimal("STRESS_VALUATION_CNY"));
            r.setPnl(rs.getBigDecimal("PNL"));
            return r;
        }, batchId, dataDate);
    }

    // ==================== JSON 工具 ====================

    private static String required(JSONObject obj, String key) {
        String v = obj.getString(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException(key + " 必填");
        return v.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String resolveNmrfBucketId(String subscenarioId) {
        String safe = trimToNull(subscenarioId);
        if (safe == null) {
            throw new IllegalArgumentException("NMRF 结果缺少 SUBSCENARIO_ID");
        }
        if (safe.endsWith("_UP")) {
            return safe.substring(0, safe.length() - 3);
        }
        if (safe.endsWith("_DOWN")) {
            return safe.substring(0, safe.length() - 5);
        }
        throw new IllegalArgumentException("NMRF SUBSCENARIO_ID 必须为 {rfetBucketId}_UP 或 {rfetBucketId}_DOWN: "
                + subscenarioId);
    }

    private static class LoadedRule {
        private final AggregationRule rule;
        private final String ruleJson;

        private LoadedRule(AggregationRule rule, String ruleJson) {
            this.rule = rule;
            this.ruleJson = ruleJson;
        }
    }

    private static class DimensionRow {
        private final Map<String, String> values = new LinkedHashMap<String, String>();

        private void put(String field, String value) {
            String safeField = trimToNull(field);
            if (safeField != null) {
                values.put(safeField.toUpperCase(Locale.ROOT), value);
            }
        }

        private String get(String field) {
            String safeField = trimToNull(field);
            return safeField == null ? null : values.get(safeField.toUpperCase(Locale.ROOT));
        }
    }

    private static class GroupedCapitalInput {
        private final String groupType;
        private final String groupValue;
        private final int groupOrder;
        private final List<SubsetPnlRecord> subsetPnls = new ArrayList<SubsetPnlRecord>();
        private final List<NmrfPnlRecord> nmrfPnls = new ArrayList<NmrfPnlRecord>();

        private GroupedCapitalInput(String groupType, String groupValue, int groupOrder) {
            this.groupType = groupType;
            this.groupValue = groupValue;
            this.groupOrder = groupOrder;
        }
    }

    private static class RuleCapitalResults {
        private final List<ImaCapitalResult> capitalResults = new ArrayList<ImaCapitalResult>();
        private final List<ImaEsResultDetail> esResultDetails = new ArrayList<ImaEsResultDetail>();
        private final List<ImaNmrfResult> nmrfResults = new ArrayList<ImaNmrfResult>();
    }

    private static class SbaCapitalSnapshot {
        private final BigDecimal totalCapital;
        private final Map<String, BigDecimal> capitalByDesk;

        private SbaCapitalSnapshot(BigDecimal totalCapital, Map<String, BigDecimal> capitalByDesk) {
            this.totalCapital = zeroIfNull(totalCapital);
            this.capitalByDesk = capitalByDesk == null
                    ? Collections.<String, BigDecimal>emptyMap()
                    : capitalByDesk;
        }

        private static SbaCapitalSnapshot empty() {
            return new SbaCapitalSnapshot(BigDecimal.ZERO, Collections.<String, BigDecimal>emptyMap());
        }
    }
}
