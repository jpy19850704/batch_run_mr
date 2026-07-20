package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrSharkFin;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrSharkFin 估值计算器
 */
public class IrSharkFinCalc extends AbstractProductCacheCalc<IrSharkFin, IrSharkFin.IrSharkFinTradeInfo> {

    public IrSharkFinCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected IrSharkFin.IrSharkFinTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrSharkFin.IrSharkFinTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(IrSharkFin.IrSharkFinTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrSharkFin createProduct(IrSharkFin.IrSharkFinTradeInfo info, MarketData md) {
        return new IrSharkFin(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(IrSharkFin product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrSharkFin product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
