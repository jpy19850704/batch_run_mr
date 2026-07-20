package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxAsian;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FX 亚式期权估值计算器。
 */
public class FxAsianCalc extends AbstractProductCacheCalc<FxAsian, FxAsian.FxAsianTradeInfo> {

    public FxAsianCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxAsian.FxAsianTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxAsian.FxAsianTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(FxAsian.FxAsianTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxAsian createProduct(FxAsian.FxAsianTradeInfo info, MarketData md) {
        return new FxAsian(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxAsian product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxAsian product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
