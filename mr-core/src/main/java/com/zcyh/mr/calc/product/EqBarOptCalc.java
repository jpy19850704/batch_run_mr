package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqBarOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqBarOpt 估值计算器
 */
public class EqBarOptCalc extends AbstractProductCacheCalc<EqBarOpt, EqBarOpt.EqBarOptTradeInfo> {

    public EqBarOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqBarOpt.EqBarOptTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqBarOpt.EqBarOptTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(EqBarOpt.EqBarOptTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqBarOpt createProduct(EqBarOpt.EqBarOptTradeInfo info, MarketData md) {
        return new EqBarOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqBarOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqBarOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
