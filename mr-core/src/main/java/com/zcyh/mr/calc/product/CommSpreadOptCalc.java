package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommSpreadOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommSpreadOpt 估值计算器
 */
public class CommSpreadOptCalc extends AbstractProductCacheCalc<CommSpreadOpt, CommSpreadOpt.SpreadOptInfo> {

    public CommSpreadOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommSpreadOpt.SpreadOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommSpreadOpt.SpreadOptInfo.class);
    }

    @Override
    protected String getInstrumentId(CommSpreadOpt.SpreadOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommSpreadOpt createProduct(CommSpreadOpt.SpreadOptInfo info, MarketData md) {
        return new CommSpreadOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommSpreadOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommSpreadOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
