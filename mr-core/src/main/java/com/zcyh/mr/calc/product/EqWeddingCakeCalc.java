package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqWeddingCake;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqWeddingCake 估值计算器
 */
public class EqWeddingCakeCalc extends AbstractProductCacheCalc<EqWeddingCake, EqWeddingCake.EqWeddingCakeTradeInfo> {

    public EqWeddingCakeCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqWeddingCake.EqWeddingCakeTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqWeddingCake.EqWeddingCakeTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(EqWeddingCake.EqWeddingCakeTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqWeddingCake createProduct(EqWeddingCake.EqWeddingCakeTradeInfo info, MarketData md) {
        return new EqWeddingCake(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqWeddingCake product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqWeddingCake product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
