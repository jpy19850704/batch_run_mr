package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Constants;
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

    /**
     * 构建单个 Job 的引擎 payload。
     *
     * @param opCode            操作码（PRICING/SCENARIO/FRTB）
     * @param dataDate          数据日期
     * @param chunkTrades       本 chunk 的交易列表
     * @param curves            切片后的市场曲线
     * @param tradeMarketDataKeys 交易引用的市场数据标识映射
     * @param batchId           批次 ID
     * @param seqNo             分片序号
     * @param regularScenarioIdList 普通情景集 ID（仅 SCENARIO 模式）
     * @param riskClassDecompScenarioIdList 风险类别分解情景集 ID（仅 SCENARIO 模式）
     * @param persistResult     是否写入结果库
     * @param frtbDisabled      是否关闭 FRTB 计量
     * @return 组装好的 payload JSON
     */
    public JSONObject buildPayload(
            String opCode,
            LocalDate dataDate,
            List<BatchTradeDataLoader.TradeRow> chunkTrades,
            List<MrMarketDataSliceService.CurveSliceSource> curves,
            Map<String, Set<String>> tradeMarketDataKeys,
            String batchId,
            int seqNo,
            String regularScenarioIdList,
            String riskClassDecompScenarioIdList,
            boolean persistResult,
            boolean frtbDisabled
    ) {
        // 组装 trade_data
        JSONArray tradeData = new JSONArray();
        for (BatchTradeDataLoader.TradeRow trade : chunkTrades) {
            Object parsed = parseJsonSafely(trade.tradeContentText);
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.size(); i++) {
                    injectMarketDataKeys(arr.get(i), trade.instrumentId, tradeMarketDataKeys);
                    tradeData.add(arr.get(i));
                }
            } else if (parsed != null) {
                injectMarketDataKeys(parsed, trade.instrumentId, tradeMarketDataKeys);
                tradeData.add(parsed);
            }
        }

        // 组装 market_data
        JSONArray marketData = new JSONArray();
        for (MrMarketDataSliceService.CurveSliceSource curve : curves) {
            Object parsed = parseJsonSafely(curve.getCurveContentText());
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
        payload.put("oper_code", opCode);
        payload.put("data_date", dataDate.format(DateTimeFormatter.BASIC_ISO_DATE));
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

        // 情景引用（仅 SCENARIO 模式）
        if (Constants.OPER_CODE.SCENARIO.equalsIgnoreCase(opCode)) {
            String safeRegularScenarioIdList = trimToNull(regularScenarioIdList);
            String safeRiskClassDecompScenarioIdList = trimToNull(riskClassDecompScenarioIdList);
            if (safeRegularScenarioIdList != null || safeRiskClassDecompScenarioIdList != null) {
                JSONObject scenarioRef = new JSONObject();
                scenarioRef.put("data_date", dataDate.format(DateTimeFormatter.BASIC_ISO_DATE));
                scenarioRef.put("batch_id", batchId);
                if (safeRegularScenarioIdList != null) {
                    scenarioRef.put("scenario_set_id", safeRegularScenarioIdList);
                }
                if (safeRiskClassDecompScenarioIdList != null) {
                    scenarioRef.put("risk_class_decomp_scenario_set_id", safeRiskClassDecompScenarioIdList);
                }
                payload.put("scenario_ref", scenarioRef);
            }
        }

        // 维度映射表使用输入表字段名。
        JSONObject tradeDimension = new JSONObject();
        for (BatchTradeDataLoader.TradeRow trade : chunkTrades) {
            String dimInstrumentId = trimToNull(trade.instrumentId);
            if (dimInstrumentId == null) {
                continue;
            }
            JSONObject dim = new JSONObject();
            if (trade.tradeDimensions != null) {
                for (Map.Entry<String, String> entry : trade.tradeDimensions.entrySet()) {
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
        return payload;
    }

    /**
     * 构建产品组成 JSON（用于 batch_item 记录）。
     */
    public static String buildProductMixJson(List<BatchTradeDataLoader.TradeRow> chunkTrades) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (BatchTradeDataLoader.TradeRow trade : chunkTrades) {
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
            List<BatchTradeDataLoader.TradeRow> chunkTrades) {
        List<MrMarketDataSliceService.TradeSliceSource> trades = new ArrayList<>();
        for (BatchTradeDataLoader.TradeRow trade : chunkTrades) {
            trades.add(new MrMarketDataSliceService.TradeSliceSource(trade.instrumentId, trade.tradeContentText));
        }
        return trades;
    }

    /**
     * 将曲线行转为市场数据切片源。
     */
    public static List<MrMarketDataSliceService.CurveSliceSource> toCurveSliceSources(
            List<BatchTradeDataLoader.CurveRow> curves) {
        List<MrMarketDataSliceService.CurveSliceSource> curveSources = new ArrayList<>();
        for (BatchTradeDataLoader.CurveRow curve : curves) {
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

    static Object parseJsonSafely(String text) {
        String safe = trimToNull(text);
        if (safe == null) {
            return null;
        }
        try {
            return JSON.parse(safe);
        } catch (Exception ignore) {
            return safe;
        }
    }

    static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
