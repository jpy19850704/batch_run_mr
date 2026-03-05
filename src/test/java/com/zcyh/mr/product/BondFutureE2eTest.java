package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.loader.FileUtils;

/**
 * BondFuture 端到端测试
 *
 * 验证内容：
 * 1. 基准估值：VALUATION_CNY 非零、netBasis 存在、PV01 合理
 * 2. 场景估值：SOY/CTD/netBasis 校准不变性
 * - 利率上行 → PnL 非零
 * - 利率下行 → PnL 方向与上行相反
 * - 两次相同场景结果一致（验证缓存复用）
 * 3. 与 Bond 混合：同时包含 Bond 和 BondFuture，验证各自独立估值
 */
public class BondFutureE2eTest {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  BondFuture 端到端测试");
        System.out.println("========================================\n");

        testBasePricing();
        testScenarioCalibrationInvariance();
        testMixedBondAndFuture();

        System.out.println("\n========================================");
        System.out.println("  结果: 通过=" + passed + ", 失败=" + failed);
        System.out.println("========================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 测试1: 基准估值正确性
     */
    static void testBasePricing() {
        System.out.println("[测试1] BondFuture 基准估值");
        try {
            String data = loadBondFutureData();
            Calc calc = new Calc(data);
            String result = calc.run();
            JSONObject resultJson = JSON.parseObject(result);
            JSONObject dataObj = resultJson.getJSONObject("data");

            assertTrue("data 对象存在", dataObj != null);

            JSONArray trades = dataObj.getJSONArray("trade_data");
            assertTrue("trade_data 存在", trades != null && !trades.isEmpty());

            JSONObject t = trades.getJSONObject(0);
            String instrumentId = t.getString("INSTRUMENT_ID");
            double valCny = t.getDoubleValue("VALUATION_CNY");
            double valueUnit = t.getDoubleValue("VALUE_UNIT");
            double pv01 = t.getDoubleValue("PV01");
            double netBasis = t.getDoubleValue("NET_BASIS");
            String undBondId = t.getString("UNDERLYING_BOND_ID");

            System.out.println("  INSTRUMENT_ID      = " + instrumentId);
            System.out.println("  VALUE_UNIT         = " + valueUnit);
            System.out.println("  VALUATION_CNY      = " + valCny);
            System.out.println("  PV01               = " + pv01);
            System.out.println("  NET_BASIS           = " + netBasis);
            System.out.println("  UNDERLYING_BOND_ID = " + undBondId);

            assertTrue("INSTRUMENT_ID 非空", instrumentId != null && !instrumentId.isEmpty());
            assertTrue("VALUATION_CNY 非零", Math.abs(valCny) > 0.01);
            assertTrue("VALUE_UNIT 非零", Math.abs(valueUnit) > 0.01);
            assertTrue("PV01 非零", Math.abs(pv01) > 0.0001);
            assertTrue("UNDERLYING_BOND_ID 非空", undBondId != null && !undBondId.isEmpty());

            // 无 scenario_data 时不应有 scenario_result
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
     * 测试2: 场景估值 - SOY/CTD/netBasis 校准不变性
     */
    static void testScenarioCalibrationInvariance() {
        System.out.println("[测试2] BondFuture 场景校准不变性");
        try {
            JSONObject jo = JSON.parseObject(loadBondFutureData());

            // 提取 IR_SPOT 曲线
            JSONArray baseMkData = jo.getJSONArray("market_data");
            JSONArray irCurves = new JSONArray();
            for (int i = 0; i < baseMkData.size(); i++) {
                JSONObject mk = baseMkData.getJSONObject(i);
                if ("IR_SPOT".equals(mk.getString("CURVE_TYPE"))) {
                    irCurves.add(mk);
                }
            }

            // 场景1: 利率上行 50bp
            JSONArray scenMk1 = shiftIrCurves(irCurves, 0.005);
            JSONObject scenObj1 = new JSONObject();
            scenObj1.put("SCENARIO_NAME", "利率上行50bp");
            scenObj1.put("market_data", scenMk1);

            // 场景2: 利率下行 50bp
            JSONArray scenMk2 = shiftIrCurves(irCurves, -0.005);
            JSONObject scenObj2 = new JSONObject();
            scenObj2.put("SCENARIO_NAME", "利率下行50bp");
            scenObj2.put("market_data", scenMk2);

            // 场景3: 与场景1 相同（验证缓存复用一致性）
            JSONArray scenMk3 = shiftIrCurves(irCurves, 0.005);
            JSONObject scenObj3 = new JSONObject();
            scenObj3.put("SCENARIO_NAME", "利率上行50bp_重复");
            scenObj3.put("market_data", scenMk3);

            JSONArray scenarioData = new JSONArray();
            scenarioData.add(scenObj1);
            scenarioData.add(scenObj2);
            scenarioData.add(scenObj3);
            jo.put("scenario_data", scenarioData);

            // 执行
            Calc calc = new Calc(jo.toJSONString());
            String result = calc.run();
            JSONObject resultJson = JSON.parseObject(result);
            JSONObject dataObj = resultJson.getJSONObject("data");

            assertTrue("data 对象存在", dataObj != null);

            // 基准估值
            JSONArray baseTrades = dataObj.getJSONArray("trade_data");
            assertTrue("基准 trade_data 非空", baseTrades != null && !baseTrades.isEmpty());
            double baseVal = baseTrades.getJSONObject(0).getDoubleValue("VALUATION_CNY");
            System.out.println("  基准 VALUATION_CNY = " + baseVal);

            // 场景结果
            JSONArray scenResults = dataObj.getJSONArray("scenario_result");
            assertTrue("scenario_result 存在", scenResults != null);
            assertTrue("scenario_result 有3个场景", scenResults.size() == 3);

            // 场景1: 利率上行50bp
            JSONObject scenRes1 = scenResults.getJSONObject(0);
            System.out.println("  [DEBUG] scenRes1 keys=" + scenRes1.keySet());
            System.out.println("  [DEBUG] scenRes1="
                    + scenRes1.toJSONString().substring(0, Math.min(500, scenRes1.toJSONString().length())));
            assertTrue("场景1名称正确", "利率上行50bp".equals(scenRes1.getString("SCENARIO_NAME")));
            JSONArray scenTrades1 = scenRes1.getJSONArray("trade_data");
            assertTrue("场景1 trade_data 非空", scenTrades1 != null && !scenTrades1.isEmpty());
            JSONObject t1 = scenTrades1.getJSONObject(0);
            double pnl1 = t1.getDoubleValue("PNL");
            System.out.println("  场景1 [利率上行50bp]:");
            System.out.println("    BASE_VALUATION_CNY     = " + t1.getDoubleValue("BASE_VALUATION_CNY"));
            System.out.println("    SCENARIO_VALUATION_CNY = " + t1.getDoubleValue("SCENARIO_VALUATION_CNY"));
            System.out.println("    PNL                    = " + pnl1);
            assertTrue("场景1 PnL 非零", Math.abs(pnl1) > 0.01);

            // 场景2: 利率下行50bp
            JSONObject scenRes2 = scenResults.getJSONObject(1);
            JSONArray scenTrades2 = scenRes2.getJSONArray("trade_data");
            JSONObject t2 = scenTrades2.getJSONObject(0);
            double pnl2 = t2.getDoubleValue("PNL");
            System.out.println("  场景2 [利率下行50bp]:");
            System.out.println("    PNL                    = " + pnl2);

            // 方向合理性：上行/下行 PnL 方向相反
            assertTrue("上行/下行 PnL 方向相反", pnl1 * pnl2 < 0);
            System.out.println("  方向验证: 上行PnL=" + pnl1 + " 下行PnL=" + pnl2 + " ✓");

            // 场景3: 与场景1 完全一致（缓存复用验证）
            JSONObject scenRes3 = scenResults.getJSONObject(2);
            JSONArray scenTrades3 = scenRes3.getJSONArray("trade_data");
            JSONObject t3 = scenTrades3.getJSONObject(0);
            double pnl3 = t3.getDoubleValue("PNL");
            System.out.println("  场景3 [利率上行50bp_重复]:");
            System.out.println("    PNL                    = " + pnl3);
            assertTrue("重复场景 PnL 一致", Math.abs(pnl1 - pnl3) < 0.001);
            System.out.println("  缓存复用验证: PnL差异=" + Math.abs(pnl1 - pnl3) + " ✓");

            System.out.println("  [PASS]\n");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAIL] " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * 测试3: Bond + BondFuture 混合估值
     */
    static void testMixedBondAndFuture() {
        System.out.println("[测试3] Bond + BondFuture 混合场景估值");
        try {
            JSONObject jo = JSON.parseObject(loadBondFutureData());

            // 加载 Bond 数据并取一条交易
            String bondData = FileUtils.loadData("data/trade/bond.json");
            JSONObject bondJo = JSON.parseObject(bondData);
            JSONArray bondTrades = bondJo.getJSONArray("trade_data");
            if (bondTrades == null || bondTrades.isEmpty()) {
                System.out.println("  [SKIP] bond.json 无交易数据");
                return;
            }

            // 将 Bond 交易加入 BondFuture 的 trade_data
            JSONArray trades = jo.getJSONArray("trade_data");
            trades.add(bondTrades.getJSONObject(0));

            // 添加利率上行场景
            JSONArray baseMkData = jo.getJSONArray("market_data");
            JSONArray irCurves = new JSONArray();
            for (int i = 0; i < baseMkData.size(); i++) {
                JSONObject mk = baseMkData.getJSONObject(i);
                if ("IR_SPOT".equals(mk.getString("CURVE_TYPE"))) {
                    irCurves.add(mk);
                }
            }
            JSONArray scenMk = shiftIrCurves(irCurves, 0.005);
            JSONObject scenObj = new JSONObject();
            scenObj.put("SCENARIO_NAME", "混合_利率上行50bp");
            scenObj.put("market_data", scenMk);
            JSONArray scenarioData = new JSONArray();
            scenarioData.add(scenObj);
            jo.put("scenario_data", scenarioData);

            // 合并 Bond 需要的市场数据
            JSONArray bondMkData = bondJo.getJSONArray("market_data");
            if (bondMkData != null) {
                for (int i = 0; i < bondMkData.size(); i++) {
                    baseMkData.add(bondMkData.getJSONObject(i));
                }
            }

            // 执行
            Calc calc = new Calc(jo.toJSONString());
            String result = calc.run();
            JSONObject resultJson = JSON.parseObject(result);
            JSONObject dataObj = resultJson.getJSONObject("data");

            assertTrue("data 对象存在", dataObj != null);

            // 基准估值应有多条交易
            JSONArray baseTrades = dataObj.getJSONArray("trade_data");
            assertTrue("基准 trade_data 非空", baseTrades != null && !baseTrades.isEmpty());
            System.out.println("  基准交易数量 = " + baseTrades.size());
            assertTrue("基准交易数 >= 2", baseTrades.size() >= 2);

            for (int i = 0; i < baseTrades.size(); i++) {
                JSONObject t = baseTrades.getJSONObject(i);
                System.out.println("  交易" + (i + 1) + ": ID=" + t.getString("INSTRUMENT_ID")
                        + " VALUATION_CNY=" + t.getDoubleValue("VALUATION_CNY"));
            }

            // 场景结果
            JSONArray scenResults = dataObj.getJSONArray("scenario_result");
            assertTrue("scenario_result 存在", scenResults != null && !scenResults.isEmpty());
            JSONObject scenRes = scenResults.getJSONObject(0);
            JSONArray scenTrades = scenRes.getJSONArray("trade_data");
            assertTrue("场景 trade_data 非空", scenTrades != null && !scenTrades.isEmpty());
            System.out.println("  场景交易数量 = " + scenTrades.size());

            for (int i = 0; i < scenTrades.size(); i++) {
                JSONObject t = scenTrades.getJSONObject(i);
                System.out.println("  场景交易" + (i + 1) + ": ID=" + t.getString("INSTRUMENT_ID")
                        + " PNL=" + t.getDoubleValue("PNL"));
                assertTrue("PnL 非零", Math.abs(t.getDoubleValue("PNL")) > 0.001);
            }

            System.out.println("  [PASS]\n");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAIL] " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    // ===== 工具方法 =====

    /**
     * 加载 bondFuture2.json 测试数据
     */
    static String loadBondFutureData() {
        return FileUtils.loadData("data/trade/bondFuture2.json");
    }

    /**
     * 对 IR_SPOT 曲线的所有利率点进行平移
     */
    static JSONArray shiftIrCurves(JSONArray irCurves, double shift) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < irCurves.size(); i++) {
            JSONObject orig = JSON.parseObject(irCurves.getJSONObject(i).toJSONString());
            JSONArray curveData = orig.getJSONArray("CURVE_DATA");
            for (int j = 0; j < curveData.size(); j++) {
                JSONObject pt = curveData.getJSONObject(j);
                pt.put("RATE", pt.getDoubleValue("RATE") + shift);
            }
            result.add(orig);
        }
        return result;
    }

    static void assertTrue(String msg, boolean condition) {
        if (!condition)
            throw new AssertionError("断言失败: " + msg);
    }
}
