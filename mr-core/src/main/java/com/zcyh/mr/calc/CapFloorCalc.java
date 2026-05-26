package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.CapFloor;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CapFloor 估值计算器
 */
public class CapFloorCalc extends AbstractProductCacheCalc<CapFloor, CapFloor.CapFloorInfo> {

    public CapFloorCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData, Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected CapFloor.CapFloorInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CapFloor.CapFloorInfo.class);
    }

    @Override
    protected String getInstrumentId(CapFloor.CapFloorInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CapFloor createProduct(CapFloor.CapFloorInfo info, MarketData md) {
        return new CapFloor(dataDate, info, md, calendar);
    }

    @Override
    protected Measure doCalc(CapFloor product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CapFloor product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
