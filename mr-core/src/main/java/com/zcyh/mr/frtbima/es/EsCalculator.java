package com.zcyh.mr.frtbima.es;

import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.model.EsResult;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 计算器（MAR33.4）。
 *
 * <p>从 Phase1 输出的 SubsetPnlRecord 列表中，
 * 按 (scenarioType, riskClass, lhDays) 维度分组后
 * 计算 97.5% 置信度的 Expected Shortfall（条件均值尾部）。
 *
 * <p>输入：TB_OUT_IMA_MODELLABLE_SCENARIO_PNL 查询结果（内存列表）。
 * 输出：EsResult 列表，供 LiquidityAdjustedEs 和 ImccAggregator 使用。
 *
 * <p>ES 必须在组合级别（portfolio level）计算：
 * 先将同一 (scenarioType, subscenarioId, lhDays, riskClass) 下所有 instrument 的 PnL 求和，
 * 得到 M 条情景的组合 PnL，再对该 M 个样本计算 97.5% ES。
 */
public class EsCalculator {

    /**
     * 计算各维度 ES。
     *
     * @param records 同一投资组合的全部 SubsetPnlRecord（可跨情景类型）
     * @return EsResult 列表，每条对应 (scenarioType, lhDays, riskClass) 一个组合
     */
    public List<EsResult> calculate(List<SubsetPnlRecord> records) {
        // 第一步：按 (scenarioType, subscenarioId, lhDays, riskClass) 汇总组合 PnL
        // key = scenarioType + "|" + subscenarioId + "|" + lhDays + "|" + riskClass
        Map<String, BigDecimal> portfolioPnlMap = new HashMap<>();

        for (SubsetPnlRecord rec : records) {
            if (rec == null) continue;
            String subId = rec.getSubscenarioId() != null ? rec.getSubscenarioId() : "";
            // ALL_PNL → riskClass=ALL
            accumulatePnl(portfolioPnlMap, rec.getScenarioType(), subId, rec.getLhDays(), "ALL",  rec.getAllPnl());
            // 各风险类别分量
            accumulatePnl(portfolioPnlMap, rec.getScenarioType(), subId, rec.getLhDays(), "IR",   rec.getIrPnl());
            accumulatePnl(portfolioPnlMap, rec.getScenarioType(), subId, rec.getLhDays(), "CS",   rec.getCsPnl());
            accumulatePnl(portfolioPnlMap, rec.getScenarioType(), subId, rec.getLhDays(), "FX",   rec.getFxPnl());
            accumulatePnl(portfolioPnlMap, rec.getScenarioType(), subId, rec.getLhDays(), "EQ",   rec.getEqPnl());
            accumulatePnl(portfolioPnlMap, rec.getScenarioType(), subId, rec.getLhDays(), "COMM", rec.getCommPnl());
        }

        // 第二步：按 (scenarioType, lhDays, riskClass) 收集 M 条情景的组合 PnL 列表
        // key = scenarioType + "|" + lhDays + "|" + riskClass
        Map<String, List<BigDecimal>> scenarioGroups = new HashMap<>();
        for (Map.Entry<String, BigDecimal> entry : portfolioPnlMap.entrySet()) {
            // 原 key = scenarioType|subscenarioId|lhDays|riskClass
            String[] parts = entry.getKey().split("\\|", 4);
            String groupKey = parts[0] + "|" + parts[2] + "|" + parts[3];
            scenarioGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(entry.getValue());
        }

        // 第三步：对每个 (scenarioType, lhDays, riskClass) 计算 ES
        List<EsResult> results = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> entry : scenarioGroups.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String scenarioType = parts[0];
            int lhDays = Integer.parseInt(parts[1]);
            String riskClass = parts[2];
            BigDecimal esValue = computeEs(entry.getValue());
            results.add(new EsResult(scenarioType, ImaConstants.ES_CONFIDENCE, lhDays, riskClass, esValue));
        }
        return results;
    }

    /**
     * 以 97.5% 置信度计算 ES：对组合级 PnL 从小到大排序，
     * 取最差 floor(n * 0.025) 条的绝对值均值（确保非负）。
     * 若样本数不足则取最差 1 条。
     */
    private BigDecimal computeEs(List<BigDecimal> pnls) {
        if (pnls == null || pnls.isEmpty()) return BigDecimal.ZERO;

        List<BigDecimal> sorted = new ArrayList<>(pnls);
        // 升序排列（最差亏损在最前）
        Collections.sort(sorted);

        int n = sorted.size();
        int tailCount = Math.max(1, (int) Math.floor(n * (1.0 - ImaConstants.ES_CONFIDENCE.doubleValue())));

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < tailCount; i++) {
            // PnL 负值为亏损，取绝对值
            sum = sum.add(sorted.get(i).negate());
        }
        return sum.divide(BigDecimal.valueOf(tailCount), 10, RoundingMode.HALF_UP);
    }

    /**
     * 将单笔 instrument PnL 累加到对应的组合 PnL bucket。
     */
    private void accumulatePnl(Map<String, BigDecimal> map,
                                String scenarioType, String subscenarioId,
                                int lhDays, String riskClass, BigDecimal pnl) {
        if (pnl == null || scenarioType == null) return;
        String key = scenarioType + "|" + subscenarioId + "|" + lhDays + "|" + riskClass;
        map.merge(key, pnl, BigDecimal::add);
    }
}
