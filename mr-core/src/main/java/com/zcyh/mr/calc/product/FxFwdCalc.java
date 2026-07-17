package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxFwd;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * 外汇远期估值计算器
 */
public class FxFwdCalc extends AbstractProductCacheCalc<FxFwd, FxFwd.FxFwdInfo> {

    public FxFwdCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxFwd.FxFwdInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxFwd.FxFwdInfo.class);
    }

    @Override
    protected String getInstrumentId(FxFwd.FxFwdInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxFwd createProduct(FxFwd.FxFwdInfo info, MarketData md) {
        return new FxFwd(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxFwd product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxFwd product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
