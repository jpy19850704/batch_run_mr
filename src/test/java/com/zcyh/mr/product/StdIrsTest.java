package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.Calc;
import org.apache.commons.io.IOUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 标准利率互换测试类
 *
 * 测试场景：
 * 1. 多头（BUY）：预期利率上升
 * 2. 空头（SELL）：预期利率下降
 */
public class StdIrsTest {

    public static void main(String[] args) {
        System.out.println("====== 标准利率互换(STD_IRS)测试 ======\n");

        try {
            InputStream is = StdIrsTest.class.getClassLoader()
                    .getResourceAsStream("data/trade/stdIrs_test.json");
            if (is == null) {
                System.err.println("[FAIL] 无法加载测试文件 stdIrs_test.json");
                return;
            }
            String json = IOUtils.toString(is, StandardCharsets.UTF_8);

            Calc calc = new Calc(json);
            String result = calc.run();
            JSONObject rslt = JSONObject.parseObject(result);

            System.out.println("计算结果:");
            System.out.println(JSONObject.toJSONString(rslt, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));

            // 校验结果
            JSONObject data = rslt.getJSONObject("data");
            if (data == null) {
                System.err.println("[FAIL] 结果中没有 data 节点");
                return;
            }

            com.alibaba.fastjson2.JSONArray trades = data.getJSONArray("trade_data");
            if (trades == null || trades.isEmpty()) {
                System.err.println("[FAIL] 结果中没有 trade_data");
                return;
            }

            System.out.println("\n--- 验证结果 ---");
            boolean allOk = true;

            for (int i = 0; i < trades.size(); i++) {
                JSONObject t = trades.getJSONObject(i);
                String id = t.getString("INSTRUMENT_ID");
                Double valuation = t.getDouble("VALUATION");
                Double pv01 = t.getDouble("PV01");
                Double fwdRate = t.getDouble("FORWARD_RATE");

                System.out.println("\n交易 " + id + ":");
                System.out.println("  估值(VALUATION)   = " + valuation);
                System.out.println("  PV01              = " + pv01);
                System.out.println("  远期利率(FWD_RATE) = " + fwdRate);

                if (valuation == null) {
                    System.err.println("  [FAIL] 估值为 null");
                    allOk = false;
                } else {
                    System.out.println("  [PASS] 估值计算成功");
                }

                if (fwdRate != null && fwdRate > 0) {
                    System.out.println("  [PASS] 远期利率合理 > 0");
                } else {
                    System.err.println("  [FAIL] 远期利率异常");
                    allOk = false;
                }
            }

            // 检查日志
            com.alibaba.fastjson2.JSONArray logs = data.getJSONArray("log_data");
            if (logs != null && !logs.isEmpty()) {
                System.out.println("\n[WARNING] 存在日志:");
                for (int i = 0; i < logs.size(); i++) {
                    System.out.println("  " + logs.getJSONObject(i));
                }
            }

            System.out.println("\n====== 测试" + (allOk ? "全部通过" : "存在失败") + " ======");

        } catch (Exception e) {
            System.err.println("[FAIL] 测试异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
