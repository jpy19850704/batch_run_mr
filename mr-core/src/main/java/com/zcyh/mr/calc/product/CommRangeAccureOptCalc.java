package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommRangeAccureOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommRangeAccureOpt 估值计算器
 */
public class CommRangeAccureOptCalc extends AbstractProductCacheCalc<CommRangeAccureOpt, CommRangeAccureOpt.CommRangeAccureTradeInfo> {

    public CommRangeAccureOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommRangeAccureOpt.CommRangeAccureTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommRangeAccureOpt.CommRangeAccureTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(CommRangeAccureOpt.CommRangeAccureTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommRangeAccureOpt createProduct(CommRangeAccureOpt.CommRangeAccureTradeInfo info, MarketData md) {
        return new CommRangeAccureOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommRangeAccureOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommRangeAccureOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
