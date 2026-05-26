package com.zcyh.mr.scenario.riskfactor;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;

/**
 * 商品现货处理器。
 */
public class CommSpotProcessor extends RiskFactorProcessor {

    public CommSpotProcessor() {
        super("COMM_SPOT");
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
