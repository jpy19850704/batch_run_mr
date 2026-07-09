package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * IMA 校验结果落库服务。
 */
@Service
public class ImaValidationResultPersistService {
    private static final Logger log = LoggerFactory.getLogger(ImaValidationResultPersistService.class);

    private static final String BACKTEST_TABLE = "TB_OUT_IMA_BACKTEST_RESULT";
    private static final String BACKTEST_DETAIL_TABLE = "TB_OUT_IMA_BACKTEST_EXCEPTION_DETAIL";
    private static final String KS_TABLE = "TB_OUT_IMA_KS_RESULT";

    private static final String BACKTEST_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,QUANTILE,VAR_SCENARIO_ID,START_DATE,END_DATE,"
                    + "SAMPLE_SIZE,ACTUAL_EXCEPTION_COUNT,HYPO_EXCEPTION_COUNT,OVERALL_EXCEPTION_COUNT,"
                    + "TRAFFIC_LIGHT_ZONE,MULTIPLIER_ADD_ON,CREATED_AT";
    private static final String BACKTEST_DETAIL_COLUMNS =
            "BATCH_ID,DATA_DATE,EXCEPTION_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,QUANTILE,"
                    + "VAR_SCENARIO_ID,PNL_TYPE,START_DATE,END_DATE,PNL,VAR_VALUE,THRESHOLD,CREATED_AT";
    private static final String KS_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,START_DATE,END_DATE,SAMPLE_SIZE,"
                    + "KS_STATISTIC,KS_ZONE,PASSED,CREATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public ImaValidationResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void replace(String batchId,
                        String dataDate,
                        String startDate,
                        String endDate,
                        String ruleId,
                        String quantile,
                        String varScenarioId,
                        List<BacktestRow> backtestRows,
                        List<ExceptionRow> exceptionRows,
                        List<KsRow> ksRows) {
        deleteExisting(batchId, dataDate, backtestRows != null, ksRows != null);
        persistBacktestRows(batchId, backtestRows);
        persistExceptionRows(batchId, exceptionRows);
        persistKsRows(batchId, ksRows);
        log.info("IMA 校验结果落库完成: batchId={}, ruleId={}, backtestRows={}, exceptionRows={}, ksRows={}",
                batchId,
                ruleId,
                backtestRows == null ? 0 : backtestRows.size(),
                exceptionRows == null ? 0 : exceptionRows.size(),
                ksRows == null ? 0 : ksRows.size());
    }

    private void deleteExisting(String batchId,
                                String dataDate,
                                boolean deleteBacktest,
                                boolean deleteKs) {
        if (deleteBacktest) {
            jdbcTemplate.update("DELETE FROM " + BACKTEST_DETAIL_TABLE
                            + " WHERE BATCH_ID=? AND DATA_DATE=?",
                    batchId, dataDate);
            jdbcTemplate.update("DELETE FROM " + BACKTEST_TABLE
                            + " WHERE BATCH_ID=? AND DATA_DATE=?",
                    batchId, dataDate);
        }
        if (deleteKs) {
            jdbcTemplate.update("DELETE FROM " + KS_TABLE
                            + " WHERE BATCH_ID=? AND DATA_DATE=?",
                    batchId, dataDate);
        }
    }

    private void persistBacktestRows(String batchId, List<BacktestRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                BACKTEST_TABLE,
                BACKTEST_COLUMNS,
                "ima_backtest_" + batchId,
                5000);
        for (BacktestRow row : rows) {
            buffer.appendRow(
                    row.batchId,
                    row.dataDate,
                    row.ruleId,
                    row.groupType,
                    row.groupValue,
                    row.quantile,
                    row.varScenarioId,
                    row.startDate,
                    row.endDate,
                    row.sampleSize,
                    row.actualExceptionCount,
                    row.hypotheticalExceptionCount,
                    row.overallExceptionCount,
                    row.zone == null ? null : row.zone.name(),
                    DorisCsvStreamLoadBuffer.decimalText(row.multiplierAddOn),
                    now);
        }
        buffer.flush();
    }

    private void persistExceptionRows(String batchId, List<ExceptionRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                BACKTEST_DETAIL_TABLE,
                BACKTEST_DETAIL_COLUMNS,
                "ima_backtest_detail_" + batchId,
                5000);
        for (ExceptionRow row : rows) {
            buffer.appendRow(
                    row.batchId,
                    row.dataDate,
                    row.exceptionDate,
                    row.ruleId,
                    row.groupType,
                    row.groupValue,
                    row.quantile,
                    row.varScenarioId,
                    row.pnlType,
                    row.startDate,
                    row.endDate,
                    DorisCsvStreamLoadBuffer.decimalText(row.pnl),
                    DorisCsvStreamLoadBuffer.decimalText(row.varValue),
                    DorisCsvStreamLoadBuffer.decimalText(row.threshold),
                    now);
        }
        buffer.flush();
    }

    private void persistKsRows(String batchId, List<KsRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                KS_TABLE,
                KS_COLUMNS,
                "ima_ks_" + batchId,
                5000);
        for (KsRow row : rows) {
            buffer.appendRow(
                    row.batchId,
                    row.dataDate,
                    row.ruleId,
                    row.groupType,
                    row.groupValue,
                    row.startDate,
                    row.endDate,
                    row.sampleSize,
                    DorisCsvStreamLoadBuffer.decimalText(row.ksStatistic),
                    row.ksZone,
                    row.passed ? "Y" : "N",
                    now);
        }
        buffer.flush();
    }

    public static class BacktestRow {
        public String batchId;
        public String dataDate;
        public String startDate;
        public String endDate;
        public String ruleId;
        public String groupType;
        public String groupValue;
        public String quantile;
        public String varScenarioId;
        public int sampleSize;
        public int actualExceptionCount;
        public int hypotheticalExceptionCount;
        public int overallExceptionCount;
        public TrafficLightZone zone;
        public BigDecimal multiplierAddOn;
    }

    public static class ExceptionRow {
        public String batchId;
        public String dataDate;
        public String startDate;
        public String endDate;
        public String exceptionDate;
        public String ruleId;
        public String groupType;
        public String groupValue;
        public String quantile;
        public String varScenarioId;
        public String pnlType;
        public BigDecimal pnl;
        public BigDecimal varValue;
        public BigDecimal threshold;
    }

    public static class KsRow {
        public String batchId;
        public String dataDate;
        public String startDate;
        public String endDate;
        public String ruleId;
        public String groupType;
        public String groupValue;
        public int sampleSize;
        public BigDecimal ksStatistic;
        public String ksZone;
        public boolean passed;
    }
}
