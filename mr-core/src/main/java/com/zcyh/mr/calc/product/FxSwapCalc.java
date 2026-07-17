package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxSwap;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxSwap 估值计算器
 */
public class FxSwapCalc extends AbstractProductCacheCalc<FxSwap, FxSwap.FxSwapInfo> {

    public FxSwapCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxSwap.FxSwapInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxSwap.FxSwapInfo.class);
    }

    @Override
    protected String getInstrumentId(FxSwap.FxSwapInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxSwap createProduct(FxSwap.FxSwapInfo info, MarketData md) {
        return new FxSwap(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxSwap product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxSwap product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
