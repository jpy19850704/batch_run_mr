package com.zcyh.mr.product.ir;

import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Series;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

class BondForwardProjectionTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2025, 12, 31);

    @Test
    void valuesFutureBondUsingBondCashflowsAndCurves() {
        MarketData marketData = buildMarketData();
        Bond bond = new Bond(DATA_DATE, buildBondInfo(), marketData, new Calendar());
        Bond.ForwardProjection projection = bond.createForwardProjection(marketData);

        LocalDate valuationDate = DATA_DATE.plusMonths(6);
        double remainingYearFraction = ChronoUnit.DAYS.between(
                valuationDate, DATA_DATE.plusYears(1)) / 365.0;
        double expected = 105.0 * Math.exp(-(0.03 + 0.02) * remainingYearFraction);

        Assertions.assertEquals(expected, projection.valueAt(valuationDate), 1e-10);
        Assertions.assertEquals(0.0, projection.valueAt(DATA_DATE.plusYears(1)), 1e-12);
    }

    private Bond.BondTradeInfo buildBondInfo() {
        Bond.BondTradeInfo info = new Bond.BondTradeInfo();
        info.productCode = EngineConstants.PRODUCT_CODE.BOND;
        info.instrumentId = "UT_BOND_FORWARD_001";
        info.bondId = "UT_BOND_FORWARD_ID_001";
        info.currencyCode = "USD";
        info.issueDate = DATA_DATE;
        info.maturityDate = DATA_DATE.plusYears(1);
        info.interestStub = "LongEnd";
        info.interestType = "FIXED";
        info.interestRate = 0.05;
        info.payFreq = "1Y";
        info.dayCountBasis = "actual/365";
        info.discountCurve = "IR_USD";
        info.creditSpreadCurve = "CR_USD";
        info.notional = 100.0;
        info.positionTrade = 1.0;
        return info;
    }

    private MarketData buildMarketData() {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_USD", buildCurve("IR_USD", 0.03));
        marketData.irSpot.put("CR_USD", buildCurve("CR_USD", 0.02));

        FxSpot.FxSpotInfo fxSpot = new FxSpot.FxSpotInfo();
        fxSpot.dataDate = DATA_DATE;
        fxSpot.pDataDate = DATA_DATE;
        fxSpot.curveData = new HashMap<>();
        fxSpot.curveData.put("USD/CNY", 1.0);
        marketData.fxSpot = fxSpot;
        return marketData;
    }

    private IrSpot.IrSpotInfo buildCurve(String curveId, double rate) {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = "IR_SPOT";
        info.curveCode = curveId;
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, rate);
        info.curveData.put(365, rate);
        return info;
    }
}
