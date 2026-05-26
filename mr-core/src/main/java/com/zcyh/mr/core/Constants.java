package com.zcyh.mr.core;

final public class Constants {
    final static public class PRODUCT_CODE {
        // 商品相关产品
        public final static String COMMFWD = "COMMFWD";
        public final static String COMMSWAP = "COMMSWAP";
        public final static String COMMOPT = "COMMOPT";

        // 利率相关产品
        public final static String BOND = "BOND";
        public final static String WILLOW_BOND = "WILLOW_BOND";
        public final static String IRSCCS = "IRSCCS";
        public final static String CAPFLOOR = "CAPFLOOR";
        public final static String SWAPTION = "SWAPTION";
        public final static String BOND_FUTURE = "BOND_FUTURE";
        public final static String STD_IRS = "STD_IRS";
        public final static String IR_SPREADOPT = "IR_SPREADOPT";

        // 通用 MC 承接产品
        public final static String AUTO_CALL = "AUTO_CALL";

        // 外汇相关产品
        public final static String FXFWD = "FXFWD";
        public final static String FXSWAP = "FXSWAP";
        public final static String FXOPT = "FXOPT";
        public final static String FX_ASIAN = "FX_ASIAN";
        public final static String EQ_ASIAN = "EQ_ASIAN";
        public final static String COMM_ASIAN = "COMM_ASIAN";
        public final static String FX_SPREADOPT = "FX_SPREADOPT";
        public final static String EQ_SPREADOPT = "EQ_SPREADOPT";
        public final static String COMM_SPREADOPT = "COMM_SPREADOPT";
        public final static String FX_SHARKFIN = "FX_SHARKFIN";
        public final static String FX_WEDDING_CAKE = "FX_WEDDING_CAKE";
        public final static String EQ_WEDDING_CAKE = "EQ_WEDDING_CAKE";
        public final static String COMM_WEDDING_CAKE = "COMM_WEDDING_CAKE";
        public final static String IR_WEDDING_CAKE = "IR_WEDDING_CAKE";
        public final static String EQ_SHARKFIN = "EQ_SHARKFIN";
        public final static String COMM_SHARKFIN = "COMM_SHARKFIN";
        public final static String IR_SHARKFIN = "IR_SHARKFIN";
        public final static String EQ_DIGITAL = "EQ_DIGITAL";
        public final static String COMM_DIGITAL = "COMM_DIGITAL";
        public final static String IR_DIGITAL = "IR_DIGITAL";
        public final static String FX_DIGITAL = "FX_DIGITAL";
        public final static String EQ_BARRIER = "EQ_BARRIER";
        public final static String COMM_BARRIER = "COMM_BARRIER";
        public final static String IR_BARRIER = "IR_BARRIER";
        public final static String FX_BARRIER = "FX_BARRIER";

        // 信用相关产品
        public final static String CDS = "CDS";
        public final static String TRS = "TRS";

        // 结存相关产品
        public final static String FX_RA = "FX_RANGE_ACCURE";
        public final static String FX_STEP_UP = "FX_STEP_UP";
        public final static String IR_RA = "IR_RANGE_ACCURE";
        public final static String IR_STEP_UP = "IR_STEP_UP";
        public final static String EQ_RA = "EQ_RANGE_ACCURE";
        public final static String EQ_STEP_UP = "EQ_STEP_UP";
        public final static String COMM_RA = "COMM_RANGE_ACCURE";
        public final static String COMM_STEP_UP = "COMM_STEP_UP";

        public final static double[] TERM_YEAR = { 0, 0.25, 0.5, 1, 2, 3, 5, 10, 15, 20, 30 };
        public final static String[] TERM_CODE = { "0D", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "10Y", "15Y", "20Y",
                "30Y" };

    }

    final public class OPER_CODE {
        public final static String PRICING = "PRICING";
        /** 估值 + FRTB 计量（Sensitivity + DRC） */
        public final static String FRTB = "FRTB";
        /** 估值 + 情景损益，不做 FRTB */
        public final static String SCENARIO = "SCENARIO";
        /** 仅执行曲线生成并返回生成后的市场数据 */
        public final static String CURVE_GENERATION = "CURVE_GENERATION";
    }

    final public class DateGeneration {
        public final static String FOWARD = "forward";
        public final static String BACKWARD = "backward";
    }

    final public class CFG {
        public final static String FX_BASE_CODE = "FX.BASE_CURRENCY_CODE";
        public final static String FX_SPOT_BASE_CODE = "FX_SPOT.BASE_CURRENCY_CODE";
        public final static String FRTB_FX_SENSITIVITY_SHOCK_CNY = "FRTB.FX_SENSITIVITY_SHOCK_CNY";
        public final static String NON_NEGATIVE_FLOOR_ENABLED = "NON_NEGATIVE_FLOOR_ENABLED";
    }

    final public static class FRTB {
        public final static class SA {
            public final static class RISK_CLASS {
                public final static String GIRR = "GIRR";
                public final static String CSR_N = "CSR (non-sec)";
                public final static String CSR_S_CTP = "CSR (ctp)";
                public final static String CSR_S_N_CTP = "CSR (non-ctp)";
                public final static String CR = "CMTY";
                public final static String FXR = "FX";
                public final static String ER = "EQ";
            }
        }

        public final static class DRC {
            /** 非证券化违约风险 */
            public final static String JTD_N = "JTD (non-sec)";
            /** 证券化非CTP违约风险 */
            public final static String JTD_S_N_CTP = "JTD (non-ctp)";
            /** 证券化CTP违约风险 */
            public final static String JTD_S_CTP = "JTD (ctp)";
        }

    }

    final public class RF_TYPE {
        public final static String IR_SPOT = "IR_SPOT";
        public final static String FIXING = "FIXING";
        public final static String IR_VOL = "IR_VOL";
        public final static String EQ_SPOT = "EQ_SPOT";
        public final static String EQ_VOL = "EQ_VOL";
        public final static String FX_SPOT = "FX_SPOT";
        public final static String FX_VOL = "FX_VOL";
        public final static String COMM_SPOT = "COMM_SPOT";
        public final static String COMM_VOL = "COMM_VOL";
    }

}
