package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.springboot.support.ResultDbDateSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final JdbcTemplate jdbcTemplate;

    public ImaValidationInputRepository(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<LocalDate> queryObservationDates(LocalDate dataDate, String ruleId) {
        String sql = "SELECT DATA_DATE FROM ("
                + "SELECT DISTINCT DATA_DATE FROM TB_EXTERNAL_IMA_GROUP_PNL "
                + "WHERE RULE_ID=? AND DATA_DATE<=? "
                + "ORDER BY DATA_DATE DESC LIMIT " + REQUIRED_OBSERVATION_COUNT
                + ") t ORDER BY DATA_DATE";
        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, ruleId);
                    ps.setDate(2, ResultDbDateSupport.sqlDate(dataDate));
                },
                (rs, rowNum) -> requireDate(rs.getDate("DATA_DATE"), "DATA_DATE"));
    }

    public Map<GroupKey, List<ExternalPnlRow>> queryExternalPnl(
            LocalDate startDate,
            LocalDate endDate,
            String ruleId) {
        String sql = "SELECT DATA_DATE, RULE_ID, GROUP_TYPE, GROUP_VALUE, ACTUAL_PNL, "
                + "HYPOTHETICAL_PNL, RISK_THEORETICAL_PNL, VALUATION_CCY "
                + "FROM TB_EXTERNAL_IMA_GROUP_PNL "
                + "WHERE RULE_ID=? AND DATA_DATE BETWEEN ? "
                + "AND ? "
                + "ORDER BY GROUP_TYPE, GROUP_VALUE, DATA_DATE";
        List<ExternalPnlRow> rows = jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, ruleId);
                    ps.setDate(2, ResultDbDateSupport.sqlDate(startDate));
                    ps.setDate(3, ResultDbDateSupport.sqlDate(endDate));
                },
                (rs, rowNum) -> {
                    ExternalPnlRow row = new ExternalPnlRow();
                    row.dataDate = requireDate(rs.getDate("DATA_DATE"), "DATA_DATE");
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
            LocalDate endDate,
            String ruleId,
            String quantile,
            String varScenarioId) {
        List<LocalDate> varDates = queryVarObservationDates(batchId, endDate, ruleId, quantile, varScenarioId);
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
        for (LocalDate varDate : varDates) {
            params.add(ResultDbDateSupport.sqlDate(varDate));
        }
        List<VarRow> rows = jdbcTemplate.query(
                sql,
                ps -> {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                },
                (rs, rowNum) -> {
                    VarRow row = new VarRow();
                    row.dataDate = requireDate(rs.getDate("DATA_DATE"), "DATA_DATE");
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

    public LocalDate minVarDate(Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup) {
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
        return min;
    }

    public LocalDate maxVarDate(Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup) {
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
        return max;
    }

    private List<LocalDate> queryVarObservationDates(
            String batchId,
            LocalDate endDate,
            String ruleId,
            String quantile,
            String varScenarioId) {
        String sql = "SELECT DATA_DATE FROM ("
                + "SELECT DISTINCT DATA_DATE FROM TB_OUT_VAR_RESULT "
                + "WHERE BATCH_ID=? AND RULE_ID=? AND QUANTILE=? AND SCENARIO_ID=? "
                + "AND RISK_CLASS=? AND DATA_DATE<? "
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
                    ps.setDate(6, ResultDbDateSupport.sqlDate(endDate));
                },
                (rs, rowNum) -> requireDate(rs.getDate("DATA_DATE"), "DATA_DATE"));
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

    private static LocalDate requireDate(java.sql.Date value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.toLocalDate();
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
                    dataDate, ruleId, groupType, groupValue);
        }
    }

    private static final class VarRow {
        private LocalDate dataDate;
        private String groupType;
        private String groupValue;
        private BigDecimal varValue;

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "DATA_DATE=%s,GROUP_TYPE=%s,GROUP_VALUE=%s",
                    dataDate, groupType, groupValue);
        }
    }
}
