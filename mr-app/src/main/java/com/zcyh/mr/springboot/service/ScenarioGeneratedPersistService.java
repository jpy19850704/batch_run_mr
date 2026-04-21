package com.zcyh.mr.springboot.service;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 情景生成结果落库服务。
 * 只按批次维度覆盖写入，不和任务分片绑定。
 */
@Service
public class ScenarioGeneratedPersistService {
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TARGET_TABLE = "TB_OUT_SCENARIO_FILE_DETAIL";
    private static final String STREAM_LOAD_COLUMNS = "BATCH_ID,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,"
            + "RISKFACTOR_TYPE,RISKFACTOR_ID,RISKFACTOR_VERTEX1,TERM_DAYS,RISKFACTOR_VERTEX2,CHANGE_VALUE,"
            + "RISKFACTOR_TERM,ORI_VALUE,SCENARIO_RESULT,SHIFT_RULE,MODIFIER,CREATED_AT,UPDATED_AT";
    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final int batchSize;

    public ScenarioGeneratedPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                           DorisStreamLoadService dorisStreamLoadService,
                                           @Value("${mr.scenario.persist.batch-size:20000}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.batchSize = batchSize <= 0 ? 20000 : batchSize;
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

        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "scenario_" + safeBatchId,
                batchSize);
        for (ScenarioGeneratedRecord record : records) {
            if (record == null) {
                continue;
            }
            buffer.appendRow(
                    safeBatchId,
                    normalizeDataDate(record.getDataDate(), dataDate),
                    trimToNull(record.getScenarioId()),
                    trimToNull(record.getSubScenarioId()),
                    trimToNull(record.getScenarioName()),
                    trimToNull(record.getScenarioType()),
                    trimToNull(record.getCurveType()),
                    trimToNull(record.getCurveCode()),
                    trimToNull(record.getTermCode()),
                    record.getTermDays(),
                    trimToNull(record.getDimension2()),
                    toPlainString(record.getShiftValue()),
                    trimToNull(resolveRiskFactorTerm(record)),
                    toPlainString(record.getOriginalValue()),
                    toPlainString(record.getChangedValue()),
                    trimToNull(record.getShiftRule()),
                    trimToNull(record.getModifier()),
                    now,
                    now
            );
        }
        buffer.flush();
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

    private static String toPlainString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
