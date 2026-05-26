package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.IrsCcs;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IrsCcs 估值计算器
 */
public class IrsCcsCalc extends AbstractProductCacheCalc<IrsCcs, IrsCcs.IrsCcsInfo> {

    public IrsCcsCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData, Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected IrsCcs.IrsCcsInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), IrsCcs.IrsCcsInfo.class);
    }

    @Override
    protected String getInstrumentId(IrsCcs.IrsCcsInfo info) {
        return info.instrumentId;
    }

    @Override
    protected IrsCcs createProduct(IrsCcs.IrsCcsInfo info, MarketData md) {
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
