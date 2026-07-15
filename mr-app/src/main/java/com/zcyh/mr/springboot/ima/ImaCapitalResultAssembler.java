package com.zcyh.mr.springboot.ima;

import com.zcyh.mr.frtbima.model.EsResult;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.frtbima.model.ImaEsResultDetail;
import com.zcyh.mr.frtbima.model.ImaNmrfResult;
import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.SesResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * IMA资本明细结果组装器。
 */
final class ImaCapitalResultAssembler {

    List<ImaNmrfResult> buildNmrfResults(
            ImaCapitalResult capitalResult,
            List<NmrfPnlRecord> nmrfPnls,
            String dataDate,
            String batchId) {
        LinkedHashSet<String> bucketIds = new LinkedHashSet<String>();
        for (NmrfPnlRecord record : nmrfPnls) {
            if (record != null) {
                bucketIds.add(resolveNmrfBucketId(record.getSubscenarioId()));
            }
        }

        SesResult sesResult = capitalResult.getSesResult();
        ImaNmrfResult result = new ImaNmrfResult();
        result.setBatchId(batchId);
        result.setDataDate(dataDate);
        result.setRuleId(capitalResult.getRuleId());
        result.setGroupType(capitalResult.getGroupType());
        result.setGroupValue(capitalResult.getGroupValue());
        result.setGroupOrder(capitalResult.getGroupOrder());
        result.setSes(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getSes()));
        result.setIdioCreditSumSq(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getIdioCreditSumSq()));
        result.setIdioEquitySumSq(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getIdioEquitySumSq()));
        result.setOtherCorrTerm(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getOtherCorrTerm()));
        result.setOtherIdioTerm(sesResult == null ? BigDecimal.ZERO : zeroIfNull(sesResult.getOtherIdioTerm()));
        result.setNmrfCount(bucketIds.size());
        return new ArrayList<ImaNmrfResult>(Collections.singletonList(result));
    }

    List<ImaEsResultDetail> buildEsResultDetails(
            ImaCapitalResult capitalResult,
            List<EsResult> esResults,
            String dataDate,
            String batchId) {
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
                detail.setRuleId(capitalResult.getRuleId());
                detail.setGroupType(capitalResult.getGroupType());
                detail.setGroupValue(capitalResult.getGroupValue());
                detail.setGroupOrder(capitalResult.getGroupOrder());
                detail.setScenarioType(scenarioType);
                detail.setConfidenceLevel(confidenceLevel);
                detail.setLiquidityHorizonDays(lhDays);
                details.put(key, detail);
            }
            assignRiskClassEs(detail, esResult.getRiskClass(), esResult.getEsValue());
        }
        return new ArrayList<ImaEsResultDetail>(details.values());
    }

    private static void assignRiskClassEs(ImaEsResultDetail detail, String riskClass, BigDecimal esValue) {
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

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
