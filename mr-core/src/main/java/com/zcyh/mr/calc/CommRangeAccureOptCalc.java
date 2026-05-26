package com.zcyh.mr.calc;

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
public class CommRangeAccureOptCalc extends AbstractProductCacheCalc<CommRangeAccureOpt, CommRangeAccureOpt.CommRangeAccureInfo> {

    public CommRangeAccureOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommRangeAccureOpt.CommRangeAccureInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommRangeAccureOpt.CommRangeAccureInfo.class);
    }

    @Override
    protected String getInstrumentId(CommRangeAccureOpt.CommRangeAccureInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommRangeAccureOpt createProduct(CommRangeAccureOpt.CommRangeAccureInfo info, MarketData md) {
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
