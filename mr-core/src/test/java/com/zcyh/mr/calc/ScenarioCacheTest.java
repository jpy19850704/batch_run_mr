package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.loader.FileUtils;

/**
 * 缓存优化端到端测试。
 * 验证 productCache / infoCache 改造后，SCENARIO 模式下基准估值与独立 PRICING 一致，
 * 场景估值的 PnL 非零（表示场景冲击生效）。
 *
 * 测试流程：
 * 1. 加载含场景数据的 JSON（fxfwd 作为 B 类产品代表）
 * 2. 分别以 PRICING 和 SCENARIO 模式执行
 * 3. 比对基准估值结果是否一致
 * 4. 验证场景 PnL 计算正确
 */
public class ScenarioCacheTest {

    /** 估值容差（CNY） */
    private static final double TOL = 0.01;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("========== 缓存优化端到端测试 ==========\n");

        // 读取已有 fxfwd 测试数据
        String rawData = FileUtils.loadData("data/trade/fxfwd.json");
        JSONObject inputJson = JSON.parseObject(rawData);

        // 构造场景冲击数据：CNY 利率曲线上移 10bp
        JSONArray scenarioData = buildIrShockScenario(inputJson);

        // ===== 步骤 1: PRICING 模式基准估值 =====
        System.out.println("[步骤1] PRICING 模式基准估值...");
        JSONObject pricingInput = inputJson.clone();
        pricingInput.put("oper_code", "PRICING");
        String pricingResult = new Calc(pricingInput.toJSONString(), null).run();
        JSONObject pricingJson = JSON.parseObject(pricingResult);
        JSONArray pricingTrades = pricingJson.getJSONObject("data").getJSONArray("trade_data");
        System.out.println("  基准估值交易数: " + pricingTrades.size());

        // ===== 步骤 2: SCENARIO 模式估值 =====
        System.out.println("\n[步骤2] SCENARIO 模式估值...");
        JSONObject scenarioInput = inputJson.clone();
        scenarioInput.put("oper_code", "SCENARIO");
        scenarioInput.put("scenario_data", scenarioData);
        String scenarioResult = new Calc(scenarioInput.toJSONString(), null).run();
        JSONObject scenarioJson = JSON.parseObject(scenarioResult);
        JSONObject scenarioDataObj = scenarioJson.getJSONObject("data");
        JSONArray scenarioTrades = scenarioDataObj.getJSONArray("trade_data");
        JSONArray scenarioResults = scenarioDataObj.getJSONArray("scenario_result");
        System.out.println("  SCENARIO 基准交易数: " + scenarioTrades.size());
        System.out.println("  场景结果条目数: " + (scenarioResults != null ? scenarioResults.size() : 0));

        // ===== 步骤 3: 比对基准估值 =====
        System.out.println("\n[步骤3] 比对基准估值一致性...");
        checkBaseConsistency(pricingTrades, scenarioTrades);

        // ===== 步骤 4: 验证场景 PnL =====
        System.out.println("\n[步骤4] 验证场景 PnL...");
        checkScenarioPnl(scenarioResults);

        // ===== 总结 =====
        System.out.println("\n========== 测试结果 ==========");
        System.out.println("  通过: " + passed);
        System.out.println("  失败: " + failed);
        System.out.println("  总计: " + (passed + failed));
        System.out.println(failed == 0 ? "  ✓ 全部通过" : "  ✗ 存在失败");
        System.out.println("==============================\n");

        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 构造利率冲击场景数据：CNY 曲线上移 10bp
     */
    private static JSONArray buildIrShockScenario(JSONObject inputJson) {
        JSONArray scenarioData = new JSONArray();

        // 从原始市场数据中找到 CNY 曲线并上移 10bp
        JSONArray marketData = inputJson.getJSONArray("market_data");
        for (int i = 0; i < marketData.size(); i++) {
            JSONObject curve = marketData.getJSONObject(i);
            if ("IR_SPOT".equals(curve.getString("CURVE_TYPE"))) {
                String curveId = curve.getString("CURVE_ID");
                // 只冲击第一条 IR_SPOT 曲线
                JSONObject scenarioEntry = new JSONObject();
                scenarioEntry.put("SCENARIO_NAME", "CNY_IR_UP_10BP");

                JSONObject shockedCurve = curve.clone();
                JSONArray shockedData = new JSONArray();
                JSONArray origData = shockedCurve.getJSONArray("CURVE_DATA");
                for (int j = 0; j < origData.size(); j++) {
                    JSONObject point = origData.getJSONObject(j).clone();
                    // 上移 10bp = 0.001
                    point.put("RATE", point.getDoubleValue("RATE") + 0.001);
                    shockedData.add(point);
                }
                shockedCurve.put("CURVE_DATA", shockedData);

                JSONArray scenMkData = new JSONArray();
                scenMkData.add(shockedCurve);
                scenarioEntry.put("market_data", scenMkData);
                scenarioData.add(scenarioEntry);
                break;
            }
        }
        return scenarioData;
    }

    /**
     * 验证 PRICING 和 SCENARIO 模式下基准估值一致
     */
    private static void checkBaseConsistency(JSONArray pricingTrades, JSONArray scenarioTrades) {
        assertEqual("基准交易笔数一致", pricingTrades.size(), scenarioTrades.size());

        for (int i = 0; i < pricingTrades.size(); i++) {
            JSONObject pTrade = pricingTrades.getJSONObject(i);
            JSONObject sTrade = scenarioTrades.getJSONObject(i);
            String instId = pTrade.getString("INSTRUMENT_ID");
            String pStatus = pTrade.getString("STATUS");
            String sStatus = sTrade.getString("STATUS");

            assertEqual("交易 " + instId + " STATUS 一致", pStatus, sStatus);

            if ("SUCCESS".equals(pStatus)) {
                double pVal = pTrade.getDoubleValue("VALUATION_CNY");
                double sVal = sTrade.getDoubleValue("VALUATION_CNY");
                assertClose("交易 " + instId + " VALUATION_CNY 一致",
                        pVal, sVal, TOL);
            }
        }
    }

    /**
     * 验证场景结果中存在非零 PnL
     */
    private static void checkScenarioPnl(JSONArray scenarioResults) {
        assertTrue("存在场景结果", scenarioResults != null && !scenarioResults.isEmpty());
        if (scenarioResults == null || scenarioResults.isEmpty()) return;

        JSONObject firstScenario = scenarioResults.getJSONObject(0);
        String scenarioName = firstScenario.getString("SCENARIO_NAME");
        System.out.println("  场景名称: " + scenarioName);

        JSONArray tradeData = firstScenario.getJSONArray("trade_data");
        assertTrue("场景交易结果非空", tradeData != null && !tradeData.isEmpty());
        if (tradeData == null || tradeData.isEmpty()) return;

        boolean hasNonZeroPnl = false;
        for (int i = 0; i < tradeData.size(); i++) {
            JSONObject item = tradeData.getJSONObject(i);
            String instId = item.getString("INSTRUMENT_ID");
            double baseCny = item.getDoubleValue("BASE_VALUATION_CNY");
            double scenCny = item.getDoubleValue("SCENARIO_VALUATION_CNY");
            double pnl = item.getDoubleValue("PNL");

            System.out.println("    " + instId
                    + " | 基准=" + String.format("%.2f", baseCny)
                    + " | 场景=" + String.format("%.2f", scenCny)
                    + " | PnL=" + String.format("%.2f", pnl));

            assertClose("交易 " + instId + " PnL 自洽",
                    pnl, scenCny - baseCny, TOL);

            if (Math.abs(pnl) > 0.001) {
                hasNonZeroPnl = true;
            }
        }
        assertTrue("至少一笔交易 PnL 非零（场景冲击生效）", hasNonZeroPnl);
    }

    // ===== 断言工具 =====

    private static void assertEqual(String label, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            System.out.println("  ✓ " + label);
        } else {
            failed++;
            System.out.println("  ✗ " + label + " (期望=" + expected + " 实际=" + actual + ")");
        }
    }

    private static void assertClose(String label, double expected, double actual, double tol) {
        if (Math.abs(expected - actual) <= tol) {
            passed++;
            System.out.println("  ✓ " + label);
        } else {
            failed++;
            System.out.println("  ✗ " + label
                    + " (期望=" + String.format("%.6f", expected)
                    + " 实际=" + String.format("%.6f", actual)
                    + " 差异=" + String.format("%.8f", Math.abs(expected - actual)) + ")");
        }
    }

    private static void assertTrue(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + label);
        } else {
            failed++;
            System.out.println("  ✗ " + label);
        }
    }
}
