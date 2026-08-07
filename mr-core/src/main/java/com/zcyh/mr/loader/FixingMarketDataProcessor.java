package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.input.MarketDataInputs;

import java.time.LocalDate;

/**
 * 定盘市场数据处理器。
 */
final class FixingMarketDataProcessor {
    private final LocalDate dataDate;
    private final MarketDataValidationCollector validationCollector;

    FixingMarketDataProcessor(LocalDate dataDate, MarketDataValidationCollector validationCollector) {
        this.dataDate = dataDate;
        this.validationCollector = validationCollector;
    }

    void process(
            MarketData target,
            JSONObject marketJson,
            String curveType) {
        String fixingId = marketJson.getString("FIXING_ID");
        if (fixingId == null || fixingId.isEmpty()) {
            validationCollector.error(curveType, "", "FIXING_ID 为空");
            return;
        }
        Fixing.FixingInfo fixingInfo = MarketDataInputMapper.parseCurveMeta(
                marketJson, Fixing.FixingInfo.class);
        try {
            fixingInfo.interpolateType = MarketDataInputMapper.normalizeInterpolateType(
                    fixingInfo.interpolateType, Interpolation.Type.FORWARD, "INTERPOLATE_TYPE");
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, fixingId, ex.getMessage());
            return;
        }
        MarketDataInputs.FixingInput input = marketJson.to(MarketDataInputs.FixingInput.class);
        for (MarketDataInputs.FixingPointInput point : input.curveData) {
            fixingInfo.curveData.put(point.tradeDate, point.fixingValue.doubleValue());
        }
        fixingInfo.pDataDate = dataDate;
        target.fixingRate.put(fixingId, fixingInfo);
    }
}
