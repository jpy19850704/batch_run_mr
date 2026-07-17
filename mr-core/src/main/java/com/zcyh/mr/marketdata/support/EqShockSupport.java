package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.MarketData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * EQ FRTB shock 支持类。
 */
public final class EqShockSupport {

    private EqShockSupport() {
    }

    /**
     * 构造 EQ Delta 情景。
     */
    public static FrtbMarketData buildDeltaShockMarket(MarketData marketData, String priceCurve, String bucket) {
        String eqBucket = normalizeBucket(bucket);
        double rw = getEqRiskWeight(eqBucket);
        Optional<EqSpot.EqSpotInfo> eqSpotInfo = Optional.ofNullable(marketData.eqSpot.get(priceCurve));
        if (!eqSpotInfo.isPresent()) {
            throw new IllegalArgumentException("缺少EQ_SPOT曲线: " + priceCurve);
        }
        MarketData marketNew = CommUtils.deepCopy(marketData);
        EqSpot.EqSpotInfo priceCurveInfo = CommUtils.deepCopy(marketNew.eqSpot.get(priceCurve));
        Series<Integer, Double> curveData = new Series<>(Integer.class, Double.class);
        for (Integer idx : priceCurveInfo.curveData.keySet()) {
            curveData.put(idx, priceCurveInfo.curveData.get(idx) * (1 + rw));
        }
        priceCurveInfo.curveData = curveData;
        FrtbMarketData frtbMarketData = new FrtbMarketData(marketNew);
        frtbMarketData.marketData.eqSpot.put(priceCurve, priceCurveInfo);
        frtbMarketData.riskFactorId = priceCurve;
        frtbMarketData.riskFactorClass = EngineConstants.FRTB.SA.RISK_CLASS.ER;
        frtbMarketData.riskFactorType = "Spot";
        frtbMarketData.riskFactorVertex1 = "";
        frtbMarketData.riskFactorBucket = eqBucket;
        frtbMarketData.sensitivityType = "Delta";
        frtbMarketData.riskWeight = rw;
        return frtbMarketData;
    }

    /**
     * 构造 EQ Curvature 情景列表。
     */
    public static List<FrtbMarketData> buildCurvatureShockMarkets(MarketData marketData, String priceCurve, String bucket) {
        List<FrtbMarketData> list = new ArrayList<>();
        Optional<EqSpot.EqSpotInfo> eqSpotInfo = Optional.ofNullable(marketData.eqSpot.get(priceCurve));
        if (!eqSpotInfo.isPresent()) {
            throw new IllegalArgumentException("缺少EQ_SPOT曲线: " + priceCurve);
        }
        String eqBucket = normalizeBucket(bucket);
        double rw = getEqRiskWeight(eqBucket);

        MarketData marketUp = CommUtils.deepCopy(marketData);
        FrtbMarketData up = new FrtbMarketData(marketUp);
        EqSpot.EqSpotInfo priceCurveUp = CommUtils.deepCopy(marketUp.eqSpot.get(priceCurve));
        Series<Integer, Double> curveDataUp = new Series<>(Integer.class, Double.class);
        for (Integer idx : priceCurveUp.curveData.keySet()) {
            curveDataUp.put(idx, priceCurveUp.curveData.get(idx) * (1 + rw));
        }
        priceCurveUp.curveData = curveDataUp;
        up.marketData.eqSpot.put(priceCurve, priceCurveUp);
        up.riskFactorId = priceCurve;
        up.riskFactorClass = EngineConstants.FRTB.SA.RISK_CLASS.ER;
        up.riskFactorType = "Spot";
        up.riskFactorBucket = eqBucket;
        up.riskFactorVertex1 = "";
        up.sensitivityType = "Curvature Up";
        up.riskWeight = -rw;
        list.add(up);

        MarketData marketDown = CommUtils.deepCopy(marketData);
        FrtbMarketData down = new FrtbMarketData(marketDown);
        EqSpot.EqSpotInfo priceCurveDown = CommUtils.deepCopy(marketDown.eqSpot.get(priceCurve));
        Series<Integer, Double> curveDataDown = new Series<>(Integer.class, Double.class);
        for (Integer idx : priceCurveDown.curveData.keySet()) {
            curveDataDown.put(idx, priceCurveDown.curveData.get(idx) * (1 - rw));
        }
        priceCurveDown.curveData = curveDataDown;
        down.marketData.eqSpot.put(priceCurve, priceCurveDown);
        down.riskFactorId = priceCurve;
        down.riskFactorClass = EngineConstants.FRTB.SA.RISK_CLASS.ER;
        down.riskFactorType = "Spot";
        down.riskFactorBucket = eqBucket;
        down.riskFactorVertex1 = "";
        down.sensitivityType = "Curvature Down";
        down.riskWeight = rw;
        list.add(down);
        return list;
    }

    private static String normalizeBucket(String bucket) {
        if (bucket == null || bucket.trim().isEmpty()) {
            throw new IllegalArgumentException("EQ 风险因子 bucket 不能为空");
        }
        return bucket.trim();
    }

    private static double getEqRiskWeight(String bucket) {
        HashMap<String, Double> eqWeights = FrtbParamsCache.getEQWeights();
        Double rw = eqWeights.get(bucket);
        if (rw == null) {
            throw new IllegalArgumentException("未配置 EQ 风险权重: " + bucket);
        }
        return rw;
    }
}
