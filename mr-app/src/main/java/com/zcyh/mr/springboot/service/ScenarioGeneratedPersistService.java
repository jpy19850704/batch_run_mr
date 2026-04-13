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

        long now = System.currentTimeMillis();
        jdbcTemplate.update("DELETE FROM TB_OUT_SCENARIO_FILE_DETAIL WHERE BATCH_ID=?", safeBatchId);

        String sql = "INSERT INTO TB_OUT_SCENARIO_FILE_DETAIL ("
                + "BATCH_ID, DATA_DATE, SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE, "
                + "RISKFACTOR_TYPE, RISKFACTOR_ID, RISKFACTOR_VERTEX1, RISKFACTOR_VERTEX2, "
                + "CHANGE_VALUE, RISKFACTOR_TERM, ORI_VALUE, SCENARIO_RESULT, MODIFIER, CREATED_AT, UPDATED_AT"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        List<Object[]> batchArgs = new ArrayList<Object[]>(records.size());
        for (ScenarioGeneratedRecord record : records) {
            if (record == null) {
                continue;
            }
            String rowDataDate = normalizeDataDate(record.getDataDate(), dataDate);
            batchArgs.add(new Object[]{
                    safeBatchId,
                    rowDataDate,
                    trimToNull(record.getScenarioId()),
                    trimToNull(record.getSubScenarioId()),
                    trimToNull(record.getScenarioName()),
                    trimToNull(record.getScenarioType()),
                    trimToNull(record.getCurveType()),
                    trimToNull(record.getCurveCode()),
                    trimToNull(record.getTermCode()),
                    trimToNull(record.getDimension2()),
                    toBigDecimal(record.getShiftValue()),
                    trimToNull(resolveRiskFactorTerm(record)),
                    toBigDecimal(record.getOriginalValue()),
                    toBigDecimal(record.getChangedValue()),
                    trimToNull(record.getModifier()),
                    now,
                    now
            });
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
