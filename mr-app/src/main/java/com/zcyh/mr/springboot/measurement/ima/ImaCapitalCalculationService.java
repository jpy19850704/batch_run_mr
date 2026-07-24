package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.frtbima.capital.ImaCapitalCalculator;
import com.zcyh.mr.frtbima.es.EsCalculator;
import com.zcyh.mr.frtbima.model.EsResult;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.frtbima.model.ImaEsResultDetail;
import com.zcyh.mr.frtbima.model.ImaNmrfResult;
import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.springboot.measurement.aggregation.DimensionAggregationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IMA资本规则计算服务。
 */
@Service
public class ImaCapitalCalculationService {
    private static final String RULE_TYPE_IMA = "IMA";
    private static final String TOTAL = "TOTAL";

    private final DimensionAggregationService dimensionAggregationService;
    private final EsCalculator esCalculator = new EsCalculator();
    private final ImaCapitalCalculator capitalCalculator = new ImaCapitalCalculator();
    private final ImaCapitalResultAssembler resultAssembler = new ImaCapitalResultAssembler();

    public ImaCapitalCalculationService(DimensionAggregationService dimensionAggregationService) {
        this.dimensionAggregationService = dimensionAggregationService;
    }

    public ImaCapitalCalculationResult calculateRule(
            AggregationRule rule,
            Map<String, Map<String, String>> dimensionsByInstrument,
            List<SubsetPnlRecord> subsetPnls,
            List<NmrfPnlRecord> nmrfPnls,
            Map<String, BigDecimal> saByDesk,
            Set<String> amberDesks,
            Set<String> greenDesks,
            LocalDate dataDate,
            String batchId) {
        validateImaRule(rule);
        if (dimensionsByInstrument == null || dimensionsByInstrument.isEmpty()) {
            throw new IllegalStateException("IMA 汇总规则未匹配到交易: ruleId=" + rule.getRuleId());
        }

        Map<String, GroupedCapitalInput> grouped = groupCapitalInputs(
                rule, dimensionsByInstrument, subsetPnls, nmrfPnls);
        if (grouped.isEmpty()) {
            throw new IllegalStateException("IMA 汇总规则未匹配到情景 PnL 结果: ruleId=" + rule.getRuleId());
        }

        List<ImaCapitalResult> capitalResults = new ArrayList<ImaCapitalResult>();
        List<ImaEsResultDetail> esResultDetails = new ArrayList<ImaEsResultDetail>();
        List<ImaNmrfResult> nmrfResults = new ArrayList<ImaNmrfResult>();
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
            capitalResults.add(result);
            esResultDetails.addAll(resultAssembler.buildEsResultDetails(result, esResults, dataDate, batchId));
            nmrfResults.addAll(resultAssembler.buildNmrfResults(result, input.nmrfPnls, dataDate, batchId));
        }
        return new ImaCapitalCalculationResult(capitalResults, esResultDetails, nmrfResults);
    }

    public void validateImaRule(AggregationRule rule) {
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

    private Map<String, GroupedCapitalInput> groupCapitalInputs(
            AggregationRule rule,
            Map<String, Map<String, String>> dimensionsByInstrument,
            List<SubsetPnlRecord> subsetPnls,
            List<NmrfPnlRecord> nmrfPnls) {
        Map<String, GroupedCapitalInput> grouped = new LinkedHashMap<String, GroupedCapitalInput>();
        for (SubsetPnlRecord record : subsetPnls) {
            if (record != null) {
                Map<String, String> row = dimensionsByInstrument.get(record.getInstrumentId());
                if (row != null) {
                    appendGroupedRecord(rule, row, grouped, record, null);
                }
            }
        }
        for (NmrfPnlRecord record : nmrfPnls) {
            if (record != null) {
                Map<String, String> row = dimensionsByInstrument.get(record.getInstrumentId());
                if (row != null) {
                    appendGroupedRecord(rule, row, grouped, null, record);
                }
            }
        }
        return grouped;
    }

    private void appendGroupedRecord(
            AggregationRule rule,
            Map<String, String> row,
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class GroupedCapitalInput {
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
}
