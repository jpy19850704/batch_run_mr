package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.fx.IrStepUpOpt;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

public class StepUpIrTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;

    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/stepupIr.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.dataDate = loader.getDataDate();

        JSONObject trade = (JSONObject) trades.get(0);
        IrStepUpOpt.StepUpIrInfo fxDigOptInfo = JSONObject.parseObject(trade.toString(), IrStepUpOpt.StepUpIrInfo.class);
        IrStepUpOpt opt = new IrStepUpOpt(dataDate, fxDigOptInfo, marketData);

        IrStepUpOpt.StepUpMeasure measure = opt.calc();
    }
}

