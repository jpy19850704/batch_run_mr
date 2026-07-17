package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrBarOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrBarOpt 估值计算器
 */
public class IrBarOptCalc extends AbstractProductCacheCalc<IrBarOpt, IrBarOpt.IrBarOptInfo> {

    public IrBarOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected IrBarOpt.IrBarOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrBarOpt.IrBarOptInfo.class);
    }

    @Override
    protected String getInstrumentId(IrBarOpt.IrBarOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrBarOpt createProduct(IrBarOpt.IrBarOptInfo info, MarketData md) {
        return new IrBarOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(IrBarOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrBarOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
