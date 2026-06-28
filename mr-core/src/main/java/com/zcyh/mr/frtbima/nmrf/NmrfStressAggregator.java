package com.zcyh.mr.frtbima.nmrf;

import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.NmrfStressResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NMRF 压力损失聚合器（Phase2 前置步骤）。
 *
 * <p>对每个 bucketId，从 TB_OUT_IMA_NMRF_SCENARIO_PNL 中读取 UP/DOWN 两行，
 * 取各 instrument PnL 合计后绝对值更大的方向，作为该桶的压力损失 SES_b。
 *
 * <p>SES_b = max( |sum_UP_PnL|, |sum_DOWN_PnL| )（MAR33.16）
 */
public class NmrfStressAggregator {

    /**
     * 聚合 NMRF 压力损失。
     *
     * @param nmrfRecords TB_OUT_IMA_NMRF_SCENARIO_PNL 查询结果
     * @return 每个桶的压力损失列表
     */
    public List<NmrfStressResult> aggregate(List<NmrfPnlRecord> nmrfRecords) {
        // subscenarioId 格式：{bucketId}_UP 或 {bucketId}_DOWN
        Map<String, BucketPnlAccum> accumMap = new HashMap<>();

        for (NmrfPnlRecord rec : nmrfRecords) {
            if (rec == null) {
                continue;
            }
            String bucketId = resolveBucketId(rec.getSubscenarioId());
            BucketPnlAccum accum = accumMap.computeIfAbsent(bucketId,
                    k -> new BucketPnlAccum(bucketId, rec.getRiskFactorId(), rec.getNmrfType()));

            BigDecimal pnl = rec.getPnl() != null ? rec.getPnl() : BigDecimal.ZERO;
            String subId = rec.getSubscenarioId();
            if (subId != null && subId.endsWith("_UP")) {
                accum.sumUpPnl = accum.sumUpPnl.add(pnl);
            } else if (subId != null && subId.endsWith("_DOWN")) {
                accum.sumDownPnl = accum.sumDownPnl.add(pnl);
            }
        }

        List<NmrfStressResult> results = new ArrayList<>();
        for (BucketPnlAccum accum : accumMap.values()) {
            // 取绝对值更大的方向（均以非负值表示损失）
            BigDecimal absUp = accum.sumUpPnl.abs();
            BigDecimal absDown = accum.sumDownPnl.abs();
            BigDecimal stressLoss = absUp.max(absDown);

            results.add(new NmrfStressResult(
                    accum.bucketId,
                    accum.riskFactorId,
                    accum.nmrfType,
                    stressLoss));
        }
        return results;
    }

    private static String resolveBucketId(String subscenarioId) {
        if (subscenarioId == null || subscenarioId.trim().isEmpty()) {
            throw new IllegalArgumentException("NMRF 结果缺少 SUBSCENARIO_ID");
        }
        String safe = subscenarioId.trim();
        if (safe.endsWith("_UP")) {
            return safe.substring(0, safe.length() - 3);
        }
        if (safe.endsWith("_DOWN")) {
            return safe.substring(0, safe.length() - 5);
        }
        throw new IllegalArgumentException("NMRF SUBSCENARIO_ID 必须为 {rfetBucketId}_UP 或 {rfetBucketId}_DOWN: "
                + subscenarioId);
    }

    private static class BucketPnlAccum {
        final String bucketId;
        final String riskFactorId;
        final String nmrfType;
        BigDecimal sumUpPnl = BigDecimal.ZERO;
        BigDecimal sumDownPnl = BigDecimal.ZERO;

        BucketPnlAccum(String bucketId, String riskFactorId, String nmrfType) {
            this.bucketId = bucketId;
            this.riskFactorId = riskFactorId;
            this.nmrfType = nmrfType;
        }
    }
}
