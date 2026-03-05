package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir_deri.IrsCcs;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * @author xujg
 * @desc
 * @date 2024-07-30 15:07
 */

class IrsCcsTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    HashMap<String, IrSpot.IrSpotInfo> irSpot;
    HashMap<String, CommSpot.CommSpotInfo> commSpot;
    LocalDate dataDate;

    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/irsccs.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.irSpot = loader.getMarketData().irSpot;
        this.commSpot = loader.getMarketData().commSpot;
        this.dataDate = loader.getDataDate();

        JSONObject trade = (JSONObject) trades.get(0);
        IrsCcs.IrsCcsInfo commOptInfo = JSONObject.parseObject(trade.toString(), IrsCcs.IrsCcsInfo.class);
        IrsCcs opt = new IrsCcs(dataDate,commOptInfo,marketData,new Calendar());
        IrsCcs.IrsCcsMeasure measure = opt.calc();
    }
}
