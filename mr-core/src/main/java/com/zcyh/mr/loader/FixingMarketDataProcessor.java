package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.MarketData;

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
            JSONArray curveData,
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
        for (Object pointObj : curveData) {
            JSONObject pointJson = (JSONObject) pointObj;
            String fixDateText = pointJson.getString("TRADE_DATE");
            Object fixingValue = pointJson.get("FIXING_VALUE");
            if (fixDateText == null || fixDateText.isEmpty()) {
                validationCollector.error(curveType, fixingId,
                        "TRADE_DATE 为空, 点位被剔除");
                continue;
            }
            if (fixingValue == null) {
                validationCollector.error(curveType, fixingId,
                        "FIXING_VALUE 为空 (TRADE_DATE=" + fixDateText + "), 点位被剔除");
                continue;
            }
            try {
                LocalDate tradeDate = LocalDate.parse(fixDateText);
                fixingInfo.curveData.put(tradeDate, pointJson.getDoubleValue("FIXING_VALUE"));
            } catch (Exception ex) {
                validationCollector.error(curveType, fixingId,
                        "TRADE_DATE 格式错误: " + fixDateText + ", 点位被剔除");
            }
        }
        fixingInfo.pDataDate = dataDate;
        target.fixingRate.put(fixingId, fixingInfo);
    }
}
