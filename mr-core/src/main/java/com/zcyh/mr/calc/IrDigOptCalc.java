package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrDigOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrDigOpt 估值计算器
 */
public class IrDigOptCalc extends AbstractProductCacheCalc<IrDigOpt, IrDigOpt.IrDigOptInfo> {

    public IrDigOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected IrDigOpt.IrDigOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrDigOpt.IrDigOptInfo.class);
    }

    @Override
    protected String getInstrumentId(IrDigOpt.IrDigOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrDigOpt createProduct(IrDigOpt.IrDigOptInfo info, MarketData md) {
        return new IrDigOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(IrDigOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrDigOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
