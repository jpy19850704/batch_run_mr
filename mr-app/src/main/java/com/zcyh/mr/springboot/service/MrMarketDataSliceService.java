package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 市场数据切片服务。
 * 负责按交易引用的 curve_id 裁剪市场数据，并为每笔交易构建其引用的市场数据标识集合。
 */
@Service
public class MrMarketDataSliceService {
    private static final String FX_SPOT = "FX_SPOT";

    /**
     * 切片结果：包含裁剪后的曲线列表和每笔交易引用的市场数据标识。
     */
    public static class SliceResult {
        private final List<CurveSliceSource> curves;
        private final Map<String, Set<String>> tradeMarketDataKeys;

        public SliceResult(List<CurveSliceSource> curves, Map<String, Set<String>> tradeMarketDataKeys) {
            this.curves = curves;
            this.tradeMarketDataKeys = tradeMarketDataKeys;
        }

        /** 裁剪后的市场数据曲线列表 */
        public List<CurveSliceSource> getCurves() {
            return curves;
        }

        /** 每笔交易引用的市场数据标识，key=instrumentId，value=市场数据标识集合（格式：CURVE_TYPE:CURVE_ID） */
        public Map<String, Set<String>> getTradeMarketDataKeys() {
            return tradeMarketDataKeys;
        }
    }

    /**
     * 按交易显式引用的 curve_id 切出当前分片需要的市场数据，并额外保留全部 FX_SPOT。
     * 同时为每笔交易构建其引用的市场数据标识集合，用于后续场景估值的逐笔 fast-skip 判断。
     *
     * @param trades 当前分片交易
     * @param curves 全量市场数据
     * @return 切片结果，包含裁剪后的曲线和 per-trade 市场数据引用标识
     */
    public SliceResult sliceCurvesWithTradeKeys(List<TradeSliceSource> trades, List<CurveSliceSource> curves) {
        if (curves == null || curves.isEmpty()) {
            return new SliceResult(new ArrayList<CurveSliceSource>(),
                    new LinkedHashMap<String, Set<String>>());
        }

        Map<String, List<CurveSliceSource>> curveIndex = buildCurveIndex(curves);
        Map<String, Set<String>> curveIdToTypes = buildCurveIdToTypesMap(curves);
        List<CurveSliceSource> fxSpotCurves = collectFxSpotCurves(curves);
        Set<String> fxCurrencies = collectFxCurrencies(fxSpotCurves);
        // 逐笔交易收集 curve_id 和 market data key
        Set<String> allMatchedCurveIds = new LinkedHashSet<String>();
        Map<String, Set<String>> tradeMarketDataKeys = new LinkedHashMap<String, Set<String>>();
        collectPerTradeMarketDataKeys(trades, curveIndex.keySet(), curveIdToTypes,
                fxCurrencies, allMatchedCurveIds, tradeMarketDataKeys);

        // 组装裁剪后的曲线列表
        Map<String, CurveSliceSource> selected = new LinkedHashMap<String, CurveSliceSource>();
        appendCurves(selected, fxSpotCurves);
        for (String curveId : allMatchedCurveIds) {
            appendCurves(selected, curveIndex.get(curveId));
        }

        return new SliceResult(new ArrayList<CurveSliceSource>(selected.values()), tradeMarketDataKeys);
    }

    /**
     * 构建 curveId → 曲线列表 索引
     */
    private Map<String, List<CurveSliceSource>> buildCurveIndex(List<CurveSliceSource> curves) {
        Map<String, List<CurveSliceSource>> curveIndex = new LinkedHashMap<String, List<CurveSliceSource>>();
        for (CurveSliceSource curve : curves) {
            String curveId = trimToNull(curve.getCurveId());
            if (curveId == null) {
                continue;
            }
            List<CurveSliceSource> sameIdCurves = curveIndex.get(curveId);
            if (sameIdCurves == null) {
                sameIdCurves = new ArrayList<CurveSliceSource>();
                curveIndex.put(curveId, sameIdCurves);
            }
            sameIdCurves.add(curve);
        }
        return curveIndex;
    }

    /**
     * 构建 curveId → 市场数据类型集合 的反向映射
     */
    private Map<String, Set<String>> buildCurveIdToTypesMap(List<CurveSliceSource> curves) {
        Map<String, Set<String>> map = new LinkedHashMap<String, Set<String>>();
        for (CurveSliceSource curve : curves) {
            String curveId = trimToNull(curve.getCurveId());
            String type = trimToNull(curve.getMarketDataType());
            if (curveId == null || type == null) {
                continue;
            }
            if (FX_SPOT.equalsIgnoreCase(type)) {
                continue;
            }
            Set<String> types = map.get(curveId);
            if (types == null) {
                types = new LinkedHashSet<String>();
                map.put(curveId, types);
            }
            types.add(type.toUpperCase());
        }
        return map;
    }

    private List<CurveSliceSource> collectFxSpotCurves(List<CurveSliceSource> curves) {
        List<CurveSliceSource> fxSpotCurves = new ArrayList<CurveSliceSource>();
        for (CurveSliceSource curve : curves) {
            if (FX_SPOT.equalsIgnoreCase(trimToNull(curve.getMarketDataType()))) {
                fxSpotCurves.add(curve);
            }
        }
        return fxSpotCurves;
    }

    /**
     * 逐笔交易收集引用的 curve_id，并构建 TYPE:ID 格式的市场数据标识。
     *
     * @param trades             交易列表
     * @param knownCurveIds      已知 curve_id 集合
     * @param curveIdToTypes     curve_id → 市场数据类型集合的映射
     * @param fxCurrencies       FX_SPOT 中解析出的非 CNY 币种集合
     * @param allMatchedCurveIds 输出：所有交易匹配到的 curve_id 并集
     * @param tradeMarketDataKeys 输出：每笔交易引用的市场数据标识（TYPE:ID 格式）
     */
    private void collectPerTradeMarketDataKeys(
            List<TradeSliceSource> trades,
            Set<String> knownCurveIds,
            Map<String, Set<String>> curveIdToTypes,
            Set<String> fxCurrencies,
            Set<String> allMatchedCurveIds,
            Map<String, Set<String>> tradeMarketDataKeys) {

        if (trades == null || trades.isEmpty()) {
            return;
        }
        for (TradeSliceSource trade : trades) {
            String tradeText = trimToNull(trade.getTradeContentText());
            if (tradeText == null) {
                continue;
            }
            // 收集当前交易引用的 curve_id
            Set<String> tradeCurveIds = new LinkedHashSet<String>();
            Object tradeJson = parseTradeJson(trade);
            collectCurveIdsRecursively(tradeJson, knownCurveIds, tradeCurveIds);
            allMatchedCurveIds.addAll(tradeCurveIds);

            // 构建 TYPE:CURVE_ID 格式的市场数据标识
            Set<String> mdKeys = new LinkedHashSet<String>();
            if (containsFxCurrencyRecursively(tradeJson, fxCurrencies)) {
                mdKeys.add(FX_SPOT);
            }
            for (String curveId : tradeCurveIds) {
                Set<String> types = curveIdToTypes.get(curveId);
                if (types != null) {
                    for (String type : types) {
                        mdKeys.add(type + ":" + curveId);
                    }
                }
            }

            String instrumentId = trimToNull(trade.getInstrumentId());
            if (instrumentId != null) {
                tradeMarketDataKeys.put(instrumentId, mdKeys);
            }
        }
    }

    private Object parseTradeJson(TradeSliceSource trade) {
        try {
            return JSON.parse(trade.getTradeContentText());
        } catch (Exception ex) {
            throw new PayloadJsonParseException(
                    "交易JSON格式异常，instrumentId=" + safeText(trade.getInstrumentId()) + ": " + ex.getMessage(), ex);
        }
    }

    private void collectCurveIdsRecursively(Object node, Set<String> knownCurveIds, Set<String> matchedCurveIds) {
        if (node == null) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) node;
            for (Object value : jsonObject.values()) {
                collectCurveIdsRecursively(value, knownCurveIds, matchedCurveIds);
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray jsonArray = (JSONArray) node;
            for (int i = 0; i < jsonArray.size(); i++) {
                collectCurveIdsRecursively(jsonArray.get(i), knownCurveIds, matchedCurveIds);
            }
            return;
        }
        if (node instanceof String) {
            String value = trimToNull((String) node);
            if (value != null && knownCurveIds.contains(value)) {
                matchedCurveIds.add(value);
            }
        }
    }

    /**
     * 递归扫描交易输入中的币种信息。
     * 只要命中任意非 CNY 币种，即视为该交易存在 FX 风险暴露。
     */
    private boolean containsFxCurrencyRecursively(Object node, Set<String> fxCurrencies) {
        if (node == null || fxCurrencies == null || fxCurrencies.isEmpty()) {
            return false;
        }
        if (node instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) node;
            for (Object value : jsonObject.values()) {
                if (containsFxCurrencyRecursively(value, fxCurrencies)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof JSONArray) {
            JSONArray jsonArray = (JSONArray) node;
            for (int i = 0; i < jsonArray.size(); i++) {
                if (containsFxCurrencyRecursively(jsonArray.get(i), fxCurrencies)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof String) {
            String token = trimToNull((String) node);
            if (token == null) {
                return false;
            }
            String upper = token.toUpperCase();
            if (fxCurrencies.contains(upper)) {
                return true;
            }
            if (upper.contains(",")) {
                String[] parts = upper.split(",");
                for (String part : parts) {
                    String item = trimToNull(part);
                    if (item != null && fxCurrencies.contains(item.toUpperCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 从 FX_SPOT 市场数据中提取所有非 CNY 币种。
     */
    private Set<String> collectFxCurrencies(List<CurveSliceSource> fxSpotCurves) {
        Set<String> currencies = new LinkedHashSet<String>();
        if (fxSpotCurves == null || fxSpotCurves.isEmpty()) {
            return currencies;
        }
        for (CurveSliceSource curve : fxSpotCurves) {
            Object parsed = parseCurveJson(curve);
            if (parsed instanceof JSONObject) {
                collectFxCurrenciesFromCurve((JSONObject) parsed, currencies);
            } else if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof JSONObject) {
                        collectFxCurrenciesFromCurve((JSONObject) item, currencies);
                    }
                }
            }
        }
        return currencies;
    }

    private Object parseCurveJson(CurveSliceSource curve) {
        String safe = trimToNull(curve == null ? null : curve.getCurveContentText());
        if (safe == null) {
            throw new PayloadJsonParseException("市场曲线JSON格式异常，marketDataType="
                    + safeText(curve == null ? null : curve.getMarketDataType())
                    + ", curveId=" + safeText(curve == null ? null : curve.getCurveId())
                    + ": 内容为空");
        }
        try {
            return JSON.parse(safe);
        } catch (Exception ex) {
            throw new PayloadJsonParseException("市场曲线JSON格式异常，marketDataType="
                    + safeText(curve == null ? null : curve.getMarketDataType())
                    + ", curveId=" + safeText(curve == null ? null : curve.getCurveId())
                    + ": " + ex.getMessage(), ex);
        }
    }

    private void collectFxCurrenciesFromCurve(JSONObject curveJson, Set<String> currencies) {
        if (curveJson == null || currencies == null) {
            return;
        }
        JSONArray curveData = curveJson.getJSONArray("CURVE_DATA");
        if (curveData == null || curveData.isEmpty()) {
            return;
        }
        for (int i = 0; i < curveData.size(); i++) {
            JSONObject point = curveData.getJSONObject(i);
            if (point == null) {
                continue;
            }
            String currencyPair = trimToNull(point.getString("CURRENCY"));
            if (currencyPair == null) {
                continue;
            }
            String[] parts = currencyPair.toUpperCase().split("/");
            for (String part : parts) {
                String ccy = trimToNull(part);
                if (ccy != null && !"CNY".equalsIgnoreCase(ccy)) {
                    currencies.add(ccy.toUpperCase());
                }
            }
        }
    }

    private Object parseJsonSafely(String text) {
        String safe = trimToNull(text);
        if (safe == null) {
            return null;
        }
        try {
            return JSON.parse(safe);
        } catch (Exception ex) {
            return null;
        }
    }

    private void appendCurves(Map<String, CurveSliceSource> selected, List<CurveSliceSource> curves) {
        if (curves == null || curves.isEmpty()) {
            return;
        }
        for (CurveSliceSource curve : curves) {
            selected.put(buildCurveKey(curve), curve);
        }
    }

    private String buildCurveKey(CurveSliceSource curve) {
        return trimToNull(curve.getMarketDataType()) + "|" + trimToNull(curve.getCurveId());
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeText(String text) {
        String value = trimToNull(text);
        return value == null ? "" : value;
    }

    /**
     * 交易切片输入。
     */
    public static class TradeSliceSource {
        private final String instrumentId;
        private final String tradeContentText;

        public TradeSliceSource(String instrumentId, String tradeContentText) {
            this.instrumentId = instrumentId;
            this.tradeContentText = tradeContentText;
        }

        public String getInstrumentId() {
            return instrumentId;
        }

        public String getTradeContentText() {
            return tradeContentText;
        }
    }

    /**
     * 市场数据切片输入。
     */
    public static class CurveSliceSource {
        private final String marketDataType;
        private final String curveId;
        private final String curveContentText;

        public CurveSliceSource(String marketDataType, String curveId, String curveContentText) {
            this.marketDataType = marketDataType;
            this.curveId = curveId;
            this.curveContentText = curveContentText;
        }

        public String getMarketDataType() {
            return marketDataType;
        }

        public String getCurveId() {
            return curveId;
        }

        public String getCurveContentText() {
            return curveContentText;
        }
    }
}
