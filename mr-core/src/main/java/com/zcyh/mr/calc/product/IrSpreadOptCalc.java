package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrSpreadOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrSpreadOpt 估值计算器
 */
public class IrSpreadOptCalc extends AbstractProductCacheCalc<IrSpreadOpt, IrSpreadOpt.SpreadOptInfo> {

    public IrSpreadOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected IrSpreadOpt.SpreadOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrSpreadOpt.SpreadOptInfo.class);
    }

    @Override
    protected String getInstrumentId(IrSpreadOpt.SpreadOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrSpreadOpt createProduct(IrSpreadOpt.SpreadOptInfo info, MarketData md) {
        return new IrSpreadOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(IrSpreadOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrSpreadOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
