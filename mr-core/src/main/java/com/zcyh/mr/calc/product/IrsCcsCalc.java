package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrsCcs;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrsCcs 估值计算器
 */
public class IrsCcsCalc extends AbstractProductCacheCalc<IrsCcs, IrsCcs.IrsCcsTradeInfo> {

    public IrsCcsCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData, Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected IrsCcs.IrsCcsTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrsCcs.IrsCcsTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(IrsCcs.IrsCcsTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrsCcs createProduct(IrsCcs.IrsCcsTradeInfo info, MarketData md) {
        return new IrsCcs(dataDate, info, md, calendar);
    }

    @Override
    protected Measure doCalc(IrsCcs product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(IrsCcs product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
