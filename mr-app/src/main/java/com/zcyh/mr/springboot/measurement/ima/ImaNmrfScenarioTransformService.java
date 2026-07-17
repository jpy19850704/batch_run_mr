package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IMA_NMRF 情景生成结果转换。
 */
public final class ImaNmrfScenarioTransformService {
    private static final String SCENARIO_TYPE_IMA_NMRF = "IMA_NMRF";
    private static final String DIRECTION_UP = "UP";
    private static final String DIRECTION_DOWN = "DOWN";

    private ImaNmrfScenarioTransformService() {
    }

    public static List<ScenarioGeneratedRecord> transform(List<ScenarioGeneratedRecord> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        Map<String, NmrfBucketScenarioBuilder> builders =
                new LinkedHashMap<String, NmrfBucketScenarioBuilder>();
        int rawNmrfCount = 0;

        for (ScenarioGeneratedRecord record : records) {
            if (record == null || !isImaNmrf(record.getScenarioType())) {
                result.add(record);
                continue;
            }
            rawNmrfCount++;
            if (Boolean.TRUE.equals(record.getRfetModellable())) {
                continue;
            }
            if (!Boolean.FALSE.equals(record.getRfetModellable())) {
                throw new IllegalStateException("IMA_NMRF 情景记录缺少 RFET 不可建模标记，scenario_id="
                        + record.getScenarioId() + ", sub_scenario_id=" + record.getSubScenarioId());
            }
            String scenarioId = required(record.getScenarioId(), "SCENARIO_ID");
            String bucketId = required(record.getRfetBucketId(), "RFET_BUCKET_ID");
            String curveType = required(record.getCurveType(), "CURVE_TYPE").toUpperCase(Locale.ROOT);
            String curveCode = required(record.getCurveCode(), "CURVE_CODE").toUpperCase(Locale.ROOT);
            String key = scenarioId + "|" + bucketId + "|" + curveType + "|" + curveCode;
            NmrfBucketScenarioBuilder builder = builders.get(key);
            if (builder == null) {
                builder = new NmrfBucketScenarioBuilder(scenarioId, bucketId, curveType, curveCode);
                builders.put(key, builder);
            }
            builder.add(record);
        }

        int transformedCount = 0;
        for (NmrfBucketScenarioBuilder builder : builders.values()) {
            List<ScenarioGeneratedRecord> transformed = builder.buildRecords();
            transformedCount += transformed.size();
            result.addAll(transformed);
        }
        if (rawNmrfCount > 0 && transformedCount == 0) {
            throw new IllegalStateException("IMA_NMRF 情景未生成不可建模风险因子 UP/DOWN 记录");
        }
        return result;
    }

    private static boolean isImaNmrf(String scenarioType) {
        String safe = trimToNull(scenarioType);
        return safe != null && SCENARIO_TYPE_IMA_NMRF.equals(safe.toUpperCase(Locale.ROOT));
    }

    private static String required(String value, String fieldName) {
        String safe = trimToNull(value);
        if (safe == null) {
            throw new IllegalStateException("IMA_NMRF 情景记录缺少 " + fieldName);
        }
        return safe;
    }

    private static BigDecimal required(BigDecimal value, String fieldName, ScenarioGeneratedRecord record) {
        if (value == null) {
            throw new IllegalStateException("IMA_NMRF 情景记录缺少 " + fieldName
                    + "，scenario_id=" + record.getScenarioId()
                    + ", sub_scenario_id=" + record.getSubScenarioId()
                    + ", curve_type=" + record.getCurveType()
                    + ", curve_code=" + record.getCurveCode());
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static class NmrfBucketScenarioBuilder {
        private final String scenarioId;
        private final String bucketId;
        private final String curveType;
        private final String curveCode;
        private final Map<String, PointStress> points = new LinkedHashMap<String, PointStress>();

        private NmrfBucketScenarioBuilder(String scenarioId, String bucketId, String curveType, String curveCode) {
            this.scenarioId = scenarioId;
            this.bucketId = bucketId;
            this.curveType = curveType;
            this.curveCode = curveCode;
        }

        private void add(ScenarioGeneratedRecord record) {
            Integer termDays = record.getTermDays();
            if (termDays == null) {
                throw new IllegalStateException("IMA_NMRF 情景记录缺少 TERM_DAYS，scenario_id="
                        + record.getScenarioId() + ", sub_scenario_id=" + record.getSubScenarioId()
                        + ", curve_type=" + record.getCurveType() + ", curve_code=" + record.getCurveCode());
            }
            String pointKey = termDays + "|" + (trimToNull(record.getDimension2()) == null
                    ? "" : record.getDimension2().trim());
            PointStress stress = points.get(pointKey);
            if (stress == null) {
                stress = new PointStress(record);
                points.put(pointKey, stress);
            }
            stress.add(record);
        }

        private List<ScenarioGeneratedRecord> buildRecords() {
            List<ScenarioGeneratedRecord> records = new ArrayList<ScenarioGeneratedRecord>();
            for (PointStress stress : points.values()) {
                records.add(stress.build(DIRECTION_UP, stress.maxUpShock));
                records.add(stress.build(DIRECTION_DOWN, stress.maxDownShock));
            }
            return records;
        }

        private class PointStress {
            private final ScenarioGeneratedRecord source;
            private BigDecimal maxUpShock = BigDecimal.ZERO;
            private BigDecimal maxDownShock = BigDecimal.ZERO;

            private PointStress(ScenarioGeneratedRecord source) {
                this.source = source;
            }

            private void add(ScenarioGeneratedRecord record) {
                BigDecimal original = required(record.getOriginalValue(), "ORIGINAL_VALUE", record);
                BigDecimal changed = required(record.getChangedValue(), "CHANGED_RATE", record);
                if (source.getOriginalValue() != null
                        && original.compareTo(source.getOriginalValue()) != 0) {
                    throw new IllegalStateException("IMA_NMRF 同一点位原始值不一致，scenario_id="
                            + record.getScenarioId() + ", rfet_bucket_id=" + bucketId
                            + ", curve_type=" + curveType + ", curve_code=" + curveCode
                            + ", term_days=" + record.getTermDays()
                            + ", dimension2=" + record.getDimension2());
                }
                validateTermCode(record);
                BigDecimal shock = changed.subtract(original);
                if (shock.compareTo(maxUpShock) > 0) {
                    maxUpShock = shock;
                }
                if (shock.compareTo(maxDownShock) < 0) {
                    maxDownShock = shock;
                }
            }

            private void validateTermCode(ScenarioGeneratedRecord record) {
                String sourceTermCode = trimToNull(source.getTermCode());
                String currentTermCode = trimToNull(record.getTermCode());
                if (sourceTermCode != null && currentTermCode != null
                        && !sourceTermCode.equals(currentTermCode)) {
                    throw new IllegalStateException("IMA_NMRF 同一点位 TERM_CODE 不一致，scenario_id="
                            + record.getScenarioId() + ", rfet_bucket_id=" + bucketId
                            + ", curve_type=" + curveType + ", curve_code=" + curveCode
                            + ", term_days=" + record.getTermDays()
                            + ", dimension2=" + record.getDimension2());
                }
            }

            private ScenarioGeneratedRecord build(String direction, BigDecimal shock) {
                ScenarioGeneratedRecord record = new ScenarioGeneratedRecord();
                record.setScenarioId(scenarioId);
                record.setSubScenarioId(bucketId + "_" + direction);
                record.setScenarioName("NMRF_" + bucketId + "_" + direction);
                record.setScenarioType(SCENARIO_TYPE_IMA_NMRF);
                record.setReducedSetFlag(source.getReducedSetFlag());
                record.setRiskGroupId(source.getRiskGroupId());
                record.setCurveType(curveType);
                record.setCurveCode(curveCode);
                record.setDataDate(source.getDataDate());
                record.setTermCode(source.getTermCode());
                record.setTermDays(source.getTermDays());
                record.setDimension2(source.getDimension2());
                record.setOriginalValue(source.getOriginalValue());
                record.setChangedValue(source.getOriginalValue().add(shock));
                record.setShiftValue(shock);
                record.setShiftRule("ABSOLUTE");
                record.setRfetBucketId(bucketId);
                record.setRfetModellable(false);
                record.setRfetReducedSet(source.getRfetReducedSet());
                record.setModifier(source.getModifier());
                return record;
            }
        }
    }
}
