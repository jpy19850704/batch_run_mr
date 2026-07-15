package com.zcyh.mr.springboot.ima;

import com.zcyh.mr.frtbima.validation.common.ValidationConstants;
import com.zcyh.mr.frtbima.validation.pla.KolmogorovSmirnovTest;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository.ExternalPnlRow;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository.GroupKey;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * IMA KS 检验计算服务。
 */
@Service
public class ImaKsCalculationService {
    private final KolmogorovSmirnovTest ksTest = new KolmogorovSmirnovTest();

    public List<GroupResult> calculate(Map<GroupKey, List<ExternalPnlRow>> pnlRows) {
        List<GroupResult> results = new ArrayList<GroupResult>();
        for (Map.Entry<GroupKey, List<ExternalPnlRow>> entry : pnlRows.entrySet()) {
            GroupKey groupKey = entry.getKey();
            List<ExternalPnlRow> rows = entry.getValue();
            rows.sort(Comparator.comparing(row -> row.dataDate));
            validateObservationCount(rows, groupKey);
            BigDecimal statistic = ksTest.compute(readHypotheticalSeries(rows), readRiskTheoreticalSeries(rows));
            results.add(new GroupResult(groupKey, rows.size(), statistic, evaluateZone(statistic)));
        }
        return results;
    }

    private void validateObservationCount(List<ExternalPnlRow> rows, GroupKey groupKey) {
        int count = rows == null ? 0 : rows.size();
        if (count != ImaValidationInputRepository.REQUIRED_OBSERVATION_COUNT) {
            throw new IllegalArgumentException("IMA 返回检验分组样本数必须为250: group_type="
                    + groupKey.groupType + ", group_value=" + groupKey.groupValue + ", actual_count=" + count);
        }
    }

    private List<BigDecimal> readHypotheticalSeries(List<ExternalPnlRow> series) {
        List<BigDecimal> values = new ArrayList<BigDecimal>();
        for (ExternalPnlRow row : series) {
            values.add(row.hypotheticalPnl);
        }
        return values;
    }

    private List<BigDecimal> readRiskTheoreticalSeries(List<ExternalPnlRow> series) {
        List<BigDecimal> values = new ArrayList<BigDecimal>();
        for (ExternalPnlRow row : series) {
            values.add(row.riskTheoreticalPnl);
        }
        return values;
    }

    private String evaluateZone(BigDecimal statistic) {
        if (statistic.compareTo(ValidationConstants.PLA_KS_GREEN_THRESHOLD) < 0) {
            return "GREEN";
        }
        if (statistic.compareTo(ValidationConstants.PLA_KS_RED_THRESHOLD) <= 0) {
            return "AMBER";
        }
        return "RED";
    }

    public static final class GroupResult {
        final GroupKey groupKey;
        final int sampleSize;
        final BigDecimal statistic;
        final String zone;

        GroupResult(GroupKey groupKey, int sampleSize, BigDecimal statistic, String zone) {
            this.groupKey = groupKey;
            this.sampleSize = sampleSize;
            this.statistic = statistic;
            this.zone = zone;
        }
    }
}
