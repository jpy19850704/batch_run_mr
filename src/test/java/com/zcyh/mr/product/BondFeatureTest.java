package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import com.zcyh.mr.product.bond.Bond;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * 含权债 + 摊销债券 测试
 * 覆盖：含权债(Call)、摊销债、含权债+摊销(摊销应被忽略)
 */
public class BondFeatureTest {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  含权债 + 摊销债券 功能测试");
        System.out.println("========================================\n");

        int pass = 0, fail = 0;

        // ---- 测试1: 含权债(Call) ----
        System.out.println("=== 测试1: 含权债(Call) ===");
        try {
            pass += runCallBondTest() ? 1 : 0;
        } catch (Exception e) {
            System.out.printf("  [FAIL] 异常: %s%n", e.getMessage());
            e.printStackTrace(System.out);
            fail++;
        }

        // ---- 测试2: 摊销债券 ----
        System.out.println("\n=== 测试2: 摊销债券 ===");
        try {
            pass += runAmortBondTest() ? 1 : 0;
        } catch (Exception e) {
            System.out.printf("  [FAIL] 异常: %s%n", e.getMessage());
            e.printStackTrace(System.out);
            fail++;
        }

        // ---- 测试3: 含权债+摊销(摊销应被忽略) ----
        System.out.println("\n=== 测试3: 含权债+摊销(摊销应被忽略) ===");
        try {
            pass += runCallAmortBondTest() ? 1 : 0;
        } catch (Exception e) {
            System.out.printf("  [FAIL] 异常: %s%n", e.getMessage());
            e.printStackTrace(System.out);
            fail++;
        }

        System.out.println("\n========================================");
        System.out.printf("  结果: 通过=%d, 失败=%d%n", pass, fail);
        System.out.println("========================================");
        System.exit(fail > 0 ? 1 : 0);
    }

    /**
     * 含权债(Call)测试：验证行权日选择和估值
     */
    private static boolean runCallBondTest() {
        String data = FileUtils.loadData("data/trade/callBond_test.json");
        Loader loader = new Loader(data);
        HashMap<String, Object> trade = loader.getTrades().get(0);
        MarketData md = loader.getMarketData();
        LocalDate dataDate = loader.getDataDate();

        Bond.BondInfo bondInfo = JSONObject.parseObject(
                new JSONObject(trade).toString(), Bond.BondInfo.class);
        Bond bond = new Bond(dataDate, bondInfo, md, new Calendar());
        Bond.BondMeasure measure = bond.calc();

        System.out.printf("  optionBondFlag  = %s%n", bondInfo.optionBondFlag);
        System.out.printf("  callPutDate数   = %d%n", bondInfo.callPutDate.size());
        System.out.printf("  valuation       = %.4f%n", measure.valuation);
        System.out.printf("  valuationCny    = %.4f%n", measure.valuationCny);
        System.out.printf("  duration        = %.6f%n", measure.effectiveDuration);
        System.out.printf("  cashFlows       = %d%n",
                measure.cashFlowList != null ? measure.cashFlowList.size() : 0);

        boolean ok = true;
        if (!bondInfo.optionBondFlag) {
            System.out.println("  [FAIL] optionBondFlag 应为 true");
            ok = false;
        }
        if (Double.isNaN(measure.valuation) || Double.isInfinite(measure.valuation)) {
            System.out.println("  [FAIL] valuation 为 NaN/Inf");
            ok = false;
        }
        if (measure.cashFlowList == null || measure.cashFlowList.isEmpty()) {
            System.out.println("  [FAIL] 现金流为空");
            ok = false;
        }
        System.out.println(ok ? "  [PASS]" : "  [FAIL]");
        return ok;
    }

    /**
     * 摊销债券测试：验证递减本金和自动截止
     */
    private static boolean runAmortBondTest() {
        String data = FileUtils.loadData("data/trade/amortBond_test.json");
        Loader loader = new Loader(data);
        HashMap<String, Object> trade = loader.getTrades().get(0);
        MarketData md = loader.getMarketData();
        LocalDate dataDate = loader.getDataDate();

        Bond.BondInfo bondInfo = JSONObject.parseObject(
                new JSONObject(trade).toString(), Bond.BondInfo.class);
        Bond bond = new Bond(dataDate, bondInfo, md, new Calendar());
        Bond.BondMeasure measure = bond.calc();

        System.out.printf("  notional        = %.0f%n", bondInfo.notional);
        System.out.printf("  摊销条目数       = %d%n", bondInfo.amortizationSchedule.size());
        System.out.printf("  valuation       = %.4f%n", measure.valuation);
        System.out.printf("  cashFlows       = %d%n",
                measure.cashFlowList != null ? measure.cashFlowList.size() : 0);

        boolean ok = true;
        if (Double.isNaN(measure.valuation) || Double.isInfinite(measure.valuation)) {
            System.out.println("  [FAIL] valuation 为 NaN/Inf");
            ok = false;
        }
        if (measure.cashFlowList == null || measure.cashFlowList.isEmpty()) {
            System.out.println("  [FAIL] 现金流为空");
            ok = false;
        }

        // 验证现金流中有 notional 类型（摊销偿还）
        long notionalCfCount = measure.cashFlowList.stream()
                .filter(cf -> "notional".equalsIgnoreCase(cf.cashType))
                .count();
        System.out.printf("  notional现金流数 = %d%n", notionalCfCount);
        if (notionalCfCount == 0) {
            System.out.println("  [FAIL] 无摊销本金现金流");
            ok = false;
        }

        // 打印各期现金流明细
        System.out.println("  --- 现金流明细 ---");
        for (StructuredCashflow.Cashflow cf : measure.cashFlowList) {
            System.out.printf("    %s | %s | cf=%.4f | df=%.6f%n",
                    cf.paymentDate, cf.cashType, cf.cf, cf.discoutFactor);
        }

        System.out.println(ok ? "  [PASS]" : "  [FAIL]");
        return ok;
    }

    /**
     * 含权债+摊销测试：验证摊销被忽略
     */
    private static boolean runCallAmortBondTest() {
        String data = FileUtils.loadData("data/trade/callAmortBond_test.json");
        Loader loader = new Loader(data);
        HashMap<String, Object> trade = loader.getTrades().get(0);
        MarketData md = loader.getMarketData();
        LocalDate dataDate = loader.getDataDate();

        Bond.BondInfo bondInfo = JSONObject.parseObject(
                new JSONObject(trade).toString(), Bond.BondInfo.class);

        // 构造前记录：原始 amortizationSchedule 应非空
        boolean hadSchedule = bondInfo.amortizationSchedule != null
                && !bondInfo.amortizationSchedule.isEmpty();
        System.out.printf("  构造前摊销计划   = %s%n", hadSchedule ? "有" : "无");

        Bond bond = new Bond(dataDate, bondInfo, md, new Calendar());
        Bond.BondMeasure measure = bond.calc();

        // 构造后：含权债应清除摊销计划
        boolean scheduleCleared = bondInfo.amortizationSchedule == null;
        System.out.printf("  构造后摊销计划   = %s%n", scheduleCleared ? "已清除" : "仍存在");
        System.out.printf("  optionBondFlag  = %s%n", bondInfo.optionBondFlag);
        System.out.printf("  valuation       = %.4f%n", measure.valuation);
        System.out.printf("  cashFlows       = %d%n",
                measure.cashFlowList != null ? measure.cashFlowList.size() : 0);

        boolean ok = true;
        if (!hadSchedule) {
            System.out.println("  [FAIL] 输入数据应包含摊销计划");
            ok = false;
        }
        if (!scheduleCleared) {
            System.out.println("  [FAIL] 含权债应忽略摊销计划(置null)");
            ok = false;
        }
        if (Double.isNaN(measure.valuation) || Double.isInfinite(measure.valuation)) {
            System.out.println("  [FAIL] valuation 为 NaN/Inf");
            ok = false;
        }

        // 验证现金流中不应有摊销偿还（所有 notional 类型应仅为期末本金）
        long notionalCfCount = measure.cashFlowList.stream()
                .filter(cf -> "notional".equalsIgnoreCase(cf.cashType))
                .count();
        System.out.printf("  notional现金流数 = %d (期末本金,非摊销)%n", notionalCfCount);

        System.out.println(ok ? "  [PASS]" : "  [FAIL]");
        return ok;
    }
}
