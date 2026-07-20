package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.structure.RangeAccureOptBase;

import java.time.LocalDate;
import java.util.*;

/**
 * 利率标的区间累计期权。
 * 标的价格取自利率定盘，波动率取自利率波动率曲面。
 * 支持两种利率计算模式：
 * - ZERO：零息远期利率，由 TERM_CODE 指定远期期限
 * - PAR：平价互换利率，由 TERM_CODE 指定互换期限，TERM_FREQ 指定付息频率
 * Delta/Gamma 使用 IR 版本（getDelta/getGamma）。
 */
public class IrRangeAccureOpt extends RangeAccureOptBase<IrRangeAccureOpt.IrRangeAccureTradeInfo> {
    /** 即期价格与远期利率的差值，在计算远期利率时作为基差调整 */
    private double diff;

    public IrRangeAccureOpt(LocalDate dataDate, IrRangeAccureTradeInfo rangeAccureInfo, MarketData marketData) {
        super(dataDate, rangeAccureInfo, marketData);
    }

    @Override
    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> list = new ArrayList<>();
        list.addAll(getSensListGIRR());
        list.addAll(getSensListFX());
        return list;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireText(rangeAccureInfo.referenceCurve, "REFERENCE_CURVE");
        if (!md.irSpot.containsKey(rangeAccureInfo.referenceCurve)) {
            throw new IllegalArgumentException("缺少利率曲线: " + rangeAccureInfo.referenceCurve);
        }
        if (!md.irVol.containsKey(rangeAccureInfo.volatilitySurface)) {
            throw new IllegalArgumentException("缺少利率波动率曲面: " + rangeAccureInfo.volatilitySurface);
        }
        validateRateTermInputs();
    }

    @Override
    protected double getSpotPrice(MarketData md) {
        Fixing fixing = new Fixing(md.fixingRate.get(resolveFixingKey()));
        return fixing.getRate(dataDate);
    }

    @Override
    protected void onBeforeCalcLoop(MarketData md, double s) {
        // 计算基差：即期定盘价 - 远期利率推导值
        this.diff = s - calFi(dataDate, md);
    }

    @Override
    protected List<Map<String, Object>> getVolCurve(MarketData md, int days) {
        IrVol irVol = new IrVol(md.irVol.get(rangeAccureInfo.volatilitySurface));
        return irVol.getVolCur(days);
    }

    @Override
    protected ObsParams buildObsParams(MarketData md, LocalDate obsDate, int days, double t,
            double s, double rebase, double discount) {
        ObsParams p = new ObsParams();
        p.rd = rebase;
        p.f = calFi(obsDate, md) + diff;
        p.rf = -Math.log(p.f / s) / t + p.rd;
        return p;
    }

    @Override
    protected boolean useIrGreeks() {
        return true;
    }

    @Override
    protected boolean defaultAbsFlag() {
        return true;
    }

    @Override
    protected String getDiscountCurveName() {
        return rangeAccureInfo.discountCurve;
    }

    /**
     * 根据 RATE_TYPE 分派计算利率：ZERO（零息远期利率）或 PAR（平价互换利率）
     */
    private double calFi(LocalDate date, MarketData marketData) {
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(rangeAccureInfo.referenceCurve));
        String termCode = rangeAccureInfo.termCode.trim();
        if ("ZERO".equalsIgnoreCase(rangeAccureInfo.rateType.trim())) {
            // 零息远期利率：复用 IrSpot.fwdRate + IrSpot.parseTermCodeToDate
            LocalDate endDate = IrSpot.parseTermCodeToDate(date, termCode);
            return irSpot.fwdRate(date, endDate);
        }
        String termFreq = rangeAccureInfo.termFreq.trim();
        return irSpot.parSwapRate(date, termCode, termFreq);
    }

    private void validateRateTermInputs() {
        if (!hasText(rangeAccureInfo.termCode)) {
            throw new IllegalArgumentException("TERM_CODE不能为空");
        }
        if (!hasText(rangeAccureInfo.rateType)) {
            throw new IllegalArgumentException("RATE_TYPE不能为空");
        }
        String rateType = rangeAccureInfo.rateType.trim().toUpperCase();
        if (!"ZERO".equals(rateType) && !"PAR".equals(rateType)) {
            throw new IllegalArgumentException("RATE_TYPE仅支持 ZERO/PAR: " + rangeAccureInfo.rateType);
        }
        if ("PAR".equals(rateType) && !hasText(rangeAccureInfo.termFreq)) {
            throw new IllegalArgumentException("RATE_TYPE=PAR时TERM_FREQ不能为空");
        }
    }

    @Override
    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        map.put(getDiscountCurveName(), getValuationCurrency());
        map.put(rangeAccureInfo.referenceCurve, getValuationCurrency());
        return map;
    }

    @Override
    protected boolean enableGirrDelta() {
        return true;
    }

    @Override
    protected boolean enableGirrCurvature() {
        return true;
    }

    @Override
    protected boolean enableGirrVega() {
        return true;
    }

    @Override
    protected String getGirrVegaSecondaryVertex() {
        return rangeAccureInfo.termCode;
    }

    public static class IrRangeAccureTradeInfo extends RangeAccureOptBase.RangeAccureFrtbTradeInfo {
        /** 利率类型：ZERO（零息远期利率）或 PAR（平价互换利率），不区分大小写 */
        @ProductInputField(allowedValues = {"ZERO", "PAR"}, ignoreCase = true)
        @JSONField(name = "RATE_TYPE")
        public String rateType;

        /** 标的期限代码，如 "10Y", "5Y", "3M" */
        @JSONField(name = "TERM_CODE")
        public String termCode;

        /** Par 模式付息频率，如 "1Y", "6M", "3M" */
        @JSONField(name = "TERM_FREQ")
        public String termFreq;
    }
}
