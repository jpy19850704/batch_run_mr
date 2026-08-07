package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolSurfacePoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 市场数据校验异常场景测试
 * 模拟各类异常数据，验证校验逻辑是否正确过滤/剔除
 */
public class MarketDataValidationTest {

    /** 测试1: CURVE_TYPE 为空 → 整条曲线跳过 */
    @Test
    void testCurveTypeEmpty() {
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
    @Test
    void testCurveDataEmpty() {
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
    @Test
    void testCurveIdEmpty() {
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
    @Test
    void testIrSpotBadTerm() {
        System.out.println("[测试4] IR_SPOT TERM 不是数字 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "TEST_CURVE")
                .fluentPut("DATA_DATE", "2024-03-29")
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
    @Test
    void testIrSpotBadRate() {
        System.out.println("[测试5] IR_SPOT RATE 不是数字 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "TEST_CURVE")
                .fluentPut("DATA_DATE", "2024-03-29")
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
    @Test
    void testIrSpotMixedGoodBad() {
        System.out.println("[测试6] IR_SPOT 混合正常/异常点位 → 保留正常，剔除异常");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_SPOT")
                .fluentPut("CURVE_ID", "MIXED_CURVE")
                .fluentPut("DATA_DATE", "2024-03-29")
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
    @Test
    void testFixingBadDate() {
        System.out.println("[测试7] FIXING TRADE_DATE 格式错误 → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(
                                new JSONObject().fluentPut("TRADE_DATE", "20240329").fluentPut("FIXING_VALUE", 0.025)) // 格式错
                        .fluentAdd(
                                new JSONObject().fluentPut("TRADE_DATE", "2024-03-29").fluentPut("FIXING_VALUE", 0.026)) // 正常
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "").fluentPut("FIXING_VALUE", 0.027)) // 空日期
                ));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fixingRate.get("TEST_FIX").curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只剩 1 个正常点位（yyyy-MM-dd 格式），实际: " + size);
        printLogs(loader);
    }

    /** 测试8: FIXING FIXING_VALUE 为空 → 该点位剔除 */
    @Test
    void testFixingBadValue() {
        System.out.println("[测试8] FIXING FIXING_VALUE 为 null → 该点位剔除");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX2")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "2024-03-29").fluentPut("FIXING_VALUE", null)) // null
                        .fluentAdd(
                                new JSONObject().fluentPut("TRADE_DATE", "2024-03-28").fluentPut("FIXING_VALUE", "0.025")) // 非标准数值
                        .fluentAdd(
                                new JSONObject().fluentPut("TRADE_DATE", "2024-03-27").fluentPut("FIXING_VALUE", 0.024)) // 正常数值
                ));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fixingRate.get("TEST_FIX2").curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只剩 1 个点位（null 和字符串数值被剔除），实际: " + size);
        printLogs(loader);
    }

    /** 测试8.1: CURVE_TYPE=FIXING（别名）可正常加载 */
    @Test
    void testFixingAliasType() {
        System.out.println("[测试8.1] CURVE_TYPE=FIXING（别名）可正常加载");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX_ALIAS")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "2024-03-28").fluentPut("FIXING_VALUE", 0.025))
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "2024-03-29").fluentPut("FIXING_VALUE", 0.026))));
        Loader loader = new Loader(json);
        int size = loader.getMarketData().fixingRate.get("TEST_FIX_ALIAS").curveData.size();
        boolean ok = (size == 2);
        report(ok, "FIXING 别名应正常加载 2 个点位，实际: " + size);
        printLogs(loader);
    }

    /** 测试8.2: FIXING 未指定 INTERPOLATE_TYPE 时，默认 FORWARD（向前取值） */
    @Test
    void testFixingDefaultForward() {
        System.out.println("[测试8.2] FIXING 未指定 INTERPOLATE_TYPE → 默认 FORWARD");
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "FIXING")
                .fluentPut("FIXING_ID", "TEST_FIX_DEFAULT_FORWARD")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "2024-03-28").fluentPut("FIXING_VALUE", 0.02))
                        .fluentAdd(new JSONObject().fluentPut("TRADE_DATE", "2024-03-30").fluentPut("FIXING_VALUE", 0.04))));
        Loader loader = new Loader(json);
        Fixing fixing = new Fixing(loader.getMarketData().fixingRate.get("TEST_FIX_DEFAULT_FORWARD"));
        double rate = fixing.getRate(LocalDate.parse("2024-03-29"));
        boolean ok = Math.abs(rate - 0.02) < 1e-12;
        report(ok, "默认应向前取值为 0.02（非线性 0.03），实际: " + rate);
        printLogs(loader);
    }

    /** 测试9: FX_SPOT CURRENCY 为空 → 该点位剔除 */
    @Test
    void testFxSpotBadCurrency() {
        System.out.println("[测试9] FX_SPOT CURRENCY 为空 → 该点位剔除");
        JSONObject marketDataItem = new JSONObject()
                .fluentPut("CURVE_TYPE", "FX_SPOT")
                .fluentPut("CURVE_ID", "FX_SPOT_TEST")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "").fluentPut("RATE", 7.2))
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "CNY/USD").fluentPut("RATE", 7.2)));
        JSONArray errors = new JSONArray();
        MarketData marketData = new MarketDataLoader(LocalDate.of(2024, 3, 29), errors, "USD")
                .loadBaseMarketData(new JSONArray().fluentAdd(marketDataItem));
        int size = marketData.fxSpot.curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只有 1 个币种对（空 CURRENCY 被剔除），实际: " + size);
    }

    /** 测试10: FX_SPOT RATE 不是数字 → 该点位剔除 */
    @Test
    void testFxSpotBadRate() {
        System.out.println("[测试10] FX_SPOT RATE 不是数字 → 该点位剔除");
        JSONObject marketDataItem = new JSONObject()
                .fluentPut("CURVE_TYPE", "FX_SPOT")
                .fluentPut("CURVE_ID", "FX_SPOT_TEST")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "CNY/USD").fluentPut("RATE", "bad"))
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "EUR/USD").fluentPut("RATE", 1.08)));
        JSONArray errors = new JSONArray();
        MarketData marketData = new MarketDataLoader(LocalDate.of(2024, 3, 29), errors, "USD")
                .loadBaseMarketData(new JSONArray().fluentAdd(marketDataItem));
        int size = marketData.fxSpot.curveData.size();
        boolean ok = (size == 1);
        report(ok, "应只有 1 个币种对（RATE='bad' 被剔除），实际: " + size);
    }

    /** 测试11: FX_SPOT 混合正常/异常 → 保留正常 */
    @Test
    void testFxSpotMixedGoodBad() {
        System.out.println("[测试11] FX_SPOT 混合正常/异常 → 保留正常");
        JSONObject marketDataItem = new JSONObject()
                .fluentPut("CURVE_TYPE", "FX_SPOT")
                .fluentPut("CURVE_ID", "FX_SPOT_TEST")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "CNY/USD").fluentPut("RATE", 7.2)) // 正常
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", null).fluentPut("RATE", 1.5)) // null
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "EUR/USD").fluentPut("RATE", null)) // RATE
                                                                                                              // null
                        .fluentAdd(new JSONObject().fluentPut("CURRENCY", "JPY/USD").fluentPut("RATE", 150.0)) // 正常
                );
        JSONArray errors = new JSONArray();
        MarketData marketData = new MarketDataLoader(LocalDate.of(2024, 3, 29), errors, "USD")
                .loadBaseMarketData(new JSONArray().fluentAdd(marketDataItem));
        int size = marketData.fxSpot.curveData.size();
        boolean ok = (size == 2);
        report(ok, "应保留 2 个正常币种对（CNY/USD, JPY/USD），实际: " + size);
    }

    /** 测试11.1: IR_VOL 混合正常/异常 → 保留正常 */
    @Test
    void testIrVolMixedGoodBad() {
        String json = buildJson(new JSONObject()
                .fluentPut("CURVE_TYPE", "IR_VOL")
                .fluentPut("CURVE_ID", "IR_VOL_MIXED")
                .fluentPut("DATA_DATE", "2024-03-29")
                .fluentPut("AXIS2_TYPE", "UNDERLYING_TERM")
                .fluentPut("CURVE_DATA", new JSONArray()
                        .fluentAdd(new JSONObject()
                                .fluentPut("OPTION_TERM", 30)
                                .fluentPut("UNDERLYING_TERM", 90)
                                .fluentPut("VOLATILITY_RATE", 0.20))
                        .fluentAdd(new JSONObject()
                                .fluentPut("OPTION_TERM", 60)
                                .fluentPut("UNDERLYING_TERM", null)
                                .fluentPut("VOLATILITY_RATE", 0.21))
                        .fluentAdd(new JSONObject()
                                .fluentPut("OPTION_TERM", 90)
                                .fluentPut("UNDERLYING_TERM", 180)
                                .fluentPut("VOLATILITY_RATE", 0.22))));

        Loader loader = new Loader(json);
        int size = loader.getMarketData().irVol.get("IR_VOL_MIXED").curveData.size();
        report(size == 2, "应保留2个正常波动率点，实际: " + size);
    }

    /** 测试11.2: IR_VOL 第二维类型必须为 UNDERLYING_TERM */
    @Test
    void testIrVolRejectsDeltaAxis() {
        IrVol.IrVolInfo info = new IrVol.IrVolInfo();
        info.axis2Type = "DELTA";
        info.curveData.add(new VolSurfacePoint(30, 0.25d, 0.20d));
        List<String> errors = new ArrayList<String>();

        IrVol.validateInput("IR_VOL_TEST", info, errors);

        assertTrue(errors.stream().anyMatch(error -> error.contains("AXIS2_TYPE必须为UNDERLYING_TERM")),
                "IR_VOL 应拒绝 DELTA 第二维");
    }

    // ===== 工具方法 =====

    static String buildJson(JSONObject marketDataItem) {
        JSONObject jo = new JSONObject();
        jo.put("oper_code", "PRICING");
        jo.put("data_date", "2024-03-29");
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
        assertTrue(ok, msg);
    }
}


