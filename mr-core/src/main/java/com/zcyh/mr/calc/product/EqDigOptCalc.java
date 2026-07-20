package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqDigOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqDigOpt 估值计算器
 */
public class EqDigOptCalc extends AbstractProductCacheCalc<EqDigOpt, EqDigOpt.EqDigOptTradeInfo> {

    public EqDigOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqDigOpt.EqDigOptTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqDigOpt.EqDigOptTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(EqDigOpt.EqDigOptTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqDigOpt createProduct(EqDigOpt.EqDigOptTradeInfo info, MarketData md) {
        return new EqDigOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqDigOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqDigOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
