package com.zcyh.mr.marketdata.curvegeneration.converter;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.CurveFunc;
import com.zcyh.mr.core.Interpolation;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.CurveInput;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.IrCurve;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 外汇隐含曲线构建
 * 输入远期汇率 + 基准零息曲线，通过利率平价推导隐含零息利率
 */
public class FxImpliedCurveConstruct {

    /** DAY_OFF=1 的特殊货币，其余全部默认 2 */
    private static final Set<String> DAY_OFF_ONE = new HashSet<>(Arrays.asList("CAD"));
    /** 利率标准频率默认值 */
    private static final String DEFAULT_FREQ = "cont";
    /** 利率标准日算规则默认值 */
    private static final String DEFAULT_DCB = "actual/365";
    /** 期限比较容差 */
    private static final double TERM_EPS = 1e-8;

    /**
     * 获取 DAY_OFF 默认值
     */
    private int getDefaultDayOff(String currencyCode) {
        return DAY_OFF_ONE.contains(currencyCode) ? 1 : 2;
    }

    /**
     * 构建外汇隐含曲线
     * 三阶段管线：收集 cont/365 隐含利率 → 可选自定义期限重采样 → freq/dcb 转换输出
     *
     * @param input     曲线输入（含 CURVE_DATA 远期汇率）
     * @param curvePool 已生成的曲线池（按 CURVE_ID 索引）
     * @param calendar  日历对象
     * @return 隐含曲线数据
     */
    public List<IrCurve> construct(CurveInput input,
            Map<String, List<IrCurve>> curvePool,
            Calendar calendar) {
        if (input.curveData == null || input.curveData.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate dataDate = input.dataDate;
        String calName = input.calendar != null ? input.calendar : "";
        String baseCurrency = input.baseCurrencyCode;
        String interpolateType = input.getInterpolateType();
        int dayOff = input.dayOff != null ? input.dayOff : getDefaultDayOff(baseCurrency);
        double fxSpot = input.fxSpot != null ? input.fxSpot : 0;

        // 获取基准零息曲线
        List<IrCurve> baseCurve = curvePool.getOrDefault(input.baseDiscountCurve, Collections.emptyList());
        if (baseCurve.isEmpty()) {
            throw new IllegalArgumentException("缺少依赖曲线 BASE_DISCOUNT_CURVE=" + input.baseDiscountCurve);
        }

        // 基准曲线按期限升序，确保插值输入有序
        List<IrCurve> sortedBaseCurve = new ArrayList<>(baseCurve);
        sortedBaseCurve.sort(Comparator.comparingDouble(c -> c.termDays));
        boolean allSameBaseUnit = isSameRateUnit(sortedBaseCurve);

        Double[] baseTermDays = sortedBaseCurve.stream()
                .map(c -> c.termDays)
                .toArray(Double[]::new);
        Double[] baseRates = sortedBaseCurve.stream()
                .map(c -> c.rate)
                .toArray(Double[]::new);
        Double[] baseRatesCont = null;
        if (!allSameBaseUnit) {
            baseRatesCont = new Double[sortedBaseCurve.size()];
            for (int i = 0; i < sortedBaseCurve.size(); i++) {
                IrCurve pt = sortedBaseCurve.get(i);
                baseRatesCont[i] = convertToCont365(pt.rate, dataDate, pt.termDays, pt.curveFreq, pt.curveDaycount);
            }
        }

        // ── 第 1 阶段：计算各期限的 cont/365 隐含利率 ──
        List<double[]> contPoints = new ArrayList<>(); // [termDays, fwdRate]
        double spot0 = fxSpot;

        for (JSONObject jo : input.curveData) {
            String termCode = jo.getString("TERM_CODE");
            double fwdRate = jo.getDoubleValue("FWD_RATE");
            if (fwdRate <= 0) {
                throw new IllegalArgumentException("FWD_RATE 必须大于 0, TERM_CODE=" + termCode);
            }
            LocalDate adjustDate = resolveAdjustDate(termCode, dataDate, calName, dayOff, calendar);
            double termDays = ChronoUnit.DAYS.between(dataDate, adjustDate);

            // ON 期限的远期汇率作为 spot 基准
            if ("ON".equals(termCode)) {
                spot0 = fwdRate;
            }

            if (termDays <= 0)
                continue;
            contPoints.add(new double[] { termDays, fwdRate });
        }
        if (spot0 <= 0) {
            throw new IllegalArgumentException("FX_SPOT/ON 期限远期汇率必须大于 0");
        }
        if (contPoints.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 termDays 排序并去重，计算 cont/365 隐含利率
        contPoints.sort(Comparator.comparingDouble(p -> p[0]));
        Set<Double> seen = new HashSet<>();
        List<double[]> uniqueContRates = new ArrayList<>();

        for (double[] pt : contPoints) {
            double termDays = pt[0];
            double fwdRate = pt[1];
            if (!seen.add(termDays))
                continue;

            double termYear = termDays / 365.0;
            double baseRate = resolveBaseRateCont365(sortedBaseCurve, baseTermDays, baseRates, baseRatesCont,
                    dataDate, termDays, interpolateType, allSameBaseUnit);
            double impliedRate = baseRate - Math.log(fwdRate / spot0) / termYear;
            uniqueContRates.add(new double[] { termDays, impliedRate });
        }

        // ── 第 2 阶段：如果指定了自定义期限，在 cont/365 下直接重采样 ──
        double[] customDays = input.getOutputTermDaysArray();
        if (customDays != null && customDays.length > 0) {
            Double[] srcDays = uniqueContRates.stream().map(p -> p[0]).toArray(Double[]::new);
            Double[] srcRates = uniqueContRates.stream().map(p -> p[1]).toArray(Double[]::new);
            uniqueContRates.clear();
            for (double days : customDays) {
                double rate = Interpolation.interpolate(srcDays, srcRates, days, interpolateType);
                uniqueContRates.add(new double[] { days, rate });
            }
        }

        // ── 第 3 阶段：按目标 freq/dcb 转换并输出 IrCurve ──
        List<IrCurve> result = new ArrayList<>();
        String outputDcb = input.getOutputDayCount();
        String outputFreq = input.getOutputFreq();
        String internalDcb = "actual/365";
        String internalFreq = "cont";
        boolean needConvert = !internalFreq.equalsIgnoreCase(outputFreq)
                || !internalDcb.equalsIgnoreCase(outputDcb);

        for (double[] pt : uniqueContRates) {
            double termDays = pt[0];
            double contRate = pt[1];
            double termYear = termDays / 365.0;
            double df = Math.exp(-contRate * termYear);

            double outputRate = contRate;
            if (needConvert) {
                LocalDate endDate = dataDate.plusDays((long) termDays);
                outputRate = CurveFunc.convertIrRate(contRate, dataDate, endDate,
                        internalFreq, internalDcb, outputFreq, outputDcb);
            }

            IrCurve irPt = new IrCurve();
            irPt.curveId = input.curveId;
            irPt.dataDate = dataDate;
            irPt.termCode = (long) termDays + "D";
            irPt.termDays = termDays;
            irPt.termYear = termYear;
            irPt.rate = outputRate;
            irPt.discountFactor = df;
            irPt.curveDaycount = outputDcb;
            irPt.curveFreq = outputFreq;
            irPt.interpolateType = interpolateType;
            result.add(irPt);
        }

        return result;
    }

    /**
     * 计算基准曲线在目标期限下的 cont/365 利率
     * 同单位曲线优先按原单位插值后统一转换；混合单位时先逐点转换再插值
     */
    private double resolveBaseRateCont365(List<IrCurve> baseCurve, Double[] baseTermDays, Double[] baseRates,
            Double[] baseRatesCont, LocalDate dataDate, double targetTermDays, String interpolateType,
            boolean allSameBaseUnit) {
        for (IrCurve pt : baseCurve) {
            if (isSameTerm(pt.termDays, targetTermDays)) {
                return convertToCont365(pt.rate, dataDate, targetTermDays, pt.curveFreq, pt.curveDaycount);
            }
        }

        if (allSameBaseUnit) {
            IrCurve first = baseCurve.get(0);
            String srcFreq = normalizeFreq(first.curveFreq);
            String srcDcb = normalizeDayCount(first.curveDaycount);
            double interpolatedRate = Interpolation.interpolate(baseTermDays, baseRates, targetTermDays, interpolateType);
            return convertToCont365(interpolatedRate, dataDate, targetTermDays, srcFreq, srcDcb);
        }

        return Interpolation.interpolate(baseTermDays, baseRatesCont, targetTermDays, interpolateType);
    }

    /**
     * 判断曲线点的利率单位是否一致
     */
    private boolean isSameRateUnit(List<IrCurve> curve) {
        if (curve.isEmpty()) {
            return true;
        }
        String freq = normalizeFreq(curve.get(0).curveFreq);
        String dcb = normalizeDayCount(curve.get(0).curveDaycount);
        for (IrCurve pt : curve) {
            if (!freq.equalsIgnoreCase(normalizeFreq(pt.curveFreq))
                    || !dcb.equalsIgnoreCase(normalizeDayCount(pt.curveDaycount))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将任意单位利率转换为 cont + actual/365
     */
    private double convertToCont365(double rate, LocalDate dataDate, double termDays, String srcFreq, String srcDcb) {
        String freq = normalizeFreq(srcFreq);
        String dcb = normalizeDayCount(srcDcb);
        if (DEFAULT_FREQ.equalsIgnoreCase(freq) && DEFAULT_DCB.equalsIgnoreCase(dcb)) {
            return rate;
        }
        LocalDate endDate = dataDate.plusDays(Math.round(termDays));
        return CurveFunc.convertIrRate(rate, dataDate, endDate, freq, dcb, DEFAULT_FREQ, DEFAULT_DCB);
    }

    /**
     * 归一化频率，空值回落到标准频率
     */
    private String normalizeFreq(String freq) {
        return (freq == null || freq.trim().isEmpty()) ? DEFAULT_FREQ : freq;
    }

    /**
     * 归一化日算规则，空值回落到标准日算规则
     */
    private String normalizeDayCount(String dcb) {
        return (dcb == null || dcb.trim().isEmpty()) ? DEFAULT_DCB : dcb;
    }

    /**
     * 判断期限是否为同一点
     */
    private boolean isSameTerm(double left, double right) {
        return Math.abs(left - right) < TERM_EPS;
    }

    /**
     * 根据 termCode 计算起息日调整后日期
     * ON/SN/TN 为 FX 市场特殊处理，标准 termCode 委托给 Calendar.resolveTermDate
     */
    private LocalDate resolveAdjustDate(String termCode, LocalDate startDate,
            String calName, int dayOff, Calendar calendar) {
        if ("ON".equals(termCode)) {
            return startDate;
        }
        if ("SN".equals(termCode)) {
            int skipDays = dayOff == 2 ? 3 : (dayOff == 1 ? 2 : 1);
            return calendar.addBusinessDays(calName, startDate, skipDays);
        }
        if ("TN".equals(termCode)) {
            int skipDays = dayOff == 2 ? 1 : 0;
            return calendar.addBusinessDays(calName, startDate, skipDays);
        }

        // 标准 termCode：先跳 dayOff 个工作日到起息日，再加期限
        LocalDate spotDate = calendar.addBusinessDays(calName, startDate, dayOff);
        return calendar.resolveTermDate(calName, spotDate, termCode);
    }
}
