package com.zcyh.mr.scenario.riskfactor;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;

/**
 * 汇率现货处理器。
 */
public class FxSpotProcessor extends RiskFactorProcessor {

    public FxSpotProcessor() {
        super("FX_SPOT");
    }

    @Override
    public String getUniqueCode(ScenarioMarketSeries data) {
        return data.getCurveCode();
    }

    @Override
    public String getTermCode(ScenarioMarketSeries data) {
        return data.getTermCode();
    }

    @Override
    public boolean needsTermInterpolation() {
        return false;
    }
}
