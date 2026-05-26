package com.zcyh.mr.scenario.riskfactor;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;

/**
 * 利率现货处理器。
 */
public class IrSpotProcessor extends RiskFactorProcessor {

    public IrSpotProcessor() {
        super("IR_SPOT");
    }

    @Override
    public String getUniqueCode(ScenarioMarketSeries data) {
        return data.getCurveCode() + SPLIT_STR + data.getTermCode();
    }

    @Override
    public String getTermCode(ScenarioMarketSeries data) {
        return data.getTermCode();
    }

    @Override
    public boolean needsTermInterpolation() {
        return true;
    }
}
