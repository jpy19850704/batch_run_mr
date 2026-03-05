package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.CommRangeAccureOpt;
import com.zcyh.mr.product.fx.IrRangeAccureOpt;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

public class RangeAccureIrTest {

    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;

    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/rangeaccureir.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.dataDate = loader.getDataDate();

        JSONObject trade = (JSONObject) trades.get(0);
        IrRangeAccureOpt.IrRangeAccureInfo fxDigOptInfo = JSONObject.parseObject(trade.toString(), IrRangeAccureOpt.IrRangeAccureInfo.class);
        IrRangeAccureOpt opt = new IrRangeAccureOpt(dataDate, fxDigOptInfo,marketData);

        IrRangeAccureOpt.RangeAccureMeasure measure = opt.calc();
    }
}

