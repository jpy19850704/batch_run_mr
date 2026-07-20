package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir.Bond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

public class BondCalc extends AbstractProductCacheCalc<Bond, Bond.BondTradeInfo> {
    private static final Logger log = LoggerFactory.getLogger(BondCalc.class);

    public BondCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected Bond.BondTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSON.toJSONString(tradeData), Bond.BondTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(Bond.BondTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected Bond createProduct(Bond.BondTradeInfo info, MarketData md) {
        return new Bond(dataDate, info, md, calendar);
    }

    @Override
    protected Bond.BondMeasure doCalc(Bond product) {
        return product.calc();
    }

    @Override
    protected Bond.BondMeasure doScenarioCalc(Bond product, MarketData scenarioMd) {
        return product.hasValidCallPutDates()
                ? product.calcWithReselectMaturity(scenarioMd)
                : product.calc(scenarioMd);
    }

    @Override
    protected void handleError(HashMap<String, Object> tradeData, Exception e) {
        log.error("Bond 计算异常, instrumentId={}",
                java.util.Objects.toString(tradeData.get("INSTRUMENT_ID"), ""), e);
        super.handleError(tradeData, e);
    }
}
