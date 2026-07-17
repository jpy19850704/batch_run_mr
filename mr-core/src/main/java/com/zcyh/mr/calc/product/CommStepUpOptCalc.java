package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommStepUpOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommStepUpOpt 估值计算器
 */
public class CommStepUpOptCalc extends AbstractProductCacheCalc<CommStepUpOpt, CommStepUpOpt.CommStepUpInfo> {

    public CommStepUpOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommStepUpOpt.CommStepUpInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommStepUpOpt.CommStepUpInfo.class);
    }

    @Override
    protected String getInstrumentId(CommStepUpOpt.CommStepUpInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommStepUpOpt createProduct(CommStepUpOpt.CommStepUpInfo info, MarketData md) {
        return new CommStepUpOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommStepUpOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommStepUpOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
