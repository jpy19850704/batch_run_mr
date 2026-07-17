package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqStepUpOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqStepUpOpt 估值计算器
 */
public class EqStepUpOptCalc extends AbstractProductCacheCalc<EqStepUpOpt, EqStepUpOpt.EqStepUpInfo> {

    public EqStepUpOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqStepUpOpt.EqStepUpInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqStepUpOpt.EqStepUpInfo.class);
    }

    @Override
    protected String getInstrumentId(EqStepUpOpt.EqStepUpInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqStepUpOpt createProduct(EqStepUpOpt.EqStepUpInfo info, MarketData md) {
        return new EqStepUpOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqStepUpOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqStepUpOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
