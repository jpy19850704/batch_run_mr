package com.zcyh.mr.scenario.processor;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.util.ShockUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 历史数据网格（行：风险因子+期限，列：日期）。
 */
public class HistoryGrid {

    private final ScenarioMarketSeries[][] values;
    private final List<LocalDate> colToDate = new ArrayList<LocalDate>();
    private final Map<LocalDate, Integer> dateToCol = new HashMap<LocalDate, Integer>();
    private final List<String> rowToCurve = new ArrayList<String>();
    private final Map<String, Integer> curveToRow = new HashMap<String, Integer>();
    private final List<String> rowToTerm = new ArrayList<String>();
    private final List<Integer> rowToTermDays = new ArrayList<Integer>();

    public HistoryGrid(List<LocalDate> weekdays, List<ScenarioMarketSeries> nowData) {
        if (weekdays == null || weekdays.isEmpty()) {
            throw new IllegalArgumentException("weekdays为空");
        }
        if (nowData == null || nowData.isEmpty()) {
            throw new IllegalArgumentException("nowData为空");
        }

        for (int i = 0; i < weekdays.size(); i++) {
            LocalDate date = weekdays.get(i);
            colToDate.add(date);
            dateToCol.put(date, i);
        }

        List<ScenarioMarketSeries> sorted = nowData.stream()
                .sorted(ShockUtils.getComparatorForPoint())
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            ScenarioMarketSeries row = sorted.get(i);
            String uniqueCode = ShockUtils.getUniqueCode(row);
            curveToRow.put(uniqueCode, i);
            rowToCurve.add(uniqueCode);
            rowToTerm.add(row.getTermCode() == null ? "" : row.getTermCode());
            rowToTermDays.add(row.getTermDays() == null ? 0 : row.getTermDays());
        }

        values = new ScenarioMarketSeries[rowToCurve.size()][colToDate.size()];
    }

    public int rows() {
        return rowToCurve.size();
    }

    public int cols() {
        return colToDate.size();
    }

    public Integer rowOfCurve(String uniqueCode) {
        return curveToRow.get(uniqueCode);
    }

    public Integer colOf(LocalDate date) {
        return dateToCol.get(date);
    }

    public String curveAtRow(int row) {
        if (row < 0 || row >= rowToCurve.size()) {
            return null;
        }
        return rowToCurve.get(row);
    }

    public String termAtRow(int row) {
        if (row < 0 || row >= rowToTerm.size()) {
            return null;
        }
        return rowToTerm.get(row);
    }

    public Integer termDaysAtRow(int row) {
        if (row < 0 || row >= rowToTermDays.size()) {
            return null;
        }
        return rowToTermDays.get(row);
    }

    public LocalDate dateAtCol(int col) {
        if (col < 0 || col >= colToDate.size()) {
            return null;
        }
        return colToDate.get(col);
    }

    public ScenarioMarketSeries get(int row, int col) {
        if (!inRange(row, col)) {
            return null;
        }
        return values[row][col];
    }

    public void set(int row, int col, ScenarioMarketSeries value) {
        if (!inRange(row, col)) {
            return;
        }
        values[row][col] = value;
    }

    private boolean inRange(int row, int col) {
        return row >= 0 && row < rows() && col >= 0 && col < cols();
    }
}
