package com.zcyh.mr.springboot.measurement.saccr;

import com.zcyh.mr.springboot.execution.ExecutionAdapter;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.saccr.SaccrCalculator;
import com.zcyh.mr.saccr.model.SaccrResult;
import com.zcyh.mr.springboot.measurement.saccr.SaccrInputQueryService;
import com.zcyh.mr.springboot.measurement.saccr.SaccrRunInput;
import com.zcyh.mr.springboot.output.db.SaccrResultPersistService;
import com.zcyh.mr.springboot.support.ResultDbDateSupport;

import java.time.LocalDate;
import java.util.List;

/**
 * SA-CCR 执行适配器（engineCode = "sa_ccr"）。
 *
 * <p>输入只接受 batch_id 和 data_date。MTM 来自对应批次的 TB_OUT_TRADE_RESULT_DETAIL。
 */
public class SaccrExecutionAdapter implements ExecutionAdapter {

    public static final String CODE = "sa_ccr";

    private final SaccrInputQueryService inputQueryService;
    private final SaccrResultPersistService resultPersistService;

    public SaccrExecutionAdapter(SaccrInputQueryService inputQueryService,
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
    public String execute(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }

        String batchId = requireText(req.getString("batch_id"), "batch_id");
        LocalDate dataDate = ResultDbDateSupport.localDate(req.getString("data_date"));
        boolean persistResult = readBoolean(req, false, "persist_result");

        SaccrRunInput input = inputQueryService.build(batchId, dataDate);
        List<SaccrResult> results = SaccrCalculator.calculate(input.nettingSets, dataDate);

        if (persistResult) {
            resultPersistService.persist(batchId, dataDate, results, input.tradeRows, input.collateralRows);
        }

        JSONObject output = new JSONObject();
        output.put("batch_id", batchId);
        output.put("data_date", ResultDbDateSupport.protocolDate(dataDate));
        output.put("persist_result", persistResult);
        output.put("netting_set_count", results.size());
        output.put("trade_count", input.tradeRows.size());
        output.put("collateral_count", input.collateralRows.size());
        output.put("results", JSON.parseArray(JSON.toJSONString(results)));
        return output.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填");
        }
        return value.trim();
    }
}
