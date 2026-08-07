package com.zcyh.mr.marketdata.input;

import com.alibaba.fastjson2.annotation.JSONField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class MarketDataInputs {
    private MarketDataInputs() {
    }

    public abstract static class CurveInput {
        @JSONField(name = "CURVE_TYPE")
        @MarketInputField(name = "CURVE_TYPE", label = "市场数据类型", type = MarketFieldType.TEXT,
                order = 1, required = true)
        public String curveType;

        @JSONField(name = "DATA_DATE")
        @MarketInputField(name = "DATA_DATE", label = "数据日期", type = MarketFieldType.DATE,
                order = 2, required = true)
        public LocalDate dataDate;

        @JSONField(name = "CURVE_ID")
        @MarketInputField(name = "CURVE_ID", label = "曲线ID", type = MarketFieldType.TEXT,
                order = 3, required = true)
        public String curveId;
    }

    public abstract static class InterpolatedCurveInput extends CurveInput {
        @JSONField(name = "INTERPOLATE_TYPE")
        @MarketInputField(name = "INTERPOLATE_TYPE", label = "插值类型", type = MarketFieldType.TEXT,
                order = 4, allowedValues = {"LINEAR", "LINERVAR", "CUBICSPLINE", "FORWARD", "LOG"})
        public String interpolateType;
    }

    public static final class IrSpotInput extends InterpolatedCurveInput {
        @JSONField(name = "FREQ")
        @MarketInputField(name = "FREQ", label = "复利频率", type = MarketFieldType.TEXT, order = 5)
        public String frequency;

        @JSONField(name = "DAYCOUNT")
        @MarketInputField(name = "DAYCOUNT", label = "日计数规则", type = MarketFieldType.TEXT, order = 6)
        public String dayCount;

        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 7, required = true)
        public List<IrSpotPointInput> curveData = new ArrayList<IrSpotPointInput>();
    }

    public static final class CreditSpotInput extends InterpolatedCurveInput {
        @JSONField(name = "FREQ")
        @MarketInputField(name = "FREQ", label = "复利频率", type = MarketFieldType.TEXT, order = 5)
        public String frequency;

        @JSONField(name = "DAYCOUNT")
        @MarketInputField(name = "DAYCOUNT", label = "日计数规则", type = MarketFieldType.TEXT, order = 6)
        public String dayCount;

        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 7, required = true)
        public List<CreditSpotPointInput> curveData = new ArrayList<CreditSpotPointInput>();
    }

    public static final class FxSpotInput extends CurveInput {
        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 4, required = true)
        public List<FxSpotPointInput> curveData = new ArrayList<FxSpotPointInput>();
    }

    public static final class EqSpotInput extends InterpolatedCurveInput {
        @JSONField(name = "BASE_CURRENCY_CODE")
        @MarketInputField(name = "BASE_CURRENCY_CODE", label = "基础币种", type = MarketFieldType.TEXT,
                order = 5)
        public String baseCurrencyCode;

        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 6, required = true)
        public List<EqSpotPointInput> curveData = new ArrayList<EqSpotPointInput>();
    }

    public static final class CommSpotInput extends InterpolatedCurveInput {
        @JSONField(name = "BASE_CURRENCY_CODE")
        @MarketInputField(name = "BASE_CURRENCY_CODE", label = "基础币种", type = MarketFieldType.TEXT,
                order = 5)
        public String baseCurrencyCode;

        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 6, required = true)
        public List<CommSpotPointInput> curveData = new ArrayList<CommSpotPointInput>();
    }

    public static final class FixingInput {
        @JSONField(name = "CURVE_TYPE")
        @MarketInputField(name = "CURVE_TYPE", label = "市场数据类型", type = MarketFieldType.TEXT,
                order = 1, required = true, allowedValues = {"FIXING"})
        public String curveType;

        @JSONField(name = "DATA_DATE")
        @MarketInputField(name = "DATA_DATE", label = "数据日期", type = MarketFieldType.DATE,
                order = 2, required = true)
        public LocalDate dataDate;

        @JSONField(name = "FIXING_ID")
        @MarketInputField(name = "FIXING_ID", label = "定盘ID", type = MarketFieldType.TEXT,
                order = 3, required = true)
        public String fixingId;

        @JSONField(name = "INTERPOLATE_TYPE")
        @MarketInputField(name = "INTERPOLATE_TYPE", label = "插值类型", type = MarketFieldType.TEXT,
                order = 4, allowedValues = {"LINEAR", "LINERVAR", "CUBICSPLINE", "FORWARD", "LOG"})
        public String interpolateType;

        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 5, required = true)
        public List<FixingPointInput> curveData = new ArrayList<FixingPointInput>();
    }

    public abstract static class VolInput extends CurveInput {
        @JSONField(name = "TERM_INTERPOLATE_TYPE")
        @MarketInputField(name = "TERM_INTERPOLATE_TYPE", label = "期限插值类型",
                type = MarketFieldType.TEXT, order = 4, allowedValues = {"LINERVAR"})
        public String termInterpolateType;

        @JSONField(name = "AXIS2_TYPE")
        @MarketInputField(name = "AXIS2_TYPE", label = "第二轴类型", type = MarketFieldType.TEXT,
                order = 5, allowedValues = {"DELTA", "MONEYNESS", "STRIKE", "UNDERLYING_TERM", "NONE"})
        public String axis2Type;

        @JSONField(name = "AXIS2_INTERPOLATE_TYPE")
        @MarketInputField(name = "AXIS2_INTERPOLATE_TYPE", label = "第二轴插值类型",
                type = MarketFieldType.TEXT, order = 6,
                allowedValues = {"LINEAR", "LINERVAR", "CUBICSPLINE", "FORWARD", "LOG"})
        public String axis2InterpolateType;
    }

    public static final class IrVolInput extends VolInput {
        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 7, required = true)
        public List<IrVolPointInput> curveData = new ArrayList<IrVolPointInput>();
    }

    public static final class FxVolInput extends VolInput {
        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 7, required = true)
        public List<FxVolPointInput> curveData = new ArrayList<FxVolPointInput>();
    }

    public static final class EqVolInput extends VolInput {
        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 7, required = true)
        public List<EqVolPointInput> curveData = new ArrayList<EqVolPointInput>();
    }

    public static final class CommVolInput extends VolInput {
        @JSONField(name = "CURVE_DATA")
        @MarketInputField(name = "CURVE_DATA", label = "期限点", type = MarketFieldType.JSON,
                order = 7, required = true)
        public List<CommVolPointInput> curveData = new ArrayList<CommVolPointInput>();
    }

    public static final class IrSpotPointInput {
        @JSONField(name = "TERM")
        @MarketInputField(name = "TERM", label = "期限", type = MarketFieldType.INTEGER,
                order = 1, required = true)
        public Integer term;

        @JSONField(name = "RATE")
        @MarketInputField(name = "RATE", label = "利率", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal rate;
    }

    public static final class CreditSpotPointInput {
        @JSONField(name = "TERM")
        @MarketInputField(name = "TERM", label = "期限", type = MarketFieldType.INTEGER,
                order = 1, required = true)
        public Integer term;

        @JSONField(name = "RATE")
        @MarketInputField(name = "RATE", label = "信用利差", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal rate;
    }

    public static final class FxSpotPointInput {
        @JSONField(name = "CURRENCY")
        @MarketInputField(name = "CURRENCY", label = "货币对", type = MarketFieldType.TEXT,
                order = 1, required = true)
        public String currency;

        @JSONField(name = "RATE")
        @MarketInputField(name = "RATE", label = "汇率", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal rate;
    }

    public static final class EqSpotPointInput {
        @JSONField(name = "TERM")
        @MarketInputField(name = "TERM", label = "期限", type = MarketFieldType.INTEGER,
                order = 1, required = true)
        public Integer term;

        @JSONField(name = "EQ_PRICE")
        @MarketInputField(name = "EQ_PRICE", label = "权益价格", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal price;
    }

    public static final class CommSpotPointInput {
        @JSONField(name = "TERM")
        @MarketInputField(name = "TERM", label = "期限", type = MarketFieldType.INTEGER,
                order = 1, required = true)
        public Integer term;

        @JSONField(name = "COMM_PRICE")
        @MarketInputField(name = "COMM_PRICE", label = "商品价格", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal price;
    }

    public static final class FixingPointInput {
        @JSONField(name = "TRADE_DATE")
        @MarketInputField(name = "TRADE_DATE", label = "交易日期", type = MarketFieldType.DATE,
                order = 1, required = true)
        public LocalDate tradeDate;

        @JSONField(name = "FIXING_VALUE")
        @MarketInputField(name = "FIXING_VALUE", label = "定盘值", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal fixingValue;
    }

    public abstract static class VolPointInput {
        @JSONField(name = "OPTION_TERM")
        @MarketInputField(name = "OPTION_TERM", label = "期权期限", type = MarketFieldType.INTEGER,
                order = 1, required = true)
        public Integer optionTerm;

        @JSONField(name = "VOLATILITY_RATE")
        @MarketInputField(name = "VOLATILITY_RATE", label = "波动率", type = MarketFieldType.NUMBER,
                order = 3, required = true)
        public BigDecimal volatilityRate;
    }

    public static final class IrVolPointInput extends VolPointInput {
        @JSONField(name = "UNDERLYING_TERM")
        @MarketInputField(name = "UNDERLYING_TERM", label = "底层期限", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal underlyingTerm;
    }

    public static final class FxVolPointInput extends VolPointInput {
        @JSONField(name = "DELTA")
        @MarketInputField(name = "DELTA", label = "Delta", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal delta;
    }

    public static final class EqVolPointInput extends VolPointInput {
        @JSONField(name = "DELTA")
        @MarketInputField(name = "DELTA", label = "Delta", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal delta;
    }

    public static final class CommVolPointInput extends VolPointInput {
        @JSONField(name = "DELTA")
        @MarketInputField(name = "DELTA", label = "Delta", type = MarketFieldType.NUMBER,
                order = 2, required = true)
        public BigDecimal delta;
    }
}
