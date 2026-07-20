package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommSwap;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommSwap 估值计算器
 */
public class CommSwapCalc extends AbstractProductCacheCalc<CommSwap, CommSwap.CommSwapTradeInfo> {

    public CommSwapCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommSwap.CommSwapTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommSwap.CommSwapTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(CommSwap.CommSwapTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommSwap createProduct(CommSwap.CommSwapTradeInfo info, MarketData md) {
        return new CommSwap(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommSwap product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommSwap product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
