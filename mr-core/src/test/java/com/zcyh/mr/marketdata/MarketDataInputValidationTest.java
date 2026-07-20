package com.zcyh.mr.marketdata;

import com.zcyh.mr.support.EngineConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataInputValidationTest {

    @Test
    void validatesOnlyRequestedMarketDataType() {
        MarketData marketData = new MarketData();
        marketData.fxSpot.curveData.put("USD/CNY", 7.2d);

        List<String> errors = marketData.validateInput(EngineConstants.RF_TYPE.FX_SPOT);

        assertTrue(errors.isEmpty());
    }

    @Test
    void rejectsInvalidRequestedMarketDataPoint() {
        MarketData marketData = new MarketData();
        marketData.fxSpot.curveData.put("USDCNY", 0.0d);

        List<String> errors = marketData.validateInput(EngineConstants.RF_TYPE.FX_SPOT);

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("CURRENCY 格式错误")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("RATE 必须大于0")));
    }

    @Test
    void doesNotTreatIrSpotAsCreditSpot() {
        MarketData marketData = new MarketData();
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = EngineConstants.RF_TYPE.IR_SPOT;
        marketData.irSpot.put("IR_CNY", info);

        List<String> errors = marketData.validateInput(EngineConstants.RF_TYPE.CREDIT_SPOT);

        assertTrue(errors.stream().anyMatch(error -> error.equals("CREDIT_SPOT: 市场数据为空")));
    }
}
