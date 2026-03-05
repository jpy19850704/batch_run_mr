package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir_deri.Swaption;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * @author xujg
 * @desc
 * @date 2024-11-01 09:09
 */
public class SwaptionTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    HashMap<String, IrSpot.IrSpotInfo> irSpot;
    HashMap<String, CommSpot.CommSpotInfo> commSpot;
    LocalDate dataDate;

    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/swaption.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.irSpot = loader.getMarketData().irSpot;
        this.commSpot = loader.getMarketData().commSpot;
        this.dataDate = loader.getDataDate();

        JSONObject trade = (JSONObject) trades.get(0);
        Swaption.SwaptionInfo info = JSONObject.parseObject(trade.toString(), Swaption.SwaptionInfo.class);
        Swaption opt = new Swaption(dataDate,info,marketData,new Calendar());
        Swaption.SwaptionMeasure measure = opt.calc();
    }
}
