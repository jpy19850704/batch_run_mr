package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.JsonNumberUtils;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir.BondFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author xujg
 * @date 2024-10-25 15:23
 */
public class BondFutureCalc implements Runnable, Calc.ScenarioCapable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BondFutureCalc.class);
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;
    String operCode;
    Calendar calendar;
    JSONObject otherData;

    // 缓存已校准的 BondFuture 实例，场景估值时复用
    Map<String, BondFuture> bondFutureCache = new LinkedHashMap<>();

    JSONObject result = new JSONObject();
    JSONArray trade = new JSONArray();
    JSONArray log = new JSONArray();

    public BondFutureCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar, JSONObject otherData) {
        this.operCode = operCode;
        this.dataDate = dataDate;
        this.trades = trades;
        this.marketData = marketData;
        this.calendar = calendar;
        this.otherData = otherData;
    }

    public String calc() {
        this.run();
        this.result.put("data", new JSONObject());
        ((JSONObject) this.result.get("data")).put("trade_data", trade);
        ((JSONObject) this.result.get("data")).put("log_data", log);
        JsonNumberUtils.normalizeNumbersInPlace(this.result);
        return JSON.toJSONString(this.result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    @Override
    public void run() {
        if (Constants.CALC_MODE.PRICING.equalsIgnoreCase(operCode)) {
            for (HashMap<String, Object> trade : trades) {
                try {
                    calcTrade(trade);
                } catch (Exception e) {
                    LOGGER.error("BondFuture 计算异常, instrumentId={}", java.util.Objects.toString(trade.get("INSTRUMENT_ID"), ""), e);
                    AbstractCalc.appendErrorResult(this.trade, log, dataDate, trade, e);
                }
            }
        }
    }

    private void calcTrade(HashMap<String, Object> trade) {
        BondFuture.BondFutureInfo bondFutureInfo = JSONObject.parseObject(JSON.toJSONString(trade),
                BondFuture.BondFutureInfo.class);
        // 从 trade_data 中的 UNDERLYING_DATA 数组还原为按 BOND_ID 索引的 JSONObject
        JSONObject und = new JSONObject();
        Object rawUd = trade.get("UNDERLYING_DATA");
        if (rawUd instanceof JSONArray) {
            for (Object obj : (JSONArray) rawUd) {
                JSONObject bond = (JSONObject) obj;
                String bondId = bond.getString("BOND_ID");
                if (bondId != null) {
                    und.put(bondId, bond);
                }
            }
        }
        BondFuture future = new BondFuture(dataDate, bondFutureInfo, marketData, calendar, und);
        BondFuture.BondFutureMeasure measure = future.calc();
        // 缓存已校准的 BondFuture 实例
        bondFutureCache.put(bondFutureInfo.instrumentId, future);
        this.trade.add(measure);
    }

    /**
     * 场景估值：复用缓存的 BondFuture 实例，SOY + CTD + netBasis 固定不重新校准
     */
    @Override
    public JSONArray calcScenario(MarketData scenarioMd) {
        return calcScenario(scenarioMd, null);
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        JSONArray scenarioRst = new JSONArray();
        for (Map.Entry<String, BondFuture> entry : bondFutureCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            BondFuture bf = entry.getValue();
            try {
                BondFuture.BondFutureMeasure measure = bf.calc(scenarioMd);
                scenarioRst.add(measure);
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
        return scenarioRst;
    }

}
