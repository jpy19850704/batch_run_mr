package com.zcyh.mr.product.basic.scf;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.calendar.CashflowUtils;
import com.zcyh.mr.marketdata.CurveFunc;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.CommUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

/**
 * 固息债、浮息债估值类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/12 10:31
 */

public class StructuredCashflow {
    LinkedList<LocalDate> cfDatelistOri;
    LinkedList<LocalDate> cfDatelist;
    LinkedList<Cashflow> cashflowList;
    private LocalDate dataDate;
    private ScfInfo scfInfo;
    private MarketData marketData;
    private Calendar cal;
    private final List<String> warnings = new ArrayList<>();
    HashMap<LocalDate, LinkedList<ResetDateInfo>> resetDateInfosMap;

    public StructuredCashflow(LocalDate dataDate, ScfInfo scfInfo, MarketData marketData, Calendar calendar) {
        this.dataDate = dataDate;
        this.scfInfo = scfInfo;
        this.marketData = marketData;

        this.cal = calendar;
        this.cashflowList = new LinkedList<>();

        // 生成现金流的理论日期
        cfDatelistOri = Cashflows.generateCashflowDays(scfInfo.issueDate, scfInfo.maturityDate,
                CashflowUtils.convertFreq(
                        scfInfo.payFreq),
                CashflowUtils.att2DateGenerationRule(
                        scfInfo.interestStub));
        // 根据节假日规则对付息日期进行调整,发行日不调整
        for (int i = 0, n = cfDatelistOri.size(); i < n; i++) {
            if (i == 0) {
                cfDatelist = new LinkedList<>();
                cfDatelist.add(cfDatelistOri.get(0));
                continue;
            }
            LocalDate adjustDate = cal.getBusinessDay(scfInfo.settleCalendar, cfDatelistOri.get(i),
                    CashflowUtils.attr2BusinessDayConvention(scfInfo.settleRule),
                    scfInfo.settleDayoff);
            cfDatelist.add(adjustDate);
        }
        validateAdjustedCashflowDates();

        // 浮息债生成利率重置日期；key值为每次的付息日期
        resetDateInfosMap = new HashMap<>();
        if (!"fixed".equalsIgnoreCase(scfInfo.interestType)) {
            Period payFreq = CashflowUtils.convertFreq(scfInfo.payFreq);
            if (payFreq != null && payFreq.isZero()) {
                throw new IllegalArgumentException("浮息现金流不支持到期一次性支付频率: PAY_FREQ=" + scfInfo.payFreq);
            }
            resetDateInfosMap = generateResetDays(this.cfDatelist);
        }

        // 摊销日期归一化：映射到最近的付息日，最近的是上一个付息日则忽略
        normalizeAmortizationSchedule();

    }

    private void validateAdjustedCashflowDates() {
        for (int i = 0, n = cfDatelist.size(); i < n - 1; i++) {
            LocalDate prePaymentDate = cfDatelist.get(i);
            LocalDate paymentDate = cfDatelist.get(i + 1);
            if (!prePaymentDate.isBefore(paymentDate)) {
                throw new IllegalArgumentException("现金流支付日期调整后非递增: preOriDate=" + cfDatelistOri.get(i)
                        + ", oriDate=" + cfDatelistOri.get(i + 1)
                        + ", prePaymentDate=" + prePaymentDate
                        + ", paymentDate=" + paymentDate);
            }
        }
    }

    public ScfMeasure calc(MarketData marketData) {
        this.marketData = marketData;
        warnings.clear();
        // 利率曲线变动、定盘曲线变动
        LinkedList<Cashflow> cashflows = generateCashflow(dataDate, cfDatelist, resetDateInfosMap);
        this.cashflowList = cashflows;

        ScfMeasure scfMeasure = new ScfMeasure();
        scfMeasure.cashFlowList = cashflowList;
        return scfMeasure;
    }

    public ScfMeasure calc() {
        warnings.clear();
        // 利率曲线变动、定盘曲线变动
        LinkedList<Cashflow> cashflows = generateCashflow(dataDate, cfDatelist, resetDateInfosMap);
        this.cashflowList = cashflows;

        ScfMeasure scfMeasure = new ScfMeasure();
        scfMeasure.cashFlowList = cashflowList;
        return scfMeasure;
    }

    public LinkedList<Cashflow> generateCashflow(LocalDate simDate, LinkedList<LocalDate> cfDate,
            HashMap<LocalDate, LinkedList<ResetDateInfo>> resetDateMap) {
        // 折现曲线校验
        if (marketData.irSpot == null || !marketData.irSpot.containsKey(scfInfo.discountCurve)) {
            throw new IllegalArgumentException("折现曲线不存在: " + scfInfo.discountCurve);
        }
        IrSpot.IrSpotInfo discountCurveInfo = CommUtils.deepCopy(marketData.irSpot.get(scfInfo.discountCurve));
        IrSpot irSpot = new IrSpot(discountCurveInfo);

        LinkedList<Cashflow> cashflows = new LinkedList<>();
        // 本金规则统一由 NOTIONAL_FLAG 控制：NONE / START / END / START_END
        String principalRule = resolveNotionalRule(scfInfo.notionalFlag);
        boolean allowPrincipalFlow = !"NONE".equals(principalRule);
        boolean allowPrincipalStart = "START".equals(principalRule) || "START_END".equals(principalRule);
        boolean allowPrincipalEnd = "END".equals(principalRule) || "START_END".equals(principalRule);
        for (int i = 0, n = cfDate.size(); i < n - 1; i++) {
            // 当期剩余本金（以该付息期起始日为基准）
            double currentNotional = getNotionalAtDate(cfDate.get(i));
            if (currentNotional <= 0)
                break;

            LinkedList<ResetDateInfo> resetDates = resetDateMap.get(cfDate.get(i + 1));
            HashMap<String, Double> rst = findScfCashflow(simDate, cfDate.get(i), cfDate.get(i + 1), resetDates);

            Double r = rst.get("r");
            Double cf = rst.get("cashflow");

            // 期初支付（advance）时利息支付日为期初，期末支付（arrear）时为期末
            boolean isAdvance = "advance".equalsIgnoreCase(scfInfo.paymentTiming);
            LocalDate interestPayDate = isAdvance ? cfDate.get(i) : cfDate.get(i + 1);

            Cashflow cashflow = new Cashflow();
            cashflow.cf = currentNotional * cf;
            cashflow.rate = r;
            cashflow.startNotional = currentNotional;
            cashflow.timeFactor = CurveFunc.timeFactor(simDate, interestPayDate, scfInfo.dayCountBasis);
            cashflow.discoutFactor = irSpot.fwdDiscount(simDate, interestPayDate);
            cashflow.cashType = "interest";
            cashflow.paymentDate = interestPayDate;
            cashflow.prePaymentDate = cfDate.get(i);
            cashflow.theoPaymentDate = cfDatelistOri.get(i + 1);
            cashflow.theoPrePaymentDate = cfDatelistOri.get(i);
            cashflow.paymentType = scfInfo.interestType;

            // 浮动债保存远期利率起止时间
            if (resetDates != null && resetDates.size() > 0) {
                cashflow.fwdStartDate = resetDates.get(0).fwdStart;
                cashflow.fwdEndDate = resetDates.get(0).fwdEnd;
            }

            // 不包含当日现金流时，将当日到期的折现因子置零
            if (scfInfo.includeTodayCashflow != null && !scfInfo.includeTodayCashflow) {
                if (cashflow.paymentDate.equals(simDate)) {
                    cashflow.discoutFactor = 0.0;
                }
            }
            cashflows.add(cashflow);

            // 本金偿还现金流：当期与下期的剩余本金差额
            double nextNotional = getNotionalAtDate(cfDate.get(i + 1));
            cashflow.endNotional = nextNotional;
            double principalPayment = currentNotional - nextNotional;
            if (allowPrincipalFlow && principalPayment > 0) {
                Cashflow pcf = new Cashflow();
                pcf.cf = principalPayment;
                pcf.rate = 0;
                pcf.startNotional = currentNotional;
                pcf.endNotional = nextNotional;
                pcf.cashType = "PRINCIPAL";
                pcf.timeFactor = CurveFunc.timeFactor(simDate, cfDate.get(i + 1), scfInfo.dayCountBasis);
                pcf.discoutFactor = irSpot.fwdDiscount(simDate, cfDate.get(i + 1));
                pcf.paymentDate = cfDate.get(i + 1);
                pcf.prePaymentDate = cfDate.get(i);
                pcf.paymentType = scfInfo.interestType;
                if (scfInfo.includeTodayCashflow != null && !scfInfo.includeTodayCashflow) {
                    if (pcf.paymentDate.equals(simDate)) {
                        pcf.discoutFactor = 0.0;
                    }
                }
                cashflows.add(pcf);
            }

            // 期初本金
            if (allowPrincipalStart && i == 0) {
                Cashflow lcf = new Cashflow();
                lcf.cf = -currentNotional;
                lcf.rate = 0;
                lcf.startNotional = currentNotional;
                lcf.endNotional = currentNotional;
                lcf.cashType = "PRINCIPAL";
                lcf.timeFactor = CurveFunc.timeFactor(simDate, cfDate.get(0), scfInfo.dayCountBasis);
                lcf.discoutFactor = irSpot.fwdDiscount(simDate, cfDate.get(0));
                lcf.paymentDate = cfDatelist.get(0);
                if (scfInfo.includeTodayCashflow != null && !scfInfo.includeTodayCashflow) {
                    if (lcf.paymentDate.equals(simDate)) {
                        lcf.discoutFactor = 0.0;
                    }
                }
                cashflows.add(lcf);
            }

            // 期末本金（最后一期或下期本金为0时）
            if (allowPrincipalEnd && (i == n - 2 || nextNotional <= 0)) {
                double endNotional = getNotionalAtDate(cfDate.get(i + 1));
                if (endNotional > 0) {
                    Cashflow lcf = new Cashflow();
                    lcf.cf = endNotional;
                    lcf.rate = 0;
                    lcf.startNotional = endNotional;
                    lcf.endNotional = 0.0;
                    lcf.cashType = "PRINCIPAL";
                    lcf.timeFactor = CurveFunc.timeFactor(simDate, cfDate.get(i + 1), scfInfo.dayCountBasis);
                    lcf.discoutFactor = irSpot.fwdDiscount(simDate, cfDate.get(i + 1));
                    lcf.paymentDate = cfDatelist.get(i + 1);
                    if (scfInfo.includeTodayCashflow != null && !scfInfo.includeTodayCashflow) {
                        if (lcf.paymentDate.equals(simDate)) {
                            lcf.discoutFactor = 0.0;
                        }
                    }
                    cashflows.add(lcf);
                }
            }
        }

        return cashflows;
    }

    public List<Cashflow> getPrepayCashflow() {
        List<Cashflow> newCF = cashflowList.stream().filter(o -> o.cashType.equalsIgnoreCase("interest"))
                .sorted(Comparator.comparing(o -> o.paymentDate))
                .collect(Collectors.toList());

        List<Cashflow> prepayCashflow = new LinkedList<>();

        for (int i = 0, n = newCF.size(); i < n; i++) {
            if (i == 0) {
                Cashflow now = CommUtils.deepCopy(newCF.get(i));
                now.paymentDate = now.prePaymentDate;
                prepayCashflow.add(now);
            } else {
                Cashflow now = CommUtils.deepCopy(newCF.get(i));
                now.paymentDate = now.prePaymentDate;
                now.prePaymentDate = prepayCashflow.get(i - 1).paymentDate;
                now.discoutFactor = newCF.get(i - 1).discoutFactor;
                prepayCashflow.add(now);
            }
        }

        return prepayCashflow;
    }

    /**
     * 统一解释本金规则：
     * 1. 空值默认视为 END，表示到期归还本金
     * 2. 允许值：START / END / START_END / NONE
     * 3. 非法值退化为 END，避免因脏数据丢失本金现金流
     */
    private static String resolveNotionalRule(String notionalFlag) {
        String normalized = StringUtils.trimToEmpty(notionalFlag).toUpperCase();
        if (StringUtils.isBlank(normalized)) {
            return "END";
        }
        if ("START".equals(normalized) || "END".equals(normalized)
                || "START_END".equals(normalized) || "NONE".equals(normalized)) {
            return normalized;
        }
        return "END";
    }

    private HashMap<String, Double> findScfCashflow(LocalDate simDate,
            LocalDate prePaymentDate,
            LocalDate paymentDay,
            LinkedList<ResetDateInfo> resetdays) {
        LocalDate cfdate1 = prePaymentDate;
        LocalDate cfdate2 = paymentDay;

        double r = 0.0;
        if ("fixed".equalsIgnoreCase(scfInfo.interestType)) {
            r = scfInfo.interestRate;
        }

        if ("floating".equalsIgnoreCase(scfInfo.interestType)) {
            boolean blankReferenceCurve = StringUtils.isBlank(scfInfo.referenceCurve);
            boolean missingReferenceCurve = blankReferenceCurve
                    || marketData.irSpot == null
                    || !marketData.irSpot.containsKey(scfInfo.referenceCurve);
            if (!blankReferenceCurve && missingReferenceCurve
                    && Boolean.FALSE.equals(scfInfo.allowMissingReferenceCurveAsZeroForward)) {
                throw new IllegalArgumentException("参考曲线不存在: " + scfInfo.referenceCurve);
            }
            // 标准处理逻辑：允许缺失浮息参考曲线时，未来远期利率统一按0处理，并向产品结果透出WARNING。
            if (blankReferenceCurve) {
                addWarning("标准处理：浮息参考曲线为空，定盘校验跳过，未来远期利率按0处理");
            } else if (missingReferenceCurve) {
                addWarning("标准处理：浮息参考曲线不存在，未来远期利率按0处理: REFERENCE_CURVE="
                        + scfInfo.referenceCurve);
            }
            IrSpot refCurve = missingReferenceCurve ? null : new IrSpot(marketData.irSpot.get(scfInfo.referenceCurve));

            // 利率重置频率
            Period resetFreq = CashflowUtils.convertFreq(scfInfo.resetFreq);
            // 付息频率
            Period payFreq = CashflowUtils.convertFreq(scfInfo.payFreq);

            String frq = "smp";
            String dcb = scfInfo.dayCountBasis;

            // 重定价频率和付息频率相等
            if (CommUtils.periodCompare(resetFreq, payFreq) >= 0) {
                if (resetdays == null || resetdays.isEmpty()) {
                    throw new IllegalArgumentException("重置日期数据缺失: paymentDay=" + paymentDay);
                }
                LocalDate fixingStart = resetdays.get(0).fixingStart;
                LocalDate fixingEnd = resetdays.get(0).fixingEnd;
                LocalDate fwdStart = resetdays.get(0).fwdStart;
                LocalDate fwdEnd = resetdays.get(0).fwdEnd;
                double r0 = 0.0;

                if (blankReferenceCurve) {
                    r0 = 0.0;
                } else if (!fixingStart.isAfter(simDate) && fixingEnd.isAfter(simDate)) {
                    // 估值日所在付息区间，优先取lastResetRate
                    if (scfInfo.lastResetRate != null && scfInfo.lastResetRate != 0) {
                        r0 = scfInfo.lastResetRate;
                    } else {
                        r0 = findFixingRate(fwdStart);
                    }
                } else if (fixingStart.isBefore(simDate) && fixingEnd.isBefore(simDate)) {
                    // 估值日之前的付息区间
                    r0 = findFixingRate(fwdStart);
                } else {
                    // 估值日之后的付息区间
                    if (missingReferenceCurve) {
                        r0 = 0.0;
                    } else {
                        r0 = refCurve.fwdRate(fwdStart, fwdEnd);
                        // 浮动端目标计息口径统一为单利，按当前腿的 dayCountBasis 计息
                        r0 = CurveFunc.convertIrRate(r0, fwdStart, fwdEnd, refCurve.getIrSpotInfo().freq,
                                refCurve.getIrSpotInfo().dayCount, frq, dcb);
                    }

                }

                r = r0;
            }

            // 重定价频率高于付息频率
            if (CommUtils.periodCompare(resetFreq, payFreq) < 0) {
                if (resetdays == null || resetdays.isEmpty()) {
                    throw new IllegalArgumentException("重置日期数据缺失: paymentDay=" + paymentDay);
                }
                LocalDate start = resetdays.getFirst().fwdStart;
                LocalDate end = resetdays.getFirst().fwdEnd;

                if (blankReferenceCurve) {
                    r = 0.0;
                } else if (start.isAfter(simDate)) {
                    // 完全未来开始的付息区间，直接按整个付息期计算远期利率
                    if (missingReferenceCurve) {
                        r = 0.0;
                    } else {
                        r = refCurve.fwdRate(start, end);
                        // 浮动端目标计息口径统一为单利，按当前腿的 dayCountBasis 计息
                        r = CurveFunc.convertIrRate(r, start, end, refCurve.getIrSpotInfo().freq,
                                refCurve.getIrSpotInfo().dayCount, frq, dcb);
                    }
                } else {
                    r = calcResetShorterThanPayRate(simDate, paymentDay, resetdays, refCurve, frq, dcb,
                            missingReferenceCurve);
                }

            }

            r = r + (scfInfo.spread != null ? scfInfo.spread : 0.0);

        }

        double cf = 0;

        Period period = CashflowUtils.convertFreq(scfInfo.payFreq);
        if (period == null) {
            // 零息债券
            period = Period.ZERO;
        }
        if (abs(ChronoUnit.DAYS.between(cfdate1.plus(period), cfdate2)) < 10 &&
                (scfInfo.couponProrated != null && scfInfo.couponProrated == false)) {
            int totalMons = (int) period.toTotalMonths();
            cf = r * totalMons / 12;
        } else {
            cf = r * CurveFunc.timeFactor(cfdate1, cfdate2, scfInfo.dayCountBasis);
        }

        HashMap<String, Double> rst = new HashMap<>();
        rst.put("r", r);
        rst.put("cashflow", cf);
        return rst;
    }

    private double calcResetShorterThanPayRate(LocalDate simDate,
            LocalDate paymentDay,
            LinkedList<ResetDateInfo> resetdays,
            IrSpot refCurve,
            String frq,
            String dcb,
            boolean missingReferenceCurve) {
        LocalDate start = resetdays.getFirst().fwdStart;
        LocalDate end = resetdays.getFirst().fwdEnd;
        double fullTimeFactor = CurveFunc.timeFactor(start, end, dcb);
        if (fullTimeFactor <= 0.0) {
            throw new ArithmeticException("付息期计息因子异常: start=" + start + ", end=" + end);
        }

        double interestFactor = 0.0;
        LocalDate forwardStart = null;
        for (ResetDateInfo resetDateInfo : resetdays) {
            LocalDate fixingStart = resetDateInfo.fixingStart;
            LocalDate fixingEnd = resetDateInfo.fixingEnd;
            if (fixingStart == null || fixingEnd == null || !fixingEnd.isAfter(fixingStart)) {
                continue;
            }

            if (paymentDay.isAfter(simDate) && fixingEnd.isAfter(simDate)) {
                forwardStart = fixingEnd;
                double fixingRate = findFixingRate(fixingStart);
                interestFactor += fixingRate * CurveFunc.timeFactor(fixingStart, fixingEnd, dcb);
                break;
            }

            double fixingRate = findFixingRate(fixingStart);
            interestFactor += fixingRate * CurveFunc.timeFactor(fixingStart, fixingEnd, dcb);
        }

        if (forwardStart != null && forwardStart.isBefore(end)) {
            double forwardRate = 0.0;
            if (!missingReferenceCurve) {
                forwardRate = refCurve.fwdRate(forwardStart, end);
                forwardRate = CurveFunc.convertIrRate(forwardRate, forwardStart, end,
                        refCurve.getIrSpotInfo().freq, refCurve.getIrSpotInfo().dayCount, frq, dcb);
            }
            interestFactor += forwardRate * CurveFunc.timeFactor(forwardStart, end, dcb);
        }

        if (Double.isNaN(interestFactor) || Double.isInfinite(interestFactor)) {
            throw new ArithmeticException("浮息分段计息因子计算异常: interestFactor=" + interestFactor);
        }
        return interestFactor / fullTimeFactor;
    }

    /**
     * 获取定盘点的值
     *
     * @param fixingDate: 输入定盘日期
     * @return double
     * @author lsd
     * @date 2024/7/16 9:59
     */
    private double findFixingRate(LocalDate fixingDate) {
        if (marketData.fixingRate == null) {
            throw new IllegalArgumentException("定盘利率数据缺失: fixingRate为null");
        }
        Fixing.FixingInfo fixingInfo = marketData.fixingRate.get(scfInfo.fixingId);
        if (fixingInfo == null || fixingInfo.curveData == null) {
            throw new IllegalArgumentException("未找到定盘利率数据: fixingId=" + scfInfo.fixingId);
        }
        if (fixingInfo.curveData.isEmpty()) {
            throw new IllegalArgumentException("定盘利率数据为空: fixingId=" + scfInfo.fixingId);
        }

        // 按日期排序后查找 fixingDate 当日或之前最近一个点；若都在未来则回退到最早点。
        LocalDate floorDate = null;
        LocalDate firstDate = null;
        for (LocalDate d : fixingInfo.curveData.keySet().stream().sorted().collect(Collectors.toList())) {
            if (firstDate == null) {
                firstDate = d;
            }
            if (!d.isAfter(fixingDate)) {
                floorDate = d;
            } else {
                break;
            }
        }

        LocalDate targetDate = (floorDate != null) ? floorDate : firstDate;
        Double rate = fixingInfo.curveData.get(targetDate);
        if (rate == null) {
            throw new IllegalArgumentException("定盘利率数据缺失: date=" + targetDate + ", fixingId=" + scfInfo.fixingId);
        }
        return rate;
    }

    public HashMap<LocalDate, LinkedList<ResetDateInfo>> generateResetDays(LinkedList<LocalDate> cfDate) {
        HashMap<LocalDate, LinkedList<ResetDateInfo>> resetDataInfosMap = new HashMap<>();

        Period payFreq = CashflowUtils.convertFreq(scfInfo.payFreq);
        Period resetFreq = CashflowUtils.convertFreq(scfInfo.resetFreq);

        // 重置频率低于付息频率时，计算每N个付息期对应一次重置
        int resetPayRatio = 1;
        LocalDate groupFwdStart = null, groupFwdEnd = null;
        if (CommUtils.periodCompare(resetFreq, payFreq) > 0) {
            long resetMonths = resetFreq.toTotalMonths();
            long payMonths = payFreq.toTotalMonths();
            if (payMonths == 0 || resetMonths % payMonths != 0) {
                throw new IllegalArgumentException(
                        "重置频率与付息频率不兼容: reset=" + scfInfo.resetFreq + ", pay=" + scfInfo.payFreq);
            }
            resetPayRatio = (int) (resetMonths / payMonths);
        }

        for (int i = 0, n = cfDate.size(); i < n - 1; i++) {
            LocalDate prePaymentDate = cfDate.get(i);
            LocalDate paymentDate = cfDate.get(i + 1);
            long paymentDays = ChronoUnit.DAYS.between(prePaymentDate, paymentDate);
            if (paymentDays <= 0) {
                throw new IllegalArgumentException(
                        "现金流支付区间异常: prePaymentDate=" + prePaymentDate + ", paymentDate=" + paymentDate);
            }
            LinkedList<ResetDateInfo> resetDateInfos = new LinkedList<>();

            LocalDate fwdStart = cal.getBusinessDay(scfInfo.fixingCalendar, prePaymentDate,
                    CashflowUtils.attr2BusinessDayConvention(
                            scfInfo.resetRule),
                    scfInfo.resetDayoff);
            LocalDate fwdEnd = fwdStart.plusDays(paymentDays);

            if (CommUtils.periodCompare(resetFreq, payFreq) == 0) {
                ResetDateInfo resetDateInfo = new ResetDateInfo();
                resetDateInfo.fixingStart = fwdStart;
                resetDateInfo.fixingEnd = fwdEnd;
                resetDateInfo.fwdStart = fwdStart;
                resetDateInfo.fwdEnd = fwdEnd;
                resetDateInfo.prePaymentDay = prePaymentDate;
                resetDateInfo.paymentDay = paymentDate;
                resetDateInfos.add(resetDateInfo);
                resetDataInfosMap.put(paymentDate, resetDateInfos);

            }

            if (CommUtils.periodCompare(resetFreq, payFreq) < 0) {
                LocalDate left = fwdStart;

                while (true) {
                    LocalDate fixingStart = left;
                    LocalDate fixingEnd = left.plus(resetFreq);

                    ResetDateInfo resetDateInfo = new ResetDateInfo();
                    resetDateInfo.fixingStart = fixingStart;
                    resetDateInfo.fixingEnd = fixingEnd;
                    resetDateInfo.fwdStart = fwdStart;
                    resetDateInfo.fwdEnd = fwdEnd;
                    resetDateInfo.prePaymentDay = prePaymentDate;
                    resetDateInfo.paymentDay = paymentDate;

                    if (!fixingEnd.isBefore(fwdEnd)) {
                        resetDateInfo.fixingEnd = fwdEnd;
                        resetDateInfos.add(resetDateInfo);
                        break;
                    }

                    resetDateInfos.add(resetDateInfo);
                    left = left.plus(resetFreq);

                }
                resetDataInfosMap.put(paymentDate, resetDateInfos);
            }

            // 重置频率低于付息频率（如6M重置，3M付息），多个付息期共享一次重置利率
            if (CommUtils.periodCompare(resetFreq, payFreq) > 0) {
                if (i % resetPayRatio == 0) {
                    // 新的重置期开始，记录远期利率区间
                    groupFwdStart = fwdStart;
                    groupFwdEnd = fwdEnd;
                }
                ResetDateInfo resetDateInfo = new ResetDateInfo();
                resetDateInfo.fixingStart = groupFwdStart;
                resetDateInfo.fixingEnd = groupFwdEnd;
                resetDateInfo.fwdStart = groupFwdStart;
                resetDateInfo.fwdEnd = groupFwdEnd;
                resetDateInfo.prePaymentDay = prePaymentDate;
                resetDateInfo.paymentDay = paymentDate;
                resetDateInfos.add(resetDateInfo);
                resetDataInfosMap.put(paymentDate, resetDateInfos);
            }

        }

        return resetDataInfosMap;
    }

    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }

    private void addWarning(String message) {
        if (StringUtils.isBlank(message) || warnings.contains(message)) {
            return;
        }
        warnings.add(message);
    }

    public LinkedList<Cashflow> getCashflowList() {
        return cashflowList;
    }

    public LinkedList<LocalDate> getCfDatelist() {
        return cfDatelist;
    }

    /**
     * 根据摊销计划计算指定日期的剩余本金。
     * 剩余本金 = notional - Σ(date <= 指定日期的摊销金额)。
     * 无摊销计划时返回 notional 原值。
     */
    private double getNotionalAtDate(LocalDate date) {
        if (scfInfo.amortizationSchedule == null || scfInfo.amortizationSchedule.isEmpty()) {
            return scfInfo.notional;
        }
        double remaining = scfInfo.notional;
        for (AmortizationEntry e : scfInfo.amortizationSchedule) {
            if (!e.date.isAfter(date)) {
                remaining -= e.amount;
            }
        }
        return Math.max(remaining, 0);
    }

    /**
     * 将摊销日期映射到最近的付息日（不论前后方向）。
     * 映射到同一付息日的多条摊销金额累加。
     */
    private void normalizeAmortizationSchedule() {
        if (scfInfo.amortizationSchedule == null || scfInfo.amortizationSchedule.isEmpty()) {
            return;
        }
        for (AmortizationEntry entry : scfInfo.amortizationSchedule) {
            if (entry == null) {
                throw new IllegalArgumentException("AMORTIZATION_SCHEDULE 条目不能为空");
            }
            if (entry.date == null) {
                throw new IllegalArgumentException("AMORTIZATION_SCHEDULE.DATE 不能为空");
            }
            if (entry.amount == null || !Double.isFinite(entry.amount) || entry.amount < 0.0) {
                throw new IllegalArgumentException("AMORTIZATION_SCHEDULE.AMOUNT 必须为非负有限数: " + entry.amount);
            }
        }
        // 付息日列表（跳过第一个发行日）
        List<LocalDate> couponDates = new ArrayList<>(cfDatelist.subList(1, cfDatelist.size()));

        Map<LocalDate, Double> merged = new LinkedHashMap<>();
        for (AmortizationEntry entry : scfInfo.amortizationSchedule) {
            // 找最近的付息日（取绝对距离最小的）
            LocalDate nearest = null;
            long minDays = Long.MAX_VALUE;
            for (LocalDate cd : couponDates) {
                long diff = Math.abs(ChronoUnit.DAYS.between(entry.date, cd));
                if (diff < minDays) {
                    minDays = diff;
                    nearest = cd;
                }
            }
            if (nearest == null)
                continue;
            // 映射后的付息日早于估值日，属于已完成的摊销（应已反映在 NOTIONAL 中），忽略
            if (nearest.isBefore(dataDate))
                continue;
            // 映射到该付息日，金额累加
            merged.merge(nearest, entry.amount, Double::sum);
        }

        // 用归一化后的摊销计划替换原始数据
        List<AmortizationEntry> normalized = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : merged.entrySet()) {
            AmortizationEntry ae = new AmortizationEntry();
            ae.date = e.getKey();
            ae.amount = e.getValue();
            normalized.add(ae);
        }
        scfInfo.amortizationSchedule = normalized;
    }

    static public class ResetDateInfo {
        public LocalDate prePaymentDay;
        public LocalDate paymentDay;
        public LocalDate fwdStart;
        public LocalDate fwdEnd;
        public LocalDate fixingStart;
        public LocalDate fixingEnd;

        @Override
        public String toString() {
            return "ResetDateInfo{" +
                    "prePaymentDay=" + prePaymentDay +
                    ", paymentDay=" + paymentDay +
                    ", fwdStart=" + fwdStart +
                    ", fwdEnd=" + fwdEnd +
                    ", fixingStart=" + fixingStart +
                    ", fixingEnd=" + fixingEnd +
                    '}';
        }
    }

    // 债券内部类，封装计量指标
    static public class ScfMeasure {
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "CASHFLOW")
        public List<StructuredCashflow.Cashflow> cashFlowList;

        @Override
        public String toString() {
            return "ScfMeasure{" +
                    "instrumentId='" + instrumentId + '\'' +
                    '}';
        }
    }

    static public class Cashflow implements Serializable {
        @JSONField(name = "PREPAYMENT_DATE", format = "yyyyMMdd", ordinal = 1)
        public LocalDate prePaymentDate;
        @JSONField(name = "PAYMENT_DATE", format = "yyyyMMdd", ordinal = 2)
        public LocalDate paymentDate;
        @JSONField(name = "FWDSTART_DATE", format = "yyyyMMdd", ordinal = 3)
        public LocalDate fwdStartDate;
        @JSONField(name = "FWDEND_DATE", format = "yyyyMMdd", ordinal = 4)
        public LocalDate fwdEndDate;
        @JSONField(name = "CASHFLOW_TYPE", ordinal = 5)
        public String cashType;
        @JSONField(name = "CASHFLOW", ordinal = 6)
        public double cf;
        @JSONField(name = "RATE", ordinal = 7)
        public double rate;
        @JSONField(name = "DISCOUNT_FACTOR", ordinal = 8)
        public double discoutFactor;
        @JSONField(name = "TIME_FACTOR", ordinal = 9)
        public double timeFactor;
        @JSONField(name = "PAYMENT_TYPE", ordinal = 10)
        public String paymentType;
        @JSONField(name = "THEO_PAYMENT_DATE", format = "yyyyMMdd", ordinal = 11)
        public LocalDate theoPaymentDate;
        @JSONField(name = "THEO_PRE_PAYMENT_DATE", format = "yyyyMMdd", ordinal = 12)
        public LocalDate theoPrePaymentDate;
        public Double startNotional;
        public Double endNotional;

        @Override
        public String toString() {
            return "Cashflow{" +
                    "prePaymentDate=" + prePaymentDate +
                    ", paymentDate=" + paymentDate +
                    ", fwdStartDate=" + fwdStartDate +
                    ", fwdEndDate=" + fwdEndDate +
                    ", cashType='" + cashType + '\'' +
                    ", cf=" + cf +
                    ", rate=" + rate +
                    ", discoutFactor=" + discoutFactor +
                    ", timeFactor=" + timeFactor +
                    ", paymentType='" + paymentType + '\'' +
                    ", theoPaymentDate=" + theoPaymentDate +
                    ", theoPrePaymentDate=" + theoPrePaymentDate +
                    ", startNotional=" + startNotional +
                    ", endNotional=" + endNotional +
                    '}';
        }
    }

    // 债券内部类，封装基本信息
    static public class ScfInfo {
        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "ISSUE_DATE", format = "yyyyMMdd")
        public LocalDate issueDate;
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @JSONField(name = "INTEREST_STUB")
        public String interestStub;
        @JSONField(name = "INTEREST_TYPE")
        public String interestType;
        @JSONField(name = "INTEREST_RATE")
        public Double interestRate;
        @JSONField(name = "PAY_FREQ")
        public String payFreq;
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @JSONField(name = "SPREAD")
        public Double spread;
        @JSONField(name = "FIXING_FREQ")
        public String fixingFreq;
        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis = "actual/365";
        @JSONField(name = "SETTLE_CALENDAR")
        public String settleCalendar;
        @JSONField(name = "SETTLE_RULE")
        public String settleRule;
        @JSONField(name = "SETTLE_DAYOFF")
        public Integer settleDayoff;
        @JSONField(name = "RESET_RULE")
        public String resetRule;
        @JSONField(name = "RESET_DAYOFF")
        public Integer resetDayoff;
        @JSONField(name = "FIXING_CALENDAR")
        public String fixingCalendar;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @JSONField(name = "RESET_FREQ")
        public String resetFreq;
        @JSONField(name = "LAST_RESET_RATE")
        public Double lastResetRate;
        @JSONField(name = "NOTIONAL_FLAG")
        public String notionalFlag;
        @JSONField(name = "INCLUDE_TODAY_CASHFLOW")
        public Boolean includeTodayCashflow = true;
        @JSONField(name = "COUPON_PRORATED")
        public Boolean couponProrated = true;
        @JSONField(name = "ALLOW_MISSING_REFERENCE_CURVE_AS_ZERO_FORWARD")
        public Boolean allowMissingReferenceCurveAsZeroForward = true;

        @JSONField(name = "PAYMENT_TIMING")
        public String paymentTiming = "arrear"; /* "arrear"=期末支付（默认），"advance"=期初支付 */

        @JSONField(name = "AMORTIZATION_SCHEDULE")
        public List<AmortizationEntry> amortizationSchedule;
    }

    /**
     * 摊销计划条目，每条表示一个日期的摊销金额
     */
    public static class AmortizationEntry {
        @ProductInputField(required = true)
        @JSONField(name = "DATE", format = "yyyyMMdd")
        public LocalDate date;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "AMOUNT")
        public Double amount; /* 当期摊销金额（正数表示偿还本金） */
    }
}

