package com.zcyh.mr.product;

import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.FxFwd;
import com.zcyh.mr.product.fx.FxSwap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;

public class FxForwardSwapInputValidationTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2025, 12, 31);

    @Test
    public void testFxFwdAllowsZeroNotional() {
        FxFwd.FxFwdInfo info = buildFxFwdInfo();
        info.underlyingCurrencyNotional = 0.0;
        info.baseCurrencyNotional = 0.0;

        FxFwd.FxFwdMeasure result = new FxFwd(DATA_DATE, info, buildMarketData()).calc(buildMarketData());

        Assertions.assertEquals(0.0, result.valuation);
        Assertions.assertEquals(0.0, result.position);
    }

    @Test
    public void testFxSwapAllowsZeroNotional() {
        FxSwap.FxSwapInfo info = buildFxSwapInfo();
        info.underlyingCurrencyNotionalSpot = 0.0;
        info.baseCurrencyNotionalSpot = 0.0;
        info.underlyingCurrencyNotionalFwd = 0.0;
        info.baseCurrencyNotionalFwd = 0.0;

        FxSwap.FxSwapMeasure result = new FxSwap(DATA_DATE, info, buildMarketData()).calc(buildMarketData());

        Assertions.assertEquals(0.0, result.valuation);
        Assertions.assertEquals(0.0, result.position);
    }

    @Test
    public void testFxFwdRejectsNegativeNotional() {
        FxFwd.FxFwdInfo info = buildFxFwdInfo();
        info.baseCurrencyNotional = -1.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FxFwd(DATA_DATE, info, buildMarketData()).calc(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("BASE_CURRENCY_NOTIONAL"));
    }

    @Test
    public void testFxSwapRejectsNegativeNotional() {
        FxSwap.FxSwapInfo info = buildFxSwapInfo();
        info.underlyingCurrencyNotionalSpot = -1.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FxSwap(DATA_DATE, info, buildMarketData()).calc(buildMarketData()));

        Assertions.assertTrue(exception.getMessage().contains("UNDERLYING_CURRENCY_NOTIONAL_SPOT"));
    }

    @Test
    public void testFxFwdDirectionIgnoresCase() {
        FxFwd.FxFwdInfo uppercaseInfo = buildFxFwdInfo();
        FxFwd.FxFwdInfo lowercaseInfo = buildFxFwdInfo();
        lowercaseInfo.buyOrSell = "b";

        FxFwd.FxFwdMeasure uppercase = new FxFwd(
                DATA_DATE, uppercaseInfo, buildMarketData()).calc(buildMarketData());
        FxFwd.FxFwdMeasure lowercase = new FxFwd(
                DATA_DATE, lowercaseInfo, buildMarketData()).calc(buildMarketData());

        Assertions.assertEquals(uppercase.valuation, lowercase.valuation);
        Assertions.assertEquals(uppercase.position, lowercase.position);
    }

    @Test
    public void testFxSwapDirectionIgnoresCase() {
        FxSwap.FxSwapInfo uppercaseInfo = buildFxSwapInfo();
        FxSwap.FxSwapInfo lowercaseInfo = buildFxSwapInfo();
        lowercaseInfo.buyOrSell = "b";

        FxSwap.FxSwapMeasure uppercase = new FxSwap(
                DATA_DATE, uppercaseInfo, buildMarketData()).calc(buildMarketData());
        FxSwap.FxSwapMeasure lowercase = new FxSwap(
                DATA_DATE, lowercaseInfo, buildMarketData()).calc(buildMarketData());

        Assertions.assertEquals(uppercase.valuation, lowercase.valuation);
        Assertions.assertEquals(uppercase.position, lowercase.position);
    }

    private FxFwd.FxFwdInfo buildFxFwdInfo() {
        FxFwd.FxFwdInfo info = new FxFwd.FxFwdInfo();
        info.productCode = Constants.PRODUCT_CODE.FXFWD;
        info.instrumentId = "UT_FXFWD_001";
        info.buyOrSell = "B";
        info.underlyingCurrencyCode = "EUR";
        info.baseCurrencyCode = "USD";
        info.underlyingCurrencyNotional = 1_000_000.0;
        info.baseCurrencyNotional = 1_100_000.0;
        info.settleDate = DATA_DATE.plusMonths(3);
        info.underlyingDiscountCurve = "IR_EUR";
        info.baseDiscountCurve = "IR_USD";
        return info;
    }

    private FxSwap.FxSwapInfo buildFxSwapInfo() {
        FxSwap.FxSwapInfo info = new FxSwap.FxSwapInfo();
        info.productCode = Constants.PRODUCT_CODE.FXSWAP;
        info.instrumentId = "UT_FXSWAP_001";
        info.buyOrSell = "B";
        info.underlyingCurrencyCode = "EUR";
        info.baseCurrencyCode = "USD";
        info.underlyingCurrencyNotionalSpot = 1_000_000.0;
        info.baseCurrencyNotionalSpot = 1_100_000.0;
        info.underlyingCurrencyNotionalFwd = 1_000_000.0;
        info.baseCurrencyNotionalFwd = 1_105_000.0;
        info.spotSettleDate = DATA_DATE.plusDays(2);
        info.fwdSettleDate = DATA_DATE.plusMonths(3);
        info.underlyingDiscountCurve = "IR_EUR";
        info.baseDiscountCurve = "IR_USD";
        return info;
    }

    private MarketData buildMarketData() {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_USD", buildIrCurve("IR_USD", 0.028, 0.031));
        marketData.irSpot.put("IR_EUR", buildIrCurve("IR_EUR", 0.016, 0.020));
        marketData.fxSpot = buildFxSpot();
        return marketData;
    }

    private IrSpot.IrSpotInfo buildIrCurve(String code, double currentRate, double oneYearRate) {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = "IR_SPOT";
        info.curveCode = code;
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, currentRate);
        info.curveData.put(365, oneYearRate);
        return info;
    }

    private FxSpot.FxSpotInfo buildFxSpot() {
        FxSpot.FxSpotInfo info = new FxSpot.FxSpotInfo();
        info.curveType = "FX_SPOT";
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.curveData = new HashMap<>();
        info.curveData.put("EUR/USD", 1.10);
        info.curveData.put("USD/CNY", 7.20);
        return info;
    }
}
