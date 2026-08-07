package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolSurfacePoint;
import com.zcyh.mr.marketdata.VolUtil;
import com.zcyh.mr.marketdata.input.MarketDataInputs;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class VolMarketDataProcessor {
    private final LocalDate dataDate;
    private final MarketDataValidationCollector validationCollector;

    VolMarketDataProcessor(LocalDate dataDate, MarketDataValidationCollector validationCollector) {
        this.dataDate = dataDate;
        this.validationCollector = validationCollector;
    }

    void processCommVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        CommVol.CommVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, CommVol.CommVolInfo.class);
        if (!normalizeVolSurfaceMeta(info, curveType, curveId, "DELTA")) {
            return;
        }
        MarketDataInputs.CommVolInput input = marketJson.to(MarketDataInputs.CommVolInput.class);
        info.curveData = toVolSurfacePoints(input.curveData, point -> point.delta);
        collectVolWarnings(info.curveData, curveType, curveId);
        info.pDataDate = dataDate;
        target.commVol.put(curveId, info);
    }

    void processFxVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        FxVol.FxVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, FxVol.FxVolInfo.class);
        if (!normalizeVolSurfaceMeta(info, curveType, curveId, "DELTA")) {
            return;
        }
        MarketDataInputs.FxVolInput input = marketJson.to(MarketDataInputs.FxVolInput.class);
        info.curveData = toVolSurfacePoints(input.curveData, point -> point.delta);
        collectVolWarnings(info.curveData, curveType, curveId);
        info.pDataDate = dataDate;
        target.fxVol.put(curveId, info);
    }

    void processIrVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        IrVol.IrVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, IrVol.IrVolInfo.class);
        if (!normalizeVolSurfaceMeta(info, curveType, curveId, "UNDERLYING_TERM")) {
            return;
        }
        MarketDataInputs.IrVolInput input = marketJson.to(MarketDataInputs.IrVolInput.class);
        info.curveData = toVolSurfacePoints(input.curveData, point -> point.underlyingTerm);
        collectVolWarnings(info.curveData, curveType, curveId);
        info.pDataDate = dataDate;
        target.irVol.put(curveId, info);
    }

    void processEqVol(MarketData target, JSONObject marketJson, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        EqVol.EqVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, EqVol.EqVolInfo.class);
        if (!normalizeVolSurfaceMeta(info, curveType, curveId, "DELTA")) {
            return;
        }
        MarketDataInputs.EqVolInput input = marketJson.to(MarketDataInputs.EqVolInput.class);
        info.curveData = toVolSurfacePoints(input.curveData, point -> point.delta);
        collectVolWarnings(info.curveData, curveType, curveId);
        info.pDataDate = dataDate;
        target.eqVol.put(curveId, info);
    }

    private boolean normalizeVolSurfaceMeta(
            Object info,
            String curveType,
            String curveId,
            String defaultAxis2Type) {
        try {
            if (info instanceof IrVol.IrVolInfo) {
                IrVol.IrVolInfo value = (IrVol.IrVolInfo) info;
                normalize(value, defaultAxis2Type);
            } else if (info instanceof FxVol.FxVolInfo) {
                FxVol.FxVolInfo value = (FxVol.FxVolInfo) info;
                normalize(value, defaultAxis2Type);
            } else if (info instanceof EqVol.EqVolInfo) {
                EqVol.EqVolInfo value = (EqVol.EqVolInfo) info;
                normalize(value, defaultAxis2Type);
            } else if (info instanceof CommVol.CommVolInfo) {
                CommVol.CommVolInfo value = (CommVol.CommVolInfo) info;
                normalize(value, defaultAxis2Type);
            }
            return true;
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return false;
        }
    }

    private void normalize(IrVol.IrVolInfo info, String defaultAxis2Type) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = normalizeAxis2Type(info.axis2Type, defaultAxis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private void normalize(FxVol.FxVolInfo info, String defaultAxis2Type) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = normalizeAxis2Type(info.axis2Type, defaultAxis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private void normalize(EqVol.EqVolInfo info, String defaultAxis2Type) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = normalizeAxis2Type(info.axis2Type, defaultAxis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private void normalize(CommVol.CommVolInfo info, String defaultAxis2Type) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = normalizeAxis2Type(info.axis2Type, defaultAxis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private String normalizeAxis2Type(String axis2Type, String defaultAxis2Type) {
        String value = MarketDataInputMapper.isBlank(axis2Type) ? defaultAxis2Type : axis2Type;
        return VolUtil.normalizeAxis2Type(value);
    }

    private <T extends MarketDataInputs.VolPointInput> List<VolSurfacePoint> toVolSurfacePoints(
            List<T> inputPoints,
            Function<T, BigDecimal> axis2Extractor) {
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>(inputPoints.size());
        for (T point : inputPoints) {
            BigDecimal axis2Value = axis2Extractor.apply(point);
            result.add(new VolSurfacePoint(
                    point.optionTerm,
                    axis2Value.doubleValue(),
                    point.volatilityRate.doubleValue()));
        }
        return result;
    }

    private void collectVolWarnings(
            List<VolSurfacePoint> curveData,
            String curveType,
            String curveId) {
        for (VolSurfacePoint point : curveData) {
            if (point.getVolatilityRate() > 1.0d) {
                validationCollector.warning(curveType, curveId,
                        "VOLATILITY_RATE值大于1: " + point.getVolatilityRate()
                                + " (OPTION_TERM=" + point.getOptionTerm() + ")，请确认是否正确");
            }
        }
    }
}
