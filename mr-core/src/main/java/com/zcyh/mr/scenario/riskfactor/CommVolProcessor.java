package com.zcyh.mr.scenario.riskfactor;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.util.ShockUtils;

import java.util.List;

/**
 * 商品波动率处理器。
 */
public class CommVolProcessor extends RiskFactorProcessor {

    public CommVolProcessor() {
        super("COMM_VOL");
    }

    @Override
    public void postProcess(List<ScenarioGeneratedRecord> data) {
        // 结果期限码已在 getTermCode 中统一处理，此处无需额外逻辑
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
