package com.zcyh.mr.frtbsa.sba.core;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBBucketResult;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FRTB SBA标准结果映射器。
 */
public final class FrtbResultMapper {
    private static final Logger log = LoggerFactory.getLogger(FrtbResultMapper.class);
    private static final String RISK_CLASS_ALL = "ALL";
    private static final List<String> SENSITIVITY_TYPES = Arrays.asList("Delta", "Vega", "Curvature");
    private static final Set<String> SUPPORTED_RISK_CLASSES = new HashSet<String>(Arrays.asList(
            FrtbConstants.RISK_CLASS_GIRR,
            FrtbConstants.RISK_CLASS_CSRNS,
            FrtbConstants.RISK_CLASS_CSRNC,
            FrtbConstants.RISK_CLASS_EQ,
            FrtbConstants.RISK_CLASS_FX,
            FrtbConstants.RISK_CLASS_CMTY,
            FrtbConstants.RISK_CLASS_CSRCTP,
            RISK_CLASS_ALL));

    @SuppressWarnings("unchecked")
    public Map<String, List<?>> buildResults(
            Map<String, Object> mapResult,
            String ruleId,
            String groupType,
            String groupValue) {
        List<FRTBClassResult> classResults = new ArrayList<FRTBClassResult>();
        List<FRTBBucketResult> bucketResults = new ArrayList<FRTBBucketResult>();
        List<FRTBPosResult> posResults = new ArrayList<FRTBPosResult>();
        String selectedScenarioName = selectResultScenarioName(mapResult, ruleId, groupType, groupValue);

        if (mapResult != null) {
            for (Map.Entry<String, Object> classEntry : mapResult.entrySet()) {
                String riskClass = classEntry.getKey();
                if (!SUPPORTED_RISK_CLASSES.contains(riskClass) || !(classEntry.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> sensitivityMap = (Map<String, Object>) classEntry.getValue();
                FRTBClassResult classResult = newClassResult(ruleId, groupType, groupValue, riskClass);
                boolean hasValidClassCapital = false;
                for (String sensitivityType : SENSITIVITY_TYPES) {
                    Object sensitivityObject = sensitivityMap.get(sensitivityType);
                    if (!(sensitivityObject instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> sensitivityResult = (Map<String, Object>) sensitivityObject;
                    hasValidClassCapital |= mapClassCapital(
                            classResult, sensitivityResult, sensitivityType,
                            ruleId, groupType, groupValue, riskClass);
                    mapBuckets(
                            bucketResults, sensitivityResult, sensitivityType,
                            ruleId, groupType, groupValue, riskClass);
                    DecompSnapshot decompSnapshot = buildDecompSnapshot(
                            sensitivityResult, sensitivityType,
                            ruleId, groupType, groupValue, riskClass);
                    assignAllocatedCapital(classResult, sensitivityType, decompSnapshot);
                    mapPositions(
                            posResults, sensitivityResult, decompSnapshot, selectedScenarioName,
                            sensitivityType, ruleId, groupType, groupValue, riskClass);
                }
                if (hasValidClassCapital) {
                    fillClassRiskSummary(classResult);
                    classResults.add(classResult);
                } else {
                    log.warn("FRTB SBA排除无有效资本的Class结果: ruleId={}, groupType={}, groupValue={}, riskClass={}",
                            ruleId, groupType, groupValue, riskClass);
                }
            }
        }

        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        result.put("classResults", classResults);
        result.put("bucketResults", bucketResults);
        result.put("posResults", posResults);
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean mapClassCapital(
            FRTBClassResult target,
            Map<String, Object> sensitivityResult,
            String sensitivityType,
            String ruleId,
            String groupType,
            String groupValue,
            String riskClass) {
        Object classObject = sensitivityResult.get("class");
        if (!(classObject instanceof Map)) {
            return false;
        }
        Map<String, Object> classData = (Map<String, Object>) classObject;
        try {
            BigDecimal normal = decimal(requiredNumber(classData, "capital_normal"));
            BigDecimal high = decimal(requiredNumber(classData, "capital_high"));
            BigDecimal low = decimal(requiredNumber(classData, "capital_low"));
            if ("Delta".equals(sensitivityType)) {
                target.setNormalDelta(normal);
                target.setHighDelta(high);
                target.setLowDelta(low);
            } else if ("Vega".equals(sensitivityType)) {
                target.setNormalVega(normal);
                target.setHighVega(high);
                target.setLowVega(low);
            } else if ("Curvature".equals(sensitivityType)) {
                target.setNormalCurvature(normal);
                target.setHighCurvature(high);
                target.setLowCurvature(low);
            }
            return true;
        } catch (InvalidResultException ex) {
            logMappingIssue("Class", ruleId, groupType, groupValue, riskClass, sensitivityType, null, ex);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void mapBuckets(
            List<FRTBBucketResult> target,
            Map<String, Object> sensitivityResult,
            String sensitivityType,
            String ruleId,
            String groupType,
            String groupValue,
            String riskClass) {
        Object bucketObject = sensitivityResult.get("bucket");
        if (!(bucketObject instanceof List)) {
            return;
        }
        for (Object item : (List<?>) bucketObject) {
            if (!(item instanceof Map)) {
                if (item != null) {
                    log.warn("FRTB SBA排除异常Bucket记录: ruleId={}, groupType={}, groupValue={}, riskClass={}, sensitivityType={}, value={}",
                            ruleId, groupType, groupValue, riskClass, sensitivityType, item);
                }
                continue;
            }
            Map<String, Object> bucket = (Map<String, Object>) item;
            String recordKey = text(bucket.get("riskFactorBucket"));
            try {
                FRTBBucketResult result = new FRTBBucketResult();
                result.setRuleId(ruleId);
                result.setGroupType(groupType);
                result.setGroupValue(groupValue);
                result.setRiskFactorClass(riskClass);
                result.setRiskFactorBucket(recordKey);
                result.setSensitivityType(sensitivityType);
                result.setKbM(decimal(requiredNumber(bucket, "Kb_MM")));
                result.setSbM(decimal(requiredNumber(bucket, "Sb_M")));
                result.setSbbM(decimal(requiredNumber(bucket, "Sbb_M")));
                result.setKbH(decimal(requiredNumber(bucket, "Kb_HH")));
                result.setSbH(decimal(requiredNumber(bucket, "Sb_H")));
                result.setSbbH(decimal(requiredNumber(bucket, "Sbb_H")));
                result.setKbL(decimal(requiredNumber(bucket, "Kb_LL")));
                result.setSbL(decimal(requiredNumber(bucket, "Sb_L")));
                result.setSbbL(decimal(requiredNumber(bucket, "Sbb_L")));
                target.add(result);
            } catch (InvalidResultException ex) {
                logMappingIssue("Bucket", ruleId, groupType, groupValue, riskClass,
                        sensitivityType, recordKey, ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private DecompSnapshot buildDecompSnapshot(
            Map<String, Object> sensitivityResult,
            String sensitivityType,
            String ruleId,
            String groupType,
            String groupValue,
            String riskClass) {
        DecompSnapshot snapshot = new DecompSnapshot();
        Object decompObject = sensitivityResult.get("decompRslt");
        if (!(decompObject instanceof List)) {
            return snapshot;
        }
        for (Object item : (List<?>) decompObject) {
            if (!(item instanceof Map)) {
                if (item != null) {
                    log.warn("FRTB SBA排除异常Decomp记录: ruleId={}, groupType={}, groupValue={}, riskClass={}, sensitivityType={}, value={}",
                            ruleId, groupType, groupValue, riskClass, sensitivityType, item);
                }
                continue;
            }
            Map<String, Object> decomp = (Map<String, Object>) item;
            String key = buildDecompKey(decomp, sensitivityType);
            try {
                double normal = requiredNumber(decomp, "allocatedCapital_normal");
                double high = requiredNumber(decomp, "allocatedCapital_high");
                double low = requiredNumber(decomp, "allocatedCapital_low");
                snapshot.index.put(key, decomp);
                snapshot.allocatedNormal += normal;
                snapshot.allocatedHigh += high;
                snapshot.allocatedLow += low;
            } catch (InvalidResultException ex) {
                snapshot.invalidKeys.add(key);
                logMappingIssue("Decomp", ruleId, groupType, groupValue, riskClass,
                        sensitivityType, key, ex);
            }
        }
        return snapshot;
    }

    private static void assignAllocatedCapital(
            FRTBClassResult target,
            String sensitivityType,
            DecompSnapshot snapshot) {
        BigDecimal normal = decimal(snapshot.allocatedNormal);
        BigDecimal high = decimal(snapshot.allocatedHigh);
        BigDecimal low = decimal(snapshot.allocatedLow);
        if ("Delta".equals(sensitivityType)) {
            target.setAllocDeltaNormal(normal);
            target.setAllocDeltaHigh(high);
            target.setAllocDeltaLow(low);
        } else if ("Vega".equals(sensitivityType)) {
            target.setAllocVegaNormal(normal);
            target.setAllocVegaHigh(high);
            target.setAllocVegaLow(low);
        } else if ("Curvature".equals(sensitivityType)) {
            target.setAllocCurvatureNormal(normal);
            target.setAllocCurvatureHigh(high);
            target.setAllocCurvatureLow(low);
        }
    }

    @SuppressWarnings("unchecked")
    private void mapPositions(
            List<FRTBPosResult> target,
            Map<String, Object> sensitivityResult,
            DecompSnapshot decompSnapshot,
            String selectedScenarioName,
            String sensitivityType,
            String ruleId,
            String groupType,
            String groupValue,
            String riskClass) {
        Object positionObject = sensitivityResult.get("pos");
        if (!(positionObject instanceof List)) {
            return;
        }
        for (Object item : (List<?>) positionObject) {
            if (!(item instanceof Map)) {
                if (item != null) {
                    log.warn("FRTB SBA排除异常Position记录: ruleId={}, groupType={}, groupValue={}, riskClass={}, sensitivityType={}, value={}",
                            ruleId, groupType, groupValue, riskClass, sensitivityType, item);
                }
                continue;
            }
            Map<String, Object> position = (Map<String, Object>) item;
            String key = buildDecompKey(position, sensitivityType);
            if (decompSnapshot.invalidKeys.contains(key)) {
                log.warn("FRTB SBA排除关联异常Decomp的Position记录: ruleId={}, groupType={}, groupValue={}, riskClass={}, sensitivityType={}, recordKey={}",
                        ruleId, groupType, groupValue, riskClass, sensitivityType, key);
                continue;
            }
            try {
                Map<String, Object> decomp = decompSnapshot.index.get(key);
                Double contribution = null;
                Double activeCvr = null;
                if (decomp != null) {
                    if (selectedScenarioName == null) {
                        throw new InvalidResultException("selectedScenario", null);
                    }
                    contribution = requiredNumber(decomp, "allocatedCapital_" + selectedScenarioName);
                    if (FrtbConstants.SENS_CURVATURE.equals(sensitivityType)) {
                        activeCvr = requiredNumber(decomp, "activeCvr_" + selectedScenarioName);
                    }
                }
                FRTBPosResult result = buildBasePosResult(
                        ruleId, groupType, groupValue, riskClass, sensitivityType, position);
                if (FrtbConstants.SENS_CURVATURE.equals(sensitivityType)) {
                    if (activeCvr != null) {
                        result.setSensitivityValRptCurrCny(decimal(activeCvr));
                        result.setWs(decimal(activeCvr));
                        fillContribution(result, contribution, activeCvr);
                    }
                    result.setRiskWeight(null);
                } else {
                    double originalSensitivity = requiredNumber(position, "sensitivityValRptCurrCny");
                    result.setSensitivityValRptCurrCny(decimal(originalSensitivity));
                    result.setRiskWeight(decimal(requiredNumber(position, "riskWeight")));
                    result.setWs(decimal(requiredNumber(position, "ws")));
                    fillContribution(result, contribution, originalSensitivity);
                }
                target.add(result);
            } catch (InvalidResultException ex) {
                logMappingIssue("Position", ruleId, groupType, groupValue, riskClass,
                        sensitivityType, key, ex);
            }
        }
    }

    private static FRTBClassResult newClassResult(
            String ruleId,
            String groupType,
            String groupValue,
            String riskClass) {
        FRTBClassResult result = new FRTBClassResult();
        result.setRuleId(ruleId);
        result.setGroupType(groupType);
        result.setGroupValue(groupValue);
        result.setRiskFactorClass(riskClass);
        return result;
    }

    private static FRTBPosResult buildBasePosResult(
            String ruleId,
            String groupType,
            String groupValue,
            String riskClass,
            String sensitivityType,
            Map<String, Object> position) {
        FRTBPosResult result = new FRTBPosResult();
        result.setRuleId(ruleId);
        result.setGroupType(groupType);
        result.setGroupValue(groupValue);
        result.setRiskFactorId(text(position.get("riskFactorId")));
        result.setRiskFactorBucket(text(position.get("riskFactorBucket")));
        result.setRiskFactorClass(riskClass);
        result.setRiskFactorType(nullableText(position.get("riskFactorType")));
        result.setRiskFactorVertex1(nullableText(position.get("riskFactorVertex1")));
        result.setRiskFactorVertex2(nullableText(position.get("riskFactorVertex2")));
        result.setSensitivityType(sensitivityType);
        return result;
    }

    private static void fillContribution(FRTBPosResult result, Double contribution, double sensitivity) {
        if (contribution == null) {
            return;
        }
        result.setContribution(decimal(contribution));
        if (Math.abs(sensitivity) > 1e-12) {
            result.setUnitContribution(decimal(contribution / sensitivity));
        }
    }

    private static void fillClassRiskSummary(FRTBClassResult result) {
        double totalNormal = sum(result.getNormalDelta(), result.getNormalVega(), result.getNormalCurvature());
        double totalHigh = sum(result.getHighDelta(), result.getHighVega(), result.getHighCurvature());
        double totalLow = sum(result.getLowDelta(), result.getLowVega(), result.getLowCurvature());
        double maxTotal = Math.max(Math.max(totalNormal, totalHigh), totalLow);
        result.setRiskCharge(decimal(maxTotal));
        if (maxTotal == totalHigh) {
            result.setMaxSign("high");
        } else if (maxTotal == totalLow) {
            result.setMaxSign("low");
        } else {
            result.setMaxSign("normal");
        }

        double allocatedNormal = sum(
                result.getAllocDeltaNormal(), result.getAllocVegaNormal(), result.getAllocCurvatureNormal());
        double allocatedHigh = sum(
                result.getAllocDeltaHigh(), result.getAllocVegaHigh(), result.getAllocCurvatureHigh());
        double allocatedLow = sum(
                result.getAllocDeltaLow(), result.getAllocVegaLow(), result.getAllocCurvatureLow());
        result.setAllocatedCapital(decimal(Math.max(Math.max(allocatedNormal, allocatedHigh), allocatedLow)));
    }

    @SuppressWarnings("unchecked")
    private String selectResultScenarioName(
            Map<String, Object> mapResult,
            String ruleId,
            String groupType,
            String groupValue) {
        Object allObject = mapResult == null ? null : mapResult.get(RISK_CLASS_ALL);
        if (!(allObject instanceof Map)) {
            logMappingIssue("Scenario", ruleId, groupType, groupValue,
                    RISK_CLASS_ALL, null, null, new InvalidResultException("ALL", allObject));
            return null;
        }
        Map<String, Object> allMap = (Map<String, Object>) allObject;
        double totalNormal = 0.0;
        double totalHigh = 0.0;
        double totalLow = 0.0;
        boolean found = false;
        for (String sensitivityType : SENSITIVITY_TYPES) {
            Object sensitivityObject = allMap.get(sensitivityType);
            if (!(sensitivityObject instanceof Map)) {
                continue;
            }
            try {
                Object classObject = ((Map<String, Object>) sensitivityObject).get("class");
                if (!(classObject instanceof Map)) {
                    continue;
                }
                Map<String, Object> classData = (Map<String, Object>) classObject;
                totalNormal += requiredNumber(classData, "capital_normal");
                totalHigh += requiredNumber(classData, "capital_high");
                totalLow += requiredNumber(classData, "capital_low");
                found = true;
            } catch (InvalidResultException ex) {
                logMappingIssue("Scenario", ruleId, groupType, groupValue,
                        RISK_CLASS_ALL, sensitivityType, null, ex);
            }
        }
        if (!found) {
            logMappingIssue("Scenario", ruleId, groupType, groupValue,
                    RISK_CLASS_ALL, null, null, new InvalidResultException("ALL.class", null));
            return null;
        }
        return selectMaxScenarioName(totalNormal, totalHigh, totalLow);
    }

    private static String selectMaxScenarioName(double normal, double high, double low) {
        if (high >= normal && high >= low) {
            return "high";
        }
        if (low >= normal && low >= high) {
            return "low";
        }
        return "normal";
    }

    private static double requiredNumber(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Number)) {
            throw new InvalidResultException(field, value);
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) {
            throw new InvalidResultException(field, value);
        }
        return number;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }

    private static double sum(BigDecimal... values) {
        double result = 0.0;
        for (BigDecimal value : values) {
            if (value != null) {
                result += value.doubleValue();
            }
        }
        return result;
    }

    private static String buildDecompKey(Map<String, Object> values, String sensitivityType) {
        String key = text(values.get("riskFactorBucket")) + "|"
                + text(values.get("riskFactorId")) + "|"
                + text(values.get("riskFactorVertex1")) + "|"
                + text(values.get("riskFactorVertex2"));
        if (useRiskFactorTypeInDecompKey(values, sensitivityType)) {
            key = key + "|" + text(values.get("riskFactorType"));
        }
        return key;
    }

    private static boolean useRiskFactorTypeInDecompKey(Map<String, Object> values, String sensitivityType) {
        if (!FrtbConstants.SENS_CURVATURE.equals(sensitivityType)) {
            return true;
        }
        String riskClass = text(values.get("riskFactorClass"));
        return !FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)
                && !FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullableText(Object value) {
        return value == null ? null : value.toString();
    }

    private static void logMappingIssue(
            String resultType,
            String ruleId,
            String groupType,
            String groupValue,
            String riskClass,
            String sensitivityType,
            String recordKey,
            InvalidResultException error) {
        log.warn("FRTB SBA排除异常结果: resultType={}, ruleId={}, groupType={}, groupValue={}, riskClass={}, sensitivityType={}, recordKey={}, field={}, value={}",
                resultType, ruleId, groupType, groupValue, riskClass, sensitivityType,
                recordKey, error.field, error.value);
    }

    private static final class DecompSnapshot {
        private final Map<String, Map<String, Object>> index =
                new HashMap<String, Map<String, Object>>();
        private final Set<String> invalidKeys = new HashSet<String>();
        private double allocatedNormal;
        private double allocatedHigh;
        private double allocatedLow;
    }

    private static final class InvalidResultException extends RuntimeException {
        private final String field;
        private final Object value;

        private InvalidResultException(String field, Object value) {
            super("FRTB SBA结果字段异常: " + field);
            this.field = field;
            this.value = value;
        }
    }
}
