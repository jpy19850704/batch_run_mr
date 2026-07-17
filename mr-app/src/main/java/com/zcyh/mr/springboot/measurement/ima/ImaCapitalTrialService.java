package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.springboot.input.db.InputFilterExpression;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.springboot.measurement.frtb.FrtbSbaDbRunnerService;
import org.springframework.stereotype.Service;

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
 * IMA 资本试算服务。
 */
@Service
public class ImaCapitalTrialService {
    private static final String TREATMENT_IMA_GREEN = "IMA_GREEN";
    private static final String TREATMENT_IMA_AMBER = "IMA_AMBER";
    private static final String TREATMENT_SA = "SA";
    private static final String TOTAL = "TOTAL";

    private final ImaCapitalRuleResolver ruleRepository;
    private final ImaCapitalPnlRepository pnlRepository;
    private final ImaCapitalDimensionService capitalDimensionService;
    private final ImaCapitalCalculationService calculationService;
    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;

    public ImaCapitalTrialService(
            ImaCapitalRuleResolver ruleRepository,
            ImaCapitalPnlRepository pnlRepository,
            ImaCapitalDimensionService capitalDimensionService,
            ImaCapitalCalculationService calculationService,
            FrtbSbaDbRunnerService frtbSbaDbRunnerService) {
        this.ruleRepository = ruleRepository;
        this.pnlRepository = pnlRepository;
        this.capitalDimensionService = capitalDimensionService;
        this.calculationService = calculationService;
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
    }

    public JSONObject calculate(JSONObject request) {
        String batchId = required(request, "batch_id");
        String dataDate = required(request, "data_date");
        String imaRuleId = required(request, "ima_rule_id");
        String filterRuleId = required(request, "filter_rule_id");
        LinkedHashMap<String, String> deskTreatments = parseDeskTreatments(request);

        Set<String> greenDesks = desksByTreatment(deskTreatments, TREATMENT_IMA_GREEN);
        Set<String> amberDesks = desksByTreatment(deskTreatments, TREATMENT_IMA_AMBER);
        Set<String> saDesks = desksByTreatment(deskTreatments, TREATMENT_SA);
        LinkedHashSet<String> imaDesks = new LinkedHashSet<String>();
        imaDesks.addAll(greenDesks);
        imaDesks.addAll(amberDesks);

        InputFilterExpression tradeFilter = ruleRepository.loadTradeFilter(filterRuleId);
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

        Map<String, ImaCapitalResult> imaByGroup = Collections.emptyMap();
        if (!imaDesks.isEmpty()) {
            imaByGroup = calculateTrialCapital(
                    batchId,
                    dataDate,
                    imaRuleId,
                    andFilters(tradeFilter, deskInFilter(imaDesks)),
                    allDeskSa.capitalByDesk,
                    amberDesks,
                    greenDesks);
        }

        List<JSONObject> rows = new ArrayList<JSONObject>();
        rows.add(buildTrialRow(
                TOTAL,
                TOTAL,
                "MIXED",
                imaByGroup.get(TOTAL + "|" + TOTAL),
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

    private Map<String, ImaCapitalResult> calculateTrialCapital(
            String batchId,
            String dataDate,
            String imaRuleId,
            InputFilterExpression filterTree,
            Map<String, BigDecimal> saByDesk,
            Set<String> amberDesks,
            Set<String> greenDesks) {
        ImaCapitalRuleResolver.LoadedRule loadedRule = ruleRepository.loadImaRule(imaRuleId);
        AggregationRule trialRule = buildTrialImaRule(loadedRule.getRule(), filterTree);
        LocalDate localDataDate = LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        ImaCapitalCalculationResult calculationResult = calculationService.calculateRule(
                trialRule,
                capitalDimensionService.buildDimensionRows(localDataDate, trialRule),
                pnlRepository.queryModellablePnl(batchId, dataDate),
                pnlRepository.queryNmrfPnl(batchId, dataDate),
                saByDesk,
                amberDesks,
                greenDesks,
                dataDate,
                batchId);
        Map<String, ImaCapitalResult> result = new LinkedHashMap<String, ImaCapitalResult>();
        for (ImaCapitalResult capitalResult : calculationResult.getCapitalResults()) {
            result.put(capitalResult.getGroupType() + "|" + capitalResult.getGroupValue(), capitalResult);
        }
        return result;
    }

    private AggregationRule buildTrialImaRule(
            AggregationRule sourceRule,
            InputFilterExpression filterTree) {
        AggregationRule rule = new AggregationRule();
        rule.setRuleId(sourceRule.getRuleId());
        rule.setRuleType("IMA");
        rule.setRuleName(sourceRule.getRuleName());
        rule.setBuildOrder(new ArrayList<String>(Collections.singletonList("DESK")));
        rule.setFilterTree(filterTree);
        calculationService.validateImaRule(rule);
        return rule;
    }

    private SbaCapitalSnapshot calculateSbaSnapshot(
            String batchId,
            String dataDate,
            String filterRuleId,
            InputFilterExpression tradeFilter,
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
                Collections.singletonList(ruleDefinition),
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

    private JSONObject buildTrialRow(
            String groupType,
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
        row.put("amber_surcharge_ratio",
                imaResult == null ? BigDecimal.ZERO : zeroIfNull(imaResult.getAmberSurchargeRatio()));
        row.put("ima_capital", imaCapital);
        row.put("sa_capital", safeSaCapital);
        row.put("total_capital", imaCapital.add(safeSaCapital));
        return row;
    }

    private LinkedHashMap<String, String> parseDeskTreatments(JSONObject request) {
        JSONArray array = request.getJSONArray("desk_treatments");
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
            result.put(desk, normalizeTreatment(item.getString("treatment"), i));
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
            throw new IllegalArgumentException(
                    "desk_treatments[" + index + "].treatment 仅支持 IMA_GREEN/IMA_AMBER/SA");
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

    private InputFilterExpression andFilters(
            InputFilterExpression first,
            InputFilterExpression second) {
        List<InputFilterExpression> children = new ArrayList<InputFilterExpression>();
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
        InputFilterExpression root = new InputFilterExpression();
        root.setLogic("AND");
        root.setChildren(children);
        return root;
    }

    private InputFilterExpression deskInFilter(Set<String> desks) {
        if (desks == null || desks.isEmpty()) {
            throw new IllegalArgumentException("DESK过滤列表不能为空");
        }
        InputFilterExpression filter = new InputFilterExpression();
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

    private static String required(JSONObject object, String key) {
        String value = object.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " 必填");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class SbaCapitalSnapshot {
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
