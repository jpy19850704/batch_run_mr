import com.zcyh.mr.scenario.ScenarioRangeResolver;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.processor.HistoryDataCompleter;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TmpScenarioBusinessDayCheck {

    private static final String CALENDAR_PATH = "E:\\zcyh_mr\\engine\\calendar_data";
    private static final String CALENDAR_CODE = "DEFAULT_WEEKEND";
    private static final String H2_URL = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String H2_USER = "sa";
    private static final String H2_PASSWORD = "";

    public static void main(String[] args) throws Exception {
        com.zcyh.mr.core.Calendar calendar = new com.zcyh.mr.core.Calendar();
        calendar.loadFromPath(CALENDAR_PATH);

        printCalendarChecks(calendar);

        MarketSeriesInfo info = loadOneYearMarketSeries();
        System.out.println("H2_SAMPLE=" + info.curveType + "|" + info.curveCode + "|" + info.startDate + "|" + info.endDate + "|DATES=" + info.distinctDates);

        ScenarioRangeResolver resolver = new ScenarioRangeResolver(calendar);
        LocalDate valuationDate = info.endDate;

        printRange("VAR", resolver.resolve(buildVarDefinition(info), valuationDate));
        printRange("MC", resolver.resolve(buildMcDefinition(info), valuationDate));
        printRange("BACKTEST", resolver.resolve(buildBacktestDefinition(info), valuationDate));
        printRange("SVAR", resolver.resolve(buildSvarDefinition(info), valuationDate));
        printRange("HISTORY", resolver.resolve(buildHistoryDefinition(info), valuationDate));

        printCompleterChecks();
    }

    private static void printCalendarChecks(com.zcyh.mr.core.Calendar calendar) {
        LocalDate friday = LocalDate.of(2024, 1, 5);
        LocalDate saturday = LocalDate.of(2024, 1, 6);
        LocalDate sunday = LocalDate.of(2024, 1, 7);
        LocalDate monday = LocalDate.of(2024, 1, 8);
        System.out.println("CAL_IS_BIZ_2024-01-05=" + calendar.isBusinessDay(CALENDAR_CODE, friday));
        System.out.println("CAL_IS_BIZ_2024-01-06=" + calendar.isBusinessDay(CALENDAR_CODE, saturday));
        System.out.println("CAL_IS_BIZ_2024-01-07=" + calendar.isBusinessDay(CALENDAR_CODE, sunday));
        System.out.println("CAL_IS_BIZ_2024-01-08=" + calendar.isBusinessDay(CALENDAR_CODE, monday));
        System.out.println("CAL_ADD_BIZ_2024-01-05_PLUS_1=" + calendar.addBusinessDays(CALENDAR_CODE, friday, 1));
        System.out.println("CAL_ADD_BIZ_2024-01-08_MINUS_1=" + calendar.getBusinessDay(CALENDAR_CODE, monday, "P", 1));
    }

    private static MarketSeriesInfo loadOneYearMarketSeries() throws Exception {
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD)) {
            String selectSql = ""
                    + "select riskfactor_type, riskfactor_id, min(data_date) as min_date, max(data_date) as max_date, count(distinct data_date) as date_count "
                    + "from MR_RISKFACTOR_DATA "
                    + "group by riskfactor_type, riskfactor_id "
                    + "having count(distinct data_date) >= 240 "
                    + "order by date_count desc";
            try (PreparedStatement ps = connection.prepareStatement(selectSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String curveType = rs.getString("riskfactor_type");
                    String curveCode = rs.getString("riskfactor_id");
                    LocalDate minDate = toLocalDate(rs.getObject("min_date"));
                    LocalDate maxDate = toLocalDate(rs.getObject("max_date"));
                    if (curveType == null || curveCode == null || minDate == null || maxDate == null) {
                        continue;
                    }
                    LocalDate oneYearStart = maxDate.minusYears(1).plusDays(1);
                    long count = countDates(connection, curveType, curveCode, oneYearStart, maxDate);
                    if (count >= 200) {
                        MarketSeriesInfo info = new MarketSeriesInfo();
                        info.curveType = curveType;
                        info.curveCode = curveCode;
                        info.startDate = oneYearStart;
                        info.endDate = maxDate;
                        info.distinctDates = count;
                        return info;
                    }
                }
            }
        }
        throw new IllegalStateException("未找到覆盖一年且日期数量足够的市场数据序列");
    }

    private static long countDates(Connection connection, String curveType, String curveCode, LocalDate startDate, LocalDate endDate) throws Exception {
        String countSql = ""
                + "select count(distinct data_date) as cnt "
                + "from MR_RISKFACTOR_DATA "
                + "where riskfactor_type = ? and riskfactor_id = ? and data_date between ? and ?";
        try (PreparedStatement ps = connection.prepareStatement(countSql)) {
            ps.setString(1, curveType);
            ps.setString(2, curveCode);
            ps.setObject(3, startDate);
            ps.setObject(4, endDate);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("cnt");
                }
            }
        }
        return 0L;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static ScenarioDefinition buildVarDefinition(MarketSeriesInfo info) {
        ScenarioDefinition definition = baseDefinition(info, "VAR", "TEST_VAR");
        definition.setScenarioNo(3);
        definition.setJumpDayNo(2);
        definition.setIncreaseDays(5);
        return definition;
    }

    private static ScenarioDefinition buildMcDefinition(MarketSeriesInfo info) {
        ScenarioDefinition definition = baseDefinition(info, "MC", "TEST_MC");
        definition.setScenarioNo(3);
        definition.setJumpDayNo(2);
        definition.setIncreaseDays(5);
        return definition;
    }

    private static ScenarioDefinition buildBacktestDefinition(MarketSeriesInfo info) {
        ScenarioDefinition definition = baseDefinition(info, "BACKTEST", "TEST_BACKTEST");
        return definition;
    }

    private static ScenarioDefinition buildHistoryDefinition(MarketSeriesInfo info) {
        ScenarioDefinition definition = baseDefinition(info, "HISTORY", "TEST_HISTORY");
        definition.setStartDate(info.startDate.plusDays(10));
        definition.setEndDate(info.startDate.plusDays(40));
        definition.setJumpDayNo(2);
        definition.setIncreaseDays(5);
        return definition;
    }

    private static ScenarioDefinition buildSvarDefinition(MarketSeriesInfo info) {
        ScenarioDefinition definition = baseDefinition(info, "SVAR", "TEST_SVAR");
        definition.setStartDate(info.startDate.plusDays(10));
        definition.setScenarioNo(4);
        definition.setJumpDayNo(2);
        definition.setIncreaseDays(5);
        return definition;
    }

    private static ScenarioDefinition baseDefinition(MarketSeriesInfo info, String scenarioType, String scenarioId) {
        ScenarioDefinition definition = new ScenarioDefinition();
        definition.setScenarioId(scenarioId);
        definition.setScenarioCode(scenarioId);
        definition.setScenarioName(scenarioId);
        definition.setScenarioType(scenarioType);
        definition.setCurveType(info.curveType);
        definition.setCurveCode(info.curveCode);
        definition.setHolidayCalendarCode(CALENDAR_CODE);
        return definition;
    }

    private static void printRange(String label, ScenarioRangeResolver.ResolvedRange range) {
        if (range == null) {
            System.out.println(label + "_RANGE=NULL");
            return;
        }
        System.out.println(label + "_SEARCH=" + range.getDataSearchStartDate() + "->" + range.getDataSearchEndDate());
        System.out.println(label + "_CALC=" + range.getCalculationStartDate() + "->" + range.getCalculationEndDate());
        System.out.println(label + "_JUMP=" + range.getJumpDayNo() + ",INCREASE=" + range.getIncreaseDays());
        printDates(label + "_SAMPLES", range.getSampleDates(), 8);
        printDates(label + "_COMPARE", range.getComparisonDates(), 8);
        printDates(label + "_SEARCH_DATES", range.getDataSearchDates(), 8);
    }

    private static void printDates(String label, List<LocalDate> dates, int maxSize) {
        if (dates == null) {
            System.out.println(label + "=NULL");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("_COUNT=").append(dates.size()).append(",VALUES=");
        int limit = Math.min(dates.size(), maxSize);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("|");
            }
            sb.append(dates.get(i));
        }
        if (dates.size() > limit) {
            sb.append("|...");
        }
        System.out.println(sb);
    }

    private static void printCompleterChecks() {
        HistoryDataCompleter completer = new HistoryDataCompleter();
        List<LocalDate> dates = Arrays.asList(
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 4));

        List<ScenarioMarketSeries> nowData = Arrays.asList(
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "1Y", 365, null, LocalDate.of(2024, 1, 4), "0.0200"),
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "2Y", 730, null, LocalDate.of(2024, 1, 4), "0.0300"));

        List<ScenarioMarketSeries> missingMiddleDayHistory = Arrays.asList(
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "1Y", 365, null, LocalDate.of(2024, 1, 2), "0.0100"),
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "2Y", 730, null, LocalDate.of(2024, 1, 2), "0.0200"),
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "1Y", 365, null, LocalDate.of(2024, 1, 4), "0.0110"),
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "2Y", 730, null, LocalDate.of(2024, 1, 4), "0.0210"));
        List<ScenarioMarketSeries> completedPrev = completer.complete(nowData, missingMiddleDayHistory, dates);
        System.out.println("COMPLETER_PREV_DAY=" + collectDayValues(completedPrev, LocalDate.of(2024, 1, 3)));

        List<ScenarioMarketSeries> missingFirstDayHistory = Arrays.asList(
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "1Y", 365, null, LocalDate.of(2024, 1, 3), "0.0120"),
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "2Y", 730, null, LocalDate.of(2024, 1, 3), "0.0220"),
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "1Y", 365, null, LocalDate.of(2024, 1, 4), "0.0130"),
                buildCompleterPoint("IR_SPOT", "IR_CURVE_TEST", "2Y", 730, null, LocalDate.of(2024, 1, 4), "0.0230"));
        List<ScenarioMarketSeries> completedNext = completer.complete(nowData, missingFirstDayHistory, dates);
        System.out.println("COMPLETER_NEXT_DAY=" + collectDayValues(completedNext, LocalDate.of(2024, 1, 2)));
    }

    private static String collectDayValues(List<ScenarioMarketSeries> series, LocalDate targetDate) {
        Map<String, BigDecimal> values = new LinkedHashMap<String, BigDecimal>();
        for (ScenarioMarketSeries point : series) {
            if (targetDate.equals(point.getDataDate())) {
                values.put(point.getTermCode(), point.getValue());
            }
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("|"));
    }

    private static ScenarioMarketSeries buildCompleterPoint(
            String curveType,
            String curveCode,
            String termCode,
            int termDays,
            String dimension2,
            LocalDate dataDate,
            String value) {
        ScenarioMarketSeries point = new ScenarioMarketSeries();
        point.setCurveType(curveType);
        point.setCurveCode(curveCode);
        point.setTermCode(termCode);
        point.setTermDays(termDays);
        point.setDimension2(dimension2);
        point.setDataDate(dataDate);
        point.setValue(new BigDecimal(value));
        return point;
    }

    private static class MarketSeriesInfo {
        private String curveType;
        private String curveCode;
        private LocalDate startDate;
        private LocalDate endDate;
        private long distinctDates;
    }
}
