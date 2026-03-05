package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.bond.Bond;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * Bond 6场景批量测试
 * 覆盖：固息/无信用点差/浮息/外币/全价反解/信用曲线缺失
 */
public class BondBatchTest {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  Bond 6场景批量测试");
        System.out.println("========================================\n");

        String data = FileUtils.loadData("data/trade/bond_test6.json");
        Loader loader = new Loader(data);
        List<HashMap<String, Object>> trades = loader.getTrades();
        MarketData marketData = loader.getMarketData();
        LocalDate dataDate = loader.getDataDate();
        System.out.println("加载交易数 = " + trades.size() + "\n");

        String[] desc = {
                "固息债 + 信用点差曲线（CNY）",
                "固息债 无信用点差曲线（CNY）",
                "浮息债 + 定盘利率（CNY）",
                "固息债 外币USD + 信用点差",
                "固息债 全价反解 spreadOverYield",
                "固息债 信用点差曲线缺失（应警告并继续）"
        };

        int pass = 0, fail = 0;
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = (JSONObject) trades.get(i);
            String id = trade.getString("INSTRUMENT_ID");
            System.out.printf("[测试%d] %s (%s)%n", i + 1, desc[i], id);
            try {
                Bond.BondInfo bondInfo = JSONObject.parseObject(
                        trade.toString(), Bond.BondInfo.class);
                Bond bond = new Bond(dataDate, bondInfo, marketData, new Calendar());
                Bond.BondMeasure measure = bond.calc();

                System.out.printf("  valuation       = %.4f%n", measure.valuation);
                System.out.printf("  valuationCny    = %.4f%n", measure.valuationCny);
                System.out.printf("  spreadOverYield = %.8f%n", measure.spreadOverYield);
                System.out.printf("  pv01            = %.6f%n", measure.pv01);
                System.out.printf("  duration        = %.6f%n", measure.effectiveDuration);
                System.out.printf("  convexity       = %.6f%n", measure.effectiveConvexity);
                System.out.printf("  accruedInterest = %.4f%n", measure.accruedInterestCny);
                System.out.printf("  cashFlows       = %d%n",
                        measure.cashFlowList != null ? measure.cashFlowList.size() : 0);
                System.out.printf("  sensitivities   = %d%n",
                        measure.sensitivityList != null ? measure.sensitivityList.size() : 0);
                System.out.printf("  drc.jtd         = %.4f%n",
                        measure.drcDetail != null ? measure.drcDetail.jtd : 0.0);

                // 合理性校验
                boolean ok = true;
                if (Double.isNaN(measure.valuation) || Double.isInfinite(measure.valuation)) {
                    System.out.println("  [FAIL] valuation 为 NaN/Inf");
                    ok = false;
                }
                if (measure.cashFlowList == null || measure.cashFlowList.isEmpty()) {
                    System.out.println("  [FAIL] 现金流为空");
                    ok = false;
                }
                // 测试5: spreadOverYield 应非零
                if (i == 4 && measure.spreadOverYield == 0.0) {
                    System.out.println("  [FAIL] 全价反解但 spreadOverYield=0");
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
}
