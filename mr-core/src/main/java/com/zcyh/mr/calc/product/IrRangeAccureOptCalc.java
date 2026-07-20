package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrRangeAccureOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrRangeAccureOpt 估值计算器
 */
public class IrRangeAccureOptCalc extends AbstractProductCacheCalc<IrRangeAccureOpt, IrRangeAccureOpt.IrRangeAccureTradeInfo> {

    public IrRangeAccureOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected IrRangeAccureOpt.IrRangeAccureTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrRangeAccureOpt.IrRangeAccureTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(IrRangeAccureOpt.IrRangeAccureTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrRangeAccureOpt createProduct(IrRangeAccureOpt.IrRangeAccureTradeInfo info, MarketData md) {
        return new IrRangeAccureOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(IrRangeAccureOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrRangeAccureOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
