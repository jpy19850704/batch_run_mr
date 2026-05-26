package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxWeddingCake;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxWeddingCake 估值计算器
 */
public class FxWeddingCakeCalc extends AbstractProductCacheCalc<FxWeddingCake, FxWeddingCake.FxWeddingCakeInfo> {

    public FxWeddingCakeCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxWeddingCake.FxWeddingCakeInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxWeddingCake.FxWeddingCakeInfo.class);
    }

    @Override
    protected String getInstrumentId(FxWeddingCake.FxWeddingCakeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxWeddingCake createProduct(FxWeddingCake.FxWeddingCakeInfo info, MarketData md) {
        return new FxWeddingCake(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxWeddingCake product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxWeddingCake product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
