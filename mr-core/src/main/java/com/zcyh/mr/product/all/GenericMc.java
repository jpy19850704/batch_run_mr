package com.zcyh.mr.product.all;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.mc.GenericMcEngine;
import com.zcyh.mr.product.basic.mc.McFrtbBuilder;
import com.zcyh.mr.product.basic.mc.McPricingContext;
import com.zcyh.mr.product.basic.mc.McProductResultBuilder;
import com.zcyh.mr.product.basic.mc.ValidationCollector;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 MC 单笔产品估值编排器。
 */
public final class GenericMc {
    private final LocalDate dataDate;
    private final GenericMcEngine engine;

    public GenericMc(LocalDate dataDate) {
        this(dataDate, new GenericMcEngine());
    }

    public GenericMc(LocalDate dataDate, GenericMcEngine engine) {
        this.dataDate = dataDate;
        this.engine = engine == null ? new GenericMcEngine() : engine;
    }

    public OptionMeasure price(Map<String, Object> tradeData, MarketData marketData) {
        GenericMcInfo input = GenericMcInfo.fromTradeMap(tradeData);
        return price(input, marketData, true);
    }

    public OptionMeasure price(GenericMcInfo input, MarketData marketData) {
        return price(input, marketData, true);
    }

    private OptionMeasure priceWithoutFrtb(GenericMcInfo input, MarketData marketData) {
        return price(input, marketData, false);
    }

    private OptionMeasure price(GenericMcInfo input, MarketData marketData, boolean includeFrtb) {
        try {
            input.normalize();
            ValidationCollector errors = new ValidationCollector();
            input.validate(marketData, errors);
            if (errors.hasErrors()) {
                return McProductResultBuilder.error(input, dataDate, errors.errors());
            }

            McPricingContext ctx = McPricingContext.fromInput(input, marketData, dataDate, errors);
            if (errors.hasErrors()) {
                return McProductResultBuilder.error(input, dataDate, errors.errors());
            }

            GenericMcEngine.PayoffResult payoffResult = engine.price(ctx, input.modelParams, input.payoffParams);
            if (payoffResult.errors != null && !payoffResult.errors.isEmpty()) {
                return McProductResultBuilder.error(input, dataDate, payoffResult.errors);
            }

            OptionMeasure measure = McProductResultBuilder.success(input, ctx, payoffResult);
            if (includeFrtb) {
                measure.sensitivityList = McFrtbBuilder.build(
                        input,
                        ctx,
                        measure,
                        marketData,
                        dataDate,
                        shockedMarketData -> priceWithoutFrtb(input, shockedMarketData));
            }
            return measure;
        } catch (Exception ex) {
            List<String> errors = new ArrayList<>();
            errors.add("通用 MC 计算异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return McProductResultBuilder.error(input, dataDate, errors);
        }
    }

    /**
     * 通用 MC 产品输入。字段直接平铺，保持与其他产品 Info 类一致。
     */
    public static final class GenericMcInfo {
        public String instrumentId;
        public String productCode;
        public String underlyingType;
        public String payoffType;
        public String modelType;
        public String buyOrSell;
        public String currencyCode;
        public String discountCurve;
        public String volatilitySurface;
        public String referenceCurve;
        public String baseCurrencyCode;
        public String underlyingCurrencyCode;
        public String baseDiscountCurve;
        public String underlyingDiscountCurve;
        public String frtbEqBucket;
        public String frtbCommBucket;
        public String frtbCommAsset;
        public String frtbCommLocation;
        public String underlyingCode;
        public String obsDates;
        public LocalDate startDate;
        public LocalDate settleDate;
        public LocalDate maturityDate;
        public Integer pathNb;
        public Boolean pathFlag;
        public Double notional;
        public Double barrier;
        public String barrierDirection;
        public Double payoffRate;
        public Double premiumRate;
        public Object modelParams;
        public Object payoffParams;

        public static GenericMcInfo fromTradeMap(Map<String, Object> trade) {
            GenericMcInfo input = new GenericMcInfo();
            if (trade == null) {
                return input;
            }
            input.instrumentId = text(trade.get("INSTRUMENT_ID"));
            input.productCode = text(trade.get("PRODUCT_CODE"));
            input.underlyingType = text(trade.get("UNDERLYING_TYPE"));
            input.payoffType = text(trade.get("PAYOFF_TYPE"));
            input.modelType = text(trade.get("MODEL_TYPE"));
            input.buyOrSell = text(trade.get("BUY_OR_SELL"));
            input.currencyCode = text(trade.get("CURRENCY_CODE"));
            input.discountCurve = text(trade.get("DISCOUNT_CURVE"));
            input.volatilitySurface = text(trade.get("VOLATILITY_SURFACE"));
            input.referenceCurve = text(trade.get("REFERENCE_CURVE"));
            input.baseCurrencyCode = text(trade.get("BASE_CURRENCY_CODE"));
            input.underlyingCurrencyCode = text(trade.get("UNDERLYING_CURRENCY_CODE"));
            input.baseDiscountCurve = text(trade.get("BASE_DISCOUNT_CURVE"));
            input.underlyingDiscountCurve = text(trade.get("UNDERLYING_DISCOUNT_CURVE"));
            input.frtbEqBucket = text(trade.get("FRTB_EQ_BUCKET"));
            input.frtbCommBucket = text(trade.get("FRTB_COMM_BUCKET"));
            input.frtbCommAsset = text(trade.get("FRTB_COMM_ASSET"));
            input.frtbCommLocation = text(trade.get("FRTB_COMM_LOCATION"));
            input.underlyingCode = text(trade.get("UNDERLYING_CODE"));
            input.obsDates = text(trade.get("OBS_DATES"));
            input.startDate = parseDate(trade.get("START_DATE"));
            input.settleDate = parseDate(trade.get("SETTLE_DATE"));
            input.maturityDate = parseDate(trade.get("MATURITY_DATE"));
            input.pathNb = toInteger(trade.get("PATH_NB"));
            input.pathFlag = toBoolean(trade.get("PATH_FLAG"));
            input.notional = toDoubleObject(trade.get("NOTIONAL"));
            input.barrier = toDoubleObject(trade.get("BARRIER"));
            input.barrierDirection = text(trade.get("BARRIER_DIRECTION"));
            input.payoffRate = toDoubleObject(trade.get("PAYOFF_RATE"));
            input.premiumRate = toDoubleObject(trade.get("PREMIUM_RATE"));
            input.modelParams = trade.get("MODEL_PARAMS");
            input.payoffParams = trade.get("PAYOFF_PARAMS");
            return input;
        }

        public void normalize() {
            productCode = upper(productCode);
            underlyingType = upper(underlyingType);
            payoffType = upper(payoffType);
            modelType = modelType == null || modelType.trim().isEmpty() ? "CONST_VOL" : upper(modelType);
            buyOrSell = upper(buyOrSell);
            barrierDirection = upper(barrierDirection);
            pathNb = pathNb == null ? 10000 : pathNb;
            pathFlag = Boolean.TRUE.equals(pathFlag);
            modelParams = normalizeParams(modelParams);
            payoffParams = normalizeParams(payoffParams);
            if (payoffParams == null && "AUTO_CALL".equals(payoffType)) {
                payoffParams = buildAutoCallPayoffParams(this);
            }
            if (modelParams == null) {
                modelParams = new LinkedHashMap<String, Object>();
            }
        }

        public void validate(MarketData marketData, ValidationCollector errors) {
            errors.requireText("INSTRUMENT_ID", instrumentId);
            errors.requireText("PRODUCT_CODE", productCode);
            errors.requireText("UNDERLYING_TYPE", underlyingType);
            errors.requireText("PAYOFF_TYPE", payoffType);
            errors.requireText("MODEL_TYPE", modelType);
            errors.requireText("CURRENCY_CODE", currencyCode);
            errors.requireText("OBS_DATES", obsDates);
            if (!"B".equals(buyOrSell) && !"S".equals(buyOrSell)) {
                errors.add("BUY_OR_SELL 仅支持 B/S");
            }
            if (pathNb == null || pathNb <= 0) {
                errors.add("PATH_NB 必须为正整数");
            }
            if (startDate == null) {
                errors.add("START_DATE 未设置");
            }
            if (settleDate == null) {
                errors.add("SETTLE_DATE 未设置");
            }
            if (startDate != null && settleDate != null && startDate.isAfter(settleDate)) {
                errors.add("START_DATE 不能晚于 SETTLE_DATE");
            }
            if (!validateUnderlyingType(errors)) {
                return;
            }
            validateCommonMarketData(marketData, errors);
            validateRiskMarketData(marketData, errors);
        }

        private boolean validateUnderlyingType(ValidationCollector errors) {
            if ("FX".equals(underlyingType) || "EQ".equals(underlyingType)
                    || "COMM".equals(underlyingType) || "IR".equals(underlyingType)) {
                return true;
            }
            if (underlyingType != null && !underlyingType.trim().isEmpty()) {
                errors.add("UNDERLYING_TYPE 仅支持 FX/EQ/COMM/IR");
            }
            return false;
        }

        private void validateCommonMarketData(MarketData marketData, ValidationCollector errors) {
            if (marketData == null) {
                errors.add("marketData 未设置");
                return;
            }
            if (discountCurve == null || discountCurve.trim().isEmpty()) {
                errors.add("DISCOUNT_CURVE 未设置");
            } else if (marketData.irSpot == null || !marketData.irSpot.containsKey(discountCurve)) {
                errors.add("缺少市场数据: DISCOUNT_CURVE(IR_SPOT)=" + discountCurve);
            }
        }

        private void validateRiskMarketData(MarketData marketData, ValidationCollector errors) {
            if (marketData == null) {
                return;
            }
            if ("FX".equals(underlyingType)) {
                errors.requireText("BASE_CURRENCY_CODE", baseCurrencyCode);
                errors.requireText("UNDERLYING_CURRENCY_CODE", underlyingCurrencyCode);
                requireIrCurve(marketData, errors, "BASE_DISCOUNT_CURVE", baseDiscountCurve);
                requireIrCurve(marketData, errors, "UNDERLYING_DISCOUNT_CURVE", underlyingDiscountCurve);
                if (volatilitySurface == null || volatilitySurface.trim().isEmpty()
                        || marketData.fxVol == null || !marketData.fxVol.containsKey(volatilitySurface)) {
                    errors.add("缺少市场数据: VOLATILITY_SURFACE(FX_VOL)=" + volatilitySurface);
                }
                return;
            }
            if ("EQ".equals(underlyingType)) {
                if (referenceCurve == null || referenceCurve.trim().isEmpty()
                        || marketData.eqSpot == null || !marketData.eqSpot.containsKey(referenceCurve)) {
                    errors.add("缺少市场数据: REFERENCE_CURVE(EQ_SPOT)=" + referenceCurve);
                }
                if (volatilitySurface == null || volatilitySurface.trim().isEmpty()
                        || marketData.eqVol == null || !marketData.eqVol.containsKey(volatilitySurface)) {
                    errors.add("缺少市场数据: VOLATILITY_SURFACE(EQ_VOL)=" + volatilitySurface);
                }
                return;
            }
            if ("COMM".equals(underlyingType)) {
                if (referenceCurve == null || referenceCurve.trim().isEmpty()
                        || marketData.commSpot == null || !marketData.commSpot.containsKey(referenceCurve)) {
                    errors.add("缺少市场数据: REFERENCE_CURVE(COMM_SPOT)=" + referenceCurve);
                }
                if (volatilitySurface == null || volatilitySurface.trim().isEmpty()
                        || marketData.commVol == null || !marketData.commVol.containsKey(volatilitySurface)) {
                    errors.add("缺少市场数据: VOLATILITY_SURFACE(COMM_VOL)=" + volatilitySurface);
                }
                return;
            }
            if ("IR".equals(underlyingType)) {
                errors.add("IR 标的通用 MC 路径上下文第一阶段尚未接入");
                return;
            }
            errors.add("通用 MC 暂不支持的 UNDERLYING_TYPE: " + underlyingType);
        }

        private void requireIrCurve(MarketData marketData, ValidationCollector errors, String fieldName, String curve) {
            if (curve == null || curve.trim().isEmpty()
                    || marketData.irSpot == null || !marketData.irSpot.containsKey(curve)) {
                errors.add("缺少市场数据: " + fieldName + "(IR_SPOT)=" + curve);
            }
        }
    }

    private static Map<String, Object> buildAutoCallPayoffParams(GenericMcInfo input) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BARRIER", input.barrier);
        params.put("BARRIER_DIRECTION", input.barrierDirection);
        params.put("PAYOFF_RATE", input.payoffRate);
        params.put("PREMIUM_RATE", input.premiumRate);
        params.put("NOTIONAL", input.notional);
        return params;
    }

    private static Object normalizeParams(Object raw) {
        if (!(raw instanceof String)) {
            return raw;
        }
        String text = ((String) raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return JSON.parseObject(text, JSONObject.class);
        }
        return raw;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.contains("-")) {
            return LocalDate.parse(text);
        }
        return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    private static Double toDoubleObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Double.valueOf(text);
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return "TRUE".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text) || "1".equals(text);
    }
}
