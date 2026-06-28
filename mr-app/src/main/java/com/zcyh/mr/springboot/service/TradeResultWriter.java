package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写入基准估值结果明细。
 */
@Service
public class TradeResultWriter {
    private static final String TARGET_TABLE = "TB_OUT_TRADE_RESULT_DETAIL";
    private static final List<String> COLUMN_LIST = Collections.unmodifiableList(Arrays.asList(
            "REQUEST_ID",
            "JOB_ID",
            "BATCH_ID",
            "SEQ_NO",
            "DATA_DATE",
            "INSTRUMENT_ID",
            "PRODUCT_CODE",
            "PORTFOLIO",
            "DESK",
            "TRADER",
            "POSITION",
            "VALUATION_UNIT",
            "VALUATION",
            "VALUATION_CCY",
            "VALUATION_CNY",
            "RRAO_TYPE",
            "RRAO_NOTIONAL",
            "PV01",
            "DELTA",
            "GAMMA",
            "VEGA",
            "THETA",
            "RHO",
            "STATUS",
            "ERROR",
            "DETAIL",
            "LOGS_JSON",
            "CASHFLOW_JSON",
            "RESULT_JSON",
            "TRADE_INPUT_JSON",
            "MARKET_DATA_KEYS_JSON",
            "CREATED_AT",
            "UPDATED_AT"
    ));
    private static final String COLUMNS = String.join(",", COLUMN_LIST);
    private static final Map<String, String> DIMENSION_SOURCE_COLUMNS = buildDimensionSourceColumns();

    private final DorisStreamLoadService dorisStreamLoadService;

    public TradeResultWriter(DorisStreamLoadService dorisStreamLoadService) {
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    List<String> requiredColumns() {
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
                "trade_result_" + context.batchId + "_" + context.jobId,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = CalcResultPersistSupport.trimToNull(trade.getString("INSTRUMENT_ID"));
            JSONObject inputTrade = (instrumentId == null || context.inputTradeIndex == null)
                    ? null : context.inputTradeIndex.get(instrumentId);
            JSONObject inputRrao = (instrumentId == null || context.tradeRrao == null)
                    ? null : context.tradeRrao.getJSONObject(instrumentId);
            buffer.appendRow(buildRow(context, trade, instrumentId, inputTrade, inputRrao));
        }
        buffer.flush();
    }

    private Object[] buildRow(CalcPersistContext context, JSONObject trade, String instrumentId,
                              JSONObject inputTrade, JSONObject inputRrao) {
        return new Object[]{
                context.requestId,
                context.jobId,
                context.batchId,
                context.seqNo,
                CalcResultPersistSupport.normalizeDataDate(context.dataDate),
                instrumentId,
                CalcResultPersistSupport.trimToNull(trade.getString("PRODUCT_CODE")),
                resolveDimensionField(context.tradeDimension, instrumentId, "PORTFOLIO"),
                resolveDimensionField(context.tradeDimension, instrumentId, "DESK"),
                resolveDimensionField(context.tradeDimension, instrumentId, "TRADER"),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("POSITION"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("VALUATION_UNIT"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("VALUATION"))),
                CalcResultPersistSupport.trimToNull(trade.getString("VALUATION_CCY")),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("VALUATION_CNY"))),
                inputRrao == null ? null : CalcResultPersistSupport.trimToNull(inputRrao.getString("RRAO_TYPE")),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(inputRrao == null ? null : inputRrao.get("RRAO_NOTIONAL"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("PV01"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("DELTA"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("GAMMA"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("VEGA"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("THETA"))),
                DorisCsvStreamLoadBuffer.decimalText(CalcResultPersistSupport.toBigDecimal(trade.get("RHO"))),
                CalcResultPersistSupport.trimToNull(trade.getString("STATUS")),
                null,
                CalcResultPersistSupport.toTextValue(trade.get("DETAIL")),
                CalcResultPersistSupport.toJsonString(trade.get("LOGS")),
                CalcResultPersistSupport.toJsonString(trade.get("CASH_FLOW")),
                CalcResultPersistSupport.isSyntheticErrorTrade(trade) ? null : CalcResultPersistSupport.toJsonString(trade),
                CalcResultPersistSupport.toJsonString(inputTrade),
                inputTrade == null ? null : CalcResultPersistSupport.toJsonString(inputTrade.get("_MARKET_DATA_KEYS")),
                context.createdAt,
                context.updatedAt
        };
    }

    private static Map<String, String> buildDimensionSourceColumns() {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        map.put("PORTFOLIO", "portfolio");
        map.put("DESK", "desk");
        map.put("TRADER", "trader");
        return Collections.unmodifiableMap(map);
    }

    private static String resolveDimensionField(JSONObject tradeDimension, String instrumentId,
                                                String outputColumn) {
        String sourceColumn = DIMENSION_SOURCE_COLUMNS.get(outputColumn);
        if (sourceColumn == null) {
            throw new IllegalStateException("trade_detail输出字段缺少输入维度映射: " + outputColumn);
        }
        if (tradeDimension != null && instrumentId != null) {
            JSONObject dim = tradeDimension.getJSONObject(instrumentId);
            if (dim != null) {
                return CalcResultPersistSupport.trimToNull(dim.getString(sourceColumn));
            }
        }
        return null;
    }
}
