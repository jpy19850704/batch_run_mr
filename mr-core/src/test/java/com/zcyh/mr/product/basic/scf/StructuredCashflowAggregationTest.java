package com.zcyh.mr.product.basic.scf;

import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.CurveFunc;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.support.Series;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

class StructuredCashflowAggregationTest {

    @Test
    void scfInfo_whenMethodNotProvided_shouldUseCompoundingAsProductDefault() {
        Assertions.assertEquals("COMPOUNDING",
                new StructuredCashflow.ScfInfo().interestAggregationMethod);
    }

    @Test
    void scfInfo_whenNotionalFlagNotProvided_shouldDefaultToNone() {
        Assertions.assertEquals("NONE", new StructuredCashflow.ScfInfo().notionalFlag);
    }

    @Test
    void generateCashflow_whenNotionalFlagInvalid_shouldFail() {
        LocalDate dataDate = LocalDate.of(2025, 12, 31);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 4, 5), "3M", "3M", "3M");
        info.notionalFlag = "INVALID";
        StructuredCashflow cashflow = new StructuredCashflow(
                dataDate, info, buildMarketData(dataDate), new Calendar());

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class, cashflow::calc);

        Assertions.assertTrue(error.getMessage().contains("NOTIONAL_FLAG"));
    }

    @Test
    void resolveRateEnd_whenResetSegmentIsStub_shouldStillUseFixingTenor() {
        LocalDate fixingStart = LocalDate.of(2026, 1, 5);

        LocalDate actual = StructuredCashflow.resolveRateEnd(fixingStart, Period.ofMonths(1));

        Assertions.assertEquals(LocalDate.of(2026, 2, 5), actual);
    }

    @Test
    void resolveRateEnd_whenFixingTenorIsFiveYears_shouldUseFixingTenor() {
        LocalDate fixingStart = LocalDate.of(2026, 1, 5);

        LocalDate actual = StructuredCashflow.resolveRateEnd(fixingStart, Period.ofYears(5));

        Assertions.assertEquals(LocalDate.of(2031, 1, 5), actual);
    }

    @Test
    void generateResetDays_whenResetPrecedesPayment_shouldKeepForwardIntervalEqualToPaymentInterval() {
        StructuredCashflow.ScfInfo info = new StructuredCashflow.ScfInfo();
        info.issueDate = LocalDate.of(2026, 1, 5);
        info.maturityDate = LocalDate.of(2026, 4, 5);
        info.payFreq = "3M";
        info.resetFreq = "3M";
        info.fixingFreq = "6M";
        info.interestType = "Floating";
        info.interestStub = "ShortStart";
        info.settleRule = "";
        info.settleDayoff = 0;
        info.resetRule = "Regular_Preceding";
        info.resetDayoff = 1;
        info.fixingCalendar = "TEST";

        Calendar calendar = new Calendar();
        calendar.addHoliday("TEST", LocalDate.of(2026, 1, 3));
        calendar.addHoliday("TEST", LocalDate.of(2026, 1, 4));
        StructuredCashflow cashflow = new StructuredCashflow(
                LocalDate.of(2026, 1, 1), info, new MarketData(), calendar);
        LinkedList<LocalDate> paymentDates = cashflow.getCfDatelist();
        StructuredCashflow.ResetDateInfo resetDate = cashflow.resetDateInfosMap
                .get(paymentDates.get(1)).getFirst();

        long paymentDays = ChronoUnit.DAYS.between(paymentDates.get(0), paymentDates.get(1));
        long forwardDays = ChronoUnit.DAYS.between(resetDate.fwdStart, resetDate.fwdEnd);
        Assertions.assertEquals(LocalDate.of(2026, 1, 2), resetDate.fwdStart);
        Assertions.assertEquals(paymentDays, forwardDays);
        Assertions.assertEquals(resetDate.fwdStart.plusDays(paymentDays), resetDate.fwdEnd);
        Assertions.assertNotEquals(paymentDates.get(1), resetDate.fwdEnd);
    }

    @Test
    void generateResetDays_whenResetLongerThanPay_shouldShareFirstForwardInterval() {
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 7, 5), "3M", "6M", "6M");
        StructuredCashflow cashflow = new StructuredCashflow(
                LocalDate.of(2025, 12, 31), info, buildMarketData(LocalDate.of(2025, 12, 31)), new Calendar());

        LinkedList<LocalDate> paymentDates = cashflow.getCfDatelist();
        StructuredCashflow.ResetDateInfo first = cashflow.resetDateInfosMap.get(paymentDates.get(1)).getFirst();
        StructuredCashflow.ResetDateInfo second = cashflow.resetDateInfosMap.get(paymentDates.get(2)).getFirst();

        Assertions.assertEquals(first.fwdStart, second.fwdStart);
        Assertions.assertEquals(first.fwdEnd, second.fwdEnd);
        Assertions.assertEquals(paymentDates.get(1), first.fwdEnd);
        Assertions.assertEquals(first.fwdStart.plusMonths(6),
                StructuredCashflow.resolveRateEnd(first.fixingStart, Period.ofMonths(6)));
    }

    @Test
    void generateResetDays_whenMonthlyResetStartsAtMonthEnd_shouldKeepMonthEndAnchor() {
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30), "3M", "1M", "1M");
        StructuredCashflow cashflow = new StructuredCashflow(
                LocalDate.of(2026, 1, 1), info, buildMarketData(LocalDate.of(2026, 1, 1)), new Calendar());

        LinkedList<StructuredCashflow.ResetDateInfo> resets = cashflow.resetDateInfosMap
                .get(cashflow.getCfDatelist().get(1));

        Assertions.assertEquals(3, resets.size());
        Assertions.assertEquals(LocalDate.of(2026, 1, 31), resets.get(0).accrualStart);
        Assertions.assertEquals(LocalDate.of(2026, 2, 28), resets.get(0).accrualEnd);
        Assertions.assertEquals(LocalDate.of(2026, 2, 28), resets.get(1).accrualStart);
        Assertions.assertEquals(LocalDate.of(2026, 3, 31), resets.get(1).accrualEnd);
        Assertions.assertEquals(LocalDate.of(2026, 3, 31), resets.get(2).accrualStart);
        Assertions.assertEquals(LocalDate.of(2026, 4, 30), resets.get(2).accrualEnd);
    }

    @Test
    void generateResetDays_whenFixingDateIsAdjusted_shouldNotShiftAccrualPeriod() {
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30), "3M", "1M", "1M");
        info.resetRule = "Regular_Preceding";
        Calendar calendar = new Calendar();
        calendar.addHoliday("TEST", LocalDate.of(2026, 1, 31));

        StructuredCashflow cashflow = new StructuredCashflow(
                LocalDate.of(2026, 1, 1), info, buildMarketData(LocalDate.of(2026, 1, 1)), calendar);
        StructuredCashflow.ResetDateInfo first = cashflow.resetDateInfosMap
                .get(cashflow.getCfDatelist().get(1)).getFirst();

        Assertions.assertEquals(LocalDate.of(2026, 1, 30), first.fixingStart);
        Assertions.assertEquals(LocalDate.of(2026, 1, 31), first.accrualStart);
        Assertions.assertEquals(LocalDate.of(2026, 2, 28), first.accrualEnd);
        Assertions.assertEquals(LocalDate.of(2026, 2, 28), first.rateEnd);
    }

    @Test
    void generateCashflow_whenResetIsShorterThanPay_shouldUseWholePaymentAccrualDcf() {
        LocalDate dataDate = LocalDate.of(2026, 1, 1);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30), "3M", "1M", "1M");
        info.referenceCurve = null;
        info.dayCountBasis = "30/360";
        info.spread = 0.03;
        StructuredCashflow cashflow = new StructuredCashflow(
                dataDate, info, buildMarketData(dataDate), new Calendar());

        double fullTimeFactor = CurveFunc.timeFactor(
                info.issueDate, info.maturityDate, info.dayCountBasis);

        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);

        Assertions.assertEquals(info.notional * info.spread * fullTimeFactor, interest.cf, 1e-12);
    }

    @Test
    void generateCashflow_whenFixingOccursOnSimulationDate_shouldUseFixing() {
        LocalDate simDate = LocalDate.of(2026, 1, 5);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                simDate, LocalDate.of(2026, 4, 5), "3M", "3M", "5Y");
        MarketData marketData = buildMarketData(simDate);
        marketData.fixingRate.put("FIX", buildFixing(simDate, simDate, 0.0123));

        StructuredCashflow cashflow = new StructuredCashflow(simDate, info, marketData, new Calendar());
        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);

        Assertions.assertEquals(0.0123, interest.rate, 1e-12);
    }

    @Test
    void generateCashflow_whenOnlyLaterFixingExists_shouldUseNearestLaterValue() {
        LocalDate simDate = LocalDate.of(2026, 1, 5);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                simDate, LocalDate.of(2026, 4, 5), "3M", "3M", "5Y");
        MarketData marketData = buildMarketData(simDate);
        marketData.fixingRate.put("FIX", buildFixing(simDate, simDate.plusDays(1), 0.0123));

        StructuredCashflow cashflow = new StructuredCashflow(simDate, info, marketData, new Calendar());
        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);

        Assertions.assertEquals(0.0123, interest.rate, 1e-12);
    }

    @Test
    void generateCashflow_whenFixingIsFuture_shouldUseFixingFrequencyForForwardRate() {
        LocalDate simDate = LocalDate.of(2025, 12, 31);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 4, 5), "3M", "3M", "5Y");
        MarketData marketData = buildMarketData(simDate);

        StructuredCashflow cashflow = new StructuredCashflow(simDate, info, marketData, new Calendar());
        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);
        LocalDate rateStart = interest.fwdStartDate;
        LocalDate rateEnd = rateStart.plusYears(5);
        IrSpot referenceCurve = new IrSpot(marketData.irSpot.get("REF"));
        double expected = referenceCurve.fwdRate(rateStart, rateEnd);
        expected = CurveFunc.convertIrRate(expected, rateStart, rateEnd,
                referenceCurve.getIrSpotInfo().freq, referenceCurve.getIrSpotInfo().dayCount,
                "smp", info.dayCountBasis);

        Assertions.assertEquals(expected, interest.rate, 1e-12);
        Assertions.assertNotEquals(rateEnd, interest.fwdEndDate);
    }

    @Test
    void generateCashflow_whenMultipleCoupons_shouldPrepareReferenceCurveOncePerValuation() {
        LocalDate dataDate = LocalDate.of(2025, 12, 31);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                LocalDate.of(2026, 1, 5), LocalDate.of(2027, 1, 5), "3M", "3M", "3M");
        MarketData marketData = buildMarketData(dataDate);
        CountingSeries referenceData = new CountingSeries();
        referenceData.putAll(marketData.irSpot.get("REF").curveData);
        marketData.irSpot.get("REF").curveData = referenceData;

        StructuredCashflow cashflow = new StructuredCashflow(dataDate, info, marketData, new Calendar());
        cashflow.calc();

        Assertions.assertEquals(1, referenceData.entrySetCount);
    }

    @Test
    void generateCashflow_whenSevenDayResetsAreFuture_shouldUseWholeUnfixedInterval() {
        LocalDate dataDate = LocalDate.of(2025, 12, 31);
        LocalDate startDate = LocalDate.of(2026, 1, 5);
        LocalDate endDate = LocalDate.of(2026, 4, 5);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                startDate, endDate, "3M", "7D", "7D");
        MarketData marketData = buildMarketData(dataDate);
        StructuredCashflow cashflow = new StructuredCashflow(dataDate, info, marketData, new Calendar());

        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);
        LinkedList<StructuredCashflow.ResetDateInfo> resets = cashflow.resetDateInfosMap.get(endDate);
        StructuredCashflow.ResetDateInfo lastReset = resets.getLast();
        IrSpot referenceCurve = new IrSpot(marketData.irSpot.get("REF"));
        double expectedInterestFactor = 1.0 / referenceCurve.fwdDiscount(startDate, endDate) - 1.0;

        Assertions.assertEquals(13, resets.size());
        Assertions.assertEquals(6,
                ChronoUnit.DAYS.between(lastReset.accrualStart, lastReset.accrualEnd));
        Assertions.assertEquals(info.notional * expectedInterestFactor, interest.cf, 1e-12);
    }

    @Test
    void generateCashflow_whenSevenDayResetsArePartlyFixed_shouldAggregateFutureIntervalOnce() {
        LocalDate dataDate = LocalDate.of(2026, 1, 20);
        LocalDate startDate = LocalDate.of(2026, 1, 5);
        LocalDate endDate = LocalDate.of(2026, 4, 5);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                startDate, endDate, "3M", "7D", "7D");
        MarketData marketData = buildMarketData(dataDate);
        Fixing.FixingInfo fixingInfo = buildFixing(dataDate, startDate, 0.011);
        fixingInfo.curveData.put(startDate.plusDays(7), 0.012);
        fixingInfo.curveData.put(startDate.plusDays(14), 0.013);
        marketData.fixingRate.put("FIX", fixingInfo);
        StructuredCashflow cashflow = new StructuredCashflow(dataDate, info, marketData, new Calendar());

        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);
        double sevenDayDcf = CurveFunc.timeFactor(
                startDate, startDate.plusDays(7), info.dayCountBasis);
        double fixedFactor = (1.0 + 0.011 * sevenDayDcf)
                * (1.0 + 0.012 * sevenDayDcf)
                * (1.0 + 0.013 * sevenDayDcf);
        LocalDate futureStart = startDate.plusDays(21);
        IrSpot referenceCurve = new IrSpot(marketData.irSpot.get("REF"));
        double expectedInterestFactor = fixedFactor
                / referenceCurve.fwdDiscount(futureStart, endDate) - 1.0;

        Assertions.assertEquals(info.notional * expectedInterestFactor, interest.cf, 1e-12);
    }

    @Test
    void generateCashflow_whenAverageSevenDayResetsAreFuture_shouldAdjustWholeIntervalByResetCount() {
        LocalDate dataDate = LocalDate.of(2025, 12, 31);
        LocalDate startDate = LocalDate.of(2026, 1, 5);
        LocalDate endDate = LocalDate.of(2026, 4, 5);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                startDate, endDate, "3M", "7D", "7D");
        info.interestAggregationMethod = "AVERAGE";
        MarketData marketData = buildMarketData(dataDate);
        StructuredCashflow cashflow = new StructuredCashflow(dataDate, info, marketData, new Calendar());

        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);
        LinkedList<StructuredCashflow.ResetDateInfo> resets = cashflow.resetDateInfosMap.get(endDate);
        IrSpot referenceCurve = new IrSpot(marketData.irSpot.get("REF"));
        double accumulationFactor = 1.0 / referenceCurve.fwdDiscount(startDate, endDate);
        double expectedInterestFactor = resets.size()
                * Math.expm1(Math.log(accumulationFactor) / resets.size());

        Assertions.assertEquals(13, resets.size());
        Assertions.assertEquals(info.notional * expectedInterestFactor, interest.cf, 1e-12);
        Assertions.assertTrue(expectedInterestFactor < accumulationFactor - 1.0);
    }

    @Test
    void generateCashflow_whenAverageSevenDayResetsArePartlyFixed_shouldOnlyAdjustFuturePeriods() {
        LocalDate dataDate = LocalDate.of(2026, 1, 20);
        LocalDate startDate = LocalDate.of(2026, 1, 5);
        LocalDate endDate = LocalDate.of(2026, 4, 5);
        StructuredCashflow.ScfInfo info = buildFloatingInfo(
                startDate, endDate, "3M", "7D", "7D");
        info.interestAggregationMethod = "AVERAGE";
        MarketData marketData = buildMarketData(dataDate);
        Fixing.FixingInfo fixingInfo = buildFixing(dataDate, startDate, 0.011);
        fixingInfo.curveData.put(startDate.plusDays(7), 0.012);
        fixingInfo.curveData.put(startDate.plusDays(14), 0.013);
        marketData.fixingRate.put("FIX", fixingInfo);
        StructuredCashflow cashflow = new StructuredCashflow(dataDate, info, marketData, new Calendar());

        StructuredCashflow.Cashflow interest = cashflow.calc().cashFlowList.get(0);
        double sevenDayDcf = CurveFunc.timeFactor(
                startDate, startDate.plusDays(7), info.dayCountBasis);
        double fixedInterestFactor = (0.011 + 0.012 + 0.013) * sevenDayDcf;
        LocalDate futureStart = startDate.plusDays(21);
        IrSpot referenceCurve = new IrSpot(marketData.irSpot.get("REF"));
        double futureAccumulationFactor = 1.0 / referenceCurve.fwdDiscount(futureStart, endDate);
        int futureResetCount = 10;
        double futureInterestFactor = futureResetCount
                * Math.expm1(Math.log(futureAccumulationFactor) / futureResetCount);

        Assertions.assertEquals(info.notional * (fixedInterestFactor + futureInterestFactor), interest.cf, 1e-12);
    }

    @Test
    void approximateAverageInterestFactor_whenOneReset_shouldKeepWholeIntervalResult() {
        double accumulationFactor = 1.00375;

        double actual = StructuredCashflow.approximateAverageInterestFactor(accumulationFactor, 1);

        Assertions.assertEquals(accumulationFactor - 1.0, actual, 1e-15);
    }

    private StructuredCashflow.ScfInfo buildFloatingInfo(LocalDate issueDate, LocalDate maturityDate,
            String payFreq, String resetFreq, String fixingFreq) {
        StructuredCashflow.ScfInfo info = new StructuredCashflow.ScfInfo();
        info.issueDate = issueDate;
        info.maturityDate = maturityDate;
        info.payFreq = payFreq;
        info.resetFreq = resetFreq;
        info.fixingFreq = fixingFreq;
        info.interestType = "Floating";
        info.interestStub = "ShortStart";
        info.settleCalendar = "TEST";
        info.settleRule = "";
        info.settleDayoff = 0;
        info.resetRule = "";
        info.resetDayoff = 0;
        info.fixingCalendar = "TEST";
        info.discountCurve = "DISC";
        info.referenceCurve = "REF";
        info.fixingId = "FIX";
        info.notional = 100.0;
        info.notionalFlag = "NONE";
        info.dayCountBasis = "actual/365";
        info.spread = 0.0;
        return info;
    }

    private MarketData buildMarketData(LocalDate dataDate) {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("DISC", buildCurve(dataDate, "DISC"));
        marketData.irSpot.put("REF", buildCurve(dataDate, "REF"));
        return marketData;
    }

    private IrSpot.IrSpotInfo buildCurve(LocalDate dataDate, String curveId) {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveCode = curveId;
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, 0.01);
        info.curveData.put(90, 0.012);
        info.curveData.put(365, 0.018);
        info.curveData.put(1825, 0.03);
        info.curveData.put(3650, 0.035);
        return info;
    }

    private Fixing.FixingInfo buildFixing(LocalDate dataDate, LocalDate fixingDate, double rate) {
        Fixing.FixingInfo info = new Fixing.FixingInfo();
        info.fixingId = "FIX";
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.interpolateType = "forward";
        info.curveData = new Series<>(LocalDate.class, Double.class);
        info.curveData.put(fixingDate, rate);
        return info;
    }

    private static class CountingSeries extends Series<Integer, Double> {
        private int entrySetCount;

        private CountingSeries() {
            super(Integer.class, Double.class);
        }

        @Override
        public Set<Map.Entry<Integer, Double>> entrySet() {
            entrySetCount++;
            return super.entrySet();
        }
    }
}
