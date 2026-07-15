package com.zcyh.mr.springboot.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单类市场数据的情景加载请求。
 */
final class ScenarioMarketLoadRequest {
    private static final String FX_SPOT = "FX_SPOT";

    private final String curveType;
    private final Set<String> curveCodes = new LinkedHashSet<String>();
    private final Set<String> riskGroupIds = new LinkedHashSet<String>();
    private final Set<String> fxContainerIds = new LinkedHashSet<String>();
    private final Set<String> matchedFxContainerIds = new LinkedHashSet<String>();
    private final List<ScenarioMarketQueryPlanner.DateRange> ranges =
            new ArrayList<ScenarioMarketQueryPlanner.DateRange>();
    private final Set<String> rangeKeys = new LinkedHashSet<String>();

    ScenarioMarketLoadRequest(String curveType) {
        this.curveType = curveType;
    }

    String getCurveType() {
        return curveType;
    }

    Set<String> getCurveCodes() {
        return curveCodes;
    }

    Set<String> getRiskGroupIds() {
        return riskGroupIds;
    }

    Set<String> getFxContainerIds() {
        return fxContainerIds;
    }

    Set<String> getMatchedFxContainerIds() {
        return matchedFxContainerIds;
    }

    void addRiskGroupId(String riskGroupId) {
        if (riskGroupId != null) {
            riskGroupIds.add(riskGroupId);
        }
    }

    boolean hasRiskGroup() {
        return !riskGroupIds.isEmpty();
    }

    void addCurveCode(String curveCode) {
        if (curveCode != null) {
            curveCodes.add(FX_SPOT.equals(curveType) ? curveCode.toUpperCase() : curveCode);
        }
    }

    void addFxContainerId(String curveId) {
        if (curveId != null) {
            fxContainerIds.add(curveId);
        }
    }

    void markMatchedFxContainer(String curveId) {
        if (curveId != null) {
            matchedFxContainerIds.add(curveId);
        }
    }

    boolean matchesCurveCode(String curveCode) {
        if (curveCodes.isEmpty()) {
            return true;
        }
        return curveCode != null
                && curveCodes.contains(FX_SPOT.equals(curveType) ? curveCode.toUpperCase() : curveCode);
    }

    boolean matchesFxContainer(String curveId) {
        if (!FX_SPOT.equals(curveType) || fxContainerIds.isEmpty()) {
            return true;
        }
        return curveId != null && fxContainerIds.contains(curveId);
    }

    boolean shouldIncludeAllFxPairs() {
        return FX_SPOT.equals(curveType) && curveCodes.isEmpty();
    }

    List<String> resolveQueryCurveIds() {
        if (FX_SPOT.equals(curveType)) {
            return Collections.emptyList();
        }
        return curveCodes.isEmpty() ? Collections.emptyList() : new ArrayList<String>(curveCodes);
    }

    void addRanges(List<ScenarioMarketQueryPlanner.DateRange> dateRanges) {
        if (dateRanges == null || dateRanges.isEmpty()) {
            return;
        }
        for (ScenarioMarketQueryPlanner.DateRange dateRange : dateRanges) {
            if (dateRange == null) {
                continue;
            }
            String key = String.valueOf(dateRange.getStartDate()) + "|" + String.valueOf(dateRange.getEndDate());
            if (rangeKeys.add(key)) {
                ranges.add(dateRange);
            }
        }
    }

    List<ScenarioMarketQueryPlanner.DateRange> getRanges() {
        return ranges;
    }
}
