package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommFwd;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommFwd 估值计算器
 */
public class CommFwdCalc extends AbstractProductCacheCalc<CommFwd, CommFwd.CommFwdInfo> {

    public CommFwdCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommFwd.CommFwdInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommFwd.CommFwdInfo.class);
    }

    @Override
    protected String getInstrumentId(CommFwd.CommFwdInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommFwd createProduct(CommFwd.CommFwdInfo info, MarketData md) {
        return new CommFwd(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommFwd product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommFwd product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
