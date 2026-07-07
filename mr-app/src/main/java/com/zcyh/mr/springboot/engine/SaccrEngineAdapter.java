package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.saccr.SaccrCalculator;
import com.zcyh.mr.saccr.model.SaccrResult;
import com.zcyh.mr.springboot.saccr.SaccrInputQueryService;
import com.zcyh.mr.springboot.saccr.SaccrRunInput;
import com.zcyh.mr.springboot.out.db.SaccrResultPersistService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * SA-CCR 引擎适配器（engineCode = "sa_ccr"）。
 *
 * <p>输入只接受 batch_id 和 data_date。MTM 来自对应批次的 TB_OUT_TRADE_RESULT_DETAIL。
 */
public class SaccrEngineAdapter implements EngineAdapter {

    public static final String CODE = "sa_ccr";

    private final SaccrInputQueryService inputQueryService;
    private final SaccrResultPersistService resultPersistService;

    public SaccrEngineAdapter(SaccrInputQueryService inputQueryService,
                              SaccrResultPersistService resultPersistService) {
        this.inputQueryService = inputQueryService;
        this.resultPersistService = resultPersistService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "SA-CCR counterparty credit risk EAD calculator";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }

        String batchId = requireText(req.getString("batch_id"), "batch_id");
        String dataDateText = normalizeDataDate(req.getString("data_date"));
        LocalDate dataDate = LocalDate.parse(dataDateText, DateTimeFormatter.BASIC_ISO_DATE);
        boolean persistResult = Boolean.TRUE.equals(req.getBoolean("persist_result"));

        SaccrRunInput input = inputQueryService.build(batchId, dataDateText);
        List<SaccrResult> results = SaccrCalculator.calculate(input.nettingSets, dataDate);

        if (persistResult) {
            resultPersistService.persist(batchId, dataDateText, results, input.tradeRows, input.collateralRows);
        }

        JSONObject output = new JSONObject();
        output.put("batch_id", batchId);
        output.put("data_date", dataDateText);
        output.put("persist_result", persistResult);
        output.put("netting_set_count", results.size());
        output.put("trade_count", input.tradeRows.size());
        output.put("collateral_count", input.collateralRows.size());
        output.put("results", JSON.parseArray(JSON.toJSONString(results)));
        return output.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static String normalizeDataDate(String dataDate) {
        String value = requireText(dataDate, "data_date");
        try {
            if (value.length() == 8) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                        .format(DateTimeFormatter.BASIC_ISO_DATE);
            }
            if (value.length() == 10) {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                        .format(DateTimeFormatter.BASIC_ISO_DATE);
            }
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("data_date 日期格式必须为 yyyyMMdd 或 yyyy-MM-dd: " + dataDate, ex);
        }
        throw new IllegalArgumentException("data_date 日期格式必须为 yyyyMMdd 或 yyyy-MM-dd: " + dataDate);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填");
        }
        return value.trim();
    }
}
