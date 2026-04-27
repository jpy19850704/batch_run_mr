package com.zcyh.mr.product;

import com.zcyh.mr.core.Series;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.FxSharkFin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FxSharkFinDoubleBarrierRegressionTest {

    @Test
    public void testDoubleBarrierUsesSingleBarrierLegPath() {
        LocalDate dataDate = LocalDate.of(2026, 1, 1);
        FxSharkFin.FxSharkFinInfo info = buildInfo();
        MarketData marketData = buildMarketData(dataDate);

        FxSharkFin product = new FxSharkFinNoFrtb(dataDate, info, marketData);
        FxSharkFin.FxSharkFinMeasure measure = product.calc();

        Assertions.assertEquals("SUCCESS", measure.status);
        Assertions.assertTrue(Double.isFinite(measure.valuation));
        Assertions.assertNotNull(measure.detail);

        Object sigmaDoubleBarrier = measure.detail.get("SIGMA_DOUBLE_BARRIER");
        Assertions.assertTrue(sigmaDoubleBarrier instanceof Number);
        Assertions.assertTrue(Double.isFinite(((Number) sigmaDoubleBarrier).doubleValue()));
        Assertions.assertTrue(((Number) sigmaDoubleBarrier).doubleValue() > 0.0);

        Object upNoTouch = measure.detail.get("UP_NO_TOUCH_PROB");
        Object downNoTouch = measure.detail.get("DOWN_NO_TOUCH_PROB");
        Assertions.assertTrue(upNoTouch instanceof Number);
        Assertions.assertTrue(downNoTouch instanceof Number);
        Assertions.assertEquals(
                ((Number) upNoTouch).doubleValue(),
                ((Number) downNoTouch).doubleValue(),
                1e-12);

        Object upBarrierVvAdj = measure.detail.get("UP_BARRIER_VV_ADJ");
        Object downBarrierVvAdj = measure.detail.get("DOWN_BARRIER_VV_ADJ");
        Assertions.assertTrue(upBarrierVvAdj instanceof Number);
        Assertions.assertTrue(downBarrierVvAdj instanceof Number);
        Assertions.assertEquals(
                ((Number) upBarrierVvAdj).doubleValue(),
                ((Number) downBarrierVvAdj).doubleValue(),
                1e-12);
    }

    private FxSharkFin.FxSharkFinInfo buildInfo() {
        FxSharkFin.FxSharkFinInfo info = new FxSharkFin.FxSharkFinInfo();
        info.productCode = "FX_SHARKFIN";
        info.buyOrSell = "B";
        info.contractSize = 1.0;
        info.instrumentId = "UT_FX_SHARKFIN_DOUBLE_001";
        info.optionType = "DOUBLE";
        info.touchRate = 0.0300;
        info.baseRate = 0.0050;
        info.notional = 1_000_000.0;
        info.startDate = LocalDate.of(2026, 1, 1);
        info.maturityDate = LocalDate.of(2027, 1, 1);
        info.settleType = "CASH";
        info.volatilitySurface = "FXVOL_EURUSD";
        info.downBarrierPrice = 1.00;
        info.upBarrierPrice = 1.22;
        info.settleDate = LocalDate.of(2027, 1, 3);
        info.strikePrice = 1.10;
        info.currencyCode = "USD";
        info.vvFlag = true;

        info.underlyingCurrencyCode = "EUR";
        info.baseCurrencyCode = "USD";
        info.baseDiscountCurve = "IR_USD";
        info.underlyingDiscountCurve = "IR_EUR";
        info.discountCurve = "IR_USD";
        return info;
    }

    private MarketData buildMarketData(LocalDate dataDate) {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_USD", buildIrCurve(dataDate, 0.0280, 0.0310));
        marketData.irSpot.put("IR_EUR", buildIrCurve(dataDate, 0.0180, 0.0210));
        marketData.fxSpot = buildFxSpot(dataDate);
        marketData.fxVol.put("FXVOL_EURUSD", buildFxVol(dataDate));
        return marketData;
    }

    private IrSpot.IrSpotInfo buildIrCurve(LocalDate dataDate, double r0, double r1y) {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = "IR_SPOT";
        info.curveCode = "UT_IR";
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, r0);
        info.curveData.put(365, r1y);
        return info;
    }

    private FxSpot.FxSpotInfo buildFxSpot(LocalDate dataDate) {
        FxSpot.FxSpotInfo fx = new FxSpot.FxSpotInfo();
        fx.curveType = "FX_SPOT";
        fx.dataDate = dataDate;
        fx.pDataDate = dataDate;
        fx.curveData = new HashMap<>();
        fx.curveData.put("EUR/USD", 1.10);
        fx.curveData.put("USD/CNY", 7.20);
        return fx;
    }

    private FxVol.FxVolInfo buildFxVol(LocalDate dataDate) {
        FxVol.FxVolInfo volInfo = new FxVol.FxVolInfo();
        volInfo.curveType = "FX_VOL";
        volInfo.curveCode = "FXVOL_EURUSD";
        volInfo.dataDate = dataDate;
        volInfo.pDataDate = dataDate;
        volInfo.curveData = buildVolSurface();
        volInfo.shockCurveData = new ArrayList<>();
        return volInfo;
    }

    private List<Map<String, Object>> buildVolSurface() {
        List<Map<String, Object>> curve = new ArrayList<>();
        curve.add(volPoint(365, 0.10, 0.165));
        curve.add(volPoint(365, 0.25, 0.155));
        curve.add(volPoint(365, 0.50, 0.145));
        curve.add(volPoint(365, 0.75, 0.150));
        curve.add(volPoint(365, 0.90, 0.160));
        return curve;
    }

    private Map<String, Object> volPoint(int optionTerm, double delta, double volRate) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("DELTA", delta);
        point.put("VOLATILITY_RATE", volRate);
        return point;
    }

    private static class FxSharkFinNoFrtb extends FxSharkFin {
        public FxSharkFinNoFrtb(LocalDate dataDate, FxSharkFinInfo tradeInfo, MarketData marketData) {
            super(dataDate, tradeInfo, marketData);
        }

        @Override
        protected void postProcessOptionOutput(FxSharkFinMeasure measure) {
            measure.cashFlowList = null;
            measure.sensitivityList = null;
        }
    }
}
