package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.JsonNumberUtils;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir.Bond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author lsd
 * @version 1.0
 * @date 2024/7/15 14:49
 */

public class BondCalc implements Runnable, Calc.ScenarioCapable {
    private static final Logger log = LoggerFactory.getLogger(BondCalc.class);
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;
    String operCode;
    Calendar calendar;

    // 缓存已校准的 Bond 实例，场景估值时复用
    Map<String, Bond> bondCache = new LinkedHashMap<>();

    JSONObject rst = new JSONObject();
    JSONArray tradeCalcRst = new JSONArray();
    JSONArray logData = new JSONArray();

    public String calc() {
        this.run();
        this.rst.put("data", new JSONObject());
        ((JSONObject) this.rst.get("data")).put("trade_data", tradeCalcRst);
        ((JSONObject) this.rst.get("data")).put("log_data", logData);
        JsonNumberUtils.normalizeNumbersInPlace(this.rst);
        return JSON.toJSONString(this.rst, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    public BondCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar) {
        this.operCode = operCode;
        this.dataDate = dataDate;
        this.trades = trades;
        this.marketData = marketData;
        this.calendar = calendar;
    }

    @Override
    public void run() {
        if (Constants.OPER_CODE.PRICING.equalsIgnoreCase(operCode)) {
            for (HashMap<String, Object> trade : trades) {
                try {
                    calcTrade(trade);
                } catch (Exception e) {
                    log.error("Bond 计算异常, instrumentId={}", java.util.Objects.toString(trade.get("INSTRUMENT_ID"), ""), e);
                    AbstractCalc.appendErrorResult(tradeCalcRst, logData, dataDate, trade, e);
                }
            }
        }
    }

    private void calcTrade(HashMap<String, Object> trade) {
        Bond.BondInfo bondInfo = JSONObject.parseObject(JSON.toJSONString(trade), Bond.BondInfo.class);
        Bond bond = new Bond(dataDate, bondInfo, marketData, calendar);
        Bond.BondMeasure measure = bond.calc();
        // 缓存已校准的 Bond 实例
        bondCache.put(bondInfo.instrumentId, bond);
        tradeCalcRst.add(measure);
        if (!"SUCCESS".equals(measure.status)) {
            JSONObject loginfo = new JSONObject();
            loginfo.put("INSTRUMENT_ID", bondInfo.instrumentId);
            loginfo.put("info", "校验失败");
            logData.add(loginfo);
        }
    }

    /**
     * 场景估值：复用缓存的 Bond 实例，SOY 固定不重新校准。
     * 含权债使用 calcWithReselectMaturity 重新选行权日。
     */
    @Override
    public JSONArray calcScenario(MarketData scenarioMd) {
        return calcScenario(scenarioMd, null);
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        JSONArray scenarioRst = new JSONArray();
        for (Map.Entry<String, Bond> entry : bondCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            Bond bond = entry.getValue();
            try {
                Bond.BondMeasure measure = bond.hasValidCallPutDates()
                        ? bond.calcWithReselectMaturity(scenarioMd)
                        : bond.calc(scenarioMd);
                scenarioRst.add(measure);
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
        return scenarioRst;
    }
}
