package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.springboot.service.BatchTradeDataLoader;
import com.zcyh.mr.springboot.service.JobPayloadBuilder;
import com.zcyh.mr.springboot.service.MrMarketDataSliceService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * MR 标准 payload 构建适配器。
 * 只负责交易和市场数据标准组装，不执行计量、不落库、不补展示维度。
 */
public class MrPayloadBuildEngineAdapter implements EngineAdapter {
    public static final String CODE = "MR_PAYLOAD_BUILD";

    private final BatchTradeDataLoader dataLoader;
    private final MrMarketDataSliceService marketDataSliceService;
    private final JobPayloadBuilder payloadBuilder;

    public MrPayloadBuildEngineAdapter(BatchTradeDataLoader dataLoader,
                                       MrMarketDataSliceService marketDataSliceService,
                                       JobPayloadBuilder payloadBuilder) {
        this.dataLoader = dataLoader;
        this.marketDataSliceService = marketDataSliceService;
        this.payloadBuilder = payloadBuilder;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Build standard MR calc payload from engine input data without pricing or persistence";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        LocalDate dataDate = parseDataDate(requiredString(req, "data_date"));
        String opCode = normalizeOpCode(req.getString("op_code"));
        String portfolio = trimToNull(req.getString("portfolio"));
        String desk = trimToNull(req.getString("desk"));
        String batchId = firstNonBlank(
                req.getString("batch_id"),
                "INLINE_" + dataDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "_" + System.currentTimeMillis());
        int seqNo = Math.max(1, req.getIntValue("seq_no"));

        List<BatchTradeDataLoader.TradeRow> trades = loadTrades(req, dataDate, portfolio, desk);
        if (trades.isEmpty()) {
            throw new IllegalArgumentException("未查询到交易数据，请检查 data_date/portfolio/desk/trade_id_list 条件");
        }

        List<BatchTradeDataLoader.CurveRow> curves = dataLoader.loadCurveRows(dataDate);
        if (curves.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 MR_MARKET_CURVE_INPUT");
        }

        List<MrMarketDataSliceService.CurveSliceSource> curveSources = JobPayloadBuilder.toCurveSliceSources(curves);
        MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                JobPayloadBuilder.toTradeSliceSources(trades),
                curveSources);

        JSONObject payload = payloadBuilder.buildPayload(
                opCode,
                dataDate,
                trades,
                sliceResult.getCurves(),
                sliceResult.getTradeMarketDataKeys(),
                batchId,
                seqNo,
                null,
                false);
        return payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private List<BatchTradeDataLoader.TradeRow> loadTrades(
            JSONObject req, LocalDate dataDate, String portfolio, String desk) {
        List<String> tradeIds = BatchTradeDataLoader.normalizeTradeIds(readStringList(req.getJSONArray("trade_id_list")));
        if (!tradeIds.isEmpty()) {
            List<BatchTradeDataLoader.TradeRow> trades = dataLoader.loadTradeRowsByTradeIds(dataDate, tradeIds);
            BatchTradeDataLoader.ensureAllTradeIdsLoaded(tradeIds, trades);
            return trades;
        }
        return dataLoader.loadTradeRows(dataDate, portfolio, desk);
    }

    private static List<String> readStringList(JSONArray array) {
        java.util.ArrayList<String> result = new java.util.ArrayList<String>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.size(); i++) {
            String value = trimToNull(array.getString(i));
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private static LocalDate parseDataDate(String dataDate) {
        try {
            return LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("data_date 格式错误，必须为 yyyyMMdd: " + dataDate);
        }
    }

    private static String normalizeOpCode(String value) {
        String opCode = trimToNull(value);
        if (opCode == null) {
            return Constants.OPER_CODE.PRICING;
        }
        opCode = opCode.toUpperCase(java.util.Locale.ROOT);
        if (!Constants.OPER_CODE.PRICING.equals(opCode) && !Constants.OPER_CODE.SCENARIO.equals(opCode)) {
            throw new IllegalArgumentException("op_code 仅支持 PRICING 或 SCENARIO，实际: " + value);
        }
        return opCode;
    }

    private static String requiredString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String safe = trimToNull(value);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
