package com.zcyh.mr.springboot.ima;

import com.zcyh.mr.frtbima.validation.backtest.DeskLevelBacktest;
import com.zcyh.mr.frtbima.validation.model.BacktestResult;
import com.zcyh.mr.frtbima.validation.model.DailyPnl;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository.ExternalPnlRow;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository.GroupKey;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * IMA 回测计算服务。
 */
@Service
public class ImaBacktestCalculationService {
    private final DeskLevelBacktest backtest = new DeskLevelBacktest();

    public List<GroupResult> calculate(
            Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup,
            Map<GroupKey, List<ExternalPnlRow>> pnlRows) {
        Map<GroupKey, TreeMap<LocalDate, ExternalPnlRow>> pnlByGroupDate = indexPnlRows(pnlRows);
        List<GroupResult> results = new ArrayList<GroupResult>();
        for (Map.Entry<GroupKey, TreeMap<LocalDate, BigDecimal>> entry : varByGroup.entrySet()) {
            List<DailyPnl> series = buildDailySeries(entry.getValue(), pnlByGroupDate.get(entry.getKey()));
            results.add(new GroupResult(entry.getKey(), series.size(), backtest.run(series)));
        }
        return results;
    }

    private Map<GroupKey, TreeMap<LocalDate, ExternalPnlRow>> indexPnlRows(
            Map<GroupKey, List<ExternalPnlRow>> pnlRows) {
        Map<GroupKey, TreeMap<LocalDate, ExternalPnlRow>> result =
                new LinkedHashMap<GroupKey, TreeMap<LocalDate, ExternalPnlRow>>();
        for (Map.Entry<GroupKey, List<ExternalPnlRow>> entry : pnlRows.entrySet()) {
            TreeMap<LocalDate, ExternalPnlRow> dateRows = new TreeMap<LocalDate, ExternalPnlRow>();
            for (ExternalPnlRow row : entry.getValue()) {
                dateRows.put(row.dataDate, row);
            }
            result.put(entry.getKey(), dateRows);
        }
        return result;
    }

    private List<DailyPnl> buildDailySeries(
            TreeMap<LocalDate, BigDecimal> groupVarRows,
            TreeMap<LocalDate, ExternalPnlRow> pnlRows) {
        List<DailyPnl> series = new ArrayList<DailyPnl>();
        for (Map.Entry<LocalDate, BigDecimal> varEntry : groupVarRows.entrySet()) {
            LocalDate date = varEntry.getKey();
            BigDecimal varValue = varEntry.getValue();
            ExternalPnlRow pnlRow = pnlRows == null ? null : pnlRows.get(date);
            if (pnlRow == null) {
                BigDecimal threshold = varValue.abs().negate();
                BigDecimal missingPnl = threshold.subtract(new BigDecimal("0.01"));
                series.add(new DailyPnl(date, missingPnl, null, null, varValue));
                continue;
            }
            series.add(new DailyPnl(
                    date,
                    pnlRow.actualPnl,
                    pnlRow.hypotheticalPnl,
                    pnlRow.riskTheoreticalPnl,
                    varValue));
        }
        return series;
    }

    public static final class GroupResult {
        final GroupKey groupKey;
        final int sampleSize;
        final BacktestResult result;

        GroupResult(GroupKey groupKey, int sampleSize, BacktestResult result) {
            this.groupKey = groupKey;
            this.sampleSize = sampleSize;
            this.result = result;
        }
    }
}
