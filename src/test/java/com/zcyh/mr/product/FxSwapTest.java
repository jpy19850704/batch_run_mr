package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.FxSwap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxSwapTest
 *
 * @author cmh
 * @date 2024/8/6
 */
class FxSwapTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    HashMap<String, IrSpot.IrSpotInfo> irSpot;
    LocalDate dataDate;
    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/fxswap.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.irSpot = loader.getMarketData().irSpot;
        this.dataDate = loader.getDataDate();

        JSONObject trade = (JSONObject) trades.get(0);
        FxSwap.FxSwapInfo fxSwapInfo = JSONObject.parseObject(trade.toString(), FxSwap.FxSwapInfo.class);
        FxSwap opt = new FxSwap(dataDate,fxSwapInfo,marketData);
        FxSwap.FxSwapMeasure measure = opt.calc();

        JSONObject tradeRst = new JSONObject();
        tradeRst.put("INSTRUMENT_ID", fxSwapInfo.instrumentId);
        tradeRst.put("PRODUCT_CODE", fxSwapInfo.productCode);
        tradeRst.put("DATA_DATE", this.dataDate);
        tradeRst.put("VALUATION", measure.valuation);
        tradeRst.put("VALUATION_CNY", measure.valuationCny);
        tradeRst.put("UNDERLYING_PV01", measure.uPv01);
        tradeRst.put("BASE_PV01", measure.bPv01);
        tradeRst.put("FRTB_SENSE",measure.sensitivityList);
        tradeRst.put("CASH_FLOW",measure.cashFlowList);

        System.out.println(tradeRst.toString());

        System.out.println("VALUATION:"+measure.valuation);
        System.out.println("VALUATION_CNY:"+measure.valuationCny);
        BigDecimal un=new BigDecimal(measure.uPv01);
        System.out.println("uPv01:"+un);
        BigDecimal bn=new BigDecimal(measure.bPv01);
        System.out.println("bPv01:"+bn);
    }
}
