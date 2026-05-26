package com.zcyh.mr.scenario.riskfactor;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.util.ShockUtils;

/**
 * 利率波动率处理器。
 */
public class IrVolProcessor extends RiskFactorProcessor {

    public IrVolProcessor() {
        super("IR_VOL");
    }

    @Override
    public String getUniqueCode(ScenarioMarketSeries data) {
        return data.getCurveCode() + SPLIT_STR + ShockUtils.getVolAxis2(data) + SPLIT_STR + data.getTermCode();
    }

    @Override
    public String getTermCode(ScenarioMarketSeries data) {
        String termCode = data.getTermCode();
        String axis2 = ShockUtils.getVolAxis2(data);
        if (termCode == null || axis2.isEmpty()) {
            return termCode;
        }
        String suffix = "_" + axis2;
        return termCode.endsWith(suffix) ? termCode : termCode + suffix;
    }

    @Override
    public boolean needsTermInterpolation() {
        return true;
    }

    @Override
    public boolean validate(ScenarioMarketSeries data) {
        return super.validate(data) && !ShockUtils.getVolAxis2(data).isEmpty();
    }
}
