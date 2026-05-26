package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommBarOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommBarOpt 估值计算器
 */
public class CommBarOptCalc extends AbstractProductCacheCalc<CommBarOpt, CommBarOpt.CommBarOptInfo> {

    public CommBarOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommBarOpt.CommBarOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommBarOpt.CommBarOptInfo.class);
    }

    @Override
    protected String getInstrumentId(CommBarOpt.CommBarOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommBarOpt createProduct(CommBarOpt.CommBarOptInfo info, MarketData md) {
        return new CommBarOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommBarOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommBarOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
