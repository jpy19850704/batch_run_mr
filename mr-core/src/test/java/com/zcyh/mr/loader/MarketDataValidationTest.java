package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;

/**
 * 市场数据校验异常场景测试
 * 模拟各类异常数据，验证校验逻辑是否正确过滤/剔除
 */
public class MarketDataValidationTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  市场数据校验异常场景测试");
        System.out.println("========================================\n");

        testCurveTypeEmpty();
        testCurveDataEmpty();
        testCurveIdEmpty();
        testIrSpotBadTerm();
        testIrSpotBadRate();
        testIrSpotMixedGoodBad();
        testFixingBadDate();
        testFixingBadValue();
        testFixingAliasType();
        testFixingDefaultForward();
        testFxSpotBadCurrency();
        testFxSpotBadRate();
        testFxSpotMixedGoodBad();
        testTradeValidation();
        testTradeValidationDomain();

        System.out.println("\n========================================");
        System.out.printf("  结果: 通过=%d, 失败=%d%n", pass, fail);
        System.out.println("========================================");
        System.exit(fail > 0 ? 1 : 0);
    }

    /** 测试1: CURVE_TYPE 为空 → 整条曲线跳过 */
    static void testCurveTypeEmpty() {
        System.out.println("[测试1] CURVE_TYPE 为空 → 整条曲线跳过");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "")
                .fluentPut("CURVE_ID", "TEST_CURVE")
                .fluentPut("CURVE_DATA", new JSONArray().fluentAdd(
                        new JSONObject().fluentPut("TERM", 30).fluentPut("RATE", 0.05))));
        Loader loader = new Loader(json);
        boolean ok = loader.getMarketData().irSpot.isEmpty();
        report(ok, "IR_SPOT 应为空（CURVE_TYPE 为空被跳过）");
        printLogs(loader);
    }

    /** 测试2: CURVE_DATA 为空数组 → 整条曲线跳过 */
    static void testCurveDataEmpty() {
        System.out.println("[测试2] CURVE_DATA 为空数组 → 整条曲线跳过");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "TEST_CURVE")
                .fluentPut("CURVE_DATA", new JSONArray()));
        Loader loader = new Loader(json);
        boolean ok = loader.getMarketData().irSpot.isEmpty();
        report(ok, "IR_SPOT 应为空（CURVE_DATA 为空被跳过）");
        printLogs(loader);
    }

    /** 测试3: CURVE_ID 为空 → 非 FX_SPOT 类型跳过 */
    static void testCurveIdEmpty() {
        System.out.println("[测试3] IR_SPOT 的 CURVE_ID 为空 → 跳过");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "")
                .fluentPut("CURVE_DATA", new JSONArray().fluentAdd(
                        new JSONObject().fluentPut("TERM", 30).fluentPut("RATE", 0.05))));
        Loader loader = new Loader(json);
        boolean ok = loader.getMarketData().irSpot.isEmpty();
        report(ok, "IR_SPOT 应为空（CURVE_ID 为空被跳过）");
        printLogs(loader);
    }

    /** 测试4: IR_SPOT TERM 不是数字 → 该点位剔除 */
    static void testIrSpotBadTerm() {
        System.out.println("[测试4] IR_SPOT TERM 不是数字 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "TEST_CURVE")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("FREQ", "cont")
                .fluentPut("DAYCOUNT", "actual/365")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TERM", "abc").fluentPut("RATE", 0.05))
                        .fluentAdd(new JSONObject().fluentPut("TERM", 30).fluentPut("RATE", 0.04))));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().irSpot.get("TEST_CURVE").curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只剩 1 个点位（TERM='abc' 被剔除），实际: " + size);
        printLogs(loader);
    }

    /** 测试5: IR_SPOT RATE 不是数字 → 该点位剔除 */
    static void testIrSpotBadRate() {
        System.out.println("[测试5] IR_SPOT RATE 不是数字 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "TEST_CURVE")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("FREQ", "cont")
                .fluentPut("DAYCOUNT", "actual/365")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TERM", 30).fluentPut("RATE", "N/A"))
                        .fluentAdd(new JSONObject().fluentPut("TERM", 60).fluentPut("RATE", 0.04))));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().irSpot.get("TEST_CURVE").curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只剩 1 个点位（RATE='N/A' 被剔除），实际: " + size);
        printLogs(loader);
    }

    /** 测试6: IR_SPOT 混合正常和异常点位 → 正常的保留，异常的剔除 */
    static void testIrSpotMixedGoodBad() {
        System.out.println("[测试6] IR_SPOT 混合正常/异常点位 → 保留正常，剔除异常");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "MIXED_CURVE")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("FREQ", "cont")
                .fluentPut("DAYCOUNT", "actual/365")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TERM", 30).fluentPut("RATE", 0.05)) // 正常
                        .fluentAdd(new JSONObject().fluentPut("TERM", null).fluentPut("RATE", 0.03)) // TERM null
                        .fluentAdd(new JSONObject().fluentPut("TERM", 90).fluentPut("RATE", null)) // RATE null
                        .fluentAdd(new JSONObject().fluentPut("TERM", 180).fluentPut("RATE", 0.04)) // 正常
                        .fluentAdd(new JSONObject().fluentPut("TERM", "X").fluentPut("RATE", 0.02)) // TERM 非数字
                ));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().irSpot.get("MIXED_CURVE").curveData.size();
        boolean ok = (size == 2);
        report(ok, "应保留 2 个正常点位（TERM=30,180），实际: " + size);
        printLogs(loader);
    }

    /** 测试7: FIXING TRADE_DATE 格式错误 → 该点位剔除 */
    static void testFixingBadDate() {
        System.out.println("[测试7] FIXING TRADE_DATE 格式错误 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(
                                new JSONObject().fluentPut("TRADE_DATE", "2024-03-29").fluentPut("FIXING_VALUE", 0.025)) // 格式错
                        .fluentAdd(
                                new JSONObject().fluentPut("TRADE_DATE", "20240329").fluentPut("FIXING_VALUE", 0.026)) // 正常
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "").fluentPut("FIXING_VALUE", 0.027)) // 空日期
                ));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fixingRate.get("TEST_FIX").curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只剩 1 个正常点位（yyyyMMdd 格式），实际: " + size);
        printLogs(loader);
    }

    /** 测试8: FIXING FIXING_VALUE 为空 → 该点位剔除 */
    static void testFixingBadValue() {
        System.out.println("[测试8] FIXING FIXING_VALUE 为 null → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX2")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "20240329").fluentPut("FIXING_VALUE", null)) // null
                        .fluentAdd(
                                new JSONObject().fluentPut("TRADE_DATE", "20240328").fluentPut("FIXING_VALUE", "0.025")) // 正常字符串数字
                ));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fixingRate.get("TEST_FIX2").curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只剩 1 个点位（null 被剔除），实际: " + size);
        printLogs(loader);
    }

    /** 测试8.1: CURVE_TYPE=FIXING（别名）可正常加载 */
    static void testFixingAliasType() {
        System.out.println("[测试8.1] CURVE_TYPE=FIXING（别名）可正常加载");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX_ALIAS")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "20240328").fluentPut("FIXING_VALUE", 0.025))
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "20240329").fluentPut("FIXING_VALUE", 0.026))));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fixingRate.get("TEST_FIX_ALIAS").curveData.size();
        boolean ok = (size == 2);
        report(ok, "FIXING 别名应正常加载 2 个点位，实际: " + size);
        printLogs(loader);
    }

    /** 测试8.2: FIXING 未指定 INTERPOLATE_TYPE 时，默认 FORWARD（向前取值） */
    static void testFixingDefaultForward() {
        System.out.println("[测试8.2] FIXING 未指定 INTERPOLATE_TYPE → 默认 FORWARD");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX_DEFAULT_FORWARD")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "20240328").fluentPut("FIXING_VALUE", 0.02))
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "20240330").fluentPut("FIXING_VALUE", 0.04))));
        Loader loader = new Loader(json);
        Fixing fixing = new Fixing(loader.getMarketData().fixingRate.get("TEST_FIX_DEFAULT_FORWARD"));
        double rate = fixing.getRate(LocalDate.parse("20240329", java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        boolean ok = Math.abs(rate - 0.02) < 1e-12;
        report(ok, "默认应向前取值为 0.02（非线性 0.03），实际: " + rate);
        printLogs(loader);
    }

    /** 测试9: FX_SPOT CURRENCY 为空 → 该点位剔除 */
    static void testFxSpotBadCurrency() {
        System.out.println("[测试9] FX_SPOT CURRENCY 为空 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FX_SPOT")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "").fluentPut("RATE", 7.2))
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "CNY/USD").fluentPut("RATE", 7.2))));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fxSpot.curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只有 1 个币种对（空 CURRENCY 被剔除），实际: " + size);
        printLogs(loader);
    }

    /** 测试10: FX_SPOT RATE 不是数字 → 该点位剔除 */
    static void testFxSpotBadRate() {
        System.out.println("[测试10] FX_SPOT RATE 不是数字 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FX_SPOT")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "CNY/USD").fluentPut("RATE", "bad"))
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "EUR/USD").fluentPut("RATE", 1.08))));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fxSpot.curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只有 1 个币种对（RATE='bad' 被剔除），实际: " + size);
        printLogs(loader);
    }

    /** 测试11: FX_SPOT 混合正常/异常 → 保留正常 */
    static void testFxSpotMixedGoodBad() {
        System.out.println("[测试11] FX_SPOT 混合正常/异常 → 保留正常");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FX_SPOT")
                .fluentPut("DATA_DATE", "20240329")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "CNY/USD").fluentPut("RATE", 7.2)) // 正常
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", null).fluentPut("RATE", 1.5)) // null
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "EUR/USD").fluentPut("RATE", null)) // RATE
                                                                                                              // null
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "JPY/USD").fluentPut("RATE", 150.0)) // 正常
                ));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fxSpot.curveData.size();
        boolean ok = (size == 2);
        report(ok, "应保留 2 个正常币种对（CNY/USD, JPY/USD），实际: " + size);
        printLogs(loader);
    }

    /** 测试12: 交易数据校验 - 缺少必填字段 → 交易被过滤 */
    static void testTradeValidation() {
        System.out.println("[测试12] 交易数据校验 - 缺少必填字段 → 交易被过滤");
        JSONObject trade1 = new JSONObject()
                .fluentPut("INSTRUMENT_ID", "GOOD_TRADE")
                .fluentPut("PRODUCT_CODE", "IRSCCS")
                .fluentPut("PAY_CURRENCY_CODE", "CNY")
                .fluentPut("REC_CURRENCY_CODE", "USD")
                .fluentPut("PAY_INTEREST_TYPE", "Fixed")
                .fluentPut("REC_INTEREST_TYPE", "Fixed")
                .fluentPut("START_DATE", "20240329")
                .fluentPut("MATURITY_DATE", "20250329")
                .fluentPut("PAY_FREQ", "3M")
                .fluentPut("REC_FREQ", "3M")
                .fluentPut("PAY_NOTIONAL", 1000000)
                .fluentPut("REC_NOTIONAL", 140000)
                .fluentPut("PAY_DAY_COUNT_BASIS", "actual/360")
                .fluentPut("REC_DAY_COUNT_BASIS", "actual/360");

        JSONObject trade2 = new JSONObject()
                .fluentPut("INSTRUMENT_ID", "BAD_TRADE")
                .fluentPut("PRODUCT_CODE", "IRSCCS")
                .fluentPut("PAY_CURRENCY_CODE", "") // 空
                .fluentPut("START_DATE", "20240329")
                .fluentPut("MATURITY_DATE", "20250329");
        // 缺少 REC_CURRENCY_CODE, PAY_INTEREST_TYPE 等

        JSONObject jo = new JSONObject();
        jo.put("oper_code", "PRICING");
        jo.put("data_date", "20240329");
        jo.put("trade_data", new JSONArray().fluentAdd(trade1).fluentAdd(trade2));
        jo.put("market_data", new JSONArray());

        Loader loader = new Loader(jo.toJSONString());
        int tradeCount = loader.getTrades().size();
        int errorCount = loader.getValidationErrors().size();
        boolean ok = (tradeCount == 1 && errorCount == 1);
        report(ok, "应保留 1 笔正常交易、1 条错误记录，实际: trades=" + tradeCount + ", errors=" + errorCount);
        if (errorCount > 0) {
            System.out.println("    错误详情: " + loader.getValidationErrors().getJSONObject(0).getString("info"));
        }
    }

    /** 测试13: 交易数据校验 - domain 值不合法 → 交易被过滤 */
    static void testTradeValidationDomain() {
        System.out.println("[测试13] 交易数据校验 - domain 值不合法 → 交易被过滤");
        JSONObject trade = new JSONObject()
                .fluentPut("INSTRUMENT_ID", "DOMAIN_BAD")
                .fluentPut("PRODUCT_CODE", "IRSCCS")
                .fluentPut("PAY_CURRENCY_CODE", "CNY")
                .fluentPut("REC_CURRENCY_CODE", "USD")
                .fluentPut("PAY_INTEREST_TYPE", "InvalidType") // domain 不合法
                .fluentPut("REC_INTEREST_TYPE", "Fixed")
                .fluentPut("START_DATE", "20240329")
                .fluentPut("MATURITY_DATE", "20250329")
                .fluentPut("PAY_FREQ", "3M")
                .fluentPut("REC_FREQ", "3M")
                .fluentPut("PAY_NOTIONAL", 1000000)
                .fluentPut("REC_NOTIONAL", 140000)
                .fluentPut("PAY_DAY_COUNT_BASIS", "actual/360")
                .fluentPut("REC_DAY_COUNT_BASIS", "actual/360");

        JSONObject jo = new JSONObject();
        jo.put("oper_code", "PRICING");
        jo.put("data_date", "20240329");
        jo.put("trade_data", new JSONArray().fluentAdd(trade));
        jo.put("market_data", new JSONArray());

        Loader loader = new Loader(jo.toJSONString());
        int tradeCount = loader.getTrades().size();
        int errorCount = loader.getValidationErrors().size();
        boolean ok = (tradeCount == 0 && errorCount == 1);
        report(ok, "domain 不合法应被过滤，trades=" + tradeCount + ", errors=" + errorCount);
        if (errorCount > 0) {
            System.out.println("    错误详情: " + loader.getValidationErrors().getJSONObject(0).getString("info"));
        }
    }

    // ===== 工具方法 =====

    static String buildJson(JSONObject marketDataItem) {
        JSONObject jo = new JSONObject();
        jo.put("oper_code", "PRICING");
        jo.put("data_date", "20240329");
        jo.put("trade_data", new JSONArray());
        jo.put("market_data", new JSONArray().fluentAdd(marketDataItem));
        return jo.toJSONString();
    }

    static void printLogs(Loader loader) {
        JSONArray errors = loader.getValidationErrors();
        if (errors != null && !errors.isEmpty()) {
            for (int i = 0; i < errors.size(); i++) {
                JSONObject e = errors.getJSONObject(i);
                System.out.println("    [LOG] " + e.toJSONString());
            }
        }
    }

    static void report(boolean ok, String msg) {
        if (ok) {
            System.out.println("  [PASS] " + msg);
            pass++;
        } else {
            System.out.println("  [FAIL] " + msg);
            fail++;
        }
        System.out.println();
    }
}


