package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxStepUpOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxStepUpOpt 估值计算器
 */
public class FxStepUpOptCalc extends AbstractProductCacheCalc<FxStepUpOpt, FxStepUpOpt.FxStepUpTradeInfo> {

    public FxStepUpOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxStepUpOpt.FxStepUpTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxStepUpOpt.FxStepUpTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(FxStepUpOpt.FxStepUpTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxStepUpOpt createProduct(FxStepUpOpt.FxStepUpTradeInfo info, MarketData md) {
        return new FxStepUpOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxStepUpOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxStepUpOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
