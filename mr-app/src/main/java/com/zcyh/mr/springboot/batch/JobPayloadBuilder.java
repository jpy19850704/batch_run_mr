package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.input.db.MarketCurveInputRow;

import com.zcyh.mr.springboot.input.db.TradeInputRow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.support.EngineConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Job Payload 构建器。
 * 负责将交易数据、市场数据、场景配置组装为引擎可执行的 JSON payload。
 */
@Component
public class JobPayloadBuilder {
    private static final Logger log = LoggerFactory.getLogger(JobPayloadBuilder.class);
    /**
     * 构建单个 Job 的引擎 payload。
     *
     * @param calcMode          计量模式（PRICING/CURVE_GENERATION）
     * @param dataDate          数据日期
     * @param chunkTrades       本 chunk 的交易列表
     * @param curves            切片后的市场曲线
     * @param tradeMarketDataKeys 交易引用的市场数据标识映射
     * @param batchId           批次 ID
     * @param seqNo             分片序号
     * @param regularScenarioIdList 普通情景集 ID（仅 SCENARIO 模式）
     * @param varScenarioIdList VaR 情景集 ID（仅 SCENARIO 模式）
     * @param persistResult     是否写入结果库
     * @param frtbDisabled      是否关闭 FRTB 计量
     * @return 组装好的 payload JSON
     */
    public JSONObject buildPayload(
            String calcMode,
            LocalDate dataDate,
            List<TradeInputRow> chunkTrades,
            List<MrMarketDataSliceService.CurveSliceSource> curves,
            Map<String, Set<String>> tradeMarketDataKeys,
            String batchId,
            int seqNo,
            String regularScenarioIdList,
            String varScenarioIdList,
            boolean persistResult,
            boolean frtbDisabled
    ) {
        return buildPayload(
                calcMode,
                dataDate,
                chunkTrades,
                curves,
                tradeMarketDataKeys,
                batchId,
                seqNo,
                regularScenarioIdList,
                varScenarioIdList,
                null,
                null,
                null,
                null,
                persistResult,
                frtbDisabled);
    }

    public JSONObject buildPayload(
            String calcMode,
            LocalDate dataDate,
            List<TradeInputRow> chunkTrades,
            List<MrMarketDataSliceService.CurveSliceSource> curves,
            Map<String, Set<String>> tradeMarketDataKeys,
            String batchId,
            int seqNo,
            String regularScenarioIdList,
            String varScenarioIdList,
            String normalFullScenarioIdList,
            String normalReducedScenarioIdList,
            String stressReducedScenarioIdList,
            String nmrfScenarioIdList,
            boolean persistResult,
            boolean frtbDisabled
    ) {
        // 组装 trade_data
        JSONArray tradeData = new JSONArray();
        for (TradeInputRow trade : chunkTrades) {
            JSONObject parsed = parseTradeForPayload(trade, batchId, dataDate);
            if (parsed != null) {
                injectMarketDataKeys(parsed, trade.instrumentId, tradeMarketDataKeys);
                tradeData.add(parsed);
            }
        }

        // 组装 market_data
        JSONArray marketData = new JSONArray();
        for (MrMarketDataSliceService.CurveSliceSource curve : curves) {
            Object parsed = parseCurveJson(curve);
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.size(); i++) {
                    marketData.add(arr.get(i));
                }
            } else if (parsed != null) {
                marketData.add(parsed);
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("calc_mode", calcMode);
        payload.put("data_date", dataDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        payload.put("trade_data", tradeData);
        payload.put("market_data", marketData);
        payload.put("persist_result", persistResult);
        payload.put("frtb_disable", frtbDisabled);

        // 批次元数据
        JSONObject batchMeta = new JSONObject();
        batchMeta.put("batch_id", batchId);
        batchMeta.put("seq_no", seqNo);
        batchMeta.put("trade_count", chunkTrades.size());
        payload.put("batch_meta", batchMeta);

        putScenarioRefList(payload, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST, regularScenarioIdList);
        putScenarioRefList(payload, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST, varScenarioIdList);
        putImaModellableScenarioRefList(payload, normalFullScenarioIdList, ImaConstants.SCENARIO_TYPE_NORMAL_FULL);
        putImaModellableScenarioRefList(payload, normalReducedScenarioIdList, ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED);
        putImaModellableScenarioRefList(payload, stressReducedScenarioIdList, ImaConstants.SCENARIO_TYPE_STRESS_REDUCED);
        putScenarioRefList(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST, nmrfScenarioIdList);

        // 维度映射表使用输入表字段名。
        JSONObject tradeDimension = new JSONObject();
        for (TradeInputRow trade : chunkTrades) {
            String dimInstrumentId = trimToNull(trade.instrumentId);
            if (dimInstrumentId == null) {
                continue;
            }
            JSONObject dim = new JSONObject();
            Map<String, String> dimensions = trade.dimensionAttributes();
            if (!dimensions.isEmpty()) {
                for (Map.Entry<String, String> entry : dimensions.entrySet()) {
                    String key = trimToNull(entry.getKey());
                    String value = trimToNull(entry.getValue());
                    if (key != null && value != null) {
                        dim.put(key, value);
                    }
                }
            }
            if (!dim.isEmpty()) {
                tradeDimension.put(dimInstrumentId, dim);
            }
        }
        if (!tradeDimension.isEmpty()) {
            payload.put("trade_dimension", tradeDimension);
        }

        JSONObject tradeRrao = new JSONObject();
        for (TradeInputRow trade : chunkTrades) {
            String dimInstrumentId = trimToNull(trade.instrumentId);
            if (dimInstrumentId == null) {
                continue;
            }
            String rraoType = trimToNull(trade.getTextAttribute("RRAO_TYPE"));
            java.math.BigDecimal rraoNotional = trade.getDecimalAttribute("RRAO_NOTIONAL");
            if (rraoType == null && rraoNotional == null) {
                continue;
            }
            JSONObject rrao = new JSONObject();
            if (rraoType != null) {
                rrao.put("RRAO_TYPE", rraoType);
            }
            if (rraoNotional != null) {
                rrao.put("RRAO_NOTIONAL", rraoNotional);
            }
            tradeRrao.put(dimInstrumentId, rrao);
        }
        if (!tradeRrao.isEmpty()) {
            payload.put("trade_rrao", tradeRrao);
        }
        return payload;
    }

    private void putScenarioRefList(JSONObject payload, String fieldName, String scenarioIdList) {
        String safeScenarioIdList = trimToNull(scenarioIdList);
        if (safeScenarioIdList == null) {
            return;
        }
        JSONArray items = payload.getJSONArray(fieldName);
        if (items == null) {
            items = new JSONArray();
            payload.put(fieldName, items);
        }
        JSONObject item = new JSONObject();
        item.put("scenario_set_id", safeScenarioIdList);
        items.add(item);
    }

    private void putImaModellableScenarioRefList(JSONObject payload, String scenarioIdList, String imaScenarioType) {
        String safeScenarioIdList = trimToNull(scenarioIdList);
        if (safeScenarioIdList == null) {
            return;
        }
        JSONArray items = payload.getJSONArray(ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST);
        if (items == null) {
            items = new JSONArray();
            payload.put(ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST, items);
        }
        JSONObject item = new JSONObject();
        item.put("scenario_set_id", safeScenarioIdList);
        JSONObject scenarioMeta = new JSONObject();
        scenarioMeta.put(ScenarioProcessConstants.TAG_IMA_SCENARIO_TYPE, imaScenarioType);
        item.put(ScenarioProcessConstants.SCENARIO_META, scenarioMeta);
        items.add(item);
    }

    /**
     * 构建产品组成 JSON（用于 batch_item 记录）。
     */
    public static String buildProductMixJson(List<TradeInputRow> chunkTrades) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (TradeInputRow trade : chunkTrades) {
            String productCode = trimToNull(trade.productCode);
            if (productCode == null) {
                productCode = "UNKNOWN";
            }
            count.merge(productCode, 1, Integer::sum);
        }
        return JSON.toJSONString(count, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    /**
     * 将交易行转为市场数据切片源。
     */
    public static List<MrMarketDataSliceService.TradeSliceSource> toTradeSliceSources(
            List<TradeInputRow> chunkTrades) {
        List<MrMarketDataSliceService.TradeSliceSource> trades = new ArrayList<>();
        for (TradeInputRow trade : chunkTrades) {
            if (isTradeSliceable(trade)) {
                trades.add(new MrMarketDataSliceService.TradeSliceSource(trade.instrumentId, trade.tradeContentText));
            }
        }
        return trades;
    }

    /**
     * 将曲线行转为市场数据切片源。
     */
    public static List<MrMarketDataSliceService.CurveSliceSource> toCurveSliceSources(
            List<MarketCurveInputRow> curves) {
        List<MrMarketDataSliceService.CurveSliceSource> curveSources = new ArrayList<>();
        for (MarketCurveInputRow curve : curves) {
            curveSources.add(new MrMarketDataSliceService.CurveSliceSource(
                    curve.marketDataType,
                    curve.curveId,
                    curve.curveContentText));
        }
        return curveSources;
    }

    // ==================== 内部工具 ====================

    /**
     * 将交易引用的市场数据标识注入到交易 JSON 的 _MARKET_DATA_KEYS 字段。
     */
    private static void injectMarketDataKeys(Object tradeJson, String instrumentId,
                                             Map<String, Set<String>> tradeMarketDataKeys) {
        if (!(tradeJson instanceof JSONObject) || tradeMarketDataKeys == null) {
            return;
        }
        String safeId = trimToNull(instrumentId);
        if (safeId == null) {
            return;
        }
        Set<String> keys = tradeMarketDataKeys.get(safeId);
        // 批量链路必须稳定写入字段，空数组表示该交易无有效风险因子。
        ((JSONObject) tradeJson).put("_MARKET_DATA_KEYS",
                new JSONArray(keys == null ? new ArrayList<String>() : new ArrayList<String>(keys)));
    }

    static Object parseTradeJson(TradeInputRow trade) {
        String instrumentId = trade == null ? null : trade.instrumentId;
        String contentText = trade == null ? null : trade.tradeContentText;
        return parseJsonStrict(contentText, "交易JSON格式异常，instrumentId=" + safeText(instrumentId));
    }

    private static JSONObject parseTradeForPayload(
            TradeInputRow trade,
            String batchId,
            LocalDate dataDate) {
        String instrumentId = trimToNull(trade == null ? null : trade.instrumentId);
        String productCode = trimToNull(trade == null ? null : trade.productCode);
        if (instrumentId == null) {
            log.error("交易输入缺少INSTRUMENT_ID: batchId={}, dataDate={}, productCode={}",
                    batchId, dataDate, productCode);
            return null;
        }
        Object parsed;
        try {
            parsed = parseTradeJson(trade);
        } catch (PayloadJsonParseException ex) {
            return buildInputErrorTrade(instrumentId, productCode, ex.getMessage());
        }
        if (!(parsed instanceof JSONObject)) {
            return buildInputErrorTrade(instrumentId, productCode, "交易内容必须为JSON对象");
        }
        JSONObject tradeJson = (JSONObject) parsed;
        String contentInstrumentId = trimToNull(tradeJson.getString("INSTRUMENT_ID"));
        String contentProductCode = trimToNull(tradeJson.getString("PRODUCT_CODE"));
        if (!instrumentId.equals(contentInstrumentId)) {
            return buildInputErrorTrade(instrumentId, productCode,
                    "交易内容INSTRUMENT_ID与输入表不一致");
        }
        if (productCode == null || !productCode.equals(contentProductCode)) {
            return buildInputErrorTrade(instrumentId, productCode,
                    "交易内容PRODUCT_CODE与输入表不一致");
        }
        return tradeJson;
    }

    private static JSONObject buildInputErrorTrade(String instrumentId, String productCode, String message) {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", instrumentId);
        trade.put("PRODUCT_CODE", productCode);
        trade.put(EngineConstants.CONTROL_FIELD.INPUT_ERROR, message);
        return trade;
    }

    private static boolean isTradeSliceable(TradeInputRow trade) {
        String instrumentId = trimToNull(trade == null ? null : trade.instrumentId);
        String productCode = trimToNull(trade == null ? null : trade.productCode);
        if (instrumentId == null || productCode == null) {
            return false;
        }
        try {
            Object parsed = parseTradeJson(trade);
            if (!(parsed instanceof JSONObject)) {
                return false;
            }
            JSONObject tradeJson = (JSONObject) parsed;
            return instrumentId.equals(trimToNull(tradeJson.getString("INSTRUMENT_ID")))
                    && productCode.equals(trimToNull(tradeJson.getString("PRODUCT_CODE")));
        } catch (PayloadJsonParseException ex) {
            return false;
        }
    }

    static Object parseCurveJson(MrMarketDataSliceService.CurveSliceSource curve) {
        String marketDataType = curve == null ? null : curve.getMarketDataType();
        String curveId = curve == null ? null : curve.getCurveId();
        String contentText = curve == null ? null : curve.getCurveContentText();
        return parseJsonStrict(contentText, "市场曲线JSON格式异常，marketDataType="
                + safeText(marketDataType) + ", curveId=" + safeText(curveId));
    }

    static Object parseJsonStrict(String text, String messagePrefix) {
        String safe = trimToNull(text);
        if (safe == null) {
            throw new PayloadJsonParseException(messagePrefix + ": 内容为空");
        }
        try {
            return JSON.parse(safe);
        } catch (Exception ex) {
            throw new PayloadJsonParseException(messagePrefix + ": " + ex.getMessage(), ex);
        }
    }

    static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    private static String safeText(String txt) {
        String value = trimToNull(txt);
        return value == null ? "" : value;
    }
}

class PayloadJsonParseException extends RuntimeException {
    PayloadJsonParseException(String message) {
        super(message);
    }

    PayloadJsonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
