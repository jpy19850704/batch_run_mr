package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 情景生成结果落库服务。
 * 只按批次维度覆盖写入，不和任务分片绑定。
 */
@Service
public class ScenarioDetailPersistService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioDetailPersistService.class);
    private static final String TARGET_TABLE = "TB_OUT_SCENARIO_FILE_DETAIL";
    private static final String STREAM_LOAD_COLUMNS = "BATCH_ID,DATA_DATE,SCENARIO_ID,SUBSCENARIO_ID,SCENARIO_NAME,SCENARIO_TYPE,"
            + "RISKFACTOR_TYPE,RISKFACTOR_ID,RISKFACTOR_VERTEX1,TERM_DAYS,RISKFACTOR_VERTEX2,CHANGE_VALUE,"
            + "RISKFACTOR_TERM,ORI_VALUE,SCENARIO_RESULT,SHIFT_RULE,RFET_BUCKET_ID,RFET_MODELLABLE,RFET_REDUCED_SET,"
            + "MODIFIER,CREATED_AT,UPDATED_AT";
    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final int batchSize;

    public ScenarioDetailPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                           DorisStreamLoadService dorisStreamLoadService,
                                           @Value("${mr.scenario.persist.batch-size:20000}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.batchSize = batchSize <= 0 ? 20000 : batchSize;
    }

    /**
     * 按批次覆盖写入情景变化明细。
     * persistScenario 为空/false 或 batchId 为空时直接跳过。
     */
    public void persist(String batchId, String dataDate, Boolean persistScenario, List<ScenarioGeneratedRecord> records) {
        if (!Boolean.TRUE.equals(persistScenario)) {
            log.info("情景生成结果落库跳过: batchId={}, dataDate={}, reason=persist_scenario未开启", batchId, dataDate);
            return;
        }
        String safeBatchId = trimToNull(batchId);
        if (safeBatchId == null) {
            log.warn("情景生成结果落库跳过: batchId={}, dataDate={}, reason=batchId为空", batchId, dataDate);
            return;
        }
        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("情景生成结果为空，不能落库: batchId=" + safeBatchId + ", dataDate=" + dataDate);
        }
        String normalizedDataDate = trimToNull(dataDate);
        if (normalizedDataDate == null) {
            throw new IllegalArgumentException("情景生成结果落库缺少 dataDate");
        }

        String now = ResultPersistTime.nowText();
        int deleted = jdbcTemplate.update("DELETE FROM TB_OUT_SCENARIO_FILE_DETAIL WHERE BATCH_ID=? AND DATA_DATE=STR_TO_DATE(?, '%Y%m%d')",
                safeBatchId, normalizedDataDate);
        log.info("清理情景生成历史结果: batchId={}, dataDate={}, deleted={}", safeBatchId, normalizedDataDate, deleted);

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
                    normalizedDataDate,
                    trimToNull(record.getScenarioId()),
                    trimToNull(record.getSubScenarioId()),
                    trimToNull(record.getScenarioName()),
                    trimToNull(record.getScenarioType()),
                    trimToNull(record.getCurveType()),
                    trimToNull(record.getCurveCode()),
                    trimToNull(resolveRiskFactorVertex1(record)),
                    record.getTermDays(),
                    trimToNull(record.getDimension2()),
                    toPlainString(record.getShiftValue()),
                    trimToNull(resolveRiskFactorTerm(record)),
                    toPlainString(record.getOriginalValue()),
                    toPlainString(record.getChangedValue()),
                    trimToNull(record.getShiftRule()),
                    trimToNull(record.getRfetBucketId()),
                    booleanText(record.getRfetModellable()),
                    booleanText(record.getRfetReducedSet()),
                    trimToNull(record.getModifier()),
                    now,
                    now
            );
        }
        buffer.flush();
        log.info("情景生成结果落库完成: batchId={}, dataDate={}, rows={}",
                safeBatchId, normalizedDataDate, records.size());
    }

    /**
     * Doris 保留旧字段名，但内容语义统一为：
     * RISKFACTOR_VERTEX1 = 第一维（与 TERM_DAYS 对应）
     */
    private static String resolveRiskFactorVertex1(ScenarioGeneratedRecord record) {
        if (record == null) {
            return null;
        }
        Integer termDays = record.getTermDays();
        if (termDays == null) {
            return null;
        }
        return String.valueOf(termDays);
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

    private static String toPlainString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String booleanText(Boolean value) {
        return value == null ? null : (Boolean.TRUE.equals(value) ? "1" : "0");
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
