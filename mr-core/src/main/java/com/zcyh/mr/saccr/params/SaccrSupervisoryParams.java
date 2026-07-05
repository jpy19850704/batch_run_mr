package com.zcyh.mr.saccr.params;

/**
 * SA-CCR 监管参数表（BCBS 279 固定参数，不得内部估算）。
 *
 * <p>所有数值均以小数表示（如 0.005 = 0.50%）。
 * 常量命名规则：
 * <ul>
 *   <li>SF_* — Supervisory Factor（监管因子）</li>
 *   <li>RHO_* — 相关系数</li>
 *   <li>SIGMA_* — 监管波动率（用于期权 Delta 计算）</li>
 *   <li>IR_BUCKET_CORR_* — 利率期限桶间相关系数</li>
 * </ul>
 */
public final class SaccrSupervisoryParams {

    private SaccrSupervisoryParams() {
    }

    // ==================== 监管因子 SF ====================

    /** 利率（所有子类） */
    public static final double SF_IR = 0.005;

    /** 外汇（所有子类） */
    public static final double SF_FX = 0.040;

    /** 信用：单一主体 AAA */
    public static final double SF_CREDIT_SINGLE_AAA = 0.0038;

    /** 信用：单一主体 AA */
    public static final double SF_CREDIT_SINGLE_AA = 0.0038;

    /** 信用：单一主体 A */
    public static final double SF_CREDIT_SINGLE_A = 0.0042;

    /** 信用：单一主体 BBB */
    public static final double SF_CREDIT_SINGLE_BBB = 0.0054;

    /** 信用：单一主体 BB */
    public static final double SF_CREDIT_SINGLE_BB = 0.0106;

    /** 信用：单一主体 B */
    public static final double SF_CREDIT_SINGLE_B = 0.0160;

    /** 信用：单一主体 CCC（含 CC/C/D） */
    public static final double SF_CREDIT_SINGLE_CCC = 0.0600;

    /** 信用：指数，投资级 IG */
    public static final double SF_CREDIT_IG_INDEX = 0.0038;

    /** 信用：指数，投机级 SG */
    public static final double SF_CREDIT_SG_INDEX = 0.0106;

    /** 权益：单一股票 */
    public static final double SF_EQUITY_SINGLE = 0.32;

    /** 权益：指数 */
    public static final double SF_EQUITY_INDEX = 0.20;

    /** 大宗商品：电力 */
    public static final double SF_COMMODITY_POWER = 0.40;

    /** 大宗商品：其他 */
    public static final double SF_COMMODITY_OTHER = 0.18;

    // ==================== 相关系数 ρ ====================

    /** 信用：单一主体相关系数 */
    public static final double RHO_CREDIT_SINGLE = 0.50;

    /** 信用：指数相关系数 */
    public static final double RHO_CREDIT_INDEX = 0.80;

    /** 权益：单一股票相关系数 */
    public static final double RHO_EQUITY_SINGLE = 0.50;

    /** 权益：指数相关系数 */
    public static final double RHO_EQUITY_INDEX = 0.80;

    /** 大宗商品：桶内跨品种相关系数 */
    public static final double RHO_COMMODITY_INTRA = 0.40;

    // ==================== 监管波动率 σ（期权 Delta 用） ====================

    /** 利率期权监管波动率 */
    public static final double SIGMA_IR = 0.50;

    /** 信用期权监管波动率 */
    public static final double SIGMA_CREDIT = 1.00;

    /** 权益期权监管波动率 */
    public static final double SIGMA_EQUITY = 1.20;

    /** 外汇期权监管波动率 */
    public static final double SIGMA_FX = 0.15;

    /** 大宗商品期权监管波动率（能源和其他统一） */
    public static final double SIGMA_COMMODITY = 0.70;

    // ==================== 利率桶间相关系数 ====================

    /** 利率桶1-2 相关系数 */
    public static final double IR_BUCKET_CORR_12 = 0.70;

    /** 利率桶2-3 相关系数 */
    public static final double IR_BUCKET_CORR_23 = 0.70;

    /** 利率桶1-3 相关系数 */
    public static final double IR_BUCKET_CORR_13 = 0.30;

    // ==================== 全局固定参数 ====================

    /** EAD 固定乘数 α = 1.4 */
    public static final double ALPHA = 1.4;

    /** 乘数（multiplier）下限 */
    public static final double MULTIPLIER_FLOOR = 0.05;

    /** BA-CVA 聚合相关系数 ρ_CVA */
    public static final double CVA_RHO = 0.50;

    // ==================== MPOR 标准值（工作日） ====================

    /** 集中清算 MPOR 下限（工作日） */
    public static final int MPOR_CLEARED_DAYS = 5;

    /** 双边，日频更新 MPOR 下限（工作日） */
    public static final int MPOR_BILATERAL_DAYS = 10;

    /**
     * 大型净额结算组合 MPOR 最低要求（工作日）。
     * 适用条件：双边（非集中清算）且交易笔数 ≥ LARGE_PORTFOLIO_THRESHOLD。
     * 依据 BCBS 279 para 167。
     */
    public static final int MPOR_LARGE_PORTFOLIO_DAYS = 20;

    /** 触发大型组合 MPOR 加码的交易笔数阈值 */
    public static final int LARGE_PORTFOLIO_THRESHOLD = 5000;

    // ==================== 信用分档 ====================

    /**
     * 信用单一主体评级分档（与 BCBS 279 表2一致）。
     */
    public enum CreditSingleBucket {
        AAA, AA, A, BBB, BB, B, CCC
    }

    // ==================== 参数查询辅助方法 ====================

    /**
     * 按资产类别和子类型返回监管因子 SF。
     *
     * @param assetClass   IR / FX / Credit / Equity / Commodity
     * @param isIndex      是否指数（Credit/Equity 使用）
     * @param isIg         是否投资级（Credit 使用，true=IG，false=SG）
     * @param commodityBucket 商品桶名（"Power" 表示电力，其他用 OTHER）
     */
    public static double getSf(String assetClass, boolean isIndex, boolean isIg, String commodityBucket) {
        switch (assetClass.toUpperCase()) {
            case "IR":
                return SF_IR;
            case "FX":
                return SF_FX;
            case "CREDIT":
                if (isIndex) {
                    return isIg ? SF_CREDIT_IG_INDEX : SF_CREDIT_SG_INDEX;
                }
                throw new IllegalArgumentException("信用单一主体监管因子必须按评级分档调用 getCreditSingleSf");
            case "EQUITY":
                return isIndex ? SF_EQUITY_INDEX : SF_EQUITY_SINGLE;
            case "COMMODITY":
                if (commodityBucket != null && commodityBucket.equalsIgnoreCase("Power")) {
                    return SF_COMMODITY_POWER;
                }
                return SF_COMMODITY_OTHER;
            default:
                throw new IllegalArgumentException("未知资产类别: " + assetClass);
        }
    }

    /**
     * 按资产类别返回相关系数 ρ（Credit/Equity/Commodity 使用）。
     */
    public static double getRho(String assetClass, boolean isIndex) {
        switch (assetClass.toUpperCase()) {
            case "CREDIT":
                return isIndex ? RHO_CREDIT_INDEX : RHO_CREDIT_SINGLE;
            case "EQUITY":
                return isIndex ? RHO_EQUITY_INDEX : RHO_EQUITY_SINGLE;
            case "COMMODITY":
                return RHO_COMMODITY_INTRA;
            default:
                throw new IllegalArgumentException("资产类别 " + assetClass + " 无对应相关系数");
        }
    }

    /**
     * 按信用单一主体评级分档返回监管因子 SF。
     */
    public static double getCreditSingleSf(CreditSingleBucket bucket) {
        switch (bucket) {
            case AAA:
                return SF_CREDIT_SINGLE_AAA;
            case AA:
                return SF_CREDIT_SINGLE_AA;
            case A:
                return SF_CREDIT_SINGLE_A;
            case BBB:
                return SF_CREDIT_SINGLE_BBB;
            case BB:
                return SF_CREDIT_SINGLE_BB;
            case B:
                return SF_CREDIT_SINGLE_B;
            case CCC:
                return SF_CREDIT_SINGLE_CCC;
            default:
                throw new IllegalArgumentException("未知信用单一主体评级分档: " + bucket);
        }
    }

    /**
     * 按信用指数 IG/SG 返回监管因子 SF。
     */
    public static double getCreditIndexSf(boolean isIg) {
        return isIg ? SF_CREDIT_IG_INDEX : SF_CREDIT_SG_INDEX;
    }

    /**
     * 解析信用单一主体评级分档（AAA/AA/A/BBB/BB/B/CCC）。
     *
     * <p>无法识别返回 null，由上层显式报错。
     */
    public static CreditSingleBucket resolveCreditSingleBucket(String rating) {
        if (rating == null || rating.isBlank()) return null;
        String r = rating.trim().toUpperCase();
        if (r.startsWith("AAA")) return CreditSingleBucket.AAA;
        if (r.startsWith("AA")) return CreditSingleBucket.AA;
        if (r.equals("A") || r.equals("A+") || r.equals("A-")) return CreditSingleBucket.A;
        if (r.startsWith("BBB")) return CreditSingleBucket.BBB;
        if (r.startsWith("BB")) return CreditSingleBucket.BB;
        if (r.equals("B") || r.equals("B+") || r.equals("B-")) return CreditSingleBucket.B;
        if (r.startsWith("CCC") || r.startsWith("CC") || r.equals("C") || r.startsWith("D")) {
            return CreditSingleBucket.CCC;
        }
        return null;
    }

    /**
     * 解析信用指数 IG/SG 标识。
     *
     * <p>仅接受 IG / SG（大小写不敏感）；无法识别返回 null。
     */
    public static Boolean resolveCreditIndexIgFlag(String grade) {
        if (grade == null || grade.isBlank()) return null;
        String g = grade.trim().toUpperCase();
        if ("IG".equals(g)) return Boolean.TRUE;
        if ("SG".equals(g)) return Boolean.FALSE;
        return null;
    }

    /**
     * 判断信用单一主体评级是否属于投资级。
     *
     * <p>无法识别时直接报错。
     */
    public static boolean isInvestmentGrade(String rating) {
        CreditSingleBucket bucket = resolveCreditSingleBucket(rating);
        if (bucket == null) {
            throw new IllegalArgumentException("信用单一主体评级无法识别: " + rating);
        }
        return bucket == CreditSingleBucket.AAA
                || bucket == CreditSingleBucket.AA
                || bucket == CreditSingleBucket.A
                || bucket == CreditSingleBucket.BBB;
    }

    /**
     * 按资产类别返回期权监管波动率 σ。
     */
    public static double getSigma(String assetClass) {
        switch (assetClass.toUpperCase()) {
            case "IR":
                return SIGMA_IR;
            case "FX":
                return SIGMA_FX;
            case "CREDIT":
                return SIGMA_CREDIT;
            case "EQUITY":
                return SIGMA_EQUITY;
            case "COMMODITY":
                return SIGMA_COMMODITY;
            default:
                throw new IllegalArgumentException("未知资产类别: " + assetClass);
        }
    }
}
