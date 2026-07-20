package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommVanillaOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommVanillaOpt 估值计算器
 */
public class CommOptCalc extends AbstractProductCacheCalc<CommVanillaOpt, CommVanillaOpt.CommOptTradeInfo> {

    public CommOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommVanillaOpt.CommOptTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommVanillaOpt.CommOptTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(CommVanillaOpt.CommOptTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommVanillaOpt createProduct(CommVanillaOpt.CommOptTradeInfo info, MarketData md) {
        return new CommVanillaOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommVanillaOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommVanillaOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
