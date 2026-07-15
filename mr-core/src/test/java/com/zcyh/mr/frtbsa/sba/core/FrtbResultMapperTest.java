package com.zcyh.mr.frtbsa.sba.core;

import com.zcyh.mr.frtbsa.sba.pojo.FRTBBucketResult;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrtbResultMapperTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldExcludeInvalidDetailRecordsAndKeepValidResults() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ALL", riskClassResult(15.0, 16.0, 14.0, null, null));
        result.put("GIRR", riskClassResult(
                10.0, 12.0, 8.0,
                Arrays.asList(bucket("1", 2.0), bucket("2", "invalid")),
                Arrays.asList(position("RF-1", 30.0), position("RF-2", "invalid"))));

        Map<String, List<?>> mapped = new FrtbResultMapper().buildResults(
                result, "RULE-1", "PORTFOLIO", "P-1");

        List<FRTBClassResult> classResults = (List<FRTBClassResult>) mapped.get("classResults");
        List<FRTBBucketResult> bucketResults = (List<FRTBBucketResult>) mapped.get("bucketResults");
        List<FRTBPosResult> posResults = (List<FRTBPosResult>) mapped.get("posResults");
        assertEquals(2, classResults.size());
        assertEquals(new BigDecimal("12.0"), findClass(classResults, "GIRR").getRiskCharge());
        assertEquals(1, bucketResults.size());
        assertEquals("1", bucketResults.get(0).getRiskFactorBucket());
        assertEquals(1, posResults.size());
        assertEquals("RF-1", posResults.get(0).getRiskFactorId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExcludeInvalidAllSensitivityWhenSelectingScenario() {
        Map<String, Object> all = new LinkedHashMap<String, Object>();
        all.put("Delta", sensitivityResult("invalid", 20.0, 10.0, null, null));
        all.put("Vega", sensitivityResult(5.0, 7.0, 6.0, null, null));
        Map<String, Object> girr = riskClassResult(
                10.0, 12.0, 8.0,
                null, Arrays.asList(position("RF-1", 30.0)));
        Map<String, Object> girrDelta = (Map<String, Object>) girr.get("Delta");
        girrDelta.put("decompRslt", Arrays.asList(decomp("RF-1")));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ALL", all);
        result.put("GIRR", girr);

        Map<String, List<?>> mapped = new FrtbResultMapper().buildResults(
                result, "RULE-1", "PORTFOLIO", "P-1");

        List<FRTBPosResult> positions = (List<FRTBPosResult>) mapped.get("posResults");
        assertEquals(1, positions.size());
        assertEquals(new BigDecimal("2.0"), positions.get(0).getContribution());
    }

    private static FRTBClassResult findClass(List<FRTBClassResult> results, String riskClass) {
        return results.stream()
                .filter(result -> riskClass.equals(result.getRiskFactorClass()))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static Map<String, Object> riskClassResult(
            Object normal,
            Object high,
            Object low,
            List<Map<String, Object>> buckets,
            List<Map<String, Object>> positions) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("Delta", sensitivityResult(normal, high, low, buckets, positions));
        return result;
    }

    private static Map<String, Object> sensitivityResult(
            Object normal,
            Object high,
            Object low,
            List<Map<String, Object>> buckets,
            List<Map<String, Object>> positions) {
        Map<String, Object> capital = new LinkedHashMap<String, Object>();
        capital.put("capital_normal", normal);
        capital.put("capital_high", high);
        capital.put("capital_low", low);
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        delta.put("class", capital);
        if (buckets != null) {
            delta.put("bucket", buckets);
        }
        if (positions != null) {
            delta.put("pos", positions);
        }
        return delta;
    }

    private static Map<String, Object> bucket(String bucket, Object kbNormal) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("riskFactorBucket", bucket);
        result.put("Kb_MM", kbNormal);
        result.put("Sb_M", 1.0);
        result.put("Sbb_M", 1.0);
        result.put("Kb_HH", 2.0);
        result.put("Sb_H", 1.0);
        result.put("Sbb_H", 1.0);
        result.put("Kb_LL", 2.0);
        result.put("Sb_L", 1.0);
        result.put("Sbb_L", 1.0);
        return result;
    }

    private static Map<String, Object> position(String riskFactorId, Object ws) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("riskFactorId", riskFactorId);
        result.put("riskFactorBucket", "1");
        result.put("riskFactorType", "YIELD");
        result.put("riskFactorVertex1", "1Y");
        result.put("sensitivityValRptCurrCny", 100.0);
        result.put("riskWeight", 0.3);
        result.put("ws", ws);
        return result;
    }

    private static Map<String, Object> decomp(String riskFactorId) {
        Map<String, Object> result = position(riskFactorId, 30.0);
        result.put("allocatedCapital_normal", 1.0);
        result.put("allocatedCapital_high", 2.0);
        result.put("allocatedCapital_low", 3.0);
        return result;
    }
}
