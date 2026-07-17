package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrWeddingCake;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrWeddingCake 估值计算器
 */
public class IrWeddingCakeCalc extends AbstractProductCacheCalc<IrWeddingCake, IrWeddingCake.IrWeddingCakeInfo> {

    public IrWeddingCakeCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected IrWeddingCake.IrWeddingCakeInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrWeddingCake.IrWeddingCakeInfo.class);
    }

    @Override
    protected String getInstrumentId(IrWeddingCake.IrWeddingCakeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrWeddingCake createProduct(IrWeddingCake.IrWeddingCakeInfo info, MarketData md) {
        return new IrWeddingCake(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(IrWeddingCake product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrWeddingCake product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
