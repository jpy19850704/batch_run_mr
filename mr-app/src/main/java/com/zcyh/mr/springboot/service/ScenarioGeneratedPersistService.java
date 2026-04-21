package com.zcyh.mr.springboot.service;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 情景生成结果落库服务。
 * 只按批次维度覆盖写入，不和任务分片绑定。
 */
@Service
public class ScenarioGeneratedPersistService {
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private final JdbcTemplate jdbcTemplate;

    public ScenarioGeneratedPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按批次覆盖写入情景结果。
     * persistScenario 为空/false 或 batchId 为空时直接跳过。
     */
    public void persist(String batchId, String dataDate, Boolean persistScenario, List<ScenarioGeneratedRecord> records) {
        if (!Boolean.TRUE.equals(persistScenario)) {
            return;
        }
        String safeBatchId = trimToNull(batchId);
        if (safeBatchId == null || records == null || records.isEmpty()) {
            return;
        }

        String now = ResultPersistTime.nowText();
        jdbcTemplate.update("DELETE FROM TB_OUT_SCENARIO_FILE_DETAIL WHERE BATCH_ID=?", safeBatchId);

        String sql = "INSERT INTO TB_OUT_SCENARIO_FILE_DETAIL ("
                + "BATCH_ID, DATA_DATE, SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, "
                + "RISKFACTOR_TYPE, RISKFACTOR_ID, RISKFACTOR_VERTEX1, TERM_DAYS, RISKFACTOR_VERTEX2, "
                + "CHANGE_VALUE, RISKFACTOR_TERM, ORI_VALUE, SCENARIO_RESULT, SHIFT_RULE, MODIFIER, CREATED_AT, UPDATED_AT"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        List<Object[]> batchArgs = new ArrayList<Object[]>(records.size());
        for (ScenarioGeneratedRecord record : records) {
            if (record == null) {
                continue;
            }
            String rowDataDate = normalizeDataDate(record.getDataDate(), dataDate);
            List<Object> values = new ArrayList<Object>();
            values.add(safeBatchId);
            values.add(rowDataDate);
            values.add(trimToNull(record.getScenarioId()));
            values.add(trimToNull(record.getSubScenarioId()));
            values.add(trimToNull(record.getScenarioName()));
            values.add(trimToNull(record.getScenarioType()));
            values.add(trimToNull(record.getCurveType()));
            values.add(trimToNull(record.getCurveCode()));
            values.add(trimToNull(record.getTermCode()));
            values.add(record.getTermDays());
            values.add(trimToNull(record.getDimension2()));
            values.add(toBigDecimal(record.getShiftValue()));
            values.add(trimToNull(resolveRiskFactorTerm(record)));
            values.add(toBigDecimal(record.getOriginalValue()));
            values.add(toBigDecimal(record.getChangedValue()));
            values.add(trimToNull(record.getShiftRule()));
            values.add(trimToNull(record.getModifier()));
            values.add(now);
            values.add(now);
            batchArgs.add(values.toArray());
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batchArgs);
        }
    }

    private static String resolveRiskFactorTerm(ScenarioGeneratedRecord record) {
        if (record == null) {
            return null;
        }
        String termCode = trimToNull(record.getTermCode());
        if (termCode != null) {
            return termCode;
        }
        Integer termDays = record.getTermDays();
        return termDays == null ? null : String.valueOf(termDays);
    }

    private static String normalizeDataDate(LocalDate localDate, String fallback) {
        if (localDate != null) {
            return localDate.format(DATE_8_FORMATTER);
        }
        return trimToNull(fallback);
    }

    private static BigDecimal toBigDecimal(BigDecimal value) {
        return value;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
