package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.CsvRowWriter;
import com.zcyh.mr.springboot.support.CsvRowWriterFactory;
import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 写入 DRC 明细。
 */
@Service
public class DrcDetailWriter {
    private static final Logger log = LoggerFactory.getLogger(DrcDetailWriter.class);
    private static final int MAX_INVALID_DRC_LOG = 10;
    private static final String TARGET_TABLE = "TB_OUT_TRADE_DRC_DETAIL";
    private static final List<String> COLUMN_LIST = Arrays.asList(
            "REQUEST_ID",
            "JOB_ID",
            "BATCH_ID",
            "SEQ_NO",
            "DATA_DATE",
            "INSTRUMENT_ID",
            "PRODUCT_CODE",
            "PORTFOLIO_CODE",
            "SECURITY_ID",
            "SECURITY_TYPE",
            "LEGAL_ENTITY",
            "DRC_BUCKET",
            "JTD_TYPE",
            "SENIORITY",
            "TERM_TO_MATURITY",
            "MODIFIED_REMAIN_TERM",
            "RISK_WEIGHT",
            "JTD",
            "JTD_CNY",
            "INSTRUMENT_VALUE",
            "FRTB_LGD",
            "NOTIONAL",
            "DETAIL_JSON",
            "CREATED_AT",
            "UPDATED_AT"
    );
    private static final String COLUMNS = String.join(",", COLUMN_LIST);

    String tableName() {
        return TARGET_TABLE;
    }

    List<String> writeColumns() {
        return COLUMN_LIST;
    }

    void write(CalcPersistContext context, CsvRowWriterFactory writerFactory) {
        JSONArray trades = context == null ? null : context.effectiveBaseTrades;
        if (trades == null || trades.isEmpty()) {
            return;
        }
        int skippedNullJtdCny = 0;
        int logged = 0;
        CsvRowWriter buffer = writerFactory.create(
                TARGET_TABLE,
                COLUMNS,
                "drc_detail_" + context.batchId + "_" + context.jobId,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            JSONObject drc = trade.getJSONObject("DRC");
            if (drc == null || drc.isEmpty()) {
                continue;
            }
            String instrumentId = CalcResultPersistSupport.trimToNull(trade.getString("INSTRUMENT_ID"));
            String productCode = CalcResultPersistSupport.trimToNull(trade.getString("PRODUCT_CODE"));
            BigDecimal jtd = CalcResultPersistSupport.toBigDecimal(drc.get("JTD"));
            BigDecimal jtdCny = CalcResultPersistSupport.toBigDecimal(drc.get("JTD_CNY"));
            if (jtdCny == null) {
                skippedNullJtdCny++;
                if (logged < MAX_INVALID_DRC_LOG) {
                    log.warn("DRC明细缺少JTD_CNY，已跳过落库: batchId={}, instrumentId={}, productCode={}, drcSecurityType={}",
                            context.batchId,
                            instrumentId,
                            productCode,
                            CalcResultPersistSupport.trimToNull(drc.getString("SECURITY_TYPE")));
                    logged++;
                }
                continue;
            }
            buffer.appendRow(
                    context.requestId,
                    context.jobId,
                    context.batchId,
                    context.seqNo,
                    context.dataDate,
                    instrumentId,
                    productCode,
                    CalcResultPersistSupport.trimToNull(drc.getString("PORTFOLIO_CODE")),
                    CalcResultPersistSupport.trimToNull(drc.getString("SECURITY_ID")),
                    CalcResultPersistSupport.trimToNull(drc.getString("SECURITY_TYPE")),
                    CalcResultPersistSupport.trimToNull(drc.getString("LEGAL_ENTITY")),
                    CalcResultPersistSupport.trimToNull(drc.getString("DRC_BUCKET")),
                    CalcResultPersistSupport.trimToNull(drc.getString("JTD_TYPE")),
                    CalcResultPersistSupport.toInteger(drc.get("SENIORITY")),
                    DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(drc.get("TERM_TO_MATURITY"))),
                    DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(drc.get("MODIFIED_REMAIN_TERM"))),
                    DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(drc.get("RISK_WEIGHT"))),
                    DorisCsvStreamLoadBuffer.decimalText(jtd),
                    DorisCsvStreamLoadBuffer.decimalText(jtdCny),
                    DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(drc.get("INSTRUMENT_VALUE"))),
                    DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(drc.get("FRTB_LGD"))),
                    DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(drc.get("NOTIONAL"))),
                    null,
                    context.createdAt,
                    context.updatedAt
            );
        }
        buffer.flush();
        if (skippedNullJtdCny > 0) {
            log.warn("DRC明细落库跳过记录: batchId={}, skippedNullJtdCny={}, loggedRows={}",
                    context.batchId, skippedNullJtdCny, Math.min(skippedNullJtdCny, MAX_INVALID_DRC_LOG));
        }
    }
}
