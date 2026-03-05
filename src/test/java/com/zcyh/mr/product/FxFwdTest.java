package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.CashflowUtils;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.fx.FxFwd;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.List;

/**
 * FxFwdTest
 *
 * @author cmh
 * @date 2024/8/6
 */
class FxFwdTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    HashMap<String, IrSpot.IrSpotInfo> irSpot;
    FxSpot.FxSpotInfo fxSpotInfo;
    LocalDate dataDate;
    FrtbMarketData frtbMarketData;
    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/fxfwd.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.irSpot = loader.getMarketData().irSpot;
        this.dataDate = loader.getDataDate();
        this.fxSpotInfo = loader.getMarketData().fxSpot;
        this.frtbMarketData=new FrtbMarketData(this.marketData);

        JSONObject trade = (JSONObject) trades.get(0);
        FxFwd.FxFwdInfo FxFwdInfo = JSONObject.parseObject(trade.toString(), FxFwd.FxFwdInfo.class);
        FxFwd opt = new FxFwd(dataDate,FxFwdInfo,marketData);
        FxFwd.FxFwdMeasure measure = opt.calc();

        JSONObject tradeRst = new JSONObject();
        tradeRst.put("INSTRUMENT_ID", FxFwdInfo.instrumentId);
        tradeRst.put("PRODUCT_CODE", FxFwdInfo.productCode);
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
        System.out.println("uPv01:"+measure.uPv01);
        System.out.println("bPv01:"+measure.bPv01);

    }


    @Test
    public void testCalc1() {
        Period resetFreq = CashflowUtils.convertFreq("3M");
        Period resetFreq1 = CashflowUtils.convertFreq("4D");


        System.out.println(resetFreq1.toTotalMonths());
    }
}
