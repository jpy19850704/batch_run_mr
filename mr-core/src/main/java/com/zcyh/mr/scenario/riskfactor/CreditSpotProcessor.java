package com.zcyh.mr.scenario.riskfactor;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;

/**
 * 信用利差现货处理器。
 */
public class CreditSpotProcessor extends RiskFactorProcessor {

    public CreditSpotProcessor() {
        super("CREDIT_SPOT");
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
