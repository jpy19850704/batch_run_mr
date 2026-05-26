package com.zcyh.mr.frtbima.validation;

import com.zcyh.mr.frtbima.validation.backtest.BankWideBacktest;
import com.zcyh.mr.frtbima.validation.backtest.DeskLevelBacktest;
import com.zcyh.mr.frtbima.validation.model.BacktestResult;
import com.zcyh.mr.frtbima.validation.model.DailyPnl;
import com.zcyh.mr.frtbima.validation.model.PlaTestResult;
import com.zcyh.mr.frtbima.validation.model.ValidationNodeResult;
import com.zcyh.mr.frtbima.validation.pla.PlaTestEvaluator;

import java.math.BigDecimal;
import java.util.List;

/**
 * 验证节点编排器。
 * 接受任意维度节点（交易台、全行或自定义分组）的输入，
 * 自动执行回测+PLA测试，合并综合zone，输出ValidationNodeResult。
 *
 * 回测策略（MAR32.5-32.6）：
 * - BANK_WIDE：BankWideBacktest（仅 actualPnl）
 * - DESK：DeskLevelBacktest（actualPnl + hypotheticalPnl 合并，同一天去重计数，明细分别记录）
 * - 自定义：默认使用 BankWideBacktest（仅 actualPnl）
 *
 * 规则依据：MAR32.4-32.42
 */
public class ValidationNode {

    /** 节点类型常量：交易台 */
    public static final String NODE_TYPE_DESK = "DESK";

    /** 节点类型常量：全行 */
    public static final String NODE_TYPE_BANK_WIDE = "BANK_WIDE";

    private final BankWideBacktest bankBacktester = new BankWideBacktest();
    private final DeskLevelBacktest deskBacktester = new DeskLevelBacktest();
    private final PlaTestEvaluator plaEvaluator = new PlaTestEvaluator();

    /**
     * 对指定节点执行完整验证流程（回测 + PLA → 综合zone合并）。
     *
     * @param nodeId    节点标识
     * @param nodeType  节点类型（DESK / BANK_WIDE / 自定义）
     * @param pnlSeries 250天每日PnL序列
     * @param saCapital 标准法资本要求，可为null
     * @return 综合验证结果
     */
    public ValidationNodeResult validate(String nodeId, String nodeType,
                                         List<DailyPnl> pnlSeries,
                                         BigDecimal saCapital) {
        if (pnlSeries == null || pnlSeries.isEmpty()) {
            throw new IllegalArgumentException("pnlSeries 不能为空");
        }

        ValidationNodeResult result = new ValidationNodeResult(nodeId, nodeType);
        result.setSaCapital(saCapital);

        // 按节点类型选择回测策略
        BacktestResult backtestResult = runBacktest(nodeType, pnlSeries);
        result.setBacktestResult(backtestResult);

        // PLA 测试
        PlaTestResult plaResult = plaEvaluator.evaluate(pnlSeries);
        result.setPlaTestResult(plaResult);

        result.computeZone();
        return result;
    }

    /**
     * 仅执行回测验证（无PLA）。
     */
    public ValidationNodeResult validateBacktestOnly(String nodeId, String nodeType,
                                                     List<DailyPnl> pnlSeries,
                                                     BigDecimal saCapital) {
        if (pnlSeries == null || pnlSeries.isEmpty()) {
            throw new IllegalArgumentException("pnlSeries 不能为空");
        }

        ValidationNodeResult result = new ValidationNodeResult(nodeId, nodeType);
        result.setSaCapital(saCapital);
        result.setBacktestResult(runBacktest(nodeType, pnlSeries));
        result.computeZone();
        return result;
    }

    /**
     * 仅执行PLA验证（无回测）。
     */
    public ValidationNodeResult validatePlaOnly(String nodeId, String nodeType,
                                                List<DailyPnl> pnlSeries,
                                                BigDecimal saCapital) {
        if (pnlSeries == null || pnlSeries.isEmpty()) {
            throw new IllegalArgumentException("pnlSeries 不能为空");
        }

        ValidationNodeResult result = new ValidationNodeResult(nodeId, nodeType);
        result.setSaCapital(saCapital);
        result.setPlaTestResult(plaEvaluator.evaluate(pnlSeries));
        result.computeZone();
        return result;
    }

    /**
     * 根据节点类型选择回测策略。
     * DESK → 双重回测（actual+hypothetical合并计数）
     * 其他 → 仅 actualPnl 回测
     */
    private BacktestResult runBacktest(String nodeType, List<DailyPnl> pnlSeries) {
        if (NODE_TYPE_DESK.equals(nodeType)) {
            return deskBacktester.run(pnlSeries);
        }
        return bankBacktester.run(pnlSeries);
    }
}
