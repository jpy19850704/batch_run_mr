package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommAsian;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * COMM 亚式期权估值计算器。
 */
public class CommAsianCalc extends AbstractProductCacheCalc<CommAsian, CommAsian.CommAsianTradeInfo> {

    public CommAsianCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommAsian.CommAsianTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommAsian.CommAsianTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(CommAsian.CommAsianTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommAsian createProduct(CommAsian.CommAsianTradeInfo info, MarketData md) {
        return new CommAsian(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommAsian product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommAsian product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
