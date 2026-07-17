package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B 类计算器基类：缓存产品实例，场景估值时直接调用 product.calc(scenarioMd) 复用。
 * 适用于产品类提供 calc(MarketData) 方法的场景估值模式。
 *
 * @param <P> 产品类型（如 FxFwd, CommSwap 等）
 * @param <I> Info 类型（如 FxFwd.FxFwdInfo 等）
 */
public abstract class AbstractProductCacheCalc<P, I> extends AbstractCalc {

    /** 基准估值后缓存产品实例，场景估值时直接复用 */
    protected Map<String, P> productCache = new LinkedHashMap<>();

    protected AbstractProductCacheCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    protected AbstractProductCacheCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData,
            Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected void calcTrade(HashMap<String, Object> tradeData) {
        I info = parseInfo(tradeData);
        P product = createProduct(info, this.marketData);
        Measure measure = doCalc(product);
        productCache.put(getInstrumentId(info), product);
        this.trade.add(measure);
        addLog(getInstrumentId(info), measure);
    }

    @Override
    protected void runScenarioLoop(MarketData scenarioMd,
            Set<String> affectedIds, JSONArray scenarioRst) {
        for (Map.Entry<String, P> entry : productCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            try {
                Measure measure = doScenarioCalc(entry.getValue(), scenarioMd);
                ensureScenarioInstrumentId(measure, entry.getKey());
                scenarioRst.add(measure);
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
    }

    private void ensureScenarioInstrumentId(Measure measure, String instrumentId) {
        if (measure == null || instrumentId == null || instrumentId.trim().isEmpty()) {
            return;
        }
        if (measure.instrumentId == null || measure.instrumentId.trim().isEmpty()) {
            measure.instrumentId = instrumentId;
        }
    }

    // ===== 子类实现以下方法 =====

    /** JSON HashMap → Info 对象反序列化 */
    protected abstract I parseInfo(HashMap<String, Object> tradeData);

    /** 从 Info 对象获取 INSTRUMENT_ID */
    protected abstract String getInstrumentId(I info);

    /** 使用 Info 和市场数据创建产品实例 */
    protected abstract P createProduct(I info, MarketData md);

    /** 基准估值：调用产品的 calc() */
    protected abstract Measure doCalc(P product);

    /** 场景估值：调用产品的 calc(MarketData) */
    protected abstract Measure doScenarioCalc(P product, MarketData scenarioMd);
}
