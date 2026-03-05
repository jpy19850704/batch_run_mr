package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.CommRangeAccureOpt;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

public class RangeAccureTest {

    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;

    @Test
    public void testCalc() {
        System.out.println(0.00==0);
        String data = FileUtils.loadData("data/trade/rangeaccure.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.dataDate = loader.getDataDate();

        JSONObject trade = (JSONObject) trades.get(0);
        CommRangeAccureOpt.CommRangeAccureInfo fxDigOptInfo = JSONObject.parseObject(trade.toString(), CommRangeAccureOpt.CommRangeAccureInfo.class);
        CommRangeAccureOpt opt = new CommRangeAccureOpt(dataDate, fxDigOptInfo,marketData);

        CommRangeAccureOpt.RangeAccureMeasure measure = opt.calc();
        System.out.println(11111);
    }
}

