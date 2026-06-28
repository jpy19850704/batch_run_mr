package com.zcyh.mr.springboot.ima;

import com.zcyh.mr.frtbima.rfet.model.RfetResult;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * IMA RFET 情景点位标记器。
 */
@Component
public class ImaRfetScenarioAnnotator {

    public void annotate(List<RfetResult> buckets, List<ScenarioGeneratedRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (buckets == null || buckets.isEmpty()) {
            throw new IllegalStateException("IMA 情景缺少 RFET 数据");
        }
        for (ScenarioGeneratedRecord record : records) {
            if (record == null || !isImaScenario(record.getScenarioType())) {
                continue;
            }
            RfetResult matched = match(buckets, record);
            if (matched == null) {
                throw new IllegalStateException("IMA 情景点未匹配 RFET bucket，scenario_id="
                        + record.getScenarioId()
                        + ", sub_scenario_id=" + record.getSubScenarioId()
                        + ", curve_type=" + record.getCurveType()
                        + ", curve_code=" + record.getCurveCode()
                        + ", term_days=" + record.getTermDays()
                        + ", dimension2=" + record.getDimension2());
            }
            record.setRfetBucketId(matched.getBucketId());
            record.setRfetModellable(matched.isModellable());
            record.setRfetReducedSet(matched.isReducedSet());
        }
    }

    private RfetResult match(List<RfetResult> buckets, ScenarioGeneratedRecord record) {
        String curveCode = normalizeText(record.getCurveCode());
        String curveType = normalizeText(record.getCurveType());
        Integer termDays = record.getTermDays();
        if (curveCode == null || curveType == null || termDays == null) {
            return null;
        }
        BigDecimal delta = toBigDecimal(record.getDimension2());
        RfetResult matched = null;
        for (RfetResult bucket : buckets) {
            if (!curveCode.equals(bucket.getCurveId()) || !curveType.equals(bucket.getRfType())) {
                continue;
            }
            if (termDays < bucket.getTenorMin() || termDays > bucket.getTenorMax()) {
                continue;
            }
            if (!bucket.isDeltaBucketFlag()) {
                matched = ensureSingleMatch(record, matched, bucket);
                continue;
            }
            if (delta == null || bucket.getDeltaMin() == null || bucket.getDeltaMax() == null) {
                continue;
            }
            BigDecimal deltaMin = BigDecimal.valueOf(bucket.getDeltaMin());
            BigDecimal deltaMax = BigDecimal.valueOf(bucket.getDeltaMax());
            int lower = delta.compareTo(deltaMin);
            int upper = delta.compareTo(deltaMax);
            boolean includeUpper = deltaMax.compareTo(BigDecimal.ONE) >= 0;
            if (lower >= 0 && (includeUpper ? upper <= 0 : upper < 0)) {
                matched = ensureSingleMatch(record, matched, bucket);
            }
        }
        return matched;
    }

    private RfetResult ensureSingleMatch(
            ScenarioGeneratedRecord record,
            RfetResult matched,
            RfetResult candidate) {
        if (matched != null) {
            throw new IllegalStateException("RFET 匹配到多个桶，curve_type="
                    + record.getCurveType()
                    + ", curve_code=" + record.getCurveCode()
                    + ", term_days=" + record.getTermDays()
                    + ", dimension2=" + record.getDimension2()
                    + ", bucket1=" + matched.getBucketId()
                    + ", group1=" + matched.getGroupType() + "/" + matched.getGroupId()
                    + ", bucket2=" + candidate.getBucketId()
                    + ", group2=" + candidate.getGroupType() + "/" + candidate.getGroupId());
        }
        return candidate;
    }

    private static boolean isImaScenario(String scenarioType) {
        String value = normalizeText(scenarioType);
        return "IMA_NORMAL".equals(value) || "IMA_STRESS".equals(value) || "IMA_NMRF".equals(value);
    }

    private static String normalizeText(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isEmpty() ? null : text.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal toBigDecimal(String value) {
        String text = normalizeText(value);
        if (text == null) {
            return null;
        }
        return new BigDecimal(text);
    }
}
