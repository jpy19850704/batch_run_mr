package com.zcyh.mr.frtbsa.sba.core;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FRTB SBA全部风险类别结果组装器。
 */
final class FrtbAllRiskClassAssembler {
    private static final Logger log = LoggerFactory.getLogger(FrtbAllRiskClassAssembler.class);
    private static final String RISK_CLASS_ALL = "ALL";
    private static final List<String> SENSITIVITY_TYPES = Arrays.asList("Delta", "Vega", "Curvature");
    private static final List<String> RISK_CLASSES = Arrays.asList(
            FrtbConstants.RISK_CLASS_GIRR,
            FrtbConstants.RISK_CLASS_CSRNS,
            FrtbConstants.RISK_CLASS_CSRNC,
            FrtbConstants.RISK_CLASS_EQ,
            FrtbConstants.RISK_CLASS_FX,
            FrtbConstants.RISK_CLASS_CMTY,
            FrtbConstants.RISK_CLASS_CSRCTP);

    void appendBatch(Map<String, Map<String, Object>> batchResult) {
        if (batchResult == null || batchResult.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : batchResult.entrySet()) {
            Map<String, Object> resultMap = entry.getValue();
            if (resultMap != null && !resultMap.containsKey("ERROR_CODE")) {
                append(resultMap);
            }
        }
    }

    void append(Map<String, Object> resultMap) {
        if (resultMap == null || resultMap.isEmpty() || resultMap.containsKey("ERROR_CODE")) {
            return;
        }
        resultMap.remove(RISK_CLASS_ALL);
        Map<String, Object> allResult = new LinkedHashMap<String, Object>();
        for (String sensitivityType : SENSITIVITY_TYPES) {
            Map<String, Object> sensitivityResult = buildSensitivityResult(resultMap, sensitivityType);
            if (!sensitivityResult.isEmpty()) {
                allResult.put(sensitivityType, sensitivityResult);
            }
        }
        if (!allResult.isEmpty()) {
            resultMap.put(RISK_CLASS_ALL, allResult);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSensitivityResult(
            Map<String, Object> resultMap,
            String sensitivityType) {
        Map<String, Object> sensitivityResult = new LinkedHashMap<String, Object>();
        Map<String, Object> classCapital = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> allDecomp = new ArrayList<Map<String, Object>>();
        double capitalNormal = 0.0;
        double capitalHigh = 0.0;
        double capitalLow = 0.0;
        boolean hasClassCapital = false;

        for (String riskClass : RISK_CLASSES) {
            Object classObject = resultMap.get(riskClass);
            if (!(classObject instanceof Map)) {
                continue;
            }
            Object sensitivityObject = ((Map<String, Object>) classObject).get(sensitivityType);
            if (!(sensitivityObject instanceof Map)) {
                continue;
            }
            Map<String, Object> riskSensitivityResult = (Map<String, Object>) sensitivityObject;
            Object classDataObject = riskSensitivityResult.get("class");
            if (classDataObject instanceof Map) {
                Map<String, Object> classData = (Map<String, Object>) classDataObject;
                Double normal = readNumber(classData.get("capital_normal"), riskClass, sensitivityType,
                        "capital_normal");
                Double high = readNumber(classData.get("capital_high"), riskClass, sensitivityType,
                        "capital_high");
                Double low = readNumber(classData.get("capital_low"), riskClass, sensitivityType,
                        "capital_low");
                if (normal != null && high != null && low != null) {
                    capitalNormal += normal;
                    capitalHigh += high;
                    capitalLow += low;
                    hasClassCapital = true;
                }
            }

            Object decompObject = riskSensitivityResult.get("decompRslt");
            if (decompObject instanceof List) {
                for (Object item : (List<?>) decompObject) {
                    if (item instanceof Map) {
                        allDecomp.add((Map<String, Object>) item);
                    } else if (item != null) {
                        log.warn("FRTB SBA ALL汇总排除异常分解记录: riskClass={}, sensitivityType={}, value={}",
                                riskClass, sensitivityType, item);
                    }
                }
            }
        }

        if (hasClassCapital) {
            classCapital.put("riskFactorClass", RISK_CLASS_ALL);
            classCapital.put("capital_normal", capitalNormal);
            classCapital.put("capital_high", capitalHigh);
            classCapital.put("capital_low", capitalLow);
            classCapital.put("capital", Math.max(Math.max(capitalNormal, capitalHigh), capitalLow));
            sensitivityResult.put("class", classCapital);
        }
        if (!allDecomp.isEmpty()) {
            sensitivityResult.put("decompRslt", allDecomp);
        }
        return sensitivityResult;
    }

    private static Double readNumber(Object value, String riskClass, String sensitivityType, String field) {
        if (!(value instanceof Number)) {
            log.warn("FRTB SBA ALL汇总排除异常数值: riskClass={}, sensitivityType={}, field={}, value={}",
                    riskClass, sensitivityType, field, value);
            return null;
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) {
            log.warn("FRTB SBA ALL汇总排除非有限数值: riskClass={}, sensitivityType={}, field={}, value={}",
                    riskClass, sensitivityType, field, value);
            return null;
        }
        return number;
    }
}
