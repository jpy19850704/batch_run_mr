package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir.CapFloor;
import com.zcyh.mr.product.ir.Swaption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;

public class IrOptionInputValidationTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2025, 12, 31);

    @Test
    public void testCapFloorAllowsZeroNotional() {
        CapFloor.CapFloorTradeInfo info = buildCapFloorInfo();
        info.notional = 0.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CapFloor(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("折现曲线不存在"));
    }

    @Test
    public void testSwaptionAllowsZeroNotional() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.notional = 0.0;

        Swaption.SwaptionMeasure result = new Swaption(
                DATA_DATE, info, new MarketData(), new Calendar()).calc();

        Assertions.assertEquals("SUCCESS", result.status);
        Assertions.assertEquals(0.0, result.position);
        Assertions.assertEquals(0.0, result.valuation);
    }

    @Test
    public void testCapFloorRejectsNegativeNotional() {
        CapFloor.CapFloorTradeInfo info = buildCapFloorInfo();
        info.notional = -1.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CapFloor(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("NOTIONAL"));
    }

    @Test
    public void testSwaptionRejectsNegativeNotional() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.notional = -1.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Swaption(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("NOTIONAL"));
    }

    @Test
    public void testCapFloorRejectsInvalidOptionType() {
        CapFloor.CapFloorTradeInfo info = buildCapFloorInfo();
        info.capOrFloor = "OTHER";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CapFloor(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("CAP_OR_FLOOR"));
    }

    @Test
    public void testSwaptionRejectsInvalidOptionType() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.callOrPut = "OTHER";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Swaption(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("CALL_OR_PUT"));
    }

    @Test
    public void testSwaptionRejectsInvalidDirection() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.buyOrSell = "OTHER";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Swaption(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("BUY_OR_SELL"));
    }

    @Test
    public void testSwaptionRejectsUnsupportedCashSettlement() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.settleType = "CASH";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Swaption(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("SETTLE_TYPE"));
    }

    @Test
    public void testCapFloorRejectsInvalidDateOrder() {
        CapFloor.CapFloorTradeInfo info = buildCapFloorInfo();
        info.maturityDate = info.startDate;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CapFloor(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("START_DATE"));
    }

    @Test
    public void testSwaptionRejectsInvalidUnderlyingDateOrder() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.underlyingMaturityDate = info.underlyingStartDate;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Swaption(DATA_DATE, info, new MarketData(), new Calendar()).calc());

        Assertions.assertTrue(exception.getMessage().contains("UNDERLYING_START_DATE"));
    }

    @Test
    public void testCapFloorUsesFixingOnResetDateWithoutVolSurface() {
        CapFloor.CapFloorTradeInfo info = buildCapFloorInfo();
        info.startDate = DATA_DATE;
        info.maturityDate = DATA_DATE.plusMonths(3);
        info.fixingId = "FIX_IR";

        CapFloor.CapFloorMeasure result = new CapFloor(
                DATA_DATE, info, buildExerciseDateMarketData(0.04), new Calendar()).calc();

        Assertions.assertTrue(result.valuation > 0.0);
    }

    @Test
    public void testSwaptionUsesFixingOnExerciseDateWithoutVolSurface() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.maturityDate = DATA_DATE;
        info.underlyingStartDate = DATA_DATE.plusDays(1);
        info.fixingId = "FIX_IR";

        Swaption.SwaptionMeasure result = new Swaption(
                DATA_DATE, info, buildExerciseDateMarketData(0.04), new Calendar()).calc();

        Assertions.assertTrue(result.valuation > 0.0);
        Assertions.assertEquals(0.0, result.vega, 1e-12);
    }

    @Test
    public void testCapFloorRejectsInvalidAggregationAndScheduleFields() {
        CapFloor.CapFloorTradeInfo info = buildCapFloorInfo();
        info.interestAggregationMethod = "OTHER";
        info.settleRule = "Invalid_Rule";
        info.fixingDayoff = -1;

        String errors = info.validateInput(
                JSONObject.parseObject(JSON.toJSONString(info)), EngineConstants.PRODUCT_CODE.CAPFLOOR)
                .getErrors().toString();

        Assertions.assertTrue(errors.contains("INTEREST_AGGREGATION_METHOD"));
        Assertions.assertTrue(errors.contains("SETTLE_RULE"));
        Assertions.assertTrue(errors.contains("FIXING_DAYOFF"));
    }

    @Test
    public void testSwaptionRejectsInvalidScheduleFields() {
        Swaption.SwaptionTradeInfo info = buildMaturedSwaptionInfo();
        info.underlyingSettleRule = "Invalid_Rule";
        info.fixingDayoff = -1;

        String errors = info.validateInput(
                JSONObject.parseObject(JSON.toJSONString(info)), EngineConstants.PRODUCT_CODE.SWAPTION)
                .getErrors().toString();

        Assertions.assertTrue(errors.contains("UNDERLYING_SETTLE_RULE"));
        Assertions.assertTrue(errors.contains("FIXING_DAYOFF"));
    }

    private CapFloor.CapFloorTradeInfo buildCapFloorInfo() {
        CapFloor.CapFloorTradeInfo info = new CapFloor.CapFloorTradeInfo();
        info.instrumentId = "UT_CAPFLOOR_001";
        info.capOrFloor = "CAP";
        info.productCode = EngineConstants.PRODUCT_CODE.CAPFLOOR;
        info.buyOrSell = "B";
        info.currencyCode = "USD";
        info.notional = 1_000_000.0;
        info.startDate = DATA_DATE.plusDays(2);
        info.maturityDate = DATA_DATE.plusYears(1);
        info.strikeRate = 0.03;
        info.dayCountBasis = "actual/365";
        info.payFreq = "3M";
        info.discountCurve = "IR_USD";
        info.referenceCurve = "IR_USD";
        info.volatilitySurface = "IRVOL_USD";
        return info;
    }

    private Swaption.SwaptionTradeInfo buildMaturedSwaptionInfo() {
        Swaption.SwaptionTradeInfo info = new Swaption.SwaptionTradeInfo();
        info.productCode = EngineConstants.PRODUCT_CODE.SWAPTION;
        info.instrumentId = "UT_SWAPTION_001";
        info.callOrPut = "CALL";
        info.buyOrSell = "B";
        info.maturityDate = DATA_DATE.minusDays(1);
        info.notional = 1_000_000.0;
        info.currencyCode = "USD";
        info.underlyingStartDate = DATA_DATE.plusDays(2);
        info.underlyingMaturityDate = DATA_DATE.plusYears(1);
        info.underlyingFreq = "6M";
        info.fixedRate = 0.03;
        info.fixedDayCountBasis = "actual/365";
        info.discountCurve = "IR_USD";
        info.volatilitySurface = "IRVOL_USD";
        info.fixingFreq = "6M";
        info.referenceCurve = "IR_USD";
        return info;
    }

    private MarketData buildExerciseDateMarketData(double fixingRate) {
        MarketData marketData = new MarketData();

        IrSpot.IrSpotInfo curve = new IrSpot.IrSpotInfo();
        curve.curveType = "IR_SPOT";
        curve.curveCode = "IR_USD";
        curve.dataDate = DATA_DATE;
        curve.pDataDate = DATA_DATE;
        curve.dayCount = "actual/365";
        curve.freq = "cont";
        curve.interpolateType = "linear";
        curve.curveData = new Series<>(Integer.class, Double.class);
        curve.curveData.put(0, 0.02);
        curve.curveData.put(365, 0.02);
        curve.curveData.put(1825, 0.02);
        marketData.irSpot.put("IR_USD", curve);

        Fixing.FixingInfo fixing = new Fixing.FixingInfo();
        fixing.fixingId = "FIX_IR";
        fixing.dataDate = DATA_DATE;
        fixing.pDataDate = DATA_DATE;
        fixing.interpolateType = "forward";
        fixing.curveData = new Series<>(LocalDate.class, Double.class);
        fixing.curveData.put(DATA_DATE, fixingRate);
        marketData.fixingRate.put("FIX_IR", fixing);

        FxSpot.FxSpotInfo fxSpot = new FxSpot.FxSpotInfo();
        fxSpot.dataDate = DATA_DATE;
        fxSpot.pDataDate = DATA_DATE;
        fxSpot.curveData = new HashMap<>();
        fxSpot.curveData.put("USD/CNY", 7.0);
        marketData.fxSpot = fxSpot;
        return marketData;
    }
}
