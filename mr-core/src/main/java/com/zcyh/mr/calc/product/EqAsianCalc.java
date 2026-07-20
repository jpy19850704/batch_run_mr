package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqAsian;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EQ 亚式期权估值计算器。
 */
public class EqAsianCalc extends AbstractProductCacheCalc<EqAsian, EqAsian.EqAsianTradeInfo> {

    public EqAsianCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqAsian.EqAsianTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqAsian.EqAsianTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(EqAsian.EqAsianTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqAsian createProduct(EqAsian.EqAsianTradeInfo info, MarketData md) {
        return new EqAsian(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqAsian product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqAsian product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
