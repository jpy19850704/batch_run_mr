package com.zcyh.mr.core;

import com.zcyh.mr.marketdata.FxSpot;

import java.util.HashMap;
import java.util.Map;

/**
 * 外汇汇率工具类
 *
 * @author cmh
 * @date 2024/8/1
 */
public class FxUtils {

    /**
     * 根据外汇即期汇率数据，生成以USD和CNY为基准的汇率表
     *
     * @param fxSpotInfo 外汇即期汇率信息
     * @return 包含 "forex_usd" 和 "forex_cny" 两个子Map的结果
     */
    public static HashMap<String, Map<String, Double>> getForex(FxSpot.FxSpotInfo fxSpotInfo) {

        HashMap<String, Map<String, Double>> resultMap = new HashMap<>();

        String underlyingCurrency = "";
        double cnyUsd = 1.0;

        HashMap<String, Double> forexUsd = new HashMap<>();
        HashMap<String, Double> forexCny = new HashMap<>();
        HashMap<String, Double> forexCnyTemp = new HashMap<>();

        for (Map.Entry<String, Double> entry : fxSpotInfo.curveData.entrySet()) {
            String currency = entry.getKey();
            double rate = entry.getValue();
            String baseCcy = currency.substring(4, 7);
            String underlying = currency.substring(0, 3);

            if (currency.contains("USD")) {
                double fxRate = 0.0;
                if ("USD".equals(underlying)) {
                    fxRate = 1 / rate;
                    underlyingCurrency = baseCcy;
                }

                if ("USD".equals(baseCcy)) {
                    fxRate = rate;
                    underlyingCurrency = underlying;
                }

                forexUsd.put(underlyingCurrency, fxRate);
                if ("CNY".equals(underlyingCurrency)) {
                    cnyUsd = fxRate;
                    forexCnyTemp.put("CNY", 1.0);
                } else {
                    forexCnyTemp.put(underlyingCurrency, fxRate);
                }
            }
        }

        for (Map.Entry<String, Double> entryCny : forexCnyTemp.entrySet()) {
            String cnyCurrency = entryCny.getKey();
            double cnyRate = entryCny.getValue();
            if ("CNY".equals(cnyCurrency)) {
                forexCny.put(cnyCurrency, cnyRate);
            } else {
                forexCny.put(cnyCurrency, cnyRate / cnyUsd);
            }
        }
        forexCny.put("USD", 1 / cnyUsd);
        resultMap.put("forex_usd", forexUsd);
        resultMap.put("forex_cny", forexCny);
        return resultMap;
    }
}
