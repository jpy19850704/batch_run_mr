package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.loader.FileUtils;

/**
 * 压力情景功能端到端测试
 *
 * 使用 Calc 入口验证：
 * 1. 正常估值结果不变
 * 2. scenario_result 数组存在且包含指定情景
 * 3. 情景下估值结果与基准有差异（利率变动导致估值变化）
 */
public class ScenarioE2eTest {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  scenario_data 压力情景端到端测试");
        System.out.println("========================================\n");

        testNoScenario();
        testWithScenario();

        System.out.println("\n========================================");
        System.out.println("  结果: 通过=" + passed + ", 失败=" + failed);
        System.out.println("========================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 测试1: 无 scenario_data 时输出不含 scenario_result
     */
    static void testNoScenario() {
        System.out.println("[测试1] 无 scenario_data 时输出格式不变");
        try {
            String data = FileUtils.loadData("data/trade/irsccs_test10.json");
            // 只取第一条交易
            JSONObject jo = JSON.parseObject(data);
            JSONArray trades = jo.getJSONArray("trade_data");
            JSONArray singleTrade = new JSONArray();
            singleTrade.add(trades.get(0));
            jo.put("trade_data", singleTrade);

            Calc calc = new Calc(jo.toJSONString());
            String result = calc.run();
            JSONObject resultJson = JSON.parseObject(result);
            JSONObject dataObj = resultJson.getJSONObject("data");

            assertTrue("data 对象存在", dataObj != null);
            assertTrue("trade_data 存在", dataObj.getJSONArray("trade_data") != null);
            assertTrue("无 scenario_result", dataObj.getJSONArray("scenario_result") == null);

            System.out.println("  [PASS]\n");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAIL] " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * 测试2: 有 scenario_data 时输出包含 scenario_result
     * 构造两个情景：
     * 情景1: 利率上行100bp（IR_SPOT 曲线所有利率 + 0.01）
     * 情景2: 利率下行50bp（IR_SPOT 曲线所有利率 - 0.005）
     */
    static void testWithScenario() {
        System.out.println("[测试2] 有 scenario_data 时输出包含情景结果");
        try {
            String data = FileUtils.loadData("data/trade/irsccs_test10.json");
            JSONObject jo = JSON.parseObject(data);

            // 只用第一条交易
            JSONArray trades = jo.getJSONArray("trade_data");
            JSONArray singleTrade = new JSONArray();
            singleTrade.add(trades.get(0));
            jo.put("trade_data", singleTrade);

            // 提取基础市场数据中的 IR_SPOT 曲线
            JSONArray baseMkData = jo.getJSONArray("market_data");
            JSONArray irCurves = new JSONArray();
            for (int i = 0; i < baseMkData.size(); i++) {
                JSONObject mk = baseMkData.getJSONObject(i);
                if ("IR_SPOT".equals(mk.getString("CURVE_TYPE"))) {
                    irCurves.add(mk);
                }
            }

            // 构造情景1: 利率上行100bp
            JSONArray scenMk1 = new JSONArray();
            for (int i = 0; i < irCurves.size(); i++) {
                JSONObject orig = JSON.parseObject(irCurves.getJSONObject(i).toJSONString());
                JSONArray curveData = orig.getJSONArray("CURVE_DATA");
                for (int j = 0; j < curveData.size(); j++) {
                    JSONObject pt = curveData.getJSONObject(j);
                    pt.put("RATE", pt.getDoubleValue("RATE") + 0.01);
                }
                scenMk1.add(orig);
            }
            JSONObject scenObj1 = new JSONObject();
            scenObj1.put("SCENARIO_NAME", "利率上行100bp");
            scenObj1.put("market_data", scenMk1);

            // 构造情景2: 利率下行50bp
            JSONArray scenMk2 = new JSONArray();
            for (int i = 0; i < irCurves.size(); i++) {
                JSONObject orig = JSON.parseObject(irCurves.getJSONObject(i).toJSONString());
                JSONArray curveData = orig.getJSONArray("CURVE_DATA");
                for (int j = 0; j < curveData.size(); j++) {
                    JSONObject pt = curveData.getJSONObject(j);
                    pt.put("RATE", pt.getDoubleValue("RATE") - 0.005);
                }
                scenMk2.add(orig);
            }
            JSONObject scenObj2 = new JSONObject();
            scenObj2.put("SCENARIO_NAME", "利率下行50bp");
            scenObj2.put("market_data", scenMk2);

            // 组装 scenario_data
            JSONArray scenarioData = new JSONArray();
            scenarioData.add(scenObj1);
            scenarioData.add(scenObj2);
            jo.put("scenario_data", scenarioData);

            // 执行计算
            Calc calc = new Calc(jo.toJSONString());
            String result = calc.run();
            JSONObject resultJson = JSON.parseObject(result);
            JSONObject dataObj = resultJson.getJSONObject("data");

            assertTrue("data 对象存在", dataObj != null);

            // 验证基准估值
            JSONArray baseTrades = dataObj.getJSONArray("trade_data");
            assertTrue("基准 trade_data 非空", baseTrades != null && !baseTrades.isEmpty());
            double baseVal = baseTrades.getJSONObject(0).getDoubleValue("VALUATION_CNY");
            System.out.println("  基准 valuationCny = " + baseVal);

            // 验证 scenario_result
            JSONArray scenResults = dataObj.getJSONArray("scenario_result");
            assertTrue("scenario_result 存在", scenResults != null);
            assertTrue("scenario_result 有2个情景", scenResults.size() == 2);

            // 情景1: 利率上行100bp
            JSONObject scenRes1 = scenResults.getJSONObject(0);
            assertTrue("情景1名称正确", "利率上行100bp".equals(scenRes1.getString("SCENARIO_NAME")));
            JSONArray scenTrades1 = scenRes1.getJSONArray("trade_data");
            assertTrue("情景1 trade_data 非空", scenTrades1 != null && !scenTrades1.isEmpty());

            JSONObject t1 = scenTrades1.getJSONObject(0);
            assertTrue("情景1包含INSTRUMENT_ID", t1.getString("INSTRUMENT_ID") != null);
            double base1 = t1.getDoubleValue("BASE_VALUATION_CNY");
            double scen1 = t1.getDoubleValue("SCENARIO_VALUATION_CNY");
            double pnl1 = t1.getDoubleValue("PNL");
            System.out.println("  情景1 [利率上行100bp]:");
            System.out.println("    INSTRUMENT_ID       = " + t1.getString("INSTRUMENT_ID"));
            System.out.println("    BASE_VALUATION_CNY   = " + base1);
            System.out.println("    SCENARIO_VALUATION_CNY = " + scen1);
            System.out.println("    PNL                  = " + pnl1);
            assertTrue("PNL = 情景值 - 基准值", Math.abs(pnl1 - (scen1 - base1)) < 0.01);
            assertTrue("情景1不含多余字段", t1.get("PAY_VALUATION") == null);

            // 情景2: 利率下行50bp
            JSONObject scenRes2 = scenResults.getJSONObject(1);
            assertTrue("情景2名称正确", "利率下行50bp".equals(scenRes2.getString("SCENARIO_NAME")));
            JSONArray scenTrades2 = scenRes2.getJSONArray("trade_data");
            JSONObject t2 = scenTrades2.getJSONObject(0);
            double pnl2 = t2.getDoubleValue("PNL");
            System.out.println("  情景2 [利率下行50bp]:");
            System.out.println("    PNL                  = " + pnl2);

            // 验证方向合理性
            assertTrue("上行/下行方向相反", pnl1 * pnl2 < 0);
            System.out.println("  方向验证: 上行PnL=" + pnl1 + " 下行PnL=" + pnl2 + " ✓");

            System.out.println("  [PASS]\n");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAIL] " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    static void assertTrue(String msg, boolean condition) {
        if (!condition)
            throw new AssertionError("断言失败: " + msg);
    }
}
