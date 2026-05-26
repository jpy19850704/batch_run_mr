package com.zcyh.mr.scenario.processor;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.util.ShockUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 历史数据补全器。
 *
 * <p>
 * 使用标准市场点对象补全历史数据中的缺失值。
 */
public class HistoryDataCompleter {

    private final LinearInterpolator interpolator;

    public HistoryDataCompleter() {
        this.interpolator = new LinearInterpolator();
    }

    /**
     * 补全历史数据。
     */
    public List<ScenarioMarketSeries> complete(
            List<ScenarioMarketSeries> nowData,
            List<ScenarioMarketSeries> historicalData,
            List<LocalDate> weekdays) {
        if (nowData == null || nowData.isEmpty()) {
            return historicalData == null ? new LinkedList<ScenarioMarketSeries>() : historicalData;
        }
        if (historicalData == null) {
            historicalData = new LinkedList<ScenarioMarketSeries>();
        }
        if (weekdays.isEmpty()) {
            return historicalData;
        }

        HistoryGrid grid = new HistoryGrid(weekdays, nowData);
        Map<String, Integer> termNum = calculateTermNum(nowData);

        fillGrid(grid, historicalData);
        fillMissingWholeDays(grid);
        interpolateTermDimension(grid, termNum);
        flattenBoundaries(grid);
        interpolateTimeDimension(grid);

        return gridToList(grid);
    }

    private Map<String, Integer> calculateTermNum(List<ScenarioMarketSeries> nowData) {
        Map<String, Integer> termNum = new HashMap<String, Integer>();
        nowData.stream()
                .collect(Collectors.groupingBy(ShockUtils::getUnique))
                .forEach((id, transfer) -> termNum.put(id, transfer.size()));
        return termNum;
    }

    private void fillGrid(HistoryGrid grid, List<ScenarioMarketSeries> data) {
        data.stream()
                .filter(point -> grid.colOf(point.getDataDate()) != null)
                .forEach(point -> {
                    Integer col = grid.colOf(point.getDataDate());
                    Integer row = grid.rowOfCurve(ShockUtils.getUniqueCode(point));
                    if (row != null && col != null) {
                        grid.set(row, col, point);
                    }
                });
    }

    private void interpolateTermDimension(HistoryGrid grid, Map<String, Integer> termNum) {
        int rowSize = grid.rows();
        int colSize = grid.cols();
        for (int i = 0; i < rowSize;) {
            String unique = grid.curveAtRow(i);
            if (unique == null) {
                i++;
                continue;
            }
            int lastIndexOf = unique.lastIndexOf(ShockUtils.SPLITSTR);
            String curveGroup = (lastIndexOf == -1) ? unique : unique.substring(0, lastIndexOf);

            Integer termCodeSum = termNum.get(curveGroup);
            if (termCodeSum == null || termCodeSum <= 0) {
                i++;
                continue;
            }

            for (int j = 0; j < colSize; j++) {
                ScenarioMarketSeries startPoint = grid.get(i, j);
                if (!hasValue(startPoint)) {
                    ScenarioMarketSeries found = findFirstValidTerm(grid, i, j, termCodeSum);
                    if (found != null) {
                        grid.set(i, j, copyToCell(found, grid, i, j));
                    } else {
                        continue;
                    }
                }

                int endRow = i + termCodeSum - 1;
                ScenarioMarketSeries endPoint = grid.get(endRow, j);
                if (!hasValue(endPoint)) {
                    ScenarioMarketSeries found = findLastValidTerm(grid, i, j, termCodeSum);
                    if (found != null) {
                        grid.set(endRow, j, copyToCell(found, grid, endRow, j));
                    } else {
                        continue;
                    }
                }

                for (int n = 0; n < termCodeSum; n++) {
                    int row = i + n;
                    ScenarioMarketSeries point = grid.get(row, j);
                    if (hasValue(point)) {
                        continue;
                    }

                    ScenarioMarketSeries prevRow = grid.get(row - 1, j);
                    ScenarioMarketSeries nextRow = null;
                    for (int searchRow = row + 1; searchRow <= endRow; searchRow++) {
                        ScenarioMarketSeries candidate = grid.get(searchRow, j);
                        if (hasValue(candidate)) {
                            nextRow = candidate;
                            break;
                        }
                    }

                    if (hasValue(prevRow) && hasValue(nextRow)) {
                        int targetTermDays = resolveRowTermDays(grid, row);
                        BigDecimal interpolatedValue = interpolator.interpolate(prevRow, nextRow, targetTermDays, null, null);
                        if (interpolatedValue != null) {
                            ScenarioMarketSeries filled = copyToCell(prevRow, grid, row, j);
                            filled.setValue(interpolatedValue);
                            grid.set(row, j, filled);
                        }
                    }
                }
            }
            i += termCodeSum;
        }
    }

    private void flattenBoundaries(HistoryGrid grid) {
        int rowSize = grid.rows();
        int colSize = grid.cols();
        for (int k = 0, col = 0; k < 2; k++, col = colSize - 1) {
            for (int i = 0; i < rowSize; i++) {
                ScenarioMarketSeries point = grid.get(i, col);
                if (hasValue(point)) {
                    continue;
                }

                if (k == 0) {
                    for (int j = 0; j < colSize; j++) {
                        ScenarioMarketSeries found = grid.get(i, j);
                        if (hasValue(found)) {
                            grid.set(i, col, copyToCell(found, grid, i, col));
                            break;
                        }
                    }
                } else {
                    for (int j = colSize - 1; j >= 0; j--) {
                        ScenarioMarketSeries found = grid.get(i, j);
                        if (hasValue(found)) {
                            grid.set(i, col, copyToCell(found, grid, i, col));
                            break;
                        }
                    }
                }
            }
        }
    }

    private void fillMissingWholeDays(HistoryGrid grid) {
        int colSize = grid.cols();
        for (int col = 0; col < colSize; col++) {
            if (!isWholeDayMissing(grid, col)) {
                continue;
            }
            Integer sourceCol = findPreviousWholeDay(grid, col);
            if (sourceCol == null) {
                sourceCol = findNextWholeDay(grid, col);
            }
            if (sourceCol != null) {
                copyWholeDay(grid, sourceCol, col);
            }
        }
    }

    private void interpolateTimeDimension(HistoryGrid grid) {
        int rowSize = grid.rows();
        int colSize = grid.cols();
        for (int i = 0; i < rowSize; i++) {
            for (int j = 0; j < colSize; j++) {
                ScenarioMarketSeries point = grid.get(i, j);
                if (hasValue(point)) {
                    continue;
                }

                ScenarioMarketSeries prevCol = grid.get(i, j - 1);
                if (!hasValue(prevCol)) {
                    continue;
                }

                for (int k = j; k < colSize - 1; k++) {
                    ScenarioMarketSeries nextCol = grid.get(i, k + 1);
                    if (hasValue(nextCol)) {
                        int span = (k + 1) - (j - 1);
                        BigDecimal interpolatedValue = interpolator.interpolate(
                                prevCol,
                                nextCol,
                                resolveRowTermDays(grid, i),
                                0,
                                span);
                        if (interpolatedValue != null) {
                            ScenarioMarketSeries filled = copyToCell(prevCol, grid, i, j);
                            filled.setValue(interpolatedValue);
                            grid.set(i, j, filled);
                        }
                        break;
                    }
                }
            }
        }
    }

    private List<ScenarioMarketSeries> gridToList(HistoryGrid grid) {
        int rowSize = grid.rows();
        int colSize = grid.cols();
        LinkedList<ScenarioMarketSeries> result = new LinkedList<ScenarioMarketSeries>();
        for (int i = 0; i < rowSize; i++) {
            for (int j = 0; j < colSize; j++) {
                ScenarioMarketSeries point = grid.get(i, j);
                if (point != null) {
                    result.add(point);
                }
            }
        }
        return result;
    }

    private ScenarioMarketSeries findFirstValidTerm(
            HistoryGrid grid,
            int startRow,
            int col,
            int termCodeSum) {
        for (int k = 0; k < termCodeSum; k++) {
            ScenarioMarketSeries point = grid.get(startRow + k, col);
            if (hasValue(point)) {
                return point;
            }
        }
        return null;
    }

    private ScenarioMarketSeries findLastValidTerm(
            HistoryGrid grid,
            int startRow,
            int col,
            int termCodeSum) {
        for (int k = termCodeSum - 1; k >= 0; k--) {
            ScenarioMarketSeries point = grid.get(startRow + k, col);
            if (hasValue(point)) {
                return point;
            }
        }
        return null;
    }

    private ScenarioMarketSeries copyToCell(ScenarioMarketSeries source, HistoryGrid grid, int row, int col) {
        ScenarioMarketSeries copied = copyPoint(source);
        copied.setDataDate(grid.dateAtCol(col));
        copied.setTermCode(grid.termAtRow(row));
        copied.setTermDays(resolveRowTermDays(grid, row));
        return copied;
    }

    private ScenarioMarketSeries copyPoint(ScenarioMarketSeries source) {
        ScenarioMarketSeries copied = new ScenarioMarketSeries();
        copied.setCurveType(source.getCurveType());
        copied.setCurveCode(source.getCurveCode());
        copied.setDataDate(source.getDataDate());
        copied.setTermCode(source.getTermCode());
        copied.setTermDays(source.getTermDays());
        copied.setDimension2(source.getDimension2());
        copied.setValue(source.getValue());
        return copied;
    }

    private int resolveRowTermDays(HistoryGrid grid, int row) {
        Integer termDays = grid.termDaysAtRow(row);
        if (termDays != null && termDays > 0) {
            return termDays;
        }
        String termCode = grid.termAtRow(row);
        return ShockUtils.termCodeToInt(termCode);
    }

    private boolean hasValue(ScenarioMarketSeries point) {
        return point != null && point.getValue() != null;
    }

    private boolean isWholeDayMissing(HistoryGrid grid, int col) {
        for (int row = 0; row < grid.rows(); row++) {
            if (hasValue(grid.get(row, col))) {
                return false;
            }
        }
        return true;
    }

    private Integer findPreviousWholeDay(HistoryGrid grid, int targetCol) {
        for (int col = targetCol - 1; col >= 0; col--) {
            if (!isWholeDayMissing(grid, col)) {
                return col;
            }
        }
        return null;
    }

    private Integer findNextWholeDay(HistoryGrid grid, int targetCol) {
        for (int col = targetCol + 1; col < grid.cols(); col++) {
            if (!isWholeDayMissing(grid, col)) {
                return col;
            }
        }
        return null;
    }

    private void copyWholeDay(HistoryGrid grid, int sourceCol, int targetCol) {
        for (int row = 0; row < grid.rows(); row++) {
            ScenarioMarketSeries source = grid.get(row, sourceCol);
            if (hasValue(source)) {
                grid.set(row, targetCol, copyToCell(source, grid, row, targetCol));
            }
        }
    }

}
