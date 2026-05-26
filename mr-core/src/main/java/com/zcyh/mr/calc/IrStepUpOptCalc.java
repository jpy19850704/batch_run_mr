package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrStepUpOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrStepUpOpt 估值计算器
 */
public class IrStepUpOptCalc extends AbstractProductCacheCalc<IrStepUpOpt, IrStepUpOpt.IrStepUpInfo> {

    public IrStepUpOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected IrStepUpOpt.IrStepUpInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrStepUpOpt.IrStepUpInfo.class);
    }

    @Override
    protected String getInstrumentId(IrStepUpOpt.IrStepUpInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrStepUpOpt createProduct(IrStepUpOpt.IrStepUpInfo info, MarketData md) {
        return new IrStepUpOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(IrStepUpOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrStepUpOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
