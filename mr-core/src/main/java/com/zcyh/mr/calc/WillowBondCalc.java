package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.JsonNumberUtils;
import com.zcyh.mr.marketdata.MarketData;
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

public class WillowBondCalc implements Runnable, Calc.ScenarioCapable {
    private static final Logger log = LoggerFactory.getLogger(WillowBondCalc.class);

    private final String operCode;
    private final LocalDate dataDate;
    private final List<HashMap<String, Object>> trades;
    private final MarketData marketData;
    private final Calendar calendar;

    private final JSONObject rst = new JSONObject();
    private final JSONArray tradeCalcRst = new JSONArray();
    private final JSONArray logData = new JSONArray();
    private final Map<String, CachedWillowBond> bondCache = new LinkedHashMap<>();

    public WillowBondCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar) {
        this.operCode = operCode;
        this.dataDate = dataDate;
        this.trades = trades;
        this.marketData = marketData;
        this.calendar = calendar;
    }

    public String calc() {
        this.run();
        this.rst.put("data", new JSONObject());
        ((JSONObject) this.rst.get("data")).put("trade_data", tradeCalcRst);
        ((JSONObject) this.rst.get("data")).put("log_data", logData);
        JsonNumberUtils.normalizeNumbersInPlace(this.rst);
        return JSON.toJSONString(this.rst, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    @Override
    public void run() {
        if (!Constants.OPER_CODE.PRICING.equalsIgnoreCase(operCode)) {
            return;
        }
        for (HashMap<String, Object> trade : trades) {
            try {
                calcTrade(trade);
            } catch (Exception e) {
                log.error("WillowBond 计算异常, instrumentId={}",
                        java.util.Objects.toString(trade.get("INSTRUMENT_ID"), ""), e);
                AbstractCalc.appendErrorResult(tradeCalcRst, logData, dataDate, trade, e);
            }
        }
    }

    private void calcTrade(HashMap<String, Object> trade) {
        WillowBond.WillowBondInfo info = JSONObject.parseObject(JSON.toJSONString(trade),
                WillowBond.WillowBondInfo.class);
        WillowBond bond = new WillowBond(dataDate, info, marketData, calendar);
        Bond.BondMeasure measure = bond.calc();
        bondCache.put(info.instrumentId, new CachedWillowBond(info, measure.spreadOverYield));
        tradeCalcRst.add(measure);
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd) {
        return calcScenario(scenarioMd, null);
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        JSONArray scenarioRst = new JSONArray();
        for (Map.Entry<String, CachedWillowBond> entry : bondCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            CachedWillowBond cached = entry.getValue();
            try {
                WillowBond bond = new WillowBond(dataDate, cached.info, scenarioMd, calendar);
                bond.setSpreadOverYield(cached.spreadOverYield);
                scenarioRst.add(bond.calc());
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
        return scenarioRst;
    }

    private static class CachedWillowBond {
        private final WillowBond.WillowBondInfo info;
        private final double spreadOverYield;

        private CachedWillowBond(WillowBond.WillowBondInfo info, double spreadOverYield) {
            this.info = info;
            this.spreadOverYield = spreadOverYield;
        }
    }
}
