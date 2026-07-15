package com.zcyh.mr.springboot.service;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.ima.ImaBacktestCalculationService;
import com.zcyh.mr.springboot.ima.ImaKsCalculationService;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository.ExternalPnlRow;
import com.zcyh.mr.springboot.ima.ImaValidationInputRepository.GroupKey;
import com.zcyh.mr.springboot.ima.ImaValidationResultAssembler;
import com.zcyh.mr.springboot.ima.ImaValidationResultAssembler.BacktestOutput;
import com.zcyh.mr.springboot.ima.ImaValidationResultAssembler.KsOutput;
import com.zcyh.mr.springboot.ima.ImaValidationResultAssembler.ValidationMetadata;
import com.zcyh.mr.springboot.model.SummaryCleanupMode;
import com.zcyh.mr.springboot.out.db.ImaValidationResultPersistService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * IMA 返回检验与 KS 检验流程编排服务。
 */
@Service
public class ImaValidationService {
    private static final String VALIDATION_TYPE_BACKTEST = "BACKTEST";
    private static final String VALIDATION_TYPE_KS = "KS";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ImaValidationInputRepository inputRepository;
    private final ImaBacktestCalculationService backtestCalculationService;
    private final ImaKsCalculationService ksCalculationService;
    private final ImaValidationResultAssembler resultAssembler;
    private final ImaValidationResultPersistService persistService;

    public ImaValidationService(
            ImaValidationInputRepository inputRepository,
            ImaBacktestCalculationService backtestCalculationService,
            ImaKsCalculationService ksCalculationService,
            ImaValidationResultAssembler resultAssembler,
            ImaValidationResultPersistService persistService) {
        this.inputRepository = inputRepository;
        this.backtestCalculationService = backtestCalculationService;
        this.ksCalculationService = ksCalculationService;
        this.resultAssembler = resultAssembler;
        this.persistService = persistService;
    }

    public String calculate(String inputJson) {
        JSONObject request = JSON.parseObject(inputJson);
        if (request == null) {
            throw new IllegalArgumentException("IMA 校验输入不能为空");
        }
        String validationType = normalizeValidationType(required(request, "validation_type"));
        String batchId = required(request, "batch_id");
        String dataDate = normalizeDate(required(request, "data_date"), "data_date");
        String ruleId = required(request, "rule_id");
        String quantile = VALIDATION_TYPE_BACKTEST.equals(validationType)
                ? required(request, "quantile") : null;
        String varScenarioId = VALIDATION_TYPE_BACKTEST.equals(validationType)
                ? required(request, "var_scenario_id") : null;
        boolean persistResult = readBoolean(request, true, "persist_result");
        SummaryCleanupMode cleanupMode = readCleanupMode(request);

        List<String> observationDates = inputRepository.queryObservationDates(dataDate, ruleId);
        if (VALIDATION_TYPE_KS.equals(validationType)
                && observationDates.size() != ImaValidationInputRepository.REQUIRED_OBSERVATION_COUNT) {
            throw new IllegalArgumentException("IMA 返回检验需要最近250个有效观测日: data_date=" + dataDate
                    + ", rule_id=" + ruleId + ", actual_count=" + observationDates.size());
        }
        String startDate = observationDates.isEmpty() ? dataDate : observationDates.get(0);
        String endDate = observationDates.isEmpty() ? dataDate : observationDates.get(observationDates.size() - 1);

        Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup = null;
        if (VALIDATION_TYPE_BACKTEST.equals(validationType)) {
            varByGroup = inputRepository.queryVarRows(batchId, dataDate, ruleId, quantile, varScenarioId);
            if (varByGroup.isEmpty()) {
                throw new IllegalArgumentException("未查询到匹配的 VaR 结果: rule_id=" + ruleId
                        + ", batch_id=" + batchId + ", data_date=" + dataDate);
            }
            startDate = inputRepository.minVarDate(varByGroup);
            endDate = inputRepository.maxVarDate(varByGroup);
        }

        Map<GroupKey, List<ExternalPnlRow>> pnlRows =
                inputRepository.queryExternalPnl(startDate, endDate, ruleId);
        if (VALIDATION_TYPE_KS.equals(validationType) && pnlRows.isEmpty()) {
            throw new IllegalArgumentException("未查询到 IMA 外部接入分组 PnL: rule_id=" + ruleId);
        }

        ValidationMetadata metadata = new ValidationMetadata(
                batchId, dataDate, startDate, endDate, ruleId, quantile, varScenarioId);
        BacktestOutput backtestOutput = null;
        KsOutput ksOutput = null;
        if (VALIDATION_TYPE_BACKTEST.equals(validationType)) {
            backtestOutput = resultAssembler.assembleBacktest(
                    metadata, backtestCalculationService.calculate(varByGroup, pnlRows));
        } else {
            ksOutput = resultAssembler.assembleKs(metadata, ksCalculationService.calculate(pnlRows));
        }

        if (persistResult) {
            persistService.replace(
                    batchId,
                    dataDate,
                    cleanupMode,
                    startDate,
                    endDate,
                    ruleId,
                    quantile,
                    varScenarioId,
                    backtestOutput == null ? null : backtestOutput.getRows(),
                    backtestOutput == null ? null : backtestOutput.getExceptionRows(),
                    ksOutput == null ? null : ksOutput.getRows());
        }

        JSONObject response = new JSONObject();
        response.put("validation_type", validationType);
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("start_date", startDate);
        response.put("end_date", endDate);
        response.put("rule_id", ruleId);
        if (VALIDATION_TYPE_BACKTEST.equals(validationType)) {
            response.put("quantile", quantile);
            response.put("var_scenario_id", varScenarioId);
        }
        response.put("sample_size", ImaValidationInputRepository.REQUIRED_OBSERVATION_COUNT);
        response.put("persist_result", persistResult);
        response.put("backtest_results", backtestOutput == null ? new JSONArray() : backtestOutput.getResponseRows());
        response.put("backtest_exception_details",
                backtestOutput == null ? new JSONArray() : backtestOutput.getResponseExceptionRows());
        response.put("ks_results", ksOutput == null ? new JSONArray() : ksOutput.getResponseRows());
        return JSON.toJSONString(response, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static String required(JSONObject json, String key) {
        return requireText(json.getString(key), key);
    }

    private static String normalizeValidationType(String validationType) {
        String value = validationType.trim().toUpperCase(Locale.ROOT);
        if (!VALIDATION_TYPE_BACKTEST.equals(value) && !VALIDATION_TYPE_KS.equals(value)) {
            throw new IllegalArgumentException("validation_type 仅支持 BACKTEST 或 KS");
        }
        return value;
    }

    private static SummaryCleanupMode readCleanupMode(JSONObject request) {
        Object raw = request.get("cleanupMode");
        if (raw == null) {
            return SummaryCleanupMode.FULL;
        }
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException("cleanupMode 必须为字符串");
        }
        String value = trimToNull((String) raw);
        if (value == null) {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }
        try {
            return SummaryCleanupMode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cleanupMode 仅支持 FULL 或 RULE");
        }
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
}
