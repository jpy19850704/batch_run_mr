package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxVanillaOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxVanillaOpt 估值计算器
 */
public class FxVanillaOptCalc extends AbstractProductCacheCalc<FxVanillaOpt, FxVanillaOpt.VanillaOptInfo> {

    public FxVanillaOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxVanillaOpt.VanillaOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxVanillaOpt.VanillaOptInfo.class);
    }

    @Override
    protected String getInstrumentId(FxVanillaOpt.VanillaOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxVanillaOpt createProduct(FxVanillaOpt.VanillaOptInfo info, MarketData md) {
        return new FxVanillaOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxVanillaOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxVanillaOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
