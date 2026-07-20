package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxBarOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxBarOpt 估值计算器
 */
public class FxBarOptCalc extends AbstractProductCacheCalc<FxBarOpt, FxBarOpt.FxBarOptTradeInfo> {

    public FxBarOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxBarOpt.FxBarOptTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxBarOpt.FxBarOptTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(FxBarOpt.FxBarOptTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxBarOpt createProduct(FxBarOpt.FxBarOptTradeInfo info, MarketData md) {
        return new FxBarOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxBarOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxBarOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
