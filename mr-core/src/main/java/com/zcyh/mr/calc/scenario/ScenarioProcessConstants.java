package com.zcyh.mr.calc.scenario;

/**
 * 情景处理口径常量。
 */
public final class ScenarioProcessConstants {
    public static final String REGULAR = "REGULAR";
    public static final String VAR = "VAR";
    public static final String IMA_MODELLABLE = "IMA_MODELLABLE";
    public static final String IMA_NMRF = "IMA_NMRF";

    public static final String REGULAR_SCENARIO_REF_LIST = "regular_scenario_ref_list";
    public static final String VAR_SCENARIO_REF_LIST = "var_scenario_ref_list";
    public static final String IMA_MODELLABLE_SCENARIO_REF_LIST = "ima_modellable_scenario_ref_list";
    public static final String IMA_NMRF_SCENARIO_REF_LIST = "ima_nmrf_scenario_ref_list";

    public static final String TAG_RISK_CLASS = "riskClass";
    public static final String TAG_LH = "lh";
    public static final String TAG_IMA_RISK_CLASS = "imaRiskClass";
    public static final String TAG_IMA_SCENARIO_TYPE = "imaScenarioType";
    public static final String SCENARIO_META = "scenario_meta";

    private ScenarioProcessConstants() {
    }

    public static boolean isValidProcessType(String processType) {
        return REGULAR.equals(processType)
                || VAR.equals(processType)
                || IMA_MODELLABLE.equals(processType)
                || IMA_NMRF.equals(processType);
    }
}
