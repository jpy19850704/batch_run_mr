package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.FxDigOpt;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

public class FxDigOptTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;

    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/digopt.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.dataDate = loader.getDataDate();

        JSONObject trade = (JSONObject) trades.get(0);
        FxDigOpt.FxDigOptInfo fxDigOptInfo = JSONObject.parseObject(trade.toString(), FxDigOpt.FxDigOptInfo.class);
        FxDigOpt opt = new FxDigOpt(dataDate, fxDigOptInfo,marketData);

        FxDigOpt.FxDigOptMeasure measure = opt.calc();
        System.out.println(11111);
    }
}
