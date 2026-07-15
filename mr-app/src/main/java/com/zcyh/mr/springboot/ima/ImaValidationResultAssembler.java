package com.zcyh.mr.springboot.ima;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.frtbima.validation.backtest.BacktestMultiplierTable;
import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;
import com.zcyh.mr.frtbima.validation.model.BacktestResult;
import com.zcyh.mr.frtbima.validation.model.ExceptionDetail;
import com.zcyh.mr.springboot.ima.ImaBacktestCalculationService.GroupResult;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository.GroupKey;
import com.zcyh.mr.springboot.out.db.ImaValidationResultPersistService;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * IMA 校验持久化对象和响应对象装配器。
 */
@Component
public class ImaValidationResultAssembler {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final BacktestMultiplierTable multiplierTable = new BacktestMultiplierTable();

    public BacktestOutput assembleBacktest(ValidationMetadata metadata, List<GroupResult> groupResults) {
        List<ImaValidationResultPersistService.BacktestRow> backtestRows =
                new ArrayList<ImaValidationResultPersistService.BacktestRow>();
        List<ImaValidationResultPersistService.ExceptionRow> exceptionRows =
                new ArrayList<ImaValidationResultPersistService.ExceptionRow>();
        JSONArray responseBacktestRows = new JSONArray();
        JSONArray responseExceptionRows = new JSONArray();

        for (GroupResult groupResult : groupResults) {
            ImaValidationResultPersistService.BacktestRow backtestRow =
                    toBacktestRow(metadata, groupResult.groupKey, groupResult.sampleSize, groupResult.result);
            backtestRows.add(backtestRow);
            responseBacktestRows.add(toBacktestJson(backtestRow));
            for (ExceptionDetail detail : groupResult.result.getExceptions()) {
                ImaValidationResultPersistService.ExceptionRow exceptionRow =
                        toExceptionRow(metadata, groupResult.groupKey, detail);
                exceptionRows.add(exceptionRow);
                responseExceptionRows.add(toExceptionJson(exceptionRow));
            }
        }
        return new BacktestOutput(backtestRows, exceptionRows, responseBacktestRows, responseExceptionRows);
    }

    public KsOutput assembleKs(
            ValidationMetadata metadata,
            List<ImaKsCalculationService.GroupResult> groupResults) {
        List<ImaValidationResultPersistService.KsRow> ksRows =
                new ArrayList<ImaValidationResultPersistService.KsRow>();
        JSONArray responseKsRows = new JSONArray();
        for (ImaKsCalculationService.GroupResult groupResult : groupResults) {
            ImaValidationResultPersistService.KsRow row = toKsRow(metadata, groupResult);
            ksRows.add(row);
            responseKsRows.add(toKsJson(row));
        }
        return new KsOutput(ksRows, responseKsRows);
    }

    private ImaValidationResultPersistService.BacktestRow toBacktestRow(
            ValidationMetadata metadata,
            GroupKey groupKey,
            int sampleSize,
            BacktestResult result) {
        int actualCount = countExceptionType(result, ExceptionDetail.PNL_TYPE_ACTUAL);
        int hypotheticalCount = countExceptionType(result, ExceptionDetail.PNL_TYPE_HYPOTHETICAL);
        int overallCount = Math.max(actualCount, hypotheticalCount);

        ImaValidationResultPersistService.BacktestRow row = new ImaValidationResultPersistService.BacktestRow();
        fillCommon(row, metadata, groupKey);
        row.quantile = metadata.quantile;
        row.varScenarioId = metadata.varScenarioId;
        row.sampleSize = sampleSize;
        row.actualExceptionCount = actualCount;
        row.hypotheticalExceptionCount = hypotheticalCount;
        row.overallExceptionCount = overallCount;
        row.zone = TrafficLightZone.fromExceptions(overallCount);
        row.multiplierAddOn = multiplierTable.lookup(overallCount);
        return row;
    }

    private ImaValidationResultPersistService.ExceptionRow toExceptionRow(
            ValidationMetadata metadata,
            GroupKey groupKey,
            ExceptionDetail detail) {
        ImaValidationResultPersistService.ExceptionRow row = new ImaValidationResultPersistService.ExceptionRow();
        row.batchId = metadata.batchId;
        row.dataDate = metadata.dataDate;
        row.startDate = metadata.startDate;
        row.endDate = metadata.endDate;
        row.exceptionDate = detail.getDate().format(BASIC_DATE);
        row.ruleId = metadata.ruleId;
        row.groupType = groupKey.groupType;
        row.groupValue = groupKey.groupValue;
        row.quantile = metadata.quantile;
        row.varScenarioId = metadata.varScenarioId;
        row.pnlType = detail.getPnlType();
        row.pnl = detail.getPnl();
        row.varValue = detail.getVarValue();
        row.threshold = detail.getThreshold();
        return row;
    }

    private ImaValidationResultPersistService.KsRow toKsRow(
            ValidationMetadata metadata,
            ImaKsCalculationService.GroupResult groupResult) {
        ImaValidationResultPersistService.KsRow row = new ImaValidationResultPersistService.KsRow();
        row.batchId = metadata.batchId;
        row.dataDate = metadata.dataDate;
        row.startDate = metadata.startDate;
        row.endDate = metadata.endDate;
        row.ruleId = metadata.ruleId;
        row.groupType = groupResult.groupKey.groupType;
        row.groupValue = groupResult.groupKey.groupValue;
        row.sampleSize = groupResult.sampleSize;
        row.ksStatistic = groupResult.statistic;
        row.ksZone = groupResult.zone;
        row.passed = "GREEN".equals(groupResult.zone);
        return row;
    }

    private void fillCommon(
            ImaValidationResultPersistService.BacktestRow row,
            ValidationMetadata metadata,
            GroupKey groupKey) {
        row.batchId = metadata.batchId;
        row.dataDate = metadata.dataDate;
        row.startDate = metadata.startDate;
        row.endDate = metadata.endDate;
        row.ruleId = metadata.ruleId;
        row.groupType = groupKey.groupType;
        row.groupValue = groupKey.groupValue;
    }

    private int countExceptionType(BacktestResult result, String pnlType) {
        int count = 0;
        for (ExceptionDetail detail : result.getExceptions()) {
            if (pnlType.equals(detail.getPnlType())) {
                count++;
            }
        }
        return count;
    }

    private JSONObject toBacktestJson(ImaValidationResultPersistService.BacktestRow row) {
        JSONObject json = new JSONObject();
        json.put("data_date", row.dataDate);
        json.put("start_date", row.startDate);
        json.put("end_date", row.endDate);
        json.put("rule_id", row.ruleId);
        json.put("group_type", row.groupType);
        json.put("group_value", row.groupValue);
        json.put("sample_size", row.sampleSize);
        json.put("actual_exception_count", row.actualExceptionCount);
        json.put("hypothetical_exception_count", row.hypotheticalExceptionCount);
        json.put("overall_exception_count", row.overallExceptionCount);
        json.put("traffic_light_zone", row.zone == null ? null : row.zone.name());
        json.put("multiplier_add_on", row.multiplierAddOn);
        return json;
    }

    private JSONObject toExceptionJson(ImaValidationResultPersistService.ExceptionRow row) {
        JSONObject json = new JSONObject();
        json.put("data_date", row.dataDate);
        json.put("start_date", row.startDate);
        json.put("end_date", row.endDate);
        json.put("exception_date", row.exceptionDate);
        json.put("rule_id", row.ruleId);
        json.put("group_type", row.groupType);
        json.put("group_value", row.groupValue);
        json.put("pnl_type", row.pnlType);
        json.put("pnl", row.pnl);
        json.put("var_value", row.varValue);
        json.put("threshold", row.threshold);
        return json;
    }

    private JSONObject toKsJson(ImaValidationResultPersistService.KsRow row) {
        JSONObject json = new JSONObject();
        json.put("data_date", row.dataDate);
        json.put("start_date", row.startDate);
        json.put("end_date", row.endDate);
        json.put("rule_id", row.ruleId);
        json.put("group_type", row.groupType);
        json.put("group_value", row.groupValue);
        json.put("sample_size", row.sampleSize);
        json.put("ks_statistic", row.ksStatistic);
        json.put("ks_zone", row.ksZone);
        json.put("passed", row.passed);
        return json;
    }

    public static final class ValidationMetadata {
        final String batchId;
        final String dataDate;
        final String startDate;
        final String endDate;
        final String ruleId;
        final String quantile;
        final String varScenarioId;

        public ValidationMetadata(
                String batchId,
                String dataDate,
                String startDate,
                String endDate,
                String ruleId,
                String quantile,
                String varScenarioId) {
            this.batchId = batchId;
            this.dataDate = dataDate;
            this.startDate = startDate;
            this.endDate = endDate;
            this.ruleId = ruleId;
            this.quantile = quantile;
            this.varScenarioId = varScenarioId;
        }
    }

    public static final class BacktestOutput {
        private final List<ImaValidationResultPersistService.BacktestRow> rows;
        private final List<ImaValidationResultPersistService.ExceptionRow> exceptionRows;
        private final JSONArray responseRows;
        private final JSONArray responseExceptionRows;

        BacktestOutput(
                List<ImaValidationResultPersistService.BacktestRow> rows,
                List<ImaValidationResultPersistService.ExceptionRow> exceptionRows,
                JSONArray responseRows,
                JSONArray responseExceptionRows) {
            this.rows = rows;
            this.exceptionRows = exceptionRows;
            this.responseRows = responseRows;
            this.responseExceptionRows = responseExceptionRows;
        }

        public List<ImaValidationResultPersistService.BacktestRow> getRows() {
            return rows;
        }

        public List<ImaValidationResultPersistService.ExceptionRow> getExceptionRows() {
            return exceptionRows;
        }

        public JSONArray getResponseRows() {
            return responseRows;
        }

        public JSONArray getResponseExceptionRows() {
            return responseExceptionRows;
        }
    }

    public static final class KsOutput {
        private final List<ImaValidationResultPersistService.KsRow> rows;
        private final JSONArray responseRows;

        KsOutput(List<ImaValidationResultPersistService.KsRow> rows, JSONArray responseRows) {
            this.rows = rows;
            this.responseRows = responseRows;
        }

        public List<ImaValidationResultPersistService.KsRow> getRows() {
            return rows;
        }

        public JSONArray getResponseRows() {
            return responseRows;
        }
    }
}
