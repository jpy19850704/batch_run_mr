package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommDigOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommDigOpt 估值计算器
 */
public class CommDigOptCalc extends AbstractProductCacheCalc<CommDigOpt, CommDigOpt.CommDigOptTradeInfo> {

    public CommDigOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommDigOpt.CommDigOptTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommDigOpt.CommDigOptTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(CommDigOpt.CommDigOptTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommDigOpt createProduct(CommDigOpt.CommDigOptTradeInfo info, MarketData md) {
        return new CommDigOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommDigOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommDigOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
