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
public class EqStepUpOptCalc extends AbstractProductCacheCalc<EqStepUpOpt, EqStepUpOpt.EqStepUpTradeInfo> {

    public EqStepUpOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqStepUpOpt.EqStepUpTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqStepUpOpt.EqStepUpTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(EqStepUpOpt.EqStepUpTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqStepUpOpt createProduct(EqStepUpOpt.EqStepUpTradeInfo info, MarketData md) {
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
