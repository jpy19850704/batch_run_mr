package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Interpolation;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 市场数据加载器。
 * 负责解析基础市场数据和情景市场数据，并收集校验错误。
 */
public class MarketDataLoader {
    private static final String DEFAULT_IR_FREQ = "cont";
    private static final String DEFAULT_IR_DAY_COUNT = "actual/365";

    private final LocalDate dataDate;
    private final JSONArray validationErrors;
    private final String fxSpotBaseCurrency;

    public MarketDataLoader(LocalDate dataDate, JSONArray validationErrors) {
        this(dataDate, validationErrors, resolveDefaultFxSpotBaseCurrency());
    }

    public MarketDataLoader(LocalDate dataDate, JSONArray validationErrors, String fxSpotBaseCurrency) {
        this.dataDate = dataDate;
        this.validationErrors = validationErrors == null ? new JSONArray() : validationErrors;
        this.fxSpotBaseCurrency = normalizeCurrency(fxSpotBaseCurrency, "USD");
    }

    /**
     * 加载基础市场数据，并补齐基础利率曲线默认元信息。
     */
    public MarketData loadBaseMarketData(JSONArray marketDataArray) {
        MarketData marketData = new MarketData();
        parseMarketData(marketDataArray, marketData);
        normalizeBaseIrSpotMeta(marketData);
        return marketData;
    }

    /**
     * 加载情景市场数据，不执行基础市场数据的兜底补齐。
     */
    public MarketData loadScenarioMarketData(JSONArray marketDataArray) {
        MarketData marketData = new MarketData();
        parseMarketData(marketDataArray, marketData);
        return marketData;
    }

    /**
     * 解析市场数据数组，填充到指定的 MarketData 对象中。
     */
    public void parseMarketData(JSONArray marketDataArray, MarketData target) {
        if (marketDataArray == null || target == null) {
            return;
        }
        boolean fxFlag = true;
        System.err.println("[parseMarketData] 曲线总数=" + marketDataArray.size());
        for (int idx = 0; idx < marketDataArray.size(); idx++) {
            JSONObject marketJson = (JSONObject) marketDataArray.get(idx);
            String curveType = marketJson.getString("CURVE_TYPE");
            String curveIdIn = marketJson.getString("CURVE_ID");
            if (curveIdIn == null) {
                curveIdIn = marketJson.getString("FIXING_ID");
            }
            System.err.println("[parseMarketData] 处理曲线 " + idx + ": type=" + curveType + " id=" + curveIdIn);

            if (curveType == null || curveType.isEmpty()) {
                logMkError("UNKNOWN", "", "CURVE_TYPE 为空");
                continue;
            }

            if (Constants.RF_TYPE.FX_SPOT.equals(curveType)) {
                fxFlag = parseFxSpot(target, fxFlag, marketJson, curveType);
                continue;
            }

            parseNonFxSpot(target, marketJson, curveType, curveIdIn);
        }
    }

    private boolean parseFxSpot(MarketData target, boolean fxFlag, JSONObject marketJson, String curveType) {
        JSONArray dataArray = marketJson.getJSONArray("CURVE_DATA");
        if (dataArray == null || dataArray.isEmpty()) {
            logMkError(curveType, "", "CURVE_DATA 为空");
            return fxFlag;
        }

        FxSpot.FxSpotInfo fxSpotInfo = JSONObject.parseObject(marketJson.toString(), FxSpot.FxSpotInfo.class);
        if (fxFlag) {
            target.fxSpot = fxSpotInfo;
            fxFlag = false;
        }

        for (Object pointObj : dataArray) {
            JSONObject pointJson = (JSONObject) pointObj;
            String currency = pointJson.getString("CURRENCY");
            Object rate = pointJson.get("RATE");
            if (currency == null || currency.isEmpty()) {
                logMkError(curveType, "", "CURRENCY 为空, 点位被剔除");
                continue;
            }
            String normalizedCurrency = normalizeCurrencyPair(currency);
            if (normalizedCurrency == null) {
                logMkError(curveType, currency, "CURRENCY 货币对格式错误, 点位被剔除");
                continue;
            }
            if (rate == null) {
                logMkError(curveType, normalizedCurrency, "RATE 为空, 点位被剔除");
                continue;
            }
            if (!(rate instanceof Number)) {
                try {
                    Double.parseDouble(rate.toString());
                } catch (NumberFormatException e) {
                    logMkError(curveType, normalizedCurrency, "RATE 不是数字: " + rate + ", 点位被剔除");
                    continue;
                }
            }
            double rateValue = pointJson.getDoubleValue("RATE");
            if (Double.isNaN(rateValue) || Double.isInfinite(rateValue) || rateValue <= 0) {
                logMkError(curveType, normalizedCurrency, "RATE 必须大于 0: " + rate + ", 点位被剔除");
                continue;
            }
            if (target.fxSpot.curveData.containsKey(normalizedCurrency)) {
                logMkError(curveType, normalizedCurrency, "CURRENCY 重复, 点位被剔除");
                continue;
            }
            target.fxSpot.curveData.put(normalizedCurrency, rateValue);
        }

        if (!target.fxSpot.curveData.isEmpty()) {
            Set<String> baseCurrencies = new LinkedHashSet<>();
            for (String currencyPair : target.fxSpot.curveData.keySet()) {
                String baseCurrency = extractBaseCurrency(currencyPair);
                if (baseCurrency == null) {
                    continue;
                }
                baseCurrencies.add(baseCurrency);
            }
            if (!baseCurrencies.isEmpty()
                    && (baseCurrencies.size() > 1 || !baseCurrencies.contains(fxSpotBaseCurrency))) {
                logMkError(curveType, "", "FX_SPOT 兑换货币不一致: expectedBaseCurrency="
                        + fxSpotBaseCurrency + ", baseCurrencies=" + baseCurrencies);
            }
        }

        fxSpotInfo.pDataDate = dataDate;
        return fxFlag;
    }

    private void parseNonFxSpot(MarketData target, JSONObject marketJson, String curveType, String curveIdIn) {
        boolean isVol = Constants.RF_TYPE.FX_VOL.equals(curveType)
                || Constants.RF_TYPE.IR_VOL.equals(curveType)
                || Constants.RF_TYPE.EQ_VOL.equals(curveType)
                || Constants.RF_TYPE.COMM_VOL.equals(curveType);

        if (!isVol) {
            JSONArray curveData = marketJson.getJSONArray("CURVE_DATA");
            if (curveData == null || curveData.isEmpty()) {
                logMkError(curveType, Objects.toString(marketJson.get("CURVE_ID"), ""), "CURVE_DATA 为空");
                return;
            }
        }

        if (Constants.RF_TYPE.IR_SPOT.equals(curveType)
                || Constants.RF_TYPE.CREDIT_SPOT.equals(curveType)) {
            parseIrSpot(target, marketJson, curveType);
        } else if (Constants.RF_TYPE.FIXING.equals(curveType)) {
            parseFixing(target, marketJson, curveType);
        } else if (Constants.RF_TYPE.COMM_SPOT.equals(curveType)) {
            parseCommSpot(target, marketJson, curveType);
        } else if (Constants.RF_TYPE.EQ_SPOT.equals(curveType)) {
            parseEqSpot(target, marketJson, curveType);
        } else if (Constants.RF_TYPE.COMM_VOL.equals(curveType)) {
            parseCommVol(target, marketJson, curveType);
        } else if (Constants.RF_TYPE.FX_VOL.equals(curveType)) {
            parseFxVol(target, marketJson, curveType);
        } else if (Constants.RF_TYPE.IR_VOL.equals(curveType)) {
            parseIrVol(target, marketJson, curveType);
        } else if (Constants.RF_TYPE.EQ_VOL.equals(curveType)) {
            parseEqVol(target, marketJson, curveType);
        } else {
            logMkError(curveType, curveIdIn == null ? "" : curveIdIn, "不支持的 CURVE_TYPE");
        }
    }

    private void parseIrSpot(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            logMkError(curveType, "", "CURVE_ID 为空");
            return;
        }
        IrSpot.IrSpotInfo irSpotInfo = JSONObject.parseObject(marketJson.toString(), IrSpot.IrSpotInfo.class);
        try {
            normalizeSpotInterpolateType(irSpotInfo);
        } catch (IllegalArgumentException e) {
            logMkError(curveType, curveId, e.getMessage());
            return;
        }
        for (Object pointObj : marketJson.getJSONArray("CURVE_DATA")) {
            JSONObject pointJson = (JSONObject) pointObj;
            Object term = pointJson.get("TERM");
            Object rate = pointJson.get("RATE");
            if (term == null || rate == null) {
                logMkError(curveType, curveId, "TERM 或 RATE 为空, 点位被剔除");
                continue;
            }
            if (!(term instanceof Number)) {
                logMkError(curveType, curveId, "TERM 不是整数: " + term + ", 点位被剔除");
                continue;
            }
            if (!(rate instanceof Number)) {
                try {
                    Double.parseDouble(rate.toString());
                } catch (NumberFormatException e) {
                    logMkError(curveType, curveId, "RATE 不是数字: " + rate + " (TERM=" + term + "), 点位被剔除");
                    continue;
                }
            }
            double rateVal = pointJson.getDoubleValue("RATE");
            if (rateVal > 1) {
                logMkWarning(curveType, curveId, "RATE 值大于 1: " + rateVal + " (TERM=" + term + "), 请确认是否正确");
            }
            irSpotInfo.curveData.put(pointJson.getInteger("TERM"), rateVal);
        }
        irSpotInfo.pDataDate = dataDate;
        target.irSpot.put(curveId, irSpotInfo);
    }

    private void parseFixing(MarketData target, JSONObject marketJson, String curveType) {
        String fixingId = marketJson.getString("FIXING_ID");
        if (fixingId == null || fixingId.isEmpty()) {
            logMkError(curveType, "", "FIXING_ID 为空");
            return;
        }
        Fixing.FixingInfo fixingInfo = JSONObject.parseObject(marketJson.toString(), Fixing.FixingInfo.class);
        try {
            fixingInfo.interpolateType = normalizeInterpolateType(
                    fixingInfo.interpolateType, Interpolation.Type.FORWARD, "INTERPOLATE_TYPE");
        } catch (IllegalArgumentException e) {
            logMkError(curveType, fixingId, e.getMessage());
            return;
        }
        for (Object pointObj : marketJson.getJSONArray("CURVE_DATA")) {
            JSONObject pointJson = (JSONObject) pointObj;
            String fixDateStr = pointJson.getString("TRADE_DATE");
            Object fixVal = pointJson.get("FIXING_VALUE");
            if (fixDateStr == null || fixDateStr.isEmpty()) {
                logMkError(curveType, fixingId, "TRADE_DATE 为空, 点位被剔除");
                continue;
            }
            if (fixVal == null) {
                logMkError(curveType, fixingId, "FIXING_VALUE 为空 (TRADE_DATE=" + fixDateStr + "), 点位被剔除");
                continue;
            }
            try {
                LocalDate tradeDate = LocalDate.parse(fixDateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                fixingInfo.curveData.put(tradeDate, pointJson.getDoubleValue("FIXING_VALUE"));
            } catch (Exception e) {
                logMkError(curveType, fixingId, "TRADE_DATE 格式错误: " + fixDateStr + ", 点位被剔除");
            }
        }
        fixingInfo.pDataDate = dataDate;
        target.fixingRate.put(fixingId, fixingInfo);
    }

    private void parseCommSpot(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            logMkError(curveType, "", "CURVE_ID 为空");
            return;
        }
        CommSpot.CommSpotInfo commSpotInfo = JSONObject.parseObject(marketJson.toString(), CommSpot.CommSpotInfo.class);
        try {
            normalizeSpotInterpolateType(commSpotInfo);
        } catch (IllegalArgumentException e) {
            logMkError(curveType, curveId, e.getMessage());
            return;
        }
        for (Object pointObj : marketJson.getJSONArray("CURVE_DATA")) {
            JSONObject pointJson = (JSONObject) pointObj;
            Object term = pointJson.get("TERM");
            Object price = pointJson.get("COMM_PRICE");
            if (term == null || price == null) {
                logMkError(curveType, curveId, "TERM 或 COMM_PRICE 为空, 点位被剔除");
                continue;
            }
            if (!(term instanceof Number)) {
                logMkError(curveType, curveId, "TERM 不是整数: " + term + ", 点位被剔除");
                continue;
            }
            if (!(price instanceof Number)) {
                try {
                    Double.parseDouble(price.toString());
                } catch (NumberFormatException e) {
                    logMkError(curveType, curveId, "COMM_PRICE 不是数字: " + price + " (TERM=" + term + "), 点位被剔除");
                    continue;
                }
            }
            commSpotInfo.curveData.put(pointJson.getInteger("TERM"), pointJson.getDoubleValue("COMM_PRICE"));
        }
        commSpotInfo.pDataDate = dataDate;
        target.commSpot.put(curveId, commSpotInfo);
    }

    private void parseEqSpot(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            logMkError(curveType, "", "CURVE_ID 为空");
            return;
        }
        EqSpot.EqSpotInfo eqSpotInfo = JSONObject.parseObject(marketJson.toString(), EqSpot.EqSpotInfo.class);
        try {
            normalizeSpotInterpolateType(eqSpotInfo);
        } catch (IllegalArgumentException e) {
            logMkError(curveType, curveId, e.getMessage());
            return;
        }
        for (Object pointObj : marketJson.getJSONArray("CURVE_DATA")) {
            JSONObject pointJson = (JSONObject) pointObj;
            Object term = pointJson.get("TERM");
            Object price = pointJson.get("EQ_PRICE");
            if (term == null || price == null) {
                logMkError(curveType, curveId, "TERM 或 EQ_PRICE 为空, 点位被剔除");
                continue;
            }
            if (!(term instanceof Number)) {
                logMkError(curveType, curveId, "TERM 不是整数: " + term + ", 点位被剔除");
                continue;
            }
            if (!(price instanceof Number)) {
                try {
                    Double.parseDouble(price.toString());
                } catch (NumberFormatException e) {
                    logMkError(curveType, curveId, "EQ_PRICE 不是数字: " + price + " (TERM=" + term + "), 点位被剔除");
                    continue;
                }
            }
            eqSpotInfo.curveData.put(pointJson.getInteger("TERM"), pointJson.getDoubleValue("EQ_PRICE"));
        }
        eqSpotInfo.pDataDate = dataDate;
        target.eqSpot.put(curveId, eqSpotInfo);
    }

    private void parseCommVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            logMkError(curveType, "", "CURVE_ID 为空");
            return;
        }
        CommVol.CommVolInfo commVolInfo = JSONObject.parseObject(marketJson.toString(), CommVol.CommVolInfo.class);
        try {
            normalizeVolSurfaceMeta(commVolInfo);
        } catch (IllegalArgumentException e) {
            logMkError(curveType, curveId, e.getMessage());
            return;
        }
        validateVolCurveData(commVolInfo.curveData, curveType, curveId, commVolInfo.axis2Type);
        commVolInfo.pDataDate = dataDate;
        target.commVol.put(curveId, commVolInfo);
    }

    private void parseFxVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            logMkError(curveType, "", "CURVE_ID 为空");
            return;
        }
        FxVol.FxVolInfo fxVolInfo = JSONObject.parseObject(marketJson.toString(), FxVol.FxVolInfo.class);
        try {
            normalizeVolSurfaceMeta(fxVolInfo);
        } catch (IllegalArgumentException e) {
            logMkError(curveType, curveId, e.getMessage());
            return;
        }
        validateVolCurveData(fxVolInfo.curveData, curveType, curveId, fxVolInfo.axis2Type);
        fxVolInfo.pDataDate = dataDate;
        target.fxVol.put(curveId, fxVolInfo);
    }

    private void parseIrVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            logMkError(curveType, "", "CURVE_ID 为空");
            return;
        }
        IrVol.IrVolInfo irVolInfo = JSONObject.parseObject(marketJson.toString(), IrVol.IrVolInfo.class);
        try {
            normalizeVolSurfaceMeta(irVolInfo);
        } catch (IllegalArgumentException e) {
            logMkError(curveType, curveId, e.getMessage());
            return;
        }
        validateVolCurveData(irVolInfo.curveData, curveType, curveId, irVolInfo.axis2Type);
        irVolInfo.pDataDate = dataDate;
        target.irVol.put(curveId, irVolInfo);
    }

    private void parseEqVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            logMkError(curveType, "", "CURVE_ID 为空");
            return;
        }
        EqVol.EqVolInfo eqVolInfo = JSONObject.parseObject(marketJson.toString(), EqVol.EqVolInfo.class);
        try {
            normalizeVolSurfaceMeta(eqVolInfo);
        } catch (IllegalArgumentException e) {
            logMkError(curveType, curveId, e.getMessage());
            return;
        }
        validateVolCurveData(eqVolInfo.curveData, curveType, curveId, eqVolInfo.axis2Type);
        eqVolInfo.pDataDate = dataDate;
        target.eqVol.put(curveId, eqVolInfo);
    }

    /**
     * 仅对原始 market_data 的 IR 曲线兜底频率与日计数。
     * 该方法不会用于 scenario_data，避免情景数据被隐式修正。
     */
    private static void normalizeBaseIrSpotMeta(MarketData baseMarketData) {
        if (baseMarketData == null || baseMarketData.irSpot == null || baseMarketData.irSpot.isEmpty()) {
            return;
        }
        for (Map.Entry<String, IrSpot.IrSpotInfo> entry : baseMarketData.irSpot.entrySet()) {
            IrSpot.IrSpotInfo info = entry.getValue();
            if (info == null) {
                continue;
            }
            if (isBlank(info.freq)) {
                info.freq = DEFAULT_IR_FREQ;
            }
            if (isBlank(info.dayCount)) {
                info.dayCount = DEFAULT_IR_DAY_COUNT;
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void normalizeSpotInterpolateType(IrSpot.IrSpotInfo info) {
        info.interpolateType = normalizeInterpolateType(
                info.interpolateType, Interpolation.Type.LINEAR, "INTERPOLATE_TYPE");
    }

    private static void normalizeSpotInterpolateType(EqSpot.EqSpotInfo info) {
        info.interpolateType = normalizeInterpolateType(
                info.interpolateType, Interpolation.Type.LINEAR, "INTERPOLATE_TYPE");
    }

    private static void normalizeSpotInterpolateType(CommSpot.CommSpotInfo info) {
        info.interpolateType = normalizeInterpolateType(
                info.interpolateType, Interpolation.Type.LINEAR, "INTERPOLATE_TYPE");
    }

    private static String normalizeInterpolateType(String value, Interpolation.Type defaultType, String fieldName) {
        if (isBlank(value)) {
            return defaultType.name();
        }
        String normalized = value.trim();
        if (!Interpolation.isSupportedType(normalized)) {
            throw new IllegalArgumentException("不支持的 " + fieldName + ": " + value);
        }
        return normalized;
    }

    private static void normalizeVolSurfaceMeta(FxVol.FxVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private static void normalizeVolSurfaceMeta(IrVol.IrVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = isBlank(info.axis2Type) ? "UNDERLYING_TERM" : VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private static void normalizeVolSurfaceMeta(EqVol.EqVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private static void normalizeVolSurfaceMeta(CommVol.CommVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private static String resolveDefaultFxSpotBaseCurrency() {
        String value = Configure.getInstance().getValue(Constants.CFG.FX_SPOT_BASE_CODE);
        if (value == null || value.trim().isEmpty()) {
            return "USD";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCurrency(String currency, String defaultCurrency) {
        if (currency == null || currency.trim().isEmpty()) {
            return defaultCurrency;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private String extractBaseCurrency(String currencyPair) {
        if (currencyPair == null) {
            return null;
        }
        int separatorIndex = currencyPair.indexOf('/');
        if (separatorIndex <= 0 || separatorIndex == currencyPair.length() - 1) {
            return null;
        }
        return currencyPair.substring(0, separatorIndex).trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeCurrencyPair(String currencyPair) {
        if (currencyPair == null) {
            return null;
        }
        int separatorIndex = currencyPair.indexOf('/');
        if (separatorIndex <= 0 || separatorIndex == currencyPair.length() - 1) {
            return null;
        }
        String left = currencyPair.substring(0, separatorIndex).trim().toUpperCase(Locale.ROOT);
        String right = currencyPair.substring(separatorIndex + 1).trim().toUpperCase(Locale.ROOT);
        if (left.isEmpty() || right.isEmpty()) {
            return null;
        }
        return left + "/" + right;
    }

    /**
     * 波动率曲面 CURVE_DATA 逐点校验。
     */
    private void validateVolCurveData(List<Map<String, Object>> curveData, String curveType, String curveId,
            String axis2Type) {
        if (curveData != null) {
            java.util.Iterator<Map<String, Object>> iterator = curveData.iterator();
            while (iterator.hasNext()) {
                Map<String, Object> point = iterator.next();
                Object optionTerm = point.get("OPTION_TERM");
                if (optionTerm == null) {
                    logMkError(curveType, curveId, "OPTION_TERM 为空, 点位被剔除");
                    iterator.remove();
                    continue;
                }
                if (!(optionTerm instanceof Number)) {
                    try {
                        Double.parseDouble(optionTerm.toString());
                    } catch (NumberFormatException e) {
                        logMkError(curveType, curveId, "OPTION_TERM 不是数字: " + optionTerm + ", 点位被剔除");
                        iterator.remove();
                        continue;
                    }
                }

                String axis2Field = resolveVolAxis2Field(curveType, axis2Type);
                Object axis2 = axis2Field == null ? null : point.get(axis2Field);
                if (axis2Field != null && axis2 == null) {
                    logMkError(curveType, curveId,
                            axis2Field + " 为空 (OPTION_TERM=" + optionTerm + "), 点位被剔除");
                    iterator.remove();
                    continue;
                }
                if (axis2Field != null && !(axis2 instanceof Number)) {
                    try {
                        Double.parseDouble(axis2.toString());
                    } catch (NumberFormatException e) {
                        logMkError(curveType, curveId,
                                axis2Field + " 不是数字: " + axis2 + " (OPTION_TERM=" + optionTerm + "), 点位被剔除");
                        iterator.remove();
                        continue;
                    }
                }

                Object volRate = point.get("VOLATILITY_RATE");
                if (volRate == null) {
                    logMkError(curveType, curveId, "VOLATILITY_RATE 为空, 点位被剔除");
                    iterator.remove();
                    continue;
                }
                if (!(volRate instanceof Number)) {
                    try {
                        Double.parseDouble(volRate.toString());
                    } catch (NumberFormatException e) {
                        logMkError(curveType, curveId,
                                "VOLATILITY_RATE 不是数字: " + volRate + " (OPTION_TERM=" + optionTerm + "), 点位被剔除");
                        iterator.remove();
                        continue;
                    }
                }
                double volVal = Double.parseDouble(volRate.toString());
                if (volVal > 1) {
                    logMkWarning(curveType, curveId,
                            "VOLATILITY_RATE 值大于 1: " + volVal + " (OPTION_TERM=" + optionTerm + "), 请确认是否正确");
                }
            }
        }
        if (curveData == null || curveData.isEmpty()) {
            logMkError(curveType, curveId, "CURVE_DATA 为空");
        }
    }

    /**
     * 波动率曲面第二维字段按曲线类型唯一确定。
     */
    private String resolveVolAxis2Field(String curveType, String axis2Type) {
        if (Constants.RF_TYPE.IR_VOL.equals(curveType)) {
            return VolUtil.resolveAxis2Field(axis2Type == null ? "UNDERLYING_TERM" : axis2Type);
        }
        if (Constants.RF_TYPE.FX_VOL.equals(curveType)
                || Constants.RF_TYPE.EQ_VOL.equals(curveType)
                || Constants.RF_TYPE.COMM_VOL.equals(curveType)) {
            return VolUtil.resolveAxis2Field(axis2Type);
        }
        return null;
    }

    private void logMkError(String curveType, String curveId, String message) {
        logMkError(curveType, curveId, message, "ERROR");
    }

    private void logMkWarning(String curveType, String curveId, String message) {
        logMkError(curveType, curveId, message, "WARNING");
    }

    private void logMkError(String curveType, String curveId, String message, String level) {
        JSONObject errLog = new JSONObject();
        errLog.put("level", level);
        errLog.put("CURVE_TYPE", curveType);
        if (curveId != null && !curveId.isEmpty()) {
            errLog.put("CURVE_ID", curveId);
        }
        errLog.put("info", "市场数据校验: " + message);
        validationErrors.add(errLog);
    }
}
