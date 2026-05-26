package com.zcyh.mr.calc;

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
public class FxStepUpOptCalc extends AbstractProductCacheCalc<FxStepUpOpt, FxStepUpOpt.FxStepUpInfo> {

    public FxStepUpOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxStepUpOpt.FxStepUpInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxStepUpOpt.FxStepUpInfo.class);
    }

    @Override
    protected String getInstrumentId(FxStepUpOpt.FxStepUpInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxStepUpOpt createProduct(FxStepUpOpt.FxStepUpInfo info, MarketData md) {
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
