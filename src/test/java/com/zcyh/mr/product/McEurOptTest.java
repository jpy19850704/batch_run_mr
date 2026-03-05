package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.McEurOpt;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

public class McEurOptTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;

    @Test
    public void testCalc() {
        long l = System.currentTimeMillis();
//        for (int i = 0; i < 3000; i++) {
            String data = FileUtils.loadData("data/trade/mcopt.json");
            Loader loader = new Loader(data);
            this.trades = loader.getTrades();
            this.marketData = loader.getMarketData();
            this.dataDate = loader.getDataDate();
//            System.out.println(System.currentTimeMillis() - l);
            JSONObject trade = (JSONObject) trades.get(0);
            McEurOpt.McEurOptInfo fxEurOptInfo = JSONObject.parseObject(trade.toString(), McEurOpt.McEurOptInfo.class);
            McEurOpt opt = new McEurOpt(dataDate,fxEurOptInfo,marketData);
            McEurOpt.McEurOptMeasure measure = opt.calc();
//            System.out.println(i);
//        }
        System.out.println(System.currentTimeMillis() - l);
    }
}
