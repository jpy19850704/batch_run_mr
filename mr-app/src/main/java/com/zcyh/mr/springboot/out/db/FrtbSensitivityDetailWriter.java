package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 写入 FRTB 敏感性明细。
 */
@Service
public class FrtbSensitivityDetailWriter {
    private static final String TARGET_TABLE = "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL";
    private static final List<String> COLUMN_LIST = Arrays.asList(
            "REQUEST_ID",
            "JOB_ID",
            "BATCH_ID",
            "SEQ_NO",
            "DATA_DATE",
            "INSTRUMENT_ID",
            "PRODUCT_CODE",
            "RISK_FACTOR_ID",
            "RISK_FACTOR_VERTEX_1",
            "RISK_FACTOR_VERTEX_2",
            "RISK_FACTOR_CLASS",
            "RISK_FACTOR_BUCKET",
            "RISK_FACTOR_TYPE",
            "SENSITIVITY_TYPE",
            "SENSITIVITY_VAL_INST_CURR",
            "INSTRUMENT_CURRENCY",
            "SENSITIVITY_VAL_INST_CURR_CNY",
            "DETAIL_JSON",
            "CREATED_AT",
            "UPDATED_AT"
    );
    private static final String COLUMNS = String.join(",", COLUMN_LIST);

    private final DorisStreamLoadService dorisStreamLoadService;

    public FrtbSensitivityDetailWriter(DorisStreamLoadService dorisStreamLoadService) {
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    String tableName() {
        return TARGET_TABLE;
    }

    List<String> writeColumns() {
        return COLUMN_LIST;
    }

    void write(CalcPersistContext context) {
        JSONArray trades = context == null ? null : context.effectiveBaseTrades;
        if (trades == null || trades.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                COLUMNS,
                "frtb_sensitivity_" + context.batchId + "_" + context.jobId,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            JSONArray sensitivityList = trade.getJSONArray("FRTB_SENSITIVITY");
            if (sensitivityList == null || sensitivityList.isEmpty()) {
                continue;
            }
            String instrumentId = CalcResultPersistSupport.trimToNull(trade.getString("INSTRUMENT_ID"));
            String productCode = CalcResultPersistSupport.trimToNull(trade.getString("PRODUCT_CODE"));
            for (int j = 0; j < sensitivityList.size(); j++) {
                JSONObject sensitivity = sensitivityList.getJSONObject(j);
                if (sensitivity == null) {
                    continue;
                }
                buffer.appendRow(
                        context.requestId,
                        context.jobId,
                        context.batchId,
                        context.seqNo,
                        CalcResultPersistSupport.normalizeDataDate(context.dataDate),
                        instrumentId,
                        productCode,
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("RISK_FACTOR_ID")),
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("RISK_FACTOR_VERTEX_1")),
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("RISK_FACTOR_VERTEX_2")),
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("RISK_FACTOR_CLASS")),
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("RISK_FACTOR_BUCKET")),
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("RISK_FACTOR_TYPE")),
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("SENSITIVITY_TYPE")),
                        DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(sensitivity.get("SENSITIVITY_VAL_INST_CURR"))),
                        CalcResultPersistSupport.trimToNull(sensitivity.getString("INSTRUMENT_CURRENCY")),
                        DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(sensitivity.get("SENSITIVITY_VAL_INST_CURR_CNY"))),
                        null,
                        context.createdAt,
                        context.updatedAt
                );
            }
        }
        buffer.flush();
    }
}
