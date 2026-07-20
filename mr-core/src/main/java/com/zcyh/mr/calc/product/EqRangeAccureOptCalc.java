package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqRangeAccureOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqRangeAccureOpt 估值计算器
 */
public class EqRangeAccureOptCalc extends AbstractProductCacheCalc<EqRangeAccureOpt, EqRangeAccureOpt.EqRangeAccureTradeInfo> {

    public EqRangeAccureOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqRangeAccureOpt.EqRangeAccureTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqRangeAccureOpt.EqRangeAccureTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(EqRangeAccureOpt.EqRangeAccureTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqRangeAccureOpt createProduct(EqRangeAccureOpt.EqRangeAccureTradeInfo info, MarketData md) {
        return new EqRangeAccureOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqRangeAccureOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqRangeAccureOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
