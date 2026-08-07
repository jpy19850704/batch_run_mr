package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.VolUtil;
import com.zcyh.mr.marketdata.VolSurfacePoint;
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
    private final List<VolSurfacePoint> vertexVol;

    private LocalVolSurfaceResolver(
            String curveCode,
            String axis2Type,
            String termInterpolateType,
            String axis2InterpolateType,
            List<VolSurfacePoint> curveData,
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
            this.vertexVol = new ArrayList<VolSurfacePoint>(curveData);
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
            List<VolSurfacePoint> curveData, double initialSpot, int[] termDays, double[] dt1,
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
        List<VolSurfacePoint> termSlice = VolUtil.getVolCur(days, vertexVol,
                termInterpolateType, axis2InterpolateType);
        if ("NONE".equals(axis2Type)) {
            return firstVol(termSlice);
        }
        return interpolateAxis2(termSlice, axis2Value);
    }

    private List<VolSurfacePoint> convertDeltaSurfaceToStrike(
            List<VolSurfacePoint> curveData,
            double initialSpot,
            Map<Integer, TermContext> termContexts) {
        validateSpot(initialSpot, "初始标的价格");
        if (curveData == null || curveData.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲面数据为空");
        }
        if (termContexts.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲线 [" + curveCode + "] 缺少期限上下文");
        }
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>();
        for (VolSurfacePoint row : curveData) {
            int days = row.getOptionTerm();
            TermContext termContext = termContexts.get(days);
            if (termContext == null) {
                throw new IllegalArgumentException("LOCAL_VOL 曲线 [" + curveCode + "] 缺少期限上下文: " + days);
            }
            double vol = row.getVolatilityRate();
            double delta = row.getAxis2Value();
            double forward = initialSpot * Math.exp((termContext.rd - termContext.rf) * termContext.t);
            double strike = deltaToStrike(forward, vol, termContext.t, termContext.rf, delta);
            result.add(new VolSurfacePoint(days, strike, vol));
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

    private double interpolateAxis2(List<VolSurfacePoint> termSlice, double axis2Value) {
        TreeMap<Double, Double> points = new TreeMap<>();
        for (VolSurfacePoint row : termSlice) {
            double axis2 = row.getAxis2Value();
            double vol = row.getVolatilityRate();
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

    private static double firstVol(List<VolSurfacePoint> termSlice) {
        if (termSlice == null || termSlice.isEmpty()) {
            throw new IllegalArgumentException("LOCAL_VOL 曲面切片无有效点位");
        }
        return termSlice.get(0).getVolatilityRate();
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
