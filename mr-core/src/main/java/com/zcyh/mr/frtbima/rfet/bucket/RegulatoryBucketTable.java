package com.zcyh.mr.frtbima.rfet.bucket;

import com.zcyh.mr.frtbima.rfet.model.RfBucketDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 监管标准桶定义表（MAR31 Table 1，监管分桶法）。
 *
 * <p>调用方只需在 RF_CONFIG 中维护 (curveId, rfType) 两列，
 * 调用 {@link #buildBuckets(String, String)} 自动生成标准桶定义，
 * 无需手工配置桶边界。
 *
 * <p>rfType 与监管行对应关系：
 * <ul>
 *   <li>{@code IR_SPOT} / {@code FX_SPOT} / {@code COMM_SPOT}
 *       → <b>Row A</b>（9个tenor桶，期限1维）
 *   <li>{@code EQ_SPOT} / {@code CREDIT_SPOT}
 *       → <b>Row C</b>（5个tenor桶）
 *   <li>{@code IR_VOL} / {@code EQ_VOL} / {@code FX_VOL} / {@code COMM_VOL}
 *       → <b>Row C × Row D</b>（5×5=25个桶，到期日×delta）
 * </ul>
 *
 * <p>Row B（多期限维度，如利率Swaption）暂不支持，后续扩展时增加对应 rfType。
 */
public class RegulatoryBucketTable {

    // ==================== rfType 常量 ====================

    /** 利率即期/远期曲线（Row A） */
    public static final String RF_IR_SPOT     = "IR_SPOT";
    /** 外汇即期/远期曲线（Row A） */
    public static final String RF_FX_SPOT     = "FX_SPOT";
    /** 大宗商品期货曲线（Row A） */
    public static final String RF_COMM_SPOT   = "COMM_SPOT";
    /** 权益即期（Row C） */
    public static final String RF_EQ_SPOT     = "EQ_SPOT";
    /** 信用利差曲线（Row C，与利率曲线区分的关键） */
    public static final String RF_CREDIT_SPOT = "CREDIT_SPOT";
    /** 利率波动率，非Swaption（Row C × D） */
    public static final String RF_IR_VOL      = "IR_VOL";
    /** 权益波动率（Row C × D） */
    public static final String RF_EQ_VOL      = "EQ_VOL";
    /** 外汇波动率（Row C × D） */
    public static final String RF_FX_VOL      = "FX_VOL";
    /** 大宗商品波动率（Row C × D） */
    public static final String RF_COMM_VOL    = "COMM_VOL";

    // ==================== 监管桶边界（MAR31 Table 1） ====================

    /**
     * Row A 期限桶下界（天）。
     * 原标准单位为年，转换：年 × 365 取整。
     * 年：0 / 0.75 / 1.5 / 4 / 7 / 12 / 18 / 25 / 35
     */
    private static final int[] ROW_A_LOWER = {0, 274, 548, 1460, 2555, 4380, 6570, 9125, 12775};

    /**
     * Row C 期限桶下界（天）。
     * 年：0 / 1.5 / 3.5 / 7.5 / 15
     */
    private static final int[] ROW_C_LOWER = {0, 548, 1278, 2738, 5475};

    /**
     * Row D delta（δ）桶边界，δ = 期权到期时 ITM 的概率，范围 [0, 1]。
     * 桶（左闭右开，最后一桶闭区间）：
     * [0, 0.05) / [0.05, 0.3) / [0.3, 0.7) / [0.7, 0.95) / [0.95, 1.0]
     */
    private static final double[] ROW_D_MIN = {0.0, 0.05, 0.3,  0.7,  0.95};
    private static final double[] ROW_D_MAX = {0.05, 0.3, 0.7,  0.95, 1.0};

    /** 期限无上界哨兵值（999999天 ≈ 2740年），与现有代码约定一致 */
    static final int TENOR_UNBOUNDED = 999999;

    // ==================== 公共接口 ====================

    /**
     * 根据 curveId 和 rfType 生成标准监管桶定义列表。
     *
     * @param curveId 曲线ID（如 CNY_SWAP、ICBC_CDS、CSI300_VOL）
     * @param rfType  风险因子类型（见本类 RF_* 常量）
     * @return 标准桶定义列表（1D 或 2D）
     * @throws IllegalArgumentException 若 rfType 不在已知列表中
     */
    public List<RfBucketDefinition> buildBuckets(String curveId, String rfType) {
        List<RfBucketDefinition> result = new ArrayList<>();
        switch (rfType) {
            case RF_IR_SPOT:
            case RF_FX_SPOT:
            case RF_COMM_SPOT:
                buildTenorBuckets(result, curveId, rfType, ROW_A_LOWER);
                break;
            case RF_EQ_SPOT:
            case RF_CREDIT_SPOT:
                buildTenorBuckets(result, curveId, rfType, ROW_C_LOWER);
                break;
            case RF_IR_VOL:
                // IR vol 为 OPTION_TERM × UNDERLYING_TERM（双tenor，无 delta）
                // RFET 仅按 OPTION_TERM 分桶（Row C），暂不支持 swaption 三维
                buildTenorBuckets(result, curveId, rfType, ROW_C_LOWER);
                break;
            case RF_EQ_VOL:
            case RF_FX_VOL:
            case RF_COMM_VOL:
                // EQ/FX/COMM vol 为 OPTION_TERM × DELTA，按 Row C × Row D 分桶
                buildVolBuckets(result, curveId, rfType);
                break;
            default:
                throw new IllegalArgumentException(
                        "未知 rfType: [" + rfType + "]，合法值：IR_SPOT / FX_SPOT / COMM_SPOT"
                        + " / EQ_SPOT / CREDIT_SPOT / IR_VOL / EQ_VOL / FX_VOL / COMM_VOL");
        }
        return result;
    }

    // ==================== 私有构建方法 ====================

    /**
     * 生成一维tenor桶（Row A 或 Row C），无delta维度。
     * 每段区间左闭右开，最后一段上界为 TENOR_UNBOUNDED。
     */
    private void buildTenorBuckets(List<RfBucketDefinition> result,
                                    String curveId, String rfType, int[] lowerBounds) {
        for (int i = 0; i < lowerBounds.length; i++) {
            int tenorMin = lowerBounds[i];
            int tenorMax = (i + 1 < lowerBounds.length)
                    ? lowerBounds[i + 1] - 1
                    : TENOR_UNBOUNDED;
            result.add(new RfBucketDefinition(curveId, rfType, tenorMin, tenorMax));
        }
    }

    /**
     * 生成波动率二维桶（Row C 到期日 × Row D delta），共 5×5=25 个桶。
     * 外层为 Row C tenor 分段，内层为 Row D delta 分段。
     */
    private void buildVolBuckets(List<RfBucketDefinition> result,
                                  String curveId, String rfType) {
        for (int i = 0; i < ROW_C_LOWER.length; i++) {
            int tenorMin = ROW_C_LOWER[i];
            int tenorMax = (i + 1 < ROW_C_LOWER.length)
                    ? ROW_C_LOWER[i + 1] - 1
                    : TENOR_UNBOUNDED;
            for (int j = 0; j < ROW_D_MIN.length; j++) {
                result.add(new RfBucketDefinition(
                        curveId, rfType,
                        tenorMin, tenorMax,
                        ROW_D_MIN[j], ROW_D_MAX[j]));
            }
        }
    }
}
