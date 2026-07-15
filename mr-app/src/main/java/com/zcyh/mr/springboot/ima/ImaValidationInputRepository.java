package com.zcyh.mr.springboot.ima;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * IMA 校验输入仓储。
 */
@Repository
public class ImaValidationInputRepository {
    public static final int REQUIRED_OBSERVATION_COUNT = 250;

    private static final String RISK_CLASS_ALL = "ALL";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbcTemplate;

    public ImaValidationInputRepository(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> queryObservationDates(String dataDate, String ruleId) {
        String sql = "SELECT DATA_DATE FROM ("
                + "SELECT DISTINCT DATA_DATE FROM TB_EXTERNAL_IMA_GROUP_PNL "
                + "WHERE RULE_ID=? AND DATA_DATE<=? "
                + "ORDER BY DATA_DATE DESC LIMIT " + REQUIRED_OBSERVATION_COUNT
                + ") t ORDER BY DATA_DATE";
        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, ruleId);
                    ps.setString(2, dataDate);
                },
                (rs, rowNum) -> normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE"));
    }

    public Map<GroupKey, List<ExternalPnlRow>> queryExternalPnl(
            String startDate,
            String endDate,
            String ruleId) {
        String sql = "SELECT DATA_DATE, RULE_ID, GROUP_TYPE, GROUP_VALUE, ACTUAL_PNL, "
                + "HYPOTHETICAL_PNL, RISK_THEORETICAL_PNL, VALUATION_CCY "
                + "FROM TB_EXTERNAL_IMA_GROUP_PNL "
                + "WHERE RULE_ID=? AND DATA_DATE BETWEEN ? AND ? "
                + "ORDER BY GROUP_TYPE, GROUP_VALUE, DATA_DATE";
        List<ExternalPnlRow> rows = jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, ruleId);
                    ps.setString(2, startDate);
                    ps.setString(3, endDate);
                },
                (rs, rowNum) -> {
                    ExternalPnlRow row = new ExternalPnlRow();
                    row.dataDateText = normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE");
                    row.dataDate = parseDate(row.dataDateText, "DATA_DATE");
                    row.ruleId = requireText(rs.getString("RULE_ID"), "RULE_ID");
                    row.groupType = requireText(rs.getString("GROUP_TYPE"), "GROUP_TYPE");
                    row.groupValue = requireText(rs.getString("GROUP_VALUE"), "GROUP_VALUE");
                    row.actualPnl = requireDecimal(rs.getBigDecimal("ACTUAL_PNL"), "ACTUAL_PNL", row);
                    row.hypotheticalPnl = requireDecimal(
                            rs.getBigDecimal("HYPOTHETICAL_PNL"), "HYPOTHETICAL_PNL", row);
                    row.riskTheoreticalPnl = requireDecimal(
                            rs.getBigDecimal("RISK_THEORETICAL_PNL"), "RISK_THEORETICAL_PNL", row);
                    row.valuationCcy = trimToNull(rs.getString("VALUATION_CCY"));
                    return row;
                });

        Map<GroupKey, List<ExternalPnlRow>> result = new LinkedHashMap<GroupKey, List<ExternalPnlRow>>();
        for (ExternalPnlRow row : rows) {
            GroupKey key = new GroupKey(row.groupType, row.groupValue);
            result.computeIfAbsent(key, ignored -> new ArrayList<ExternalPnlRow>()).add(row);
        }
        return result;
    }

    public Map<GroupKey, TreeMap<LocalDate, BigDecimal>> queryVarRows(
            String batchId,
            String endDate,
            String ruleId,
            String quantile,
            String varScenarioId) {
        List<String> varDates = queryVarObservationDates(batchId, endDate, ruleId, quantile, varScenarioId);
        Map<GroupKey, TreeMap<LocalDate, BigDecimal>> result =
                new LinkedHashMap<GroupKey, TreeMap<LocalDate, BigDecimal>>();
        if (varDates.isEmpty()) {
            return result;
        }
        String sql = "SELECT DATA_DATE, GROUP_TYPE, GROUP_VALUE, VAR "
                + "FROM TB_OUT_VAR_RESULT "
                + "WHERE BATCH_ID=? AND RULE_ID=? AND QUANTILE=? AND SCENARIO_ID=? "
                + "AND RISK_CLASS=? AND DATA_DATE IN (" + placeholders(varDates.size()) + ") "
                + "ORDER BY GROUP_TYPE, GROUP_VALUE, DATA_DATE";
        List<Object> params = new ArrayList<Object>();
        params.add(batchId);
        params.add(ruleId);
        params.add(quantile);
        params.add(varScenarioId);
        params.add(RISK_CLASS_ALL);
        params.addAll(varDates);
        List<VarRow> rows = jdbcTemplate.query(
                sql,
                ps -> {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                },
                (rs, rowNum) -> {
                    VarRow row = new VarRow();
                    row.dataDateText = normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE");
                    row.dataDate = parseDate(row.dataDateText, "DATA_DATE");
                    row.groupType = requireText(rs.getString("GROUP_TYPE"), "GROUP_TYPE");
                    row.groupValue = requireText(rs.getString("GROUP_VALUE"), "GROUP_VALUE");
                    row.varValue = requireDecimal(rs.getBigDecimal("VAR"), "VAR", row);
                    return row;
                });

        for (VarRow row : rows) {
            GroupKey key = new GroupKey(row.groupType, row.groupValue);
            result.computeIfAbsent(key, ignored -> new TreeMap<LocalDate, BigDecimal>())
                    .put(row.dataDate, row.varValue);
        }
        return result;
    }

    public String minVarDate(Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup) {
        LocalDate min = null;
        for (TreeMap<LocalDate, BigDecimal> rows : varByGroup.values()) {
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            LocalDate first = rows.firstKey();
            if (min == null || first.isBefore(min)) {
                min = first;
            }
        }
        return min == null ? null : min.format(BASIC_DATE);
    }

    public String maxVarDate(Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup) {
        LocalDate max = null;
        for (TreeMap<LocalDate, BigDecimal> rows : varByGroup.values()) {
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            LocalDate last = rows.lastKey();
            if (max == null || last.isAfter(max)) {
                max = last;
            }
        }
        return max == null ? null : max.format(BASIC_DATE);
    }

    private List<String> queryVarObservationDates(
            String batchId,
            String endDate,
            String ruleId,
            String quantile,
            String varScenarioId) {
        String sql = "SELECT DATA_DATE FROM ("
                + "SELECT DISTINCT DATA_DATE FROM TB_OUT_VAR_RESULT "
                + "WHERE BATCH_ID=? AND RULE_ID=? AND QUANTILE=? AND SCENARIO_ID=? "
                + "AND RISK_CLASS=? AND DATA_DATE < ? "
                + "ORDER BY DATA_DATE DESC LIMIT " + REQUIRED_OBSERVATION_COUNT
                + ") t ORDER BY DATA_DATE";
        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, batchId);
                    ps.setString(2, ruleId);
                    ps.setString(3, quantile);
                    ps.setString(4, varScenarioId);
                    ps.setString(5, RISK_CLASS_ALL);
                    ps.setString(6, endDate);
                },
                (rs, rowNum) -> normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE"));
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("?");
        }
        return builder.toString();
    }

    private static BigDecimal requireDecimal(BigDecimal value, String fieldName, Object row) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空: " + row);
        }
        return value;
    }

    private static String normalizeDate(String text, String fieldName) {
        String value = requireText(text, fieldName).replace("-", "");
        parseDate(value, fieldName);
        return value;
    }

    private static LocalDate parseDate(String text, String fieldName) {
        try {
            return LocalDate.parse(text, BASIC_DATE);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " 必须为 yyyyMMdd 或 yyyy-MM-dd: " + text);
        }
    }

    private static String requireText(String text, String fieldName) {
        String value = trimToNull(text);
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    public static final class GroupKey {
        final String groupType;
        final String groupValue;

        public GroupKey(String groupType, String groupValue) {
            this.groupType = groupType;
            this.groupValue = groupValue;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof GroupKey)) {
                return false;
            }
            GroupKey other = (GroupKey) obj;
            return groupType.equals(other.groupType) && groupValue.equals(other.groupValue);
        }

        @Override
        public int hashCode() {
            return 31 * groupType.hashCode() + groupValue.hashCode();
        }
    }

    public static final class ExternalPnlRow {
        String dataDateText;
        LocalDate dataDate;
        String ruleId;
        String groupType;
        String groupValue;
        BigDecimal actualPnl;
        BigDecimal hypotheticalPnl;
        BigDecimal riskTheoreticalPnl;
        String valuationCcy;

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "DATA_DATE=%s,RULE_ID=%s,GROUP_TYPE=%s,GROUP_VALUE=%s",
                    dataDateText, ruleId, groupType, groupValue);
        }
    }

    private static final class VarRow {
        private String dataDateText;
        private LocalDate dataDate;
        private String groupType;
        private String groupValue;
        private BigDecimal varValue;

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "DATA_DATE=%s,GROUP_TYPE=%s,GROUP_VALUE=%s",
                    dataDateText, groupType, groupValue);
        }
    }
}
