package com.zcyh.mr.marketdata.curvegeneration.converter;

import com.zcyh.mr.marketdata.CurveFunc;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.CurveInput;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.IrCurve;

import java.time.LocalDate;
import java.util.*;

/**
 * 零息曲线相减
 * 将收益率曲线减去无风险曲线，得到信用利差等
 * 先将两条源曲线分别转换到输出曲线的 daycount/freq 后再相减
 * 输出使用标准期限点（与 ZeroCurveBootstrap 一致）
 */
public class ZeroCurveSubtract {

    /**
     * 执行曲线相减
     *
     * @param input     输入（含 YC_CURVE_CODE 和 RF_CURVE_CODE）
     * @param curvePool 曲线池
     * @return 相减后的曲线数据
     */
    public List<IrCurve> subtract(CurveInput input,
            Map<String, List<IrCurve>> curvePool) {
        String ycCode = input.ycCurveCode;
        String rfCode = input.rfCurveCode;

        List<IrCurve> ycCurve = curvePool.getOrDefault(ycCode, Collections.emptyList());
        List<IrCurve> rfCurve = curvePool.getOrDefault(rfCode, Collections.emptyList());

        if (ycCurve.isEmpty() || rfCurve.isEmpty()) {
            throw new IllegalArgumentException("缺少依赖曲线 YC_CURVE_CODE=" + ycCode + ", RF_CURVE_CODE=" + rfCode);
        }

        String outputDcb = input.getOutputDayCount();
        String outputFreq = input.getOutputFreq();
        String interpolateType = input.getInterpolateType();

        // 将两条源曲线分别转换到输出单位
        double[][] ycConverted = convertCurveToOutputUnit(ycCurve, input.dataDate, outputFreq, outputDcb);
        double[][] rfConverted = convertCurveToOutputUnit(rfCurve, input.dataDate, outputFreq, outputDcb);

        // 在标准期限点上输出
        List<IrCurve> result = new ArrayList<>();
        for (int i = 0; i < ZeroCurveBootstrap.STANDARD_TERM_DAYS.length; i++) {
            double stdDays = ZeroCurveBootstrap.STANDARD_TERM_DAYS[i];
            double termYear = stdDays / 365.0;

            // 插值获取两条曲线在此期限的利率（已转换到输出单位）
            double ycRate = Interpolation.interpolate(
                    toDoubleObjArray(ycConverted[0]), toDoubleObjArray(ycConverted[1]), stdDays, interpolateType);
            double rfRate = Interpolation.interpolate(
                    toDoubleObjArray(rfConverted[0]), toDoubleObjArray(rfConverted[1]), stdDays, interpolateType);

            // 利差 = 收益率 - 无风险利率
            double spread = ycRate - rfRate;

            // 用输出目标的 freq/dcb 计算折现因子
            LocalDate endDate = input.dataDate.plusDays((long) stdDays);
            double df = CurveFunc.discountFactor(input.dataDate, endDate, spread, outputFreq, outputDcb);

            IrCurve pt = new IrCurve();
            pt.curveId = input.curveId;
            pt.dataDate = input.dataDate;
            pt.termCode = ZeroCurveBootstrap.STANDARD_TERM_CODES[i];
            pt.termDays = stdDays;
            pt.termYear = termYear;
            pt.rate = spread;
            pt.discountFactor = df;
            pt.curveDaycount = outputDcb;
            pt.curveFreq = outputFreq;
            pt.interpolateType = interpolateType;
            result.add(pt);
        }

        return result;
    }

    /**
     * 将源曲线利率转换到目标 daycount/freq
     *
     * @return double[2][]，[0]=termDays数组，[1]=转换后rate数组
     */
    private double[][] convertCurveToOutputUnit(List<IrCurve> curve,
            LocalDate dataDate, String targetFreq, String targetDcb) {
        List<IrCurve> sortedCurve = new ArrayList<>(curve);
        sortedCurve.sort(Comparator.comparingDouble(c -> c.termDays));

        double[] termDays = new double[sortedCurve.size()];
        double[] rates = new double[sortedCurve.size()];

        for (int i = 0; i < sortedCurve.size(); i++) {
            IrCurve pt = sortedCurve.get(i);
            termDays[i] = pt.termDays;
            double rate = pt.rate;

            // 读取源曲线自身的 freq/dcb
            String srcFreq = pt.curveFreq != null ? pt.curveFreq : "cont";
            String srcDcb = pt.curveDaycount != null ? pt.curveDaycount : "actual/365";

            // 如果源与目标不同，进行转换
            if (!srcFreq.equalsIgnoreCase(targetFreq) || !srcDcb.equalsIgnoreCase(targetDcb)) {
                LocalDate endDate = dataDate.plusDays((long) termDays[i]);
                rate = CurveFunc.convertIrRate(rate, dataDate, endDate,
                        srcFreq, srcDcb, targetFreq, targetDcb);
            }
            rates[i] = rate;
        }

        return new double[][] { termDays, rates };
    }

    /**
     * double[] 转 Double[] 用于插值函数
     */
    private Double[] toDoubleObjArray(double[] arr) {
        Double[] result = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = arr[i];
        }
        return result;
    }
}
