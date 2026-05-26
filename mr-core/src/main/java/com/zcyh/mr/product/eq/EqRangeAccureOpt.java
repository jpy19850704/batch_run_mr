package com.zcyh.mr.product.eq;

import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.structure.RangeAccureOptBase;
import com.zcyh.mr.marketdata.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指数标的区间累计期权。
 * 标的价格取自 EQ_SPOT 曲线，波动率取自 EQ_VOL 曲面，
 * 远期价格通过 EQ_SPOT 曲线插值获取。
 */
public class EqRangeAccureOpt extends RangeAccureOptBase<EqRangeAccureOpt.EqRangeAccureInfo> {

    public EqRangeAccureOpt(LocalDate dataDate, EqRangeAccureInfo rangeAccureInfo, MarketData marketData) {
        super(dataDate, rangeAccureInfo, marketData);
    }

    @Override
    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> list = new ArrayList<>();
        list.addAll(getSensListGIRR());
        list.addAll(getSensListFX());
        list.addAll(getSensListEQ());
        return list;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireText(rangeAccureInfo.referenceCurve, "REFERENCE_CURVE");
        requireNotNull(md.eqSpot, "marketData.eqSpot");
        if (!md.eqSpot.containsKey(rangeAccureInfo.referenceCurve)) {
            throw new IllegalArgumentException("缺少权益价格曲线(EQ_SPOT): " + rangeAccureInfo.referenceCurve);
        }
        requireNotNull(md.eqVol, "marketData.eqVol");
        if (!md.eqVol.containsKey(rangeAccureInfo.volatilitySurface)) {
            throw new IllegalArgumentException("缺少权益波动率曲面(EQ_VOL): " + rangeAccureInfo.volatilitySurface);
        }
    }

    @Override
    protected double getSpotPrice(MarketData md) {
        EqSpot eqSpot = new EqSpot(md.eqSpot.get(rangeAccureInfo.referenceCurve));
        return eqSpot.fwdPrice(dataDate);
    }

    @Override
    protected List<Map<String, Object>> getVolCurve(MarketData md, int days) {
        EqVol eqVol = new EqVol(md.eqVol.get(rangeAccureInfo.volatilitySurface));
        return eqVol.getVolCur(days);
    }

    @Override
    protected ObsParams buildObsParams(MarketData md, LocalDate obsDate, int days, double t,
            double s, double rebase, double discount) {
        ObsParams p = new ObsParams();
        p.rd = rebase;
        // 不考虑 dividend，rf = 0
        p.rf = 0.0;
        p.f = s * Math.exp(p.rd * t);
        return p;
    }

    @Override
    protected String getDiscountCurveName() {
        return rangeAccureInfo.discountCurve;
    }

    // ===== FRTB 敏感度（GIRR + EQ） =====

    @Override
    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        map.put(getDiscountCurveName(), getValuationCurrency());
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

    private List<FrtbSenes> getSensListEQ() {
        String bucket = resolveOptionalBucket("11", "frtbEqBucket");
        List<FrtbDependency> deltaDependencies = FrtbSensitivityBuilder.buildEqDeltaDependencies(
                rangeAccureInfo.referenceCurve,
                bucket);
        List<FrtbDependency> vegaDependencies = FrtbSensitivityBuilder.buildEqVegaDependencies(
                rangeAccureInfo.volatilitySurface,
                rangeAccureInfo.referenceCurve,
                bucket);
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildEqSensitivities(
                marketData,
                dataDate,
                getFrtbSettleDate(),
                deltaDependencies,
                vegaDependencies,
                true,
                true,
                rangeAccureMeasure.instrumentId,
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

    public static class EqRangeAccureInfo extends RangeAccureOptBase.RangeAccureFrtbInfo {
    }
}

