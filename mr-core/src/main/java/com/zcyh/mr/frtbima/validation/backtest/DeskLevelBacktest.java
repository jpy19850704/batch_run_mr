package com.zcyh.mr.frtbima.validation.backtest;

import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;
import com.zcyh.mr.frtbima.validation.model.BacktestResult;
import com.zcyh.mr.frtbima.validation.model.DailyPnl;
import com.zcyh.mr.frtbima.validation.model.ExceptionDetail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易台级回测。
 * 根据 MAR32.6，交易台级回测须同时使用实际PnL和假设PnL与前一日VaR对比。
 *
 * 计数规则：
 * - 同一天中，actualPnl 和 hypotheticalPnl 任一突破即计为 1 次例外（按天计数）
 * - 若同一天两者都突破，仍只计 1 次例外，但明细中分别记录两条（标注 pnlType）
 *
 * 输出的 exceptionCount = 有突破的天数（去重），
 * exceptions 列表 = 全部突破明细（可能多于 exceptionCount，因同一天可有 ACTUAL+HYPOTHETICAL 两条）
 *
 * 规则依据：MAR32.4-32.9（交易台粒度），MAR32.6（须同时回测actual和hypothetical）
 */
public class DeskLevelBacktest {

    private final BacktestMultiplierTable multiplierTable = new BacktestMultiplierTable();

    /**
     * 执行交易台级双重回测（实际PnL + 假设PnL 合并计数）。
     *
     * @param pnlSeries 交易台250天每日PnL序列
     * @return 回测结果（exceptionCount按天去重，exceptions含全部突破明细及类型标注）
     */
    public BacktestResult run(List<DailyPnl> pnlSeries) {
        if (pnlSeries == null || pnlSeries.isEmpty()) {
            throw new IllegalArgumentException("pnlSeries 不能为空");
        }

        int exceptionCount = 0;
        List<ExceptionDetail> exceptions = new ArrayList<>();

        for (DailyPnl daily : pnlSeries) {
            BigDecimal var = daily.getVarValue();
            if (var == null) {
                continue;
            }
            BigDecimal threshold = var.abs().negate();
            boolean dayHasException = false;

            // 检查实际PnL
            BigDecimal actual = daily.getActualPnl();
            if (actual != null && actual.compareTo(threshold) < 0) {
                dayHasException = true;
                exceptions.add(new ExceptionDetail(
                        daily.getDate(), ExceptionDetail.PNL_TYPE_ACTUAL,
                        actual, var, threshold));
            }

            // 检查假设PnL
            BigDecimal hypothetical = daily.getHypotheticalPnl();
            if (hypothetical != null && hypothetical.compareTo(threshold) < 0) {
                dayHasException = true;
                exceptions.add(new ExceptionDetail(
                        daily.getDate(), ExceptionDetail.PNL_TYPE_HYPOTHETICAL,
                        hypothetical, var, threshold));
            }

            // 同一天不论几种PnL突破，例外次数只计1
            if (dayHasException) {
                exceptionCount++;
            }
        }

        TrafficLightZone zone = TrafficLightZone.fromExceptions(exceptionCount);
        BigDecimal addOn = multiplierTable.lookup(exceptionCount);
        return new BacktestResult(zone, exceptionCount, addOn, exceptions);
    }
}
