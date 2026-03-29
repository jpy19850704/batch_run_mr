import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.scenario.ScenarioRangeResolver;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.scenario.riskfactor.RiskFactorProcessor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 基于 H2 的情景试算程序。
 */
public class TmpScenarioH2Trial {

    private static final String H2_URL = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String H2_USER = "sa";
    private static final String H2_PASSWORD = "";
    private static final String CALENDAR_PATH = "E:\\zcyh_mr\\engine\\calendar_data";
    private static final String CALENDAR_CODE = "DEFAULT_WEEKEND";
    private static final LocalDate VALUATION_DATE = LocalDate.of(2025, 12, 31);
    private static final String USER = "codex";
    private static final String SOURCE = "tmp-h2-trial";
    private static final List<String> SUPPORTED_TYPES = Arrays.asList(
            "IR_SPOT", "FX_SPOT", "COMM_SPOT", "EQ_SPOT", "FX_VOL", "IR_VOL", "COMM_VOL", "EQ_VOL");
    private static final List<String> SCENARIO_IDS = Arrays.asList(
            "T_VAR_REL_J1",
            "T_VAR_REL_J10",
            "T_SVAR_REL_J1",
            "T_SVAR_REL_J10",
            "T_HIS_REL_J1",
            "T_HIS_REL_J10",
            "T_BACKTEST_REL",
            "T_CUSTOM_REL_60_180");

    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");

        Calendar holidayCalendar = new Calendar();
        holidayCalendar.loadFromPath(CALENDAR_PATH);

        List<ActiveCurve> activeCurves = loadActiveCurves();
        printActiveCurveSummary(activeCurves);

        rebuildTrialScenarioRules(activeCurves);

        Map<String, List<ScenarioDefinition>> definitionsByScenario = loadTrialDefinitions();
        List<ScenarioTaskRequest> tasks = buildTasks(definitionsByScenario, holidayCalendar);

        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList(String.join(",", SCENARIO_IDS));
        request.setValuationDate(VALUATION_DATE);
        request.setUser(USER);
        request.setSource(SOURCE);
        request.setTasks(tasks);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            ScenarioGenerationEngine engine = new ScenarioGenerationEngine(executor, holidayCalendar);
            List<ScenarioGeneratedRecord> records = engine.generate(request);
            printTrialSummary(tasks, records);
        } finally {
            executor.shutdown();
        }
    }

    private static List<ActiveCurve> loadActiveCurves() throws Exception {
        List<ActiveCurve> result = new ArrayList<ActiveCurve>();
        try (Connection connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD)) {
            String sql = "select distinct CURVE_TYPE, CURVE_CODE "
                    + "from MR_RISKFACTOR_DATA "
                    + "where DATA_DATE = ? and CURVE_TYPE in (?,?,?,?,?,?,?,?) "
                    + "order by CURVE_TYPE, CURVE_CODE";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, VALUATION_DATE);
                for (int i = 0; i < SUPPORTED_TYPES.size(); i++) {
                    ps.setString(i + 2, SUPPORTED_TYPES.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ActiveCurve curve = new ActiveCurve();
                        curve.curveType = rs.getString("CURVE_TYPE");
                        curve.curveCode = rs.getString("CURVE_CODE");
                        result.add(curve);
                    }
                }
            }
        }
        return result;
    }

    private static void printActiveCurveSummary(List<ActiveCurve> activeCurves) {
        Map<String, Long> byType = activeCurves.stream()
                .collect(Collectors.groupingBy(curve -> curve.curveType, LinkedHashMap::new, Collectors.counting()));
        System.out.println("TRIAL_VALUATION_DATE=" + VALUATION_DATE);
        System.out.println("TRIAL_ACTIVE_CURVE_COUNT=" + activeCurves.size());
        for (Map.Entry<String, Long> entry : byType.entrySet()) {
            System.out.println("TRIAL_ACTIVE_TYPE=" + entry.getKey() + "|CURVE_COUNT=" + entry.getValue());
        }
    }

    private static void rebuildTrialScenarioRules(List<ActiveCurve> activeCurves) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                deleteExistingTrialRules(connection);
                insertHistoricalScenario(connection, activeCurves, "T_VAR_REL_J1", "VAR", 50, 1, 1, null, null);
                insertHistoricalScenario(connection, activeCurves, "T_VAR_REL_J10", "VAR", 50, 10, 1, null, null);
                insertHistoricalScenario(connection, activeCurves, "T_SVAR_REL_J1", "SVAR", 50, 1, 1, LocalDate.of(2025, 1, 1), null);
                insertHistoricalScenario(connection, activeCurves, "T_SVAR_REL_J10", "SVAR", 50, 10, 1, LocalDate.of(2025, 1, 1), null);
                insertHistoricalScenario(connection, activeCurves, "T_HIS_REL_J1", "HISTORY", null, 1, 1, LocalDate.of(2025, 4, 10), LocalDate.of(2025, 5, 10));
                insertHistoricalScenario(connection, activeCurves, "T_HIS_REL_J10", "HISTORY", null, 10, 1, LocalDate.of(2025, 4, 10), LocalDate.of(2025, 5, 10));
                insertHistoricalScenario(connection, activeCurves, "T_BACKTEST_REL", "BACKTEST", 1, 1, 1, null, null);
                insertCustomScenario(connection, activeCurves);
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    private static void deleteExistingTrialRules(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("delete from MR_SCENARIO_RULE where SCENARIO_ID like 'T_%'")) {
            int deleted = ps.executeUpdate();
            System.out.println("TRIAL_RULE_DELETE_COUNT=" + deleted);
        }
    }

    private static void insertHistoricalScenario(
            Connection connection,
            List<ActiveCurve> activeCurves,
            String scenarioId,
            String scenarioType,
            Integer scenarioNo,
            Integer jumpDayNo,
            Integer increaseDays,
            LocalDate startDate,
            LocalDate endDate) throws Exception {
        String sql = "insert into MR_SCENARIO_RULE ("
                + "SCENARIO_ID, LINE_NO, SCENARIO_NAME, SCENARIO_TYPE, CURVE_TYPE, CURVE_CODE, "
                + "TERM_CODE, TERM_DAYS, SCENARIO_NO, INCREASE_DAYS, JUNP_DAY_NO, "
                + "CAL_START_DATE, CAL_END_DATE, START_DATE, HOLIDAY_CALENDAR, SCENARIO_SHIFT_VALUE, "
                + "SCENARIO_SHIFT_RULE, STATUS, REMARK, MODIFIER, CREATED_AT, UPDATED_AT"
                + ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int lineNo = 1;
            for (ActiveCurve curve : activeCurves) {
                ps.setString(1, scenarioId);
                ps.setInt(2, lineNo++);
                ps.setString(3, scenarioId);
                ps.setString(4, scenarioType);
                ps.setString(5, curve.curveType);
                ps.setString(6, curve.curveCode);
                ps.setString(7, null);
                ps.setObject(8, null);
                ps.setObject(9, scenarioNo);
                ps.setObject(10, increaseDays);
                ps.setObject(11, jumpDayNo);
                ps.setObject(12, startDate);
                ps.setObject(13, endDate);
                ps.setObject(14, startDate);
                ps.setString(15, CALENDAR_CODE);
                ps.setObject(16, null);
                ps.setString(17, "RELATIVE");
                ps.setString(18, "ACTIVE");
                ps.setString(19, "CODEx H2 trial");
                ps.setString(20, USER);
                ps.setLong(21, now);
                ps.setLong(22, now);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            System.out.println("TRIAL_INSERT=" + scenarioId + "|ROWS=" + counts.length);
        }
    }

    private static void insertCustomScenario(Connection connection, List<ActiveCurve> activeCurves) throws Exception {
        String sql = "insert into MR_SCENARIO_RULE ("
                + "SCENARIO_ID, LINE_NO, SCENARIO_NAME, SCENARIO_TYPE, CURVE_TYPE, CURVE_CODE, "
                + "TERM_CODE, TERM_DAYS, SCENARIO_NO, INCREASE_DAYS, JUNP_DAY_NO, "
                + "CAL_START_DATE, CAL_END_DATE, START_DATE, HOLIDAY_CALENDAR, SCENARIO_SHIFT_VALUE, "
                + "SCENARIO_SHIFT_RULE, STATUS, REMARK, MODIFIER, CREATED_AT, UPDATED_AT"
                + ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        long now = System.currentTimeMillis();
        List<Integer> termDaysList = Arrays.asList(60, 180);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int lineNo = 1;
            for (ActiveCurve curve : activeCurves) {
                for (Integer termDays : termDaysList) {
                    ps.setString(1, "T_CUSTOM_REL_60_180");
                    ps.setInt(2, lineNo++);
                    ps.setString(3, "T_CUSTOM_REL_60_180");
                    ps.setString(4, "CUSTOM");
                    ps.setString(5, curve.curveType);
                    ps.setString(6, curve.curveCode);
                    ps.setString(7, null);
                    ps.setInt(8, termDays);
                    ps.setObject(9, null);
                    ps.setObject(10, null);
                    ps.setObject(11, null);
                    ps.setObject(12, null);
                    ps.setObject(13, null);
                    ps.setObject(14, null);
                    ps.setString(15, null);
                    ps.setBigDecimal(16, new BigDecimal("0.1"));
                    ps.setString(17, "RELATIVE");
                    ps.setString(18, "ACTIVE");
                    ps.setString(19, "CODEx H2 trial");
                    ps.setString(20, USER);
                    ps.setLong(21, now);
                    ps.setLong(22, now);
                    ps.addBatch();
                }
            }
            int[] counts = ps.executeBatch();
            System.out.println("TRIAL_INSERT=T_CUSTOM_REL_60_180|ROWS=" + counts.length);
        }
    }

    private static Map<String, List<ScenarioDefinition>> loadTrialDefinitions() throws Exception {
        Map<String, List<ScenarioDefinition>> result = new LinkedHashMap<String, List<ScenarioDefinition>>();
        try (Connection connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD)) {
            String sql = "select SCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, CURVE_TYPE, CURVE_CODE, TERM_CODE, TERM_DAYS, "
                    + "SCENARIO_NO, INCREASE_DAYS, JUNP_DAY_NO, CAL_START_DATE, CAL_END_DATE, START_DATE, "
                    + "HOLIDAY_CALENDAR, SCENARIO_SHIFT_VALUE, SCENARIO_SHIFT_RULE "
                    + "from MR_SCENARIO_RULE where SCENARIO_ID like 'T_%' and STATUS = 'ACTIVE' "
                    + "order by SCENARIO_ID, LINE_NO";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ScenarioDefinition definition = new ScenarioDefinition();
                    definition.setScenarioId(rs.getString("SCENARIO_ID"));
                    definition.setScenarioCode(rs.getString("SCENARIO_ID"));
                    definition.setScenarioName(rs.getString("SCENARIO_NAME"));
                    definition.setScenarioType(rs.getString("SCENARIO_TYPE"));
                    definition.setCurveType(rs.getString("CURVE_TYPE"));
                    definition.setCurveCode(rs.getString("CURVE_CODE"));
                    definition.setTermCode(rs.getString("TERM_CODE"));
                    definition.setTermDays(toInteger(rs.getObject("TERM_DAYS")));
                    definition.setScenarioNo(toInteger(rs.getObject("SCENARIO_NO")));
                    definition.setIncreaseDays(toInteger(rs.getObject("INCREASE_DAYS")));
                    definition.setJumpDayNo(toInteger(rs.getObject("JUNP_DAY_NO")));
                definition.setStartDate(toLocalDate(firstNonNull(rs.getObject("START_DATE"), rs.getObject("CAL_START_DATE"))));
                    definition.setEndDate(toLocalDate(rs.getObject("CAL_END_DATE")));
                    definition.setHolidayCalendarCode(rs.getString("HOLIDAY_CALENDAR"));
                    definition.setShockValue(rs.getBigDecimal("SCENARIO_SHIFT_VALUE"));
                definition.setScenarioShiftRule(rs.getString("SCENARIO_SHIFT_RULE"));
                    result.computeIfAbsent(definition.getScenarioId(), key -> new ArrayList<ScenarioDefinition>()).add(definition);
                }
            }
        }
        for (String scenarioId : SCENARIO_IDS) {
            result.computeIfAbsent(scenarioId, key -> new ArrayList<ScenarioDefinition>());
        }
        return result;
    }

    private static List<ScenarioTaskRequest> buildTasks(
            Map<String, List<ScenarioDefinition>> definitionsByScenario,
            Calendar holidayCalendar) throws Exception {
        List<ScenarioTaskRequest> tasks = new ArrayList<ScenarioTaskRequest>();
        ScenarioRangeResolver resolver = new ScenarioRangeResolver(holidayCalendar);
        try (Connection connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD)) {
            for (String scenarioId : SCENARIO_IDS) {
                List<ScenarioDefinition> definitions = definitionsByScenario.getOrDefault(scenarioId, Collections.<ScenarioDefinition>emptyList());
                if (definitions.isEmpty()) {
                    continue;
                }
                ScenarioTaskRequest task = new ScenarioTaskRequest();
                task.setScenarioId(scenarioId);
                task.setScenarioType(definitions.get(0).getScenarioType());
                task.setValuationDate(VALUATION_DATE);
                task.setDefinitions(definitions);
                task.setCurrentMarketData(loadCurrentMarketData(connection, definitions));
                if (resolver.requiresHistoryData(task.getScenarioType())) {
                    ScenarioRangeResolver.ResolvedRange range = resolver.resolve(definitions.get(0), VALUATION_DATE);
                    if (range != null) {
                        task.setHistoricalMarketData(loadHistoricalMarketData(connection, definitions, range));
                        System.out.println("TRIAL_RANGE=" + scenarioId + "|SEARCH="
                                + range.getDataSearchStartDate() + "->" + range.getDataSearchEndDate()
                                + "|CALC=" + range.getCalculationStartDate() + "->" + range.getCalculationEndDate()
                                + "|SAMPLES=" + range.getSampleDates().size());
                    }
                }
                printTaskInputSummary(task);
                tasks.add(task);
            }
        }
        return tasks;
    }

    private static void printTaskInputSummary(ScenarioTaskRequest task) {
        int currentCount = task.getCurrentMarketData().values().stream().mapToInt(List::size).sum();
        int historyCount = task.getHistoricalMarketData().values().stream()
                .mapToInt(map -> map.values().stream().mapToInt(List::size).sum())
                .sum();
        long historyDates = task.getHistoricalMarketData().values().stream()
                .mapToLong(Map::size)
                .sum();
        System.out.println("TRIAL_TASK=" + task.getScenarioId()
                + "|TYPE=" + task.getScenarioType()
                + "|DEF_ROWS=" + task.getDefinitions().size()
                + "|CURR_ROWS=" + currentCount
                + "|HIST_ROWS=" + historyCount
                + "|HIST_DATES=" + historyDates);
    }

    private static Map<String, List<ScenarioMarketSeries>> loadCurrentMarketData(
            Connection connection,
            List<ScenarioDefinition> definitions) throws Exception {
        Map<String, List<ScenarioMarketSeries>> result = new LinkedHashMap<String, List<ScenarioMarketSeries>>();
        Map<String, Set<String>> codesByType = buildCodesByType(definitions);
        for (Map.Entry<String, Set<String>> entry : codesByType.entrySet()) {
            List<ScenarioMarketSeries> series = queryMarketSeries(connection, entry.getKey(), entry.getValue(), VALUATION_DATE, VALUATION_DATE);
            result.put(entry.getKey(), series);
        }
        return result;
    }

    private static Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> loadHistoricalMarketData(
            Connection connection,
            List<ScenarioDefinition> definitions,
            ScenarioRangeResolver.ResolvedRange range) throws Exception {
        Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result =
                new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>();
        Map<String, Set<String>> codesByType = buildCodesByType(definitions);
        for (Map.Entry<String, Set<String>> entry : codesByType.entrySet()) {
            List<ScenarioMarketSeries> allSeries = queryMarketSeries(
                    connection,
                    entry.getKey(),
                    entry.getValue(),
                    range.getDataSearchStartDate(),
                    range.getDataSearchEndDate());
            Map<LocalDate, List<ScenarioMarketSeries>> byDate = new LinkedHashMap<LocalDate, List<ScenarioMarketSeries>>();
            for (ScenarioMarketSeries series : allSeries) {
                byDate.computeIfAbsent(series.getDataDate(), key -> new ArrayList<ScenarioMarketSeries>()).add(series);
            }
            result.put(entry.getKey(), byDate);
        }
        return result;
    }

    private static Map<String, Set<String>> buildCodesByType(List<ScenarioDefinition> definitions) {
        Map<String, Set<String>> result = new LinkedHashMap<String, Set<String>>();
        for (ScenarioDefinition definition : definitions) {
            if (definition.getCurveType() == null || definition.getCurveCode() == null) {
                continue;
            }
            result.computeIfAbsent(definition.getCurveType(), key -> new LinkedHashSet<String>()).add(definition.getCurveCode());
        }
        return result;
    }

    private static List<ScenarioMarketSeries> queryMarketSeries(
            Connection connection,
            String curveType,
            Collection<String> curveCodes,
            LocalDate startDate,
            LocalDate endDate) throws Exception {
        if (curveCodes == null || curveCodes.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = curveCodes.stream().map(code -> "?").collect(Collectors.joining(","));
        String sql = "select CURVE_TYPE, CURVE_CODE, DATA_DATE, TERM_CODE, TERM_DAYS, \"value\" as MARKET_VALUE "
                + "from MR_RISKFACTOR_DATA where DATA_DATE between ? and ? and CURVE_TYPE = ? and CURVE_CODE in (" + placeholders + ") "
                + "order by DATA_DATE, CURVE_TYPE, CURVE_CODE, TERM_DAYS, TERM_CODE";
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            ps.setObject(index++, startDate);
            ps.setObject(index++, endDate);
            ps.setString(index++, curveType);
            for (String curveCode : curveCodes) {
                ps.setString(index++, curveCode);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ScenarioMarketSeries series = new ScenarioMarketSeries();
                    series.setCurveType(rs.getString("CURVE_TYPE"));
                    series.setCurveCode(rs.getString("CURVE_CODE"));
                    series.setDataDate(toLocalDate(rs.getObject("DATA_DATE")));
                    series.setTermCode(rs.getString("TERM_CODE"));
                    series.setTermDays(toInteger(rs.getObject("TERM_DAYS")));
                    series.setDimension2(null);
                    series.setValue(rs.getBigDecimal("MARKET_VALUE"));
                    result.add(series);
                }
            }
        }
        return result;
    }

    private static void printTrialSummary(List<ScenarioTaskRequest> tasks, List<ScenarioGeneratedRecord> records) {
        Map<String, String> scenarioTypeById = tasks.stream()
                .collect(Collectors.toMap(ScenarioTaskRequest::getScenarioId, ScenarioTaskRequest::getScenarioType, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<ScenarioGeneratedRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(ScenarioGeneratedRecord::getScenarioId, LinkedHashMap::new, Collectors.toList()));
        for (String scenarioId : SCENARIO_IDS) {
            List<ScenarioGeneratedRecord> scenarioRecords = grouped.getOrDefault(scenarioId, Collections.<ScenarioGeneratedRecord>emptyList());
            long subScenarioCount = scenarioRecords.stream()
                    .map(ScenarioGeneratedRecord::getSubScenarioId)
                    .filter(value -> value != null && !value.trim().isEmpty())
                    .distinct()
                    .count();
            System.out.println("TRIAL_RESULT=" + scenarioId
                    + "|TYPE=" + scenarioTypeById.get(scenarioId)
                    + "|RECORDS=" + scenarioRecords.size()
                    + "|SUBS=" + subScenarioCount);
            scenarioRecords.stream()
                    .sorted(Comparator.comparing(ScenarioGeneratedRecord::getCurveType, Comparator.nullsLast(String::compareTo))
                            .thenComparing(ScenarioGeneratedRecord::getCurveCode, Comparator.nullsLast(String::compareTo))
                            .thenComparing(ScenarioGeneratedRecord::getTermDays, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(ScenarioGeneratedRecord::getSubScenarioId, Comparator.nullsLast(String::compareTo)))
                    .limit(5)
                    .forEach(record -> System.out.println("TRIAL_SAMPLE=" + scenarioId
                            + "|SUB=" + safeString(record.getSubScenarioId())
                            + "|CURVE_TYPE=" + safeString(record.getCurveType())
                            + "|CURVE_CODE=" + safeString(record.getCurveCode())
                            + "|TERM=" + safeString(record.getTermCode())
                            + "|ORI=" + record.getOriginalValue()
                            + "|NEW=" + record.getChangedValue()
                            + "|SHIFT=" + record.getShiftValue()
                            + "|RULE=" + safeString(record.getShiftRule())));
        }
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static class ActiveCurve {
        private String curveType;
        private String curveCode;
    }
}
