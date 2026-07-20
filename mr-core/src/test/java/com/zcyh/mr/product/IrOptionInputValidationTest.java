package com.zcyh.mr.product;

import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir.CapFloor;
import com.zcyh.mr.product.ir.Swaption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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
        info.maturityDate = DATA_DATE;
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
}
