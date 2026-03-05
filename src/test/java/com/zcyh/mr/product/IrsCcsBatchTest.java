package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.ir_deri.IrsCcs;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * IRSCCS 10场景批量测试
 */
public class IrsCcsBatchTest {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  IRSCCS 10场景批量测试");
        System.out.println("========================================\n");

        String data = FileUtils.loadData("data/trade/irsccs_test10.json");
        Loader loader = new Loader(data);
        List<HashMap<String, Object>> trades = loader.getTrades();
        MarketData marketData = loader.getMarketData();
        LocalDate dataDate = loader.getDataDate();

        int pass = 0, fail = 0;
        String[] desc = {
                "CCS Fixed-Fixed 短期基准",
                "CCS Fixed-Floating CNY固定/USD浮动",
                "CCS Floating-Fixed CNY浮动/USD固定",
                "CCS Floating-Floating 双浮动",
                "IRS 同币种CNY Fixed-Floating",
                "IRS 同币种USD Fixed-Floating",
                "CCS 不同付息频率 6M/3M",
                "CCS 30/360 计息基础",
                "CCS 长期限5Y",
                "CCS 仅到期交换本金"
        };

        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = (JSONObject) trades.get(i);
            String id = trade.getString("INSTRUMENT_ID");
            System.out.printf("[测试%d] %s (%s)%n", i + 1, desc[i], id);
            try {
                IrsCcs.IrsCcsInfo info = JSONObject.parseObject(
                        trade.toString(), IrsCcs.IrsCcsInfo.class);
                IrsCcs irsCcs = new IrsCcs(dataDate, info, marketData, new Calendar());
                IrsCcs.IrsCcsMeasure measure = irsCcs.calc();
                double payValue = getDetailNumber(measure, "PAY_VALUATION");
                double recValue = getDetailNumber(measure, "REC_VALUATION");
                double payPv01 = getDetailNumber(measure, "PAY_PV01");
                double recPv01 = getDetailNumber(measure, "REC_PV01");

                System.out.printf("  valuation    = %.4f%n", measure.valuation);
                System.out.printf("  valuationCny = %.4f%n", measure.valuationCny);
                System.out.printf("  payValue     = %.4f%n", payValue);
                System.out.printf("  recValue     = %.4f%n", recValue);
                System.out.printf("  pv01         = %.6f%n", measure.pv01);
                System.out.printf("  payPv01      = %.6f%n", payPv01);
                System.out.printf("  recPv01      = %.6f%n", recPv01);
                System.out.printf("  cashFlows    = %d%n", measure.cashFlowList.size());
                System.out.printf("  sensitivities= %d%n",
                        measure.sensitivityList != null ? measure.sensitivityList.size() : 0);

                // 基本合理性校验
                boolean ok = true;
                if (Double.isNaN(measure.valuation) || Double.isInfinite(measure.valuation)) {
                    System.out.println("  [FAIL] valuation 为 NaN/Inf");
                    ok = false;
                }
                if (measure.cashFlowList.isEmpty()) {
                    System.out.println("  [FAIL] 现金流为空");
                    ok = false;
                }
                if (ok) {
                    System.out.println("  [PASS]");
                    pass++;
                } else {
                    fail++;
                }
            } catch (Exception e) {
                System.out.printf("  [FAIL] 异常: %s%n", e.getMessage());
                e.printStackTrace(System.out);
                fail++;
            }
            System.out.println();
        }

        System.out.println("========================================");
        System.out.printf("  结果: 通过=%d, 失败=%d%n", pass, fail);
        System.out.println("========================================");
        System.exit(fail > 0 ? 1 : 0);
    }

    /**
     * 从 DETAIL 中读取数值字段
     */
    private static double getDetailNumber(IrsCcs.IrsCcsMeasure measure, String key) {
        if (measure.detail == null) {
            throw new IllegalArgumentException("detail 为空，缺少字段: " + key);
        }
        Object value = measure.detail.get(key);
        if (value == null) {
            throw new IllegalArgumentException("detail 缺少字段: " + key);
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }
}
