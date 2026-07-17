package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxDigOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxDigOpt 估值计算器
 */
public class FxDigOptCalc extends AbstractProductCacheCalc<FxDigOpt, FxDigOpt.FxDigOptInfo> {

    public FxDigOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxDigOpt.FxDigOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxDigOpt.FxDigOptInfo.class);
    }

    @Override
    protected String getInstrumentId(FxDigOpt.FxDigOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxDigOpt createProduct(FxDigOpt.FxDigOptInfo info, MarketData md) {
        return new FxDigOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxDigOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxDigOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
