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
import java.util.stream.Collectors;

/**
 * 零息曲线Bootstrap构建
 * 从市场报价（ZERO/SWAP/YIELD）推导零息利率和折现因子
 */
public class ZeroCurveBootstrap {

    /** 标准输出期限代码 */
    public static final String[] STANDARD_TERM_CODES = {
            "1D", "7D", "14D", "30D", "60D", "90D", "120D", "150D", "180D",
            "270D", "365D", "547D", "730D", "1095D", "1460D", "1825D",
            "2190D", "2555D", "2920D", "3285D", "3650D", "5475D", "7300D",
            "10950D", "14600D"
    };
    public static final double[] STANDARD_TERM_DAYS = {
            1, 7, 14, 30, 60, 90, 120, 150, 180,
            270, 365, 547, 730, 1095, 1460, 1825,
            2190, 2555, 2920, 3285, 3650, 5475, 7300,
            10950, 14600
    };

    /**
     * 执行零息曲线自举
     *
     * @param input    曲线输入
     * @param calendar 日历对象
     * @return 标准化后的曲线数据
     */
    public List<IrCurve> bootstrap(CurveInput input, Calendar calendar) {
        if (input.curveData == null || input.curveData.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate dataDate = input.dataDate;

        // 解析输入期限点
        List<TermData> termList = parseTermData(input.curveData, dataDate, calendar);

        // 按 TERM_DAYS 排序
        termList.sort(Comparator.comparingDouble(t -> t.termDays));

        // 自举计算零息利率和折现因子
        bootstrapSpotRateAndDF(termList, dataDate, calendar);

        // 仅输出原始输入点
        List<TermData> originalPoints = termList.stream()
                .filter(t -> t.isOriginal)
                .collect(Collectors.toList());

        // 如果指定了自定义输出期限天数，在 cont/365 下直接重采样 spotRate
        double[] customDays = input.getOutputTermDaysArray();
        if (customDays != null && customDays.length > 0) {
            originalPoints = resampleSpotRate(originalPoints, customDays,
                    input.dataDate, input.getInterpolateType());
        }

        // 输出：将 cont/365 spotRate 转换为目标 freq/dcb
        return toOutputTerm(originalPoints, input);
    }

    /**
     * 在 cont/365 下将已自举的 spotRate 重新采样到指定期限天数
     * 在 freq/dcb 转换之前执行，避免浮点往返
     */
    private List<TermData> resampleSpotRate(List<TermData> source, double[] targetDays,
            LocalDate dataDate, String interpolateType) {
        Double[] srcDays = source.stream()
                .map(t -> (double) t.termDays)
                .toArray(Double[]::new);
        Double[] srcRates = source.stream()
                .map(t -> t.spotRate)
                .toArray(Double[]::new);

        List<TermData> result = new ArrayList<>();
        for (double days : targetDays) {
            double rate = Interpolation.interpolate(srcDays, srcRates, days, interpolateType);

            TermData td = new TermData();
            td.termCode = (int) days + "D";
            td.termDays = (long) days;
            td.spotRate = rate;
            td.adjustDate = dataDate.plusDays((long) days);
            td.isOriginal = true;
            result.add(td);
        }
        return result;
    }

    /**
     * 解析 CURVE_DATA 中的 JSONObject 为内部 TermData 结构
     */
    private List<TermData> parseTermData(List<JSONObject> curveData, LocalDate dataDate, Calendar calendar) {
        List<TermData> list = new ArrayList<>();
        for (JSONObject jo : curveData) {
            TermData td = new TermData();
            td.termCode = jo.getString("TERM_CODE");
            td.termType = jo.getString("TERM_TYPE");
            td.termValue = jo.getDoubleValue("TERM_VALUE");
            td.termDayCount = jo.getString("TERM_DAYCOUNT");
            td.termFrq = jo.getString("TERM_FRQ");
            // 业务口径：CALENDAR 为空表示所有日期均为工作日。
            td.calName = jo.getString("CALENDAR");
            if (td.calName == null) {
                td.calName = "";
            }
            // 业务口径：DAY_OFF 为空表示 0。
            td.dayOff = jo.getInteger("DAY_OFF") == null ? 0 : jo.getInteger("DAY_OFF");
            td.isOriginal = true;

            // 解析远期起始期限，默认为 0（即期）
            String startTerm = jo.getString("START_TERM");
            td.startTerm = startTerm;

            LocalDate effectiveDate = calendar.addBusinessDays(td.calName, dataDate, td.dayOff);
            if (startTerm != null && !startTerm.isEmpty()) {
                // 远期起始：起始日 = effectiveDate + START_TERM，结束日 = 起始日 + TERM_CODE
                LocalDate startDate = calendar.resolveTermDate(td.calName, effectiveDate, startTerm);
                LocalDate endDate = calendar.resolveTermDate(td.calName, startDate, td.termCode);
                td.startDate = startDate;
                td.adjustDate = endDate;
                td.startDays = ChronoUnit.DAYS.between(dataDate, startDate);
                td.termDays = ChronoUnit.DAYS.between(dataDate, endDate);
                td.isForwardStart = true;
            } else {
                // 即期：从 effectiveDate 算起
                LocalDate adjustDate = calendar.resolveTermDate(td.calName, effectiveDate, td.termCode);
                td.startDate = effectiveDate;
                td.adjustDate = adjustDate;
                td.startDays = ChronoUnit.DAYS.between(dataDate, effectiveDate);
                td.termDays = ChronoUnit.DAYS.between(dataDate, adjustDate);
            }

            // 使用各点自身的 daycount 计算时间因子
            String dcb = td.termDayCount != null ? td.termDayCount : "actual/365";
            td.timeFactor = CurveFunc.timeFactor(td.startDate, td.adjustDate, dcb);
            td.yieldRate = td.termValue;
            td.yieldRateWeighted = td.timeFactor * td.termValue;

            list.add(td);
        }
        return list;
    }

    /**
     * 在 SWAP 期限点之间插入中间付息节点
     * 对每个 SWAP 区间按 TERM_FRQ 连续补齐，直到到达当前原始节点前一档
     */
    private List<TermData> insertIntermediateNodes(List<TermData> termList, LocalDate dataDate,
            String calName, Calendar calendar) {
        if (termList.size() <= 1) {
            return termList;
        }

        List<TermData> result = new ArrayList<>();
        result.add(termList.get(0));

        for (int i = 1; i < termList.size(); i++) {
            TermData previous = termList.get(i - 1);
            TermData current = termList.get(i);

            if ("SWAP".equals(current.termType) && current.termFrq != null && !current.termFrq.isEmpty()) {
                LocalDate nextDate = calendar.resolveTermDate(calName, previous.adjustDate, current.termFrq);

                // 非法频率或无法推进时直接跳过补点，避免死循环
                if (nextDate.isAfter(previous.adjustDate)) {
                    while (nextDate.isBefore(current.adjustDate)) {
                        long intermediateDays = ChronoUnit.DAYS.between(dataDate, nextDate);
                        String dcb = current.termDayCount != null ? current.termDayCount : "actual/365";
                        double tf = CurveFunc.timeFactor(dataDate, nextDate, dcb);

                        TermData inserted = new TermData();
                        inserted.termCode = "";
                        inserted.termType = "SWAP";
                        inserted.termFrq = current.termFrq;
                        inserted.termDayCount = current.termDayCount;
                        inserted.adjustDate = nextDate;
                        inserted.termDays = intermediateDays;
                        inserted.timeFactor = tf;
                        inserted.yieldRate = 0;
                        inserted.yieldRateWeighted = 0;
                        inserted.isOriginal = false;
                        result.add(inserted);

                        LocalDate advanced = calendar.resolveTermDate(calName, nextDate, current.termFrq);
                        if (!advanced.isAfter(nextDate)) {
                            break;
                        }
                        nextDate = advanced;
                    }
                }
            }

            result.add(current);
        }

        return result;
    }

    /**
     * 线性插值填充 SWAP 中间节点的利率
     * 仅对 SWAP 类型生效，ZERO 和远期利率点不参与插值
     */
    private void interpolateYieldRates(List<TermData> termList, String interpolateType) {
        // 收集 SWAP 原始节点作为插值源
        List<Double> origTerms = new ArrayList<>();
        List<Double> origRates = new ArrayList<>();
        for (TermData td : termList) {
            if (td.isOriginal && "SWAP".equals(td.termType) && !origTerms.contains(td.timeFactor)) {
                origTerms.add(td.timeFactor);
                origRates.add(td.yieldRateWeighted);
            }
        }
        if (origTerms.isEmpty())
            return;

        Double[] xArr = origTerms.toArray(new Double[0]);
        Double[] yArr = origRates.toArray(new Double[0]);

        // 仅对 SWAP 类型的非原始中间节点进行插值，原始输入点保留其市场报价
        for (TermData td : termList) {
            if (!"SWAP".equals(td.termType) || td.isOriginal)
                continue;
            double interpolated = Interpolation.interpolate(xArr, yArr, td.timeFactor, interpolateType);
            td.yieldRateWeighted = interpolated;
            if (td.timeFactor != 0) {
                td.yieldRate = interpolated / td.timeFactor;
            }
        }
    }

    /**
     * 计算各节点间隔
     */
    private double[] calcIntervals(List<TermData> termList) {
        double[] intervals = new double[termList.size()];
        int start = findSwapStart(termList);

        if (termList.size() == 1) {
            intervals[0] = termList.get(0).timeFactor;
            return intervals;
        }

        if (start == 0) {
            intervals[0] = termList.get(0).timeFactor;
            for (int i = 1; i < termList.size(); i++) {
                intervals[i] = termList.get(i).timeFactor - termList.get(i - 1).timeFactor;
            }
        } else {
            int idx = Math.max(start - 1, 0);
            intervals[idx] = termList.get(idx).timeFactor;
            for (int i = idx + 1; i < termList.size(); i++) {
                intervals[i] = termList.get(i).timeFactor - termList.get(i - 1).timeFactor;
            }
        }
        return intervals;
    }

    /**
     * 自举计算零息利率和折现因子
     * 处理顺序：ZERO即期 → ZERO远期 → SWAP
     */
    private void bootstrapSpotRateAndDF(List<TermData> termList, LocalDate dataDate, Calendar calendar) {
        // 第一轮：处理 ZERO 即期点（startDays == 0）
        for (TermData td : termList) {
            double termYear = td.termDays / 365.0;
            if (termYear > 0 && !td.isForwardStart && !"SWAP".equals(td.termType)) {
                calcSpotZeroPoint(td, termList);
            }
        }

        // 第二轮：处理 ZERO 远期点（startDays > 0），此时所有即期点的 DF 已可用
        for (TermData td : termList) {
            double termYear = td.termDays / 365.0;
            if (termYear > 0 && td.isForwardStart && "ZERO".equals(td.termType)) {
                calcForwardZeroPoint(td, termList, dataDate);
            }
        }

        // 第三轮：SWAP 仅按原始 pillar 自举，不对缺失期限插值报价
        List<TermData> swapPillars = termList.stream()
                .filter(t -> t.isOriginal && "SWAP".equals(t.termType))
                .sorted(Comparator.comparingLong(t -> t.termDays))
                .collect(Collectors.toList());
        for (TermData swap : swapPillars) {
            bootstrapSwapPillar(swap, termList, dataDate, calendar);
        }
    }

    /**
     * 处理即期 ZERO 点。
     */
    private void calcSpotZeroPoint(TermData td, List<TermData> termList) {
        double termYear = td.termDays / 365.0;
        double dfStart = td.startDays <= 0 ? 1.0 : interpolateDF(termList, td.startDays);
        td.discountFactor = dfStart / (1.0 + td.timeFactor * td.yieldRate);
        td.spotRate = -Math.log(td.discountFactor) / termYear;
    }

    /**
     * 对单个 SWAP pillar 做自举：
     * 按 TERM_FRQ 生成票息现金流日期表，求解满足平价方程的 DF(0,Tn)。
     */
    private void bootstrapSwapPillar(TermData swap, List<TermData> termList, LocalDate dataDate, Calendar calendar) {
        if (swap.adjustDate == null || swap.termDays <= 0) {
            return;
        }

        if (swap.termFrq == null || swap.termFrq.isEmpty()) {
            throw new IllegalArgumentException("SWAP 期限点缺少 TERM_FRQ, TERM_CODE=" + swap.termCode);
        }

        List<LocalDate> payDates = buildSwapPaymentDates(swap.startDate, swap.adjustDate,
                swap.termFrq, swap.calName, calendar);
        if (payDates.isEmpty()) {
            return;
        }

        long maturityDays = swap.termDays;
        KnownDfAnchor anchor = findKnownAnchor(termList, maturityDays);
        double dfMaturity = solveSwapMaturityDf(
                swap.yieldRate,
                swap.termDayCount != null ? swap.termDayCount : "actual/365",
                payDates,
                dataDate,
                swap.startDate,
                swap.startDays,
                maturityDays,
                anchor,
                termList);

        if (dfMaturity <= 0) {
            return;
        }
        swap.discountFactor = dfMaturity;
        swap.spotRate = -Math.log(dfMaturity) / (maturityDays / 365.0);
    }

    /**
     * 构建固定端票息日期（含到期日）。
     */
    private List<LocalDate> buildSwapPaymentDates(LocalDate effectiveDate, LocalDate maturityDate,
            String termFrq, String calName, Calendar calendar) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate next = calendar.resolveTermDate(calName, effectiveDate, termFrq);
        if (!next.isAfter(effectiveDate)) {
            dates.add(maturityDate);
            return dates;
        }

        int guard = 0;
        while (next.isBefore(maturityDate) && guard < 2000) {
            dates.add(next);
            LocalDate advanced = calendar.resolveTermDate(calName, next, termFrq);
            if (!advanced.isAfter(next)) {
                break;
            }
            next = advanced;
            guard++;
        }
        if (dates.isEmpty() || !dates.get(dates.size() - 1).isEqual(maturityDate)) {
            dates.add(maturityDate);
        }
        return dates;
    }

    /**
     * 找到 maturity 之前最后一个已知 DF 点，若无则使用 t=0, DF=1。
     */
    private KnownDfAnchor findKnownAnchor(List<TermData> termList, long maturityDays) {
        long bestDays = 0;
        double bestDf = 1.0;
        for (TermData td : termList) {
            if (td.discountFactor > 0 && td.termDays < maturityDays && td.termDays >= bestDays) {
                bestDays = td.termDays;
                bestDf = td.discountFactor;
            }
        }
        if (bestDf <= 0) {
            bestDf = 1.0;
            bestDays = 0;
        }
        return new KnownDfAnchor(bestDays, bestDf);
    }

    /**
     * 求解 swap 到期折现因子：K*sum(alpha_i*DF_i)+DF(T)=1
     * 区间(anchor,T)内用 log-DF 线性插值（等价分段常数远期）。
     */
    private double solveSwapMaturityDf(double fixedRate, String dayCount, List<LocalDate> payDates,
            LocalDate dataDate, LocalDate effectiveDate, long effectiveDays, long maturityDays,
            KnownDfAnchor anchor, List<TermData> termList) {

        double low = 1e-10;
        double high = Math.max(2.0, anchor.df * 2.0);
        double fLow = swapEquation(low, fixedRate, dayCount, payDates, dataDate, effectiveDate,
                effectiveDays, maturityDays, anchor, termList);
        double fHigh = swapEquation(high, fixedRate, dayCount, payDates, dataDate, effectiveDate,
                effectiveDays, maturityDays, anchor, termList);

        int expand = 0;
        while (fLow * fHigh > 0 && expand < 40) {
            high *= 2.0;
            fHigh = swapEquation(high, fixedRate, dayCount, payDates, dataDate, effectiveDate,
                    effectiveDays, maturityDays, anchor, termList);
            expand++;
        }

        if (fLow * fHigh > 0) {
            throw new IllegalArgumentException("SWAP 方程无有效根, maturityDays=" + maturityDays);
        }

        for (int i = 0; i < 120; i++) {
            double mid = 0.5 * (low + high);
            double fMid = swapEquation(mid, fixedRate, dayCount, payDates, dataDate, effectiveDate,
                    effectiveDays, maturityDays, anchor, termList);
            if (Math.abs(fMid) < 1e-13) {
                return mid;
            }
            if (fLow * fMid <= 0) {
                high = mid;
                fHigh = fMid;
            } else {
                low = mid;
                fLow = fMid;
            }
        }
        return 0.5 * (low + high);
    }

    /**
     * SWAP 平价方程值。
     */
    private double swapEquation(double dfMaturity, double fixedRate, String dayCount, List<LocalDate> payDates,
            LocalDate dataDate, LocalDate effectiveDate, long effectiveDays, long maturityDays,
            KnownDfAnchor anchor, List<TermData> termList) {
        LocalDate prevDate = effectiveDate;
        double couponPvSum = 0.0;

        for (LocalDate payDate : payDates) {
            long payDays = ChronoUnit.DAYS.between(dataDate, payDate);
            double alpha = CurveFunc.timeFactor(prevDate, payDate, dayCount);
            double df;
            if (payDays >= maturityDays) {
                df = dfMaturity;
            } else {
                df = interpolateDfForSwapSolve(payDays, maturityDays, anchor, dfMaturity, termList);
            }
            couponPvSum += alpha * df;
            prevDate = payDate;
        }
        double dfEffective = effectiveDays <= 0
                ? 1.0
                : interpolateDfForSwapSolve(effectiveDays, maturityDays, anchor, dfMaturity, termList);
        return fixedRate * couponPvSum + dfMaturity - dfEffective;
    }

    /**
     * SWAP 求解时的 DF 插值：
     * 1) target <= anchor：使用已知曲线插值
     * 2) anchor < target < maturity：在 (anchor, maturity) 内做 log-DF 线性
     */
    private double interpolateDfForSwapSolve(long targetDays, long maturityDays, KnownDfAnchor anchor,
            double dfMaturity, List<TermData> termList) {
        if (targetDays <= anchor.days || maturityDays <= anchor.days) {
            return interpolateDF(termList, targetDays);
        }
        if (targetDays >= maturityDays) {
            return dfMaturity;
        }
        if (anchor.df <= 0 || dfMaturity <= 0) {
            return interpolateDF(termList, targetDays);
        }

        return Interpolation.logInterpolation(
                new Double[] { (double) anchor.days, (double) maturityDays },
                new Double[] { anchor.df, dfMaturity },
                (double) targetDays);
    }

    /**
     * 处理远期利率的 ZERO 点
     * 如果有即期点覆盖 T1，通过 DF(0,T1) × DF(T1,T2) 推导 DF(0,T2)
     * 如果没有即期点覆盖 T1，直接将远期利率当即期利率使用
     *
     * @param td       远期利率点（startDays > 0）
     * @param termList 全部期限点（用于插值查找 DF(0,T1)）
     */
    private void calcForwardZeroPoint(TermData td, List<TermData> termList, LocalDate dataDate) {
        double termYear = td.termDays / 365.0;

        Double dfStart = interpolateDFOrNull(termList, td.startDays);
        if (dfStart == null) {
            calcForwardZeroAsFirstZero(td, dataDate);
            return;
        }

        // DF(T1,T2) = 1 / (1 + fwdRate × dcf)
        double dfFwd = 1.0 / (1.0 + td.termValue * td.timeFactor);

        // DF(0,T2) = DF(0,T1) × DF(T1,T2)
        td.discountFactor = dfStart * dfFwd;

        // 反推连续复利即期利率
        td.spotRate = -Math.log(td.discountFactor) / termYear;
    }

    /**
     * 将远期 ZERO 作为当前曲线第一段 ZERO 处理。
     */
    private void calcForwardZeroAsFirstZero(TermData td, LocalDate dataDate) {
        double termYear = td.termDays / 365.0;
        String dcb = td.termDayCount != null ? td.termDayCount : "actual/365";
        double wholeTimeFactor = CurveFunc.timeFactor(dataDate, td.adjustDate, dcb);
        td.discountFactor = 1.0 / (1.0 + td.termValue * wholeTimeFactor);
        td.spotRate = -Math.log(td.discountFactor) / termYear;
    }

    /**
     * 从已计算的点中插值获取指定天数的折现因子
     * 包括即期点和已处理的远期点
     */
    private double interpolateDF(List<TermData> termList, long targetDays) {
        Double df = interpolateDFOrNull(termList, targetDays);
        if (df == null) {
            throw new IllegalArgumentException("缺少可插值的已知 DF 点, targetDays=" + targetDays);
        }
        return df;
    }

    /**
     * 从当前曲线已计算结果中插值获取折现因子，无法取得时返回 null。
     */
    private Double interpolateDFOrNull(List<TermData> termList, long targetDays) {
        if (targetDays <= 0) {
            return 1.0;
        }

        // 精确匹配
        for (TermData td : termList) {
            if (td.termDays == targetDays && td.discountFactor > 0) {
                return td.discountFactor;
            }
        }

        // log-DF 线性插值
        TermData lower = null, upper = null;
        for (TermData td : termList) {
            if (td.discountFactor <= 0)
                continue;
            if (td.termDays <= targetDays) {
                if (lower == null || td.termDays > lower.termDays)
                    lower = td;
            }
            if (td.termDays >= targetDays) {
                if (upper == null || td.termDays < upper.termDays)
                    upper = td;
            }
        }

        if (lower == null && upper == null) {
            return null;
        }
        if (lower == null) {
            return Interpolation.logInterpolation(
                    new Double[] { 0.0, (double) upper.termDays },
                    new Double[] { 1.0, upper.discountFactor },
                    (double) targetDays);
        }
        if (upper == null) {
            return null;
        }
        if (lower.termDays == upper.termDays) {
            return lower.discountFactor;
        }
        return Interpolation.logInterpolation(
                new Double[] { (double) lower.termDays, (double) upper.termDays },
                new Double[] { lower.discountFactor, upper.discountFactor },
                (double) targetDays);
    }

    /**
     * 找到第一个 SWAP 节点的索引
     */
    private int findSwapStart(List<TermData> termList) {
        if (termList.stream().noneMatch(t -> "SWAP".equals(t.termType) || "YIELD".equals(t.termType))) {
            return termList.size() - 1;
        }
        String lastFrq = termList.get(termList.size() - 1).termFrq;
        if (lastFrq == null)
            return 0;

        double freqDays = getFreqDaysFromTermList(termList);
        for (int i = 0; i < termList.size(); i++) {
            double tf = termList.get(i).timeFactor;
            if (tf > freqDays / 365.0) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 找到 DF bootstrap 起始索引
     */
    private int findStartDF(List<TermData> termList) {
        // 从最后一个 ZERO 节点之后开始
        int start1 = 0;
        String lastType = termList.get(termList.size() - 1).termType;
        if ("ZERO".equals(lastType)) {
            start1 = termList.size() - 1;
        } else if ("SWAP".equals(termList.get(0).termType)) {
            start1 = 0;
        } else {
            for (int i = termList.size() - 1; i >= 0; i--) {
                if ("ZERO".equals(termList.get(i).termType)) {
                    start1 = i + 1;
                    break;
                }
            }
        }

        double freqDays = getFreqDaysFromTermList(termList);
        int start2 = 0;
        for (int i = 0; i < termList.size(); i++) {
            if (termList.get(i).timeFactor > freqDays / 365.0) {
                start2 = i;
                break;
            }
        }
        return Math.max(start1, start2);
    }

    /**
     * 从最后一个有 termFrq 的节点获取频率天数
     */
    private double getFreqDaysFromTermList(List<TermData> termList) {
        String lastFrq = termList.get(termList.size() - 1).termFrq;
        if (lastFrq == null)
            return 0;
        return termCodeToDays(lastFrq);
    }

    /**
     * 将 termCode 转为近似天数（用于比较判断）
     */
    private double termCodeToDays(String termCode) {
        if (termCode == null || termCode.isEmpty())
            return 0;
        String mark = termCode.substring(termCode.length() - 1);
        int amount = Integer.parseInt(termCode.substring(0, termCode.length() - 1));
        switch (mark) {
            case "D":
                return amount;
            case "W":
                return amount * 7;
            case "M":
                return amount * 30;
            case "Y":
                return amount * 365;
            default:
                return amount;
        }
    }

    /**
     * 将自举结果转换为输出格式
     * 默认输出期限与输入期限一致
     */
    private List<IrCurve> toOutputTerm(List<TermData> points, CurveInput input) {
        if (points.isEmpty())
            return Collections.emptyList();

        List<IrCurve> result = new ArrayList<>();
        String outputDcb = input.getOutputDayCount();
        String outputFreq = input.getOutputFreq();
        // 内部计算始终以 cont + actual/365 进行
        String internalDcb = "actual/365";
        String internalFreq = "cont";
        boolean needConvert = !internalFreq.equalsIgnoreCase(outputFreq)
                || !internalDcb.equalsIgnoreCase(outputDcb);

        for (TermData td : points) {
            double termDays = td.termDays;
            double termYear = termDays / 365.0;
            double rate = td.spotRate;

            // DF 在任何 freq/dcb 下恒等，直接用 cont 计算
            double df = Math.exp(-rate * termYear);

            // 按目标 daycount/freq 转换利率
            if (needConvert) {
                LocalDate endDate = input.dataDate.plusDays((long) termDays);
                rate = CurveFunc.convertIrRate(rate, input.dataDate, endDate,
                        internalFreq, internalDcb, outputFreq, outputDcb);
            }

            IrCurve pt = new IrCurve();
            pt.curveId = input.curveId;
            pt.dataDate = input.dataDate;
            pt.termCode = td.termCode;
            pt.termDays = termDays;
            pt.termYear = termYear;
            pt.rate = rate;
            pt.discountFactor = df;
            pt.curveDaycount = outputDcb;
            pt.curveFreq = outputFreq;
            pt.interpolateType = input.getInterpolateType();
            result.add(pt);
        }
        return result;
    }

    /**
     * 自举计算中间数据结构
     */
    private static class KnownDfAnchor {
        final long days;
        final double df;

        KnownDfAnchor(long days, double df) {
            this.days = days;
            this.df = df;
        }
    }

    /**
     * 自举计算中间数据结构
     */
    private static class TermData {
        String termCode;
        String termType;
        double termValue;
        String termDayCount;
        String termFrq;
        String calName;
        int dayOff;
        String startTerm; // 远期起始期限代码，null 或空表示即期
        LocalDate startDate; // 远期起始日
        LocalDate adjustDate; // 结束日
        long startDays; // 远期起始天数（从 dataDate 算起），0=即期
        long termDays; // 结束天数（从 dataDate 算起）
        double timeFactor;
        double yieldRate;
        double yieldRateWeighted;
        double interval;
        double spotRate;
        double discountFactor;
        boolean isForwardStart;
        boolean isOriginal;
    }
}
