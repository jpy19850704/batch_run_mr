package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractCalc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.Bond;
import com.zcyh.mr.product.ir.WillowBond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WillowBondCalc extends AbstractCalc {
    private static final Logger log = LoggerFactory.getLogger(WillowBondCalc.class);
    private final Map<String, CachedWillowBond> bondCache = new LinkedHashMap<>();

    public WillowBondCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected void calcTrade(HashMap<String, Object> tradeData) {
        WillowBond.WillowBondTradeInfo info = JSONObject.parseObject(JSON.toJSONString(tradeData),
                WillowBond.WillowBondTradeInfo.class);
        info.validateInput(new JSONObject(tradeData), String.valueOf(tradeData.get("PRODUCT_CODE")))
                .throwIfInvalid();
        WillowBond bond = new WillowBond(dataDate, info, marketData, calendar);
        Bond.BondMeasure measure = bond.calc();
        bondCache.put(info.instrumentId, new CachedWillowBond(info, measure.spreadOverYield));
        trade.add(measure);
    }

    @Override
    protected void runScenarioLoop(MarketData scenarioMd, Set<String> affectedIds, JSONArray scenarioRst) {
        for (Map.Entry<String, CachedWillowBond> entry : bondCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            CachedWillowBond cached = entry.getValue();
            try {
                WillowBond bond = new WillowBond(dataDate, cached.info, scenarioMd, calendar);
                bond.setSpreadOverYield(cached.spreadOverYield);
                Measure measure = bond.calc();
                ensureScenarioInstrumentId(measure, entry.getKey());
                scenarioRst.add(measure);
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
    }

    @Override
    protected void handleError(HashMap<String, Object> tradeData, Exception e) {
        log.error("WillowBond 计算异常, instrumentId={}",
                java.util.Objects.toString(tradeData.get("INSTRUMENT_ID"), ""), e);
        super.handleError(tradeData, e);
    }

    private static class CachedWillowBond {
        private final WillowBond.WillowBondTradeInfo info;
        private final double spreadOverYield;

        private CachedWillowBond(WillowBond.WillowBondTradeInfo info, double spreadOverYield) {
            this.info = info;
            this.spreadOverYield = spreadOverYield;
        }
    }
}
