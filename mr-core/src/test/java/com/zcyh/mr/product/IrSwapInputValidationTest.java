package com.zcyh.mr.product;

import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir.IrsCcs;
import com.zcyh.mr.product.ir.StdIrs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;

public class IrSwapInputValidationTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2025, 12, 31);

    @Test
    public void testStdIrsAllowsZeroNotional() {
        StdIrs.StdIrsInfo info = buildStdIrsInfo();
        info.notional = 0.0;

        StdIrs.StdIrsMeasure result = new StdIrs(
                DATA_DATE, info, buildMarketData(), new Calendar()).calcWithMarketData(buildMarketData());

        Assertions.assertEquals(0.0, result.position);
        Assertions.assertEquals(0.0, result.valuation);
    }

    @Test
    public void testIrsCcsAllowsZeroNotional() {
        IrsCcs.IrsCcsInfo info = buildIrsCcsInfo();
        info.payNotional = 0.0;
        info.recNotional = 0.0;

        IrsCcs.IrsCcsMeasure result = new IrsCcs(
                DATA_DATE, info, buildMarketData(), new Calendar()).calc(buildMarketData());

        Assertions.assertEquals(0.0, result.valuation);
        Assertions.assertEquals(0.0, result.valuationCny);
    }

    @Test
    public void testStdIrsRejectsNegativeNotional() {
        StdIrs.StdIrsInfo info = buildStdIrsInfo();
        info.notional = -1.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new StdIrs(DATA_DATE, info, buildMarketData(), new Calendar())
                        .calcWithMarketData(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("NOTIONAL"));
    }

    @Test
    public void testIrsCcsRejectsNegativeNotional() {
        IrsCcs.IrsCcsInfo info = buildIrsCcsInfo();
        info.payNotional = -1.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new IrsCcs(DATA_DATE, info, buildMarketData(), new Calendar()).calc(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("PAY_NOTIONAL"));
    }

    @Test
    public void testStdIrsRejectsInvalidDirection() {
        StdIrs.StdIrsInfo info = buildStdIrsInfo();
        info.buyOrSell = "OTHER";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new StdIrs(DATA_DATE, info, buildMarketData(), new Calendar())
                        .calcWithMarketData(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("BUY_OR_SELL"));
    }

    @Test
    public void testStdIrsRejectsInvalidTermCode() {
        StdIrs.StdIrsInfo info = buildStdIrsInfo();
        info.termCode = "UNKNOWN";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new StdIrs(DATA_DATE, info, buildMarketData(), new Calendar())
                        .calcWithMarketData(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("TERM_CODE"));
    }

    @Test
    public void testIrsCcsRejectsInvalidSwapType() {
        IrsCcs.IrsCcsInfo info = buildIrsCcsInfo();
        info.swapType = "OTHER";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new IrsCcs(DATA_DATE, info, buildMarketData(), new Calendar()).calc(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("SWAP_TYPE"));
    }

    @Test
    public void testIrsCcsRejectsInvalidNotionalExchangeType() {
        IrsCcs.IrsCcsInfo info = buildIrsCcsInfo();
        info.notionalExchangeType = "OTHER";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new IrsCcs(DATA_DATE, info, buildMarketData(), new Calendar()).calc(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("NOTIONAL_EXCHANGE_TYPE"));
    }

    @Test
    public void testIrsCcsFloatingLegRequiresReferenceCurve() {
        IrsCcs.IrsCcsInfo info = buildIrsCcsInfo();
        info.recInterestType = "FLOATING";
        info.recInterest = null;
        info.recReferenceCurve = null;
        info.recResetFreq = "3M";
        info.recFixingFreq = "3M";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new IrsCcs(DATA_DATE, info, buildMarketData(), new Calendar()).calc(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("REC_REFERENCE_CURVE"));
    }

    private StdIrs.StdIrsInfo buildStdIrsInfo() {
        StdIrs.StdIrsInfo info = new StdIrs.StdIrsInfo();
        info.instrumentId = "UT_STD_IRS_001";
        info.productCode = Constants.PRODUCT_CODE.STD_IRS;
        info.currencyCode = "USD";
        info.buyOrSell = "B";
        info.tradePrice = 0.03;
        info.notional = 1_000_000.0;
        info.maturityDate = DATA_DATE.plusMonths(3);
        info.referenceCurve = "IR_USD";
        return info;
    }

    private IrsCcs.IrsCcsInfo buildIrsCcsInfo() {
        IrsCcs.IrsCcsInfo info = new IrsCcs.IrsCcsInfo();
        info.productCode = Constants.PRODUCT_CODE.IRSCCS;
        info.instrumentId = "UT_IRSCCS_001";
        info.swapType = "IRS";
        info.startDate = DATA_DATE.plusDays(2);
        info.maturityDate = DATA_DATE.plusYears(1);
        info.notionalExchangeType = "NONE";
        info.payNotional = 1_000_000.0;
        info.payCurrencyCode = "USD";
        info.payInterestType = "FIXED";
        info.payInterest = 0.03;
        info.payFreq = "6M";
        info.payDiscountCurve = "IR_USD";
        info.recNotional = 1_000_000.0;
        info.recCurrencyCode = "USD";
        info.recInterestType = "FIXED";
        info.recInterest = 0.025;
        info.recFreq = "6M";
        info.recDiscountCurve = "IR_USD";
        return info;
    }

    private MarketData buildMarketData() {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_USD", buildIrCurve());
        marketData.fxSpot = buildFxSpot();
        return marketData;
    }

    private IrSpot.IrSpotInfo buildIrCurve() {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = "IR_SPOT";
        info.curveCode = "IR_USD";
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, 0.028);
        info.curveData.put(365, 0.031);
        info.curveData.put(730, 0.033);
        return info;
    }

    private FxSpot.FxSpotInfo buildFxSpot() {
        FxSpot.FxSpotInfo info = new FxSpot.FxSpotInfo();
        info.curveType = "FX_SPOT";
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.curveData = new HashMap<>();
        info.curveData.put("USD/CNY", 7.20);
        return info;
    }
}
