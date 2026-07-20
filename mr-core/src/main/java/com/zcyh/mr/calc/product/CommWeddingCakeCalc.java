package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommWeddingCake;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommWeddingCake 估值计算器
 */
public class CommWeddingCakeCalc extends AbstractProductCacheCalc<CommWeddingCake, CommWeddingCake.CommWeddingCakeTradeInfo> {

    public CommWeddingCakeCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommWeddingCake.CommWeddingCakeTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommWeddingCake.CommWeddingCakeTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(CommWeddingCake.CommWeddingCakeTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommWeddingCake createProduct(CommWeddingCake.CommWeddingCakeTradeInfo info, MarketData md) {
        return new CommWeddingCake(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommWeddingCake product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommWeddingCake product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
