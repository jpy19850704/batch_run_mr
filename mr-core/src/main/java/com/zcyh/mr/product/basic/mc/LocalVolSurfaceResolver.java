package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.support.Convert;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.VolUtil;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * LOCAL_VOL 路径模型专用曲面解析器。
 */
public final class LocalVolSurfaceResolver implements McPricingContext.LocalVolResolver {
    private static final NormalDistribution STD_NORMAL = new NormalDistribution();

    private final String curveCode;
    private final String axis2Type;
    private final String termInterpolateType;
    private final String axis2InterpolateType;
    private final List<Map<String, Object>> vertexVol;

    private LocalVolSurfaceResolver(
            String curveCode,
            String axis2Type,
            String termInterpolateType,
            String axis2InterpolateType,
            List<Map<String, Object>> curveData,
            double initialSpot,
            int[] termDays,
            double[] dt1,
            double[] rd,
            double[] rf) {
        this.curveCode = curveCode;
        String normalizedAxis2Type = VolUtil.normalizeAxis2Type(axis2Type);
        this.termInterpolateType = VolUtil.normalizeTermInterpolateType(termInterpolateType);
        this.axis2InterpolateType = VolUtil.normalizeAxis2InterpolateType(axis2InterpolateType);
        if ("DELTA".equals(normalizedAxis2Type)) {
            Map<Integer, TermContext> termContexts = buildTermContexts(termDays, dt1, rd, rf);
            this.axis2Type = "STRIKE";
            this.vertexVol = convertDeltaSurfaceToStrike(curveData, initialSpot, termContexts);
        } else {
            this.axis2Type = normalizedAxis2Type;
            this.vertexVol = renameToVertex(curveData, this.axis2Type);
        }
    }

    public static LocalVolSurfaceResolver fromFx(FxVol.FxVolInfo info,
            double initialSpot, int[] termDays, double[] dt1, double[] rd, double[] rf) {
        return new LocalVolSurfaceResolver(info.curveCode, info.axis2Type, info.termInterpolateType, info.axis2InterpolateType,
                info.curveData, initialSpot, termDays, dt1, rd, rf);
    }

    public static LocalVolSurfaceResolver fromEq(EqVol.EqVolInfo info,
            double initialSpot, int[] termDays, double[] dt1, double[] rd, double[] rf) {
        return new LocalVolSurfaceResolver(info.curveCode, info.axis2Type, info.termInterpolateType, info.axis2InterpolateType,
                info.curveData, initialSpot, termDays, dt1, rd, rf);
    }

    public static LocalVolSurfaceResolver fromComm(CommVol.CommVolInfo info,
            double initialSpot, int[] termDays, double[] dt1, double[] rd, double[] rf) {
        return new LocalVolSurfaceResolver(info.curveCode, info.axis2Type, info.termInterpolateType, info.axis2InterpolateType,
                info.curveData, initialSpot, termDays, dt1, rd, rf);
    }

    static LocalVolSurfaceResolver createForTest(String curveCode, String axis2Type, String axis2InterpolateType,
            List<Map<String, Object>> curveData, double initialSpot, int[] termDays, double[] dt1,
            double[] rd, double[] rf) {
        return new LocalVolSurfaceResolver(curveCode, axis2Type, null, axis2InterpolateType,
                curveData, initialSpot, termDays, dt1, rd, rf);
    }

    @Override
    public double resolve(int days, double currentSpot, double initialSpot) {
        validateSpot(currentSpot, "当前标的价格");
        validateSpot(initialSpot, "初始标的价格");
        if ("MONEYNESS".equals(axis2Type)) {
            return resolveByAxis(days, currentSpot / initialSpot);
        }
        if ("STRIKE".equals(axis2Type)) {
            return resolveByAxis(days, currentSpot);
        }
        if ("NONE".equals(axis2Type)) {
            return resolveByAxis(days, 0.0);
        }
        throw new IllegalArgumentException("LOCAL_VOL 不支持的 AXIS2_TYPE: " + axis2Type);
    }

    @Override
    public String axis2Type() {
        return axis2Type;
    }

    public static double deltaToStrike(double forward, double sigma, double t, double rf, double delta) {
        if (!Double.isFinite(forward) || forward <= 0.0) {
            throw new IllegalArgumentException("LOCAL_VOL delta 转 strike 要求 forward 为正数");
        }
        if (!Double.isFinite(sigma) || sigma <= 0.0) {
            throw new IllegalArgumentException("LOCAL_VOL delta 转 strike 要求 sigma 为正数");
        }
        if (!Double.isFinite(t) || t <= 0.0) {
            throw new IllegalArgumentException("LOCAL_VOL delta 转 strike 要求期限为正数");
        }
        if (!Double.isFinite(delta) || delta <= 0.0 || delta >= 1.0) {
            throw new IllegalArgumentException("LOCAL_VOL delta 转 strike 要求 delta 位于 (0,1)");
        }
        if (Math.abs(delta - 0.5) < 1e-10) {
            return forward * Math.exp(0.5 * sigma * sigma * t);
        }
        double target = delta * Math.exp(rf * t);
        if (!Double.isFinite(target) || target <= 0.0 || target >= 1.0) {
            throw new IllegalArgumentException("LOCAL_VOL delta 转 strike 的标准正态分位点非法");
        }
        double sst = sigma * Math.sqrt(t);
        return forward * Math.exp(-STD_NORMAL.inverseCumulativeProbability(target) * sst
                + 0.5 * sigma * sigma * t);
    }

    private double resolveByAxis(int days, double axis2Value) {
        List<Map<String, Object>> termSlice = VolUtil.getVolCur(days, vertexVol,
                termInterpolateType, axis2InterpolateType);
        if ("NONE".equals(axis2Type)) {
            return firstVol(termSlice);
        }
        return interpolateAxis2(termSlice, axis2Value);
    }

    private static List<Map<String, Object>> renameToVertex(List<Map<String, Object>> curveData, String axis2Type) {
        if (curveData == null || curveData.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲面数据为空");
        }
        String axis2Field = VolUtil.resolveAxis2Field(axis2Type);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : curveData) {
            Object optionTerm = row.get("OPTION_TERM");
            Object volRate = row.get("VOLATILITY_RATE");
            if (optionTerm == null || volRate == null) {
                throw new IllegalArgumentException("LOCAL_VOL 曲面点位缺少 OPTION_TERM/VOLATILITY_RATE");
            }
            Object axis2 = "NONE".equals(axis2Type) ? 0.0 : row.get(axis2Field);
            if (!"NONE".equals(axis2Type) && axis2 == null) {
                throw new IllegalArgumentException("LOCAL_VOL 曲面点位缺少 OPTION_TERM/"
                        + axis2Field + "/VOLATILITY_RATE");
            }
            Map<String, Object> newMap = new HashMap<>(row);
            newMap.put("VERTEX1", optionTerm);
            newMap.put("VERTEX2", axis2);
            result.add(newMap);
        }
        return result;
    }

    private List<Map<String, Object>> convertDeltaSurfaceToStrike(
            List<Map<String, Object>> curveData,
            double initialSpot,
            Map<Integer, TermContext> termContexts) {
        validateSpot(initialSpot, "初始标的价格");
        if (curveData == null || curveData.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲面数据为空");
        }
        if (termContexts.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲线 [" + curveCode + "] 缺少期限上下文");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : curveData) {
            Object optionTerm = row.get("OPTION_TERM");
            Object deltaValue = row.get("DELTA");
            Object volRate = row.get("VOLATILITY_RATE");
            if (optionTerm == null || deltaValue == null || volRate == null) {
                throw new IllegalArgumentException("LOCAL_VOL delta 曲面点位缺少 OPTION_TERM/DELTA/VOLATILITY_RATE");
            }
            int days = Convert.toInt(optionTerm);
            TermContext termContext = termContexts.get(days);
            if (termContext == null) {
                throw new IllegalArgumentException("LOCAL_VOL 曲线 [" + curveCode + "] 缺少期限上下文: " + days);
            }
            double vol = Convert.toDouble(volRate);
            double delta = Convert.toDouble(deltaValue);
            double forward = initialSpot * Math.exp((termContext.rd - termContext.rf) * termContext.t);
            double strike = deltaToStrike(forward, vol, termContext.t, termContext.rf, delta);
            Map<String, Object> newMap = new HashMap<>(row);
            newMap.put("VERTEX1", days);
            newMap.put("VERTEX2", strike);
            newMap.put("STRIKE", strike);
            result.add(newMap);
        }
        return result;
    }

    private static Map<Integer, TermContext> buildTermContexts(int[] termDays, double[] dt1, double[] rd, double[] rf) {
        Map<Integer, TermContext> result = new HashMap<>();
        if (termDays == null || dt1 == null || rd == null || rf == null) {
            return result;
        }
        if (termDays.length != dt1.length || termDays.length != rd.length || termDays.length != rf.length) {
            throw new IllegalArgumentException("LOCAL_VOL 期限上下文数组长度不一致");
        }
        for (int i = 0; i < termDays.length; i++) {
            result.put(termDays[i], new TermContext(dt1[i], rd[i], rf[i]));
        }
        return result;
    }

    private double interpolateAxis2(List<Map<String, Object>> termSlice, double axis2Value) {
        TreeMap<Double, Double> points = new TreeMap<>();
        for (Map<String, Object> row : termSlice) {
            double axis2 = Convert.toDouble(row.get("VERTEX2"));
            double vol = Convert.toDouble(row.get("VOLATILITY_RATE"));
            if (Double.isFinite(axis2) && Double.isFinite(vol)) {
                points.put(axis2, vol);
            }
        }
        return interpolateAxis2(points, axis2Value);
    }

    private double interpolateAxis2(TreeMap<Double, Double> points, double axis2Value) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲面切片无有效点位");
        }
        Double[] x = points.keySet().toArray(new Double[0]);
        Double[] y = points.values().toArray(new Double[0]);
        return Interpolation.interpolate(x, y, axis2Value, axis2InterpolateType);
    }

    private static double firstVol(List<Map<String, Object>> termSlice) {
        if (termSlice == null || termSlice.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲面切片无有效点位");
        }
        return Convert.toDouble(termSlice.get(0).get("VOLATILITY_RATE"));
    }

    private static void validateSpot(double spot, String name) {
        if (!Double.isFinite(spot) || spot <= 0.0) {
            throw new IllegalArgumentException("LOCAL_VOL " + name + "必须为正数");
        }
    }

    private static final class TermContext {
        private final double t;
        private final double rd;
        private final double rf;

        private TermContext(double t, double rd, double rf) {
            if (!Double.isFinite(t) || t <= 0.0 || !Double.isFinite(rd) || !Double.isFinite(rf)) {
                throw new IllegalArgumentException("LOCAL_VOL 期限上下文非法");
            }
            this.t = t;
            this.rd = rd;
            this.rf = rf;
        }
    }
}
