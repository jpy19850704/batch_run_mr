package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 情景定义风险组和 FX 容器展开服务。
 */
final class ScenarioDefinitionExpansionService {
    private static final String FX_SPOT = "FX_SPOT";

    private final ScenarioMapper scenarioMapper;

    ScenarioDefinitionExpansionService(ScenarioMapper scenarioMapper) {
        this.scenarioMapper = scenarioMapper;
    }

    List<ScenarioDefinition> expandRiskGroups(List<ScenarioDefinition> definitions) {
        Map<String, List<RiskGroupMember>> membersByGroup = loadRiskGroupMembers(definitions);
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        for (ScenarioDefinition definition : definitions) {
            String riskGroupId = normalize(definition.getRiskGroupId());
            if (riskGroupId == null) {
                result.add(definition);
                continue;
            }
            List<RiskGroupMember> members = membersByGroup.get(riskGroupId);
            boolean expanded = false;
            if (members != null) {
                for (RiskGroupMember member : members) {
                    if (!matchCurveType(definition.getCurveType(), member.riskFactorType)) {
                        continue;
                    }
                    ScenarioDefinition copied = copyDefinition(definition);
                    copied.setCurveType(member.riskFactorType);
                    copied.setCurveCode(member.riskFactorId);
                    result.add(copied);
                    expanded = true;
                }
            }
            if (!expanded && normalize(definition.getCurveCode()) != null) {
                result.add(definition);
            }
        }
        return result;
    }

    List<ScenarioDefinition> expandFxSpotContainers(
            List<ScenarioDefinition> definitions,
            Map<String, List<ScenarioMarketSeries>> currentMarketData) {
        List<ScenarioMarketSeries> fxSeries = currentMarketData == null ? null : currentMarketData.get(FX_SPOT);
        if (fxSeries == null || fxSeries.isEmpty()) {
            return definitions;
        }
        LinkedHashSet<String> fxPairs = new LinkedHashSet<String>();
        for (ScenarioMarketSeries series : fxSeries) {
            String curveCode = normalize(series.getCurveCode());
            if (curveCode != null) {
                fxPairs.add(curveCode);
            }
        }
        if (fxPairs.isEmpty()) {
            return definitions;
        }
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        for (ScenarioDefinition definition : definitions) {
            String curveType = normalize(definition.getCurveType());
            String curveCode = normalize(definition.getCurveCode());
            String riskGroupId = normalize(definition.getRiskGroupId());
            if (!FX_SPOT.equals(curveType)
                    || riskGroupId != null
                    || curveCode == null
                    || curveCode.contains("/")) {
                result.add(definition);
                continue;
            }
            for (String fxPair : fxPairs) {
                ScenarioDefinition copied = copyDefinition(definition);
                copied.setCurveCode(fxPair);
                result.add(copied);
            }
        }
        return result;
    }

    List<ScenarioDefinition> buildMarketLoadDefinitions(
            List<ScenarioDefinition> expandedDefinitions,
            List<ScenarioDefinition> finalDefinitions) {
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        if (finalDefinitions != null && !finalDefinitions.isEmpty()) {
            result.addAll(finalDefinitions);
        }
        if (expandedDefinitions == null || expandedDefinitions.isEmpty()) {
            return result;
        }
        for (ScenarioDefinition definition : expandedDefinitions) {
            String curveType = normalize(definition.getCurveType());
            String curveCode = normalize(definition.getCurveCode());
            if (FX_SPOT.equals(curveType) && curveCode != null && !curveCode.contains("/")) {
                result.add(copyDefinition(definition));
            }
        }
        return result;
    }

    private Map<String, List<RiskGroupMember>> loadRiskGroupMembers(List<ScenarioDefinition> definitions) {
        Set<String> groupIds = new LinkedHashSet<String>();
        for (ScenarioDefinition definition : definitions) {
            String riskGroupId = normalize(definition.getRiskGroupId());
            if (riskGroupId != null) {
                groupIds.add(riskGroupId);
            }
        }
        if (groupIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = scenarioMapper.selectRiskGroupMembers(new ArrayList<String>(groupIds));
        Map<String, List<RiskGroupMember>> result = new LinkedHashMap<String, List<RiskGroupMember>>();
        for (Map<String, Object> row : rows) {
            String riskGroupId = normalize(toStringValue(row.get("RISKGROUP_ID")));
            String riskFactorType = normalize(toStringValue(row.get("RISKFACTOR_TYPE")));
            String riskFactorId = normalize(toStringValue(row.get("RISKFACTOR_ID")));
            if (riskGroupId == null || riskFactorType == null || riskFactorId == null) {
                continue;
            }
            result.computeIfAbsent(riskGroupId, ignored -> new ArrayList<RiskGroupMember>())
                    .add(new RiskGroupMember(riskFactorType, riskFactorId));
        }
        return result;
    }

    private ScenarioDefinition copyDefinition(ScenarioDefinition source) {
        ScenarioDefinition copied = new ScenarioDefinition();
        copied.setScenarioId(source.getScenarioId());
        copied.setScenarioName(source.getScenarioName());
        copied.setScenarioType(source.getScenarioType());
        copied.setReducedSetFlag(source.getReducedSetFlag());
        copied.setCurveType(source.getCurveType());
        copied.setCurveCode(source.getCurveCode());
        copied.setRiskGroupId(source.getRiskGroupId());
        copied.setTermCode(source.getTermCode());
        copied.setTermDays(source.getTermDays());
        copied.setShockValue(source.getShockValue());
        copied.setScenarioShiftRule(source.getScenarioShiftRule());
        copied.setScenarioNo(source.getScenarioNo());
        copied.setHoldingPeriod(source.getHoldingPeriod());
        copied.setJumpDayNo(source.getJumpDayNo());
        copied.setIncreaseDays(source.getIncreaseDays());
        copied.setHolidayCalendarCode(source.getHolidayCalendarCode());
        copied.setStartDate(source.getStartDate());
        copied.setEndDate(source.getEndDate());
        return copied;
    }

    private boolean matchCurveType(String definitionCurveType, String riskFactorType) {
        String normalizedCurveType = normalize(definitionCurveType);
        String normalizedRiskFactorType = normalize(riskFactorType);
        return normalizedCurveType == null
                ? normalizedRiskFactorType == null
                : normalizedCurveType.equals(normalizedRiskFactorType);
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static final class RiskGroupMember {
        private final String riskFactorType;
        private final String riskFactorId;

        private RiskGroupMember(String riskFactorType, String riskFactorId) {
            this.riskFactorType = riskFactorType;
            this.riskFactorId = riskFactorId;
        }
    }
}
