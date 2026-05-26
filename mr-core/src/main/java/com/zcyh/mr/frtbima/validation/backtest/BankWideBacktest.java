package com.zcyh.mr.frtbima.validation.backtest;

import com.zcyh.mr.frtbima.validation.common.ValidationConstants;
import com.zcyh.mr.frtbima.validation.model.BacktestResult;
import com.zcyh.mr.frtbima.validation.model.DailyPnl;
import com.zcyh.mr.frtbima.validation.model.ExceptionDetail;
import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 全行级回测。
 * 在250天窗口内，每日实际PnL与前一日99% VaR对比，统计亏损超出阈值的天数（例外次数），
 * 据此判定交通灯区间和乘数附加值。
 * 规则依据：MAR32.4-32.9
 *
 * 全行级仅使用 actualPnL（MAR32.5），明细中 pnlType 固定为 ACTUAL。
 */
public class BankWideBacktest {

    private final BacktestMultiplierTable multiplierTable = new BacktestMultiplierTable();

    /**
     * 执行全行级回测。
     * 每日 actualPnl 与该日记录中的前一日VaR（varValue）逐日对比。
     *
     * @param pnlSeries 250天每日 PnL 序列（按日期升序排列）
     * @return 回测结果（含突破明细，pnlType 均为 ACTUAL）
     */
    public BacktestResult run(List<DailyPnl> pnlSeries) {
        if (pnlSeries == null || pnlSeries.isEmpty()) {
            throw new IllegalArgumentException("pnlSeries 不能为空");
        }

        int exceptionCount = 0;
        List<ExceptionDetail> exceptions = new ArrayList<>();

        for (DailyPnl daily : pnlSeries) {
            BigDecimal actual = daily.getActualPnl();
            BigDecimal var = daily.getVarValue();
            if (actual == null || var == null) {
                continue;
            }
            BigDecimal threshold = var.abs().negate();
            if (actual.compareTo(threshold) < 0) {
                exceptionCount++;
                exceptions.add(new ExceptionDetail(
                        daily.getDate(), ExceptionDetail.PNL_TYPE_ACTUAL,
                        actual, var, threshold));
            }
        }

        TrafficLightZone zone = TrafficLightZone.fromExceptions(exceptionCount);
        BigDecimal addOn = multiplierTable.lookup(exceptionCount);

        if (pnlSeries.size() < ValidationConstants.BACKTEST_WINDOW_DAYS) {
            // 数据不足250天，结果仅供参考
        }

        return new BacktestResult(zone, exceptionCount, addOn, exceptions);
    }
}
