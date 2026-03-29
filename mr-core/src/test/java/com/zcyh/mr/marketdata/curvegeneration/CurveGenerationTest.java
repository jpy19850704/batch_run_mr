package com.zcyh.mr.marketdata.curvegeneration;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.IrCurve;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.DeltaTermVol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 曲线生成全链路集成测试
 * 1. 加载 test_curve_data.json
 * 2. 解析为 CurveGeneration.CurveInput 列表
 * 3. 调用 CurveGeneration.generate()
 * 4. 按曲线类型和 CURVE_ID 分组输出，验证 dcb/freq 转换结果
 */
public class CurveGenerationTest {

    public static void main(String[] args) throws Exception {
        System.out.println("========== 曲线生成集成测试 ==========\n");

        // 1. 加载测试数据
        String jsonPath = resolveDataPath();
        String jsonStr = new String(Files.readAllBytes(Paths.get(jsonPath)), StandardCharsets.UTF_8);
        List<CurveGeneration.CurveInput> inputs = JSON.parseArray(jsonStr, CurveGeneration.CurveInput.class);
        System.out.println("[加载] 共 " + inputs.size() + " 条曲线输入\n");

        // 按类型统计
        Map<String, Long> typeCounts = inputs.stream()
                .collect(Collectors.groupingBy(i -> i.conversionType, Collectors.counting()));
        typeCounts.forEach((type, count) -> System.out.println("  " + type + ": " + count + " 条"));
        System.out.println();

        // 2. 构建 Calendar（测试不加载节假日，全部使用自然日）
        Calendar calendar = new Calendar();

        // 3. 执行曲线生成
        CurveGeneration gen = new CurveGeneration();
        long start = System.currentTimeMillis();
        CurveGeneration.CurveResult result = gen.generate(inputs, calendar);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[生成] 利率曲线点: " + result.irCurves.size()
                + ", 波动率点: " + result.volPoints.size()
                + ", 耗时 " + elapsed + " ms\n");

        // 4. 按 CURVE_ID 分组利率曲线
        Map<String, List<IrCurve>> irGrouped = new LinkedHashMap<>();
        for (IrCurve pt : result.irCurves) {
            irGrouped.computeIfAbsent(pt.curveId, k -> new ArrayList<>()).add(pt);
        }

        // 4.1 Bootstrap 曲线输出
        System.out.println("==================== ZeroCurveBootstrap ====================");
        for (CurveGeneration.CurveInput input : inputs) {
            if (!"ZeroCurveBootstrap".equals(input.conversionType))
                continue;
            List<IrCurve> curvePoints = irGrouped.getOrDefault(input.curveId, Collections.emptyList());
            printCurveHeader(input.curveId, input.getOutputFreq(), input.getOutputDayCount(), curvePoints.size());
            printRateTable(curvePoints);
        }

        // 4.2 FxImplied 曲线输出
        System.out.println("\n==================== FxImpliedCurveConstruct ====================");
        for (CurveGeneration.CurveInput input : inputs) {
            if (!"FxImpliedCurveConstruct".equals(input.conversionType))
                continue;
            List<IrCurve> curvePoints = irGrouped.getOrDefault(input.curveId, Collections.emptyList());
            printCurveHeader(input.curveId, input.getOutputFreq(), input.getOutputDayCount(), curvePoints.size());
            System.out.println("  依赖: BASE_DISCOUNT_CURVE=" + input.baseDiscountCurve + ", FX_SPOT=" + input.fxSpot);
            printRateTable(curvePoints);
        }

        // 4.3 ZeroCurveSubtract 曲线输出
        System.out.println("\n==================== ZeroCurveSubtract ====================");
        for (CurveGeneration.CurveInput input : inputs) {
            if (!"ZeroCurveSubtract".equals(input.conversionType))
                continue;
            List<IrCurve> curvePoints = irGrouped.getOrDefault(input.curveId, Collections.emptyList());
            printCurveHeader(input.curveId, input.getOutputFreq(), input.getOutputDayCount(), curvePoints.size());
            System.out.println("  YC=" + input.ycCurveCode + ", RF=" + input.rfCurveCode);
            printRateTable(curvePoints);
        }

        // 4.4 VolRrbf2Delta 曲线输出
        System.out.println("\n==================== VolRrbf2Delta ====================");
        Map<String, List<DeltaTermVol>> volGrouped = new LinkedHashMap<>();
        for (DeltaTermVol pt : result.volPoints) {
            volGrouped.computeIfAbsent(pt.curveId, k -> new ArrayList<>()).add(pt);
        }
        for (CurveGeneration.CurveInput input : inputs) {
            if (!"VolRrbf2Delta".equals(input.conversionType))
                continue;
            List<DeltaTermVol> volPoints = volGrouped.getOrDefault(input.curveId, Collections.emptyList());
            System.out.println("\n--- " + input.curveId + " (FX_SPOT=" + input.fxSpot + ") ---");
            System.out.println("  BASE=" + input.baseDiscountCurve + ", UND=" + input.underlyingDiscountCurve);
            System.out.println("  " + volPoints.size() + " 个输出点");
            printVolTable(volPoints);
        }

        // 5. 验证 daycount/freq 转换一致性
        System.out.println("\n==================== 转换一致性验证 ====================");
        verifyDfConsistency(inputs, irGrouped);

        System.out.println("\n========== 测试完成 ==========");
    }

    /**
     * 打印曲线头信息
     */
    private static void printCurveHeader(String curveId, String freq, String dcb, int pointCount) {
        System.out.println("\n--- " + curveId + " [" + freq + " / " + dcb + "] ---");
        System.out.println("  " + pointCount + " 个输出点");
    }

    /**
     * 打印利率表格
     */
    private static void printRateTable(List<IrCurve> points) {
        System.out.printf("  %-10s %-10s %-14s %-14s %-10s %-10s%n",
                "TERM_CODE", "TERM_DAYS", "RATE", "DF", "FREQ", "DCB");
        for (IrCurve p : points) {
            System.out.printf("  %-10s %-10s %-14.8f %-14.8f %-10s %-10s%n",
                    p.termCode,
                    String.valueOf((long) p.termDays),
                    p.rate,
                    p.discountFactor,
                    p.curveFreq != null ? p.curveFreq : "",
                    p.curveDaycount != null ? p.curveDaycount : "");
        }
    }

    /**
     * 打印波动率表格
     */
    private static void printVolTable(List<DeltaTermVol> points) {
        System.out.printf("  %-10s %-10s %-14s %-14s %-14s%n",
                "TERM_CODE", "DELTA", "FX_VOL", "FX_FORWARD", "STRIKE");
        for (DeltaTermVol p : points) {
            System.out.printf("  %-10s %-10.2f %-14.6f %-14.6f %-14.6f%n",
                    p.termCode,
                    p.delta,
                    p.fxVol,
                    p.fxForward,
                    p.strike);
        }
    }

    /**
     * 验证：相同输入数据在 cont/actual365 和其他 freq/dcb 下的 DF 应一致
     */
    private static void verifyDfConsistency(List<CurveGeneration.CurveInput> inputs,
            Map<String, List<IrCurve>> irGrouped) {

        // 比较共用输入数据但不同 freq 输出的曲线 DF
        String[][] comparePairs = {
                { "CNY_ZERO_CONT365", "CNY_ZERO_SEMI365" },
                { "CNY_ZERO_CONT365", "CNY_ZERO_QUART365" }
        };

        for (String[] pair : comparePairs) {
            List<IrCurve> curve1 = irGrouped.getOrDefault(pair[0], Collections.emptyList());
            List<IrCurve> curve2 = irGrouped.getOrDefault(pair[1], Collections.emptyList());

            if (curve1.isEmpty() || curve2.isEmpty()) {
                System.out.println("  [跳过] " + pair[0] + " 或 " + pair[1] + " 无输出");
                continue;
            }

            // 构建 termDays → DF 映射
            Map<Long, Double> dfMap1 = new HashMap<>();
            for (IrCurve p : curve1) {
                dfMap1.put((long) p.termDays, p.discountFactor);
            }

            System.out.println("\n  对比: " + pair[0] + " vs " + pair[1]);
            System.out.printf("  %-10s %-14s %-14s %-14s %s%n",
                    "TERM_DAYS", "DF(" + pair[0].substring(pair[0].lastIndexOf('_') + 1) + ")",
                    "DF(" + pair[1].substring(pair[1].lastIndexOf('_') + 1) + ")",
                    "差异", "通过");

            boolean allPass = true;
            for (IrCurve p2 : curve2) {
                long termDays = (long) p2.termDays;
                double df2 = p2.discountFactor;
                Double df1 = dfMap1.get(termDays);
                if (df1 != null) {
                    double diff = Math.abs(df1 - df2);
                    boolean pass = diff < 1e-10;
                    if (!pass)
                        allPass = false;
                    System.out.printf("  %-10d %-14.10f %-14.10f %-14.2e %s%n",
                            termDays, df1, df2, diff, pass ? "✓" : "✗ FAIL");
                }
            }
            System.out.println("  结论: " + (allPass ? "✓ 全部通过" : "✗ 存在不一致"));
        }

        // 验证相减曲线的结果合理性
        System.out.println("\n  利差合理性检查:");
        for (String curveId : new String[] { "CNY_CREDIT_SPREAD", "USD_EUR_SPREAD",
                "USD_GBP_SPREAD", "USD_JPY_SPREAD_SEMI", "USD_HKD_SPREAD" }) {
            List<IrCurve> curve = irGrouped.getOrDefault(curveId, Collections.emptyList());
            if (curve.isEmpty())
                continue;
            double maxSpread = curve.stream()
                    .mapToDouble(p -> Math.abs(p.rate))
                    .max().orElse(0);
            boolean reasonable = maxSpread < 0.20;
            System.out.printf("  %-25s 最大|利差|=%.6f  %s%n",
                    curveId, maxSpread, reasonable ? "✓" : "✗ 异常偏大");
        }
    }

    /**
     * 查找测试数据文件路径
     */
    private static String resolveDataPath() {
        String[] candidates = {
                "src/main/java/com/zcyh/mr/marketdata/curvegeneration/test_curve_data.json",
                "test_curve_data.json"
        };
        for (String path : candidates) {
            if (Files.exists(Paths.get(path))) {
                return path;
            }
        }
        return "D:/后端代码/engine/src/main/java/com/zcyh/mr/marketdata/curvegeneration/test_curve_data.json";
    }
}
