package com.zcyh.mr.calc;

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
public class CommDigOptCalc extends AbstractProductCacheCalc<CommDigOpt, CommDigOpt.CommDigOptInfo> {

    public CommDigOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommDigOpt.CommDigOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommDigOpt.CommDigOptInfo.class);
    }

    @Override
    protected String getInstrumentId(CommDigOpt.CommDigOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommDigOpt createProduct(CommDigOpt.CommDigOptInfo info, MarketData md) {
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
