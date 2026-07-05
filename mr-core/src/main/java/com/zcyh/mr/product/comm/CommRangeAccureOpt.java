package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.structure.RangeAccureOptBase;
import com.zcyh.mr.marketdata.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 商品标的区间累计期权。
 * 标的价格取自商品远期曲线，波动率取自商品波动率曲面。
 */
public class CommRangeAccureOpt extends RangeAccureOptBase<CommRangeAccureOpt.CommRangeAccureInfo> {

    public CommRangeAccureOpt(LocalDate dataDate, CommRangeAccureInfo rangeAccureInfo, MarketData marketData) {
        super(dataDate, rangeAccureInfo, marketData);
    }

    @Override
    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> list = new ArrayList<>();
        list.addAll(getSensListGIRR());
        list.addAll(getSensListFX());
        list.addAll(getSensListCMTY());
        return list;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireText(rangeAccureInfo.referenceCurve, "REFERENCE_CURVE");
        if (!md.commSpot.containsKey(rangeAccureInfo.referenceCurve)) {
            throw new IllegalArgumentException("缺少商品价格曲线: " + rangeAccureInfo.referenceCurve);
        }
        if (!md.commVol.containsKey(rangeAccureInfo.volatilitySurface)) {
            throw new IllegalArgumentException("缺少商品波动率曲面: " + rangeAccureInfo.volatilitySurface);
        }
        resolveUnderlyingForCalc();
    }

    @Override
    protected double getSpotPrice(MarketData md) {
        CommSpot commSpot = new CommSpot(md.commSpot.get(rangeAccureInfo.referenceCurve));
        return commSpot.fwdPrice(dataDate);
    }

    @Override
    protected List<Map<String, Object>> getVolCurve(MarketData md, int days) {
        CommVol commVol = new CommVol(md.commVol.get(rangeAccureInfo.volatilitySurface));
        return commVol.getVolCur(days);
    }

    @Override
    protected ObsParams buildObsParams(MarketData md, LocalDate obsDate, int days, double t,
            double s, double rebase, double discount) {
        ObsParams p = new ObsParams();
        IrSpot irSpot = new IrSpot(md.irSpot.get(rangeAccureInfo.discountCurve));
        CommSpot commSpot = new CommSpot(md.commSpot.get(rangeAccureInfo.referenceCurve));
        p.rd = rebase;
        p.f = commSpot.fwdPrice(obsDate);
        p.rf = -Math.log(p.f / s) / t + p.rd;
        return p;
    }

    // ===== FRTB 敏感度（GIRR + CMTY） =====

    @Override
    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        map.put(rangeAccureInfo.discountCurve, getValuationCurrency());
        return map;
    }

    @Override
    protected boolean enableGirrDelta() {
        return true;
    }

    @Override
    protected boolean enableGirrCurvature() {
        return false;
    }

    @Override
    protected boolean enableGirrVega() {
        return false;
    }

    private List<FrtbSenes> getSensListCMTY() {
        String commBucket = hasText(rangeAccureInfo.frtbCommBucket) ? rangeAccureInfo.frtbCommBucket.trim() : null;
        if (!hasText(commBucket)) {
            rangeAccureMeasure.addWarningLog("FRTB_COMM_BUCKET为空，跳过CMTY敏感性计算(INSTRUMENT_ID="
                    + getText(rangeAccureInfo.instrumentId) + ")");
            return new ArrayList<>();
        }
        List<FrtbDependency> deltaDependencies = FrtbSensitivityBuilder.buildCmtyDeltaDependencies(
                rangeAccureInfo.referenceCurve,
                resolveCmtyRiskFactorId(),
                commBucket);
        List<FrtbDependency> vegaDependencies = FrtbSensitivityBuilder.buildCmtyVegaDependencies(
                rangeAccureInfo.volatilitySurface,
                resolveCmtyRiskFactorIdVega(),
                commBucket);
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildCmtySensitivities(
                marketData,
                dataDate,
                getFrtbSettleDate(),
                deltaDependencies,
                vegaDependencies,
                true,
                true,
                rangeAccureInfo.instrumentId,
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(rangeAccureMeasure.valuation, rangeAccureMeasure.valuationCny),
                shockedMarketData -> {
                    OptionMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                () -> middle.newSigma = true);
        // 敏感性已通过 valuationCny 插值变化自动包含 pos，无需额外乘以 pos
        return sensitivities;
    }

    public static class CommRangeAccureInfo extends RangeAccureOptBase.RangeAccureFrtbInfo {
        @JSONField(name = "FRTB_COMM_BUCKET")
        public String frtbCommBucket;
        @JSONField(name = "FRTB_COMM_ASSET")
        public String frtbCommAsset;
        @JSONField(name = "FRTB_COMM_LOCATION")
        public String frtbCommLocation;
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
    }

    private String resolveCmtyRiskFactorId() {
        String base = resolveCmtyRiskFactorIdBase();
        String location = getText(rangeAccureInfo.frtbCommLocation);
        if (!hasText(location)) {
            return base;
        }
        return base + "&" + location;
    }

    private String resolveCmtyRiskFactorIdBase() {
        String asset = getText(rangeAccureInfo.frtbCommAsset);
        if (hasText(asset)) {
            return asset;
        }
        return resolveUnderlyingForCalc();
    }

    private String resolveCmtyRiskFactorIdVega() {
        return resolveCmtyRiskFactorIdBase();
    }

    private String resolveUnderlyingForCalc() {
        String underlying = getText(rangeAccureInfo.underlyingCode);
        if (hasText(underlying)) {
            return underlying;
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CODE，无法进行商品交易计量");
    }

    private String getText(String value) {
        return value == null ? "" : value.trim();
    }
}

