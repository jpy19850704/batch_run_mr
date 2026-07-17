package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolUtil;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 波动率曲面市场数据处理器。
 */
final class VolMarketDataProcessor {
    private final LocalDate dataDate;
    private final MarketDataValidationCollector validationCollector;

    VolMarketDataProcessor(LocalDate dataDate, MarketDataValidationCollector validationCollector) {
        this.dataDate = dataDate;
        this.validationCollector = validationCollector;
    }

    void processCommVol(MarketData target, JSONObject marketJson, JSONArray curveData, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (!validateCurveId(curveType, curveId)) {
            return;
        }
        CommVol.CommVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, CommVol.CommVolInfo.class);
        try {
            normalizeVolSurfaceMeta(info);
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return;
        }
        info.curveData = MarketDataInputMapper.toCurveDataList(curveData);
        validateVolCurveData(info.curveData, curveType, curveId, info.axis2Type);
        info.pDataDate = dataDate;
        target.commVol.put(curveId, info);
    }

    void processFxVol(MarketData target, JSONObject marketJson, JSONArray curveData, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (!validateCurveId(curveType, curveId)) {
            return;
        }
        FxVol.FxVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, FxVol.FxVolInfo.class);
        try {
            normalizeVolSurfaceMeta(info);
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return;
        }
        info.curveData = MarketDataInputMapper.toCurveDataList(curveData);
        validateVolCurveData(info.curveData, curveType, curveId, info.axis2Type);
        info.pDataDate = dataDate;
        target.fxVol.put(curveId, info);
    }

    void processIrVol(MarketData target, JSONObject marketJson, JSONArray curveData, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (!validateCurveId(curveType, curveId)) {
            return;
        }
        IrVol.IrVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, IrVol.IrVolInfo.class);
        try {
            normalizeVolSurfaceMeta(info);
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return;
        }
        info.curveData = MarketDataInputMapper.toCurveDataList(curveData);
        validateVolCurveData(info.curveData, curveType, curveId, info.axis2Type);
        info.pDataDate = dataDate;
        target.irVol.put(curveId, info);
    }

    void processEqVol(MarketData target, JSONObject marketJson, JSONArray curveData, String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (!validateCurveId(curveType, curveId)) {
            return;
        }
        EqVol.EqVolInfo info = MarketDataInputMapper.parseCurveMeta(
                marketJson, EqVol.EqVolInfo.class);
        try {
            normalizeVolSurfaceMeta(info);
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return;
        }
        info.curveData = MarketDataInputMapper.toCurveDataList(curveData);
        validateVolCurveData(info.curveData, curveType, curveId, info.axis2Type);
        info.pDataDate = dataDate;
        target.eqVol.put(curveId, info);
    }

    private boolean validateCurveId(String curveType, String curveId) {
        if (curveId == null || curveId.isEmpty()) {
            validationCollector.error(curveType, "", "CURVE_ID 为空");
            return false;
        }
        return true;
    }

    private void validateVolCurveData(
            List<Map<String, Object>> curveData,
            String curveType,
            String curveId,
            String axis2Type) {
        if (curveData != null) {
            Iterator<Map<String, Object>> iterator = curveData.iterator();
            while (iterator.hasNext()) {
                Map<String, Object> point = iterator.next();
                Object optionTerm = point.get("OPTION_TERM");
                if (optionTerm == null) {
                    validationCollector.error(curveType, curveId,
                            "OPTION_TERM 为空, 点位被剔除");
                    iterator.remove();
                    continue;
                }
                if (!isNumber(optionTerm)) {
                    validationCollector.error(curveType, curveId,
                            "OPTION_TERM 不是数字: " + optionTerm + ", 点位被剔除");
                    iterator.remove();
                    continue;
                }

                String axis2Field = resolveVolAxis2Field(curveType, axis2Type);
                Object axis2 = axis2Field == null ? null : point.get(axis2Field);
                if (axis2Field != null && axis2 == null) {
                    validationCollector.error(curveType, curveId,
                            axis2Field + " 为空 (OPTION_TERM=" + optionTerm + "), 点位被剔除");
                    iterator.remove();
                    continue;
                }
                if (axis2Field != null && !isNumber(axis2)) {
                    validationCollector.error(curveType, curveId,
                            axis2Field + " 不是数字: " + axis2
                                    + " (OPTION_TERM=" + optionTerm + "), 点位被剔除");
                    iterator.remove();
                    continue;
                }

                Object volRate = point.get("VOLATILITY_RATE");
                if (volRate == null) {
                    validationCollector.error(curveType, curveId,
                            "VOLATILITY_RATE 为空, 点位被剔除");
                    iterator.remove();
                    continue;
                }
                if (!isNumber(volRate)) {
                    validationCollector.error(curveType, curveId,
                            "VOLATILITY_RATE 不是数字: " + volRate
                                    + " (OPTION_TERM=" + optionTerm + "), 点位被剔除");
                    iterator.remove();
                    continue;
                }
                double volValue = Double.parseDouble(volRate.toString());
                if (volValue > 1) {
                    validationCollector.warning(curveType, curveId,
                            "VOLATILITY_RATE 值大于 1: " + volValue
                                    + " (OPTION_TERM=" + optionTerm + "), 请确认是否正确");
                }
            }
        }
        if (curveData == null || curveData.isEmpty()) {
            validationCollector.error(curveType, curveId, "CURVE_DATA 为空");
        }
    }

    private boolean isNumber(Object value) {
        if (value instanceof Number) {
            return true;
        }
        try {
            Double.parseDouble(value.toString());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private String resolveVolAxis2Field(String curveType, String axis2Type) {
        if (EngineConstants.RF_TYPE.IR_VOL.equals(curveType)) {
            return VolUtil.resolveAxis2Field(axis2Type == null ? "UNDERLYING_TERM" : axis2Type);
        }
        if (EngineConstants.RF_TYPE.FX_VOL.equals(curveType)
                || EngineConstants.RF_TYPE.EQ_VOL.equals(curveType)
                || EngineConstants.RF_TYPE.COMM_VOL.equals(curveType)) {
            return VolUtil.resolveAxis2Field(axis2Type);
        }
        return null;
    }

    private void normalizeVolSurfaceMeta(FxVol.FxVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private void normalizeVolSurfaceMeta(IrVol.IrVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = MarketDataInputMapper.isBlank(info.axis2Type)
                ? "UNDERLYING_TERM" : VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private void normalizeVolSurfaceMeta(EqVol.EqVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }

    private void normalizeVolSurfaceMeta(CommVol.CommVolInfo info) {
        info.termInterpolateType = VolUtil.normalizeTermInterpolateType(info.termInterpolateType);
        info.axis2Type = VolUtil.normalizeAxis2Type(info.axis2Type);
        info.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(info.axis2InterpolateType);
    }
}
