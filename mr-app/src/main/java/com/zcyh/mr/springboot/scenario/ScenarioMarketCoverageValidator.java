package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.springboot.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 情景市场数据覆盖校验器。
 */
final class ScenarioMarketCoverageValidator {
    private static final Logger log = LoggerFactory.getLogger(ScenarioMarketCoverageValidator.class);
    private static final String FX_SPOT = "FX_SPOT";
    private static final String ALERT_CODE = "SCENARIO_RISKGROUP_MARKET_MISMATCH";

    private final AlertService alertService;

    ScenarioMarketCoverageValidator(AlertService alertService) {
        this.alertService = alertService;
    }

    List<String> collectMissingCurrentWarnings(
            String scenarioId,
            LocalDate valuationDate,
            ScenarioMarketLoadRequest request,
            List<ScenarioMarketSeries> loadedSeries) {
        if (FX_SPOT.equals(request.getCurveType())) {
            return collectFxSpotCurrentWarnings(scenarioId, valuationDate, request, loadedSeries);
        }
        if (!request.hasRiskGroup() || request.getCurveCodes().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> actualCurveCodes = readActualCurveCodes(loadedSeries, false);
        List<String> missingCurveCodes = new ArrayList<String>();
        for (String curveCode : request.getCurveCodes()) {
            if (!actualCurveCodes.contains(curveCode)) {
                missingCurveCodes.add(curveCode);
            }
        }
        if (missingCurveCodes.isEmpty()) {
            return Collections.emptyList();
        }
        String warning = "情景风险组与市场数据不一致"
                + ": scenarioId=" + safeText(scenarioId)
                + ", valuationDate=" + safeText(valuationDate)
                + ", curveType=" + safeText(request.getCurveType())
                + ", riskGroupIds=" + request.getRiskGroupIds()
                + ", missingCurveCodes=" + missingCurveCodes
                + ", actualCurveCodes=" + actualCurveCodes;
        return Collections.singletonList(reportCurrentMismatch(warning));
    }

    private List<String> collectFxSpotCurrentWarnings(
            String scenarioId,
            LocalDate valuationDate,
            ScenarioMarketLoadRequest request,
            List<ScenarioMarketSeries> loadedSeries) {
        if (!request.hasRiskGroup() && request.getFxContainerIds().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> warnings = new ArrayList<String>();
        Set<String> actualCurveCodes = readActualCurveCodes(loadedSeries, true);
        List<String> missingFxContainerIds = new ArrayList<String>();
        for (String fxContainerId : request.getFxContainerIds()) {
            if (!request.getMatchedFxContainerIds().contains(fxContainerId)) {
                missingFxContainerIds.add(fxContainerId);
            }
        }
        if (!missingFxContainerIds.isEmpty()) {
            warnings.add(reportCurrentMismatch(buildFxSpotCurrentWarning(
                    scenarioId, valuationDate, request, missingFxContainerIds,
                    Collections.<String>emptyList(), actualCurveCodes)));
        }

        List<String> missingCurveCodes = new ArrayList<String>();
        for (String curveCode : request.getCurveCodes()) {
            if (!actualCurveCodes.contains(curveCode.toUpperCase())) {
                missingCurveCodes.add(curveCode);
            }
        }
        if (!missingCurveCodes.isEmpty()) {
            warnings.add(reportCurrentMismatch(buildFxSpotCurrentWarning(
                    scenarioId, valuationDate, request, Collections.<String>emptyList(),
                    missingCurveCodes, actualCurveCodes)));
        }

        if (warnings.isEmpty()
                && !request.getFxContainerIds().isEmpty()
                && request.getCurveCodes().isEmpty()
                && request.getMatchedFxContainerIds().size() == request.getFxContainerIds().size()
                && actualCurveCodes.isEmpty()) {
            warnings.add(reportCurrentMismatch(buildFxSpotCurrentWarning(
                    scenarioId, valuationDate, request, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), actualCurveCodes)));
        }
        return warnings;
    }

    private Set<String> readActualCurveCodes(List<ScenarioMarketSeries> loadedSeries, boolean upperCase) {
        Set<String> actualCurveCodes = new LinkedHashSet<String>();
        if (loadedSeries == null) {
            return actualCurveCodes;
        }
        for (ScenarioMarketSeries series : loadedSeries) {
            String curveCode = normalize(series.getCurveCode());
            if (curveCode != null) {
                actualCurveCodes.add(upperCase ? curveCode.toUpperCase() : curveCode);
            }
        }
        return actualCurveCodes;
    }

    private String buildFxSpotCurrentWarning(
            String scenarioId,
            LocalDate valuationDate,
            ScenarioMarketLoadRequest request,
            List<String> missingFxContainerIds,
            List<String> missingCurveCodes,
            Set<String> actualCurveCodes) {
        return "情景FX市场数据与配置不一致"
                + ": scenarioId=" + safeText(scenarioId)
                + ", valuationDate=" + safeText(valuationDate)
                + ", curveType=" + safeText(request.getCurveType())
                + ", riskGroupIds=" + request.getRiskGroupIds()
                + ", fxContainerIds=" + request.getFxContainerIds()
                + ", matchedFxContainerIds=" + request.getMatchedFxContainerIds()
                + ", missingFxContainerIds=" + missingFxContainerIds
                + ", requestedCurveCodes=" + request.getCurveCodes()
                + ", missingCurveCodes=" + missingCurveCodes
                + ", actualCurveCodes=" + actualCurveCodes;
    }

    private String reportCurrentMismatch(String warning) {
        log.warn(warning);
        if (alertService != null) {
            alertService.warn(ALERT_CODE, warning);
        }
        return warning;
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
