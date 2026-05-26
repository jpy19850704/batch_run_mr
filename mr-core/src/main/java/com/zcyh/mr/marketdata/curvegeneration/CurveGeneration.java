package com.zcyh.mr.marketdata.curvegeneration;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.curvegeneration.converter.FxImpliedCurveConstruct;
import com.zcyh.mr.marketdata.curvegeneration.converter.VolRrbf2Delta;
import com.zcyh.mr.marketdata.curvegeneration.converter.ZeroCurveBootstrap;
import com.zcyh.mr.marketdata.curvegeneration.converter.ZeroCurveSubtract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 市场数据曲线生成门面类
 * 通过拓扑排序自动解析曲线间依赖关系，支持链式推导
 */
public class CurveGeneration {

    private static final Logger logger = LoggerFactory.getLogger(CurveGeneration.class);

    /**
     * 单条曲线输入：公共字段 + CURVE_DATA 子数组
     * CURVE_DATA 为 List<JSONObject>，各转换器自行解析所需字段
     */
    public static class CurveInput {
        @JSONField(name = "CONVERSION_TYPE")
        public String conversionType;

        @JSONField(name = "CURVE_ID")
        public String curveId;

        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;

        /** 输出目标日算规则，默认 actual/365 */
        @JSONField(name = "CURVE_DAYCOUNT")
        public String curveDayCount;

        /** 输出目标频率，默认 cont */
        @JSONField(name = "CURVE_FREQ")
        public String curveFreq;

        @JSONField(name = "CALENDAR")
        public String calendar;

        /** 起息日偏移，可选，FxImplied 有默认值 */
        @JSONField(name = "DAY_OFF")
        public Integer dayOff;

        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;

        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;

        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;

        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;

        @JSONField(name = "FX_SPOT")
        public Double fxSpot;

        /** 曲线相减：收益率曲线ID */
        @JSONField(name = "YC_CURVE_CODE")
        public String ycCurveCode;

        /** 曲线相减：无风险曲线ID */
        @JSONField(name = "RF_CURVE_CODE")
        public String rfCurveCode;

        /** 期限点子数组，各转换器自行解析 */
        @JSONField(name = "CURVE_DATA")
        public List<JSONObject> curveData;

        /** 插值类型，默认 linear */
        @JSONField(name = "INTERPOLATE_TYPE")
        public String interpolateType;

        /** 自定义输出期限天数，逗号分隔（如 "1,7,30,90,365,1825,3650"），为空则按输入期限输出 */
        @JSONField(name = "OUTPUT_TERM_DAYS")
        public String outputTermDays;

        /**
         * 获取输出日算规则，默认 actual/365
         */
        public String getOutputDayCount() {
            return (curveDayCount == null || curveDayCount.trim().isEmpty()) ? "actual/365" : curveDayCount;
        }

        /**
         * 获取输出频率，默认 cont
         */
        public String getOutputFreq() {
            return (curveFreq == null || curveFreq.trim().isEmpty()) ? "cont" : curveFreq;
        }

        /**
         * 获取插值类型，默认 linear
         */
        public String getInterpolateType() {
            return interpolateType != null ? interpolateType : "linear";
        }

        /**
         * 解析自定义输出期限天数数组，按升序排列
         * 
         * @return 期限天数数组，未配置时返回 null
         */
        public double[] getOutputTermDaysArray() {
            if (outputTermDays == null || outputTermDays.trim().isEmpty()) {
                return null;
            }
            // 先将期限转换为整数天，再排序去重，避免后续整型映射时出现覆盖歧义
            String[] parts = outputTermDays.split(",");
            Set<Long> daySet = new TreeSet<>();
            for (String part : parts) {
                if (part == null || part.trim().isEmpty()) {
                    continue;
                }
                long roundedDays = Math.round(Double.parseDouble(part.trim()));
                daySet.add(roundedDays);
            }

            double[] days = new double[daySet.size()];
            int idx = 0;
            for (Long day : daySet) {
                days[idx++] = day;
            }
            return days;
        }

        /**
         * 收集当前曲线依赖的其他曲线ID
         */
        public List<String> getDependencies() {
            List<String> deps = new ArrayList<>();
            if (baseDiscountCurve != null && !baseDiscountCurve.isEmpty()) {
                deps.add(baseDiscountCurve);
            }
            if (underlyingDiscountCurve != null && !underlyingDiscountCurve.isEmpty()) {
                deps.add(underlyingDiscountCurve);
            }
            if (ycCurveCode != null && !ycCurveCode.isEmpty()) {
                deps.add(ycCurveCode);
            }
            if (rfCurveCode != null && !rfCurveCode.isEmpty()) {
                deps.add(rfCurveCode);
            }
            return deps;
        }
    }

    private static final String TYPE_ZERO_BOOTSTRAP = "ZeroCurveBootstrap";
    private static final String TYPE_FX_IMPLIED = "FxImpliedCurveConstruct";
    private static final String TYPE_ZERO_SUBTRACT = "ZeroCurveSubtract";
    private static final String TYPE_VOL_RRBF = "VolRrbf2Delta";

    /**
     * 利率曲线输出点
     * 用于零息自举、外汇隐含曲线、曲线相减三种转换器的输出
     */
    public static class IrCurve {
        /** 曲线标识 */
        public String curveId;
        /** 数据日期 */
        public LocalDate dataDate;
        /** 期限代码（如 1M, 3M, 1Y） */
        public String termCode;
        /** 期限天数 */
        public double termDays;
        /** 期限年化 (termDays/365) */
        public double termYear;
        /** 零息利率（按输出 freq/dcb 转换后） */
        public double rate;
        /** 折现因子 */
        public double discountFactor;
        /** 日算规则（如 actual/365） */
        public String curveDaycount;
        /** 利率频率（如 cont, semi） */
        public String curveFreq;
        /** 插值类型（如 linear/linervar/cubicspline/forward） */
        public String interpolateType;
    }

    /**
     * 波动率曲面输出点
     * 用于 VolRrbf2Delta（VV 模型）的 delta-期限 波动率网格输出
     */
    public static class DeltaTermVol {
        /** 曲线标识 */
        public String curveId;
        /** 数据日期 */
        public LocalDate dataDate;
        /** 期限代码（如 1M, 3M, 1Y） */
        public String termCode;
        /** 期限天数 */
        public double termDays;
        /** 期限年化 (termDays/365) */
        public double termYear;
        /** Call Delta（0.05~0.95） */
        public double delta;
        /** VV 模型波动率 */
        public double fxVol;
        /** 远期汇率 F = S × exp((rd-rf)T) */
        public double fxForward;
        /** 执行价 */
        public double strike;
    }

    /**
     * 曲线生成结果容器
     */
    public static class CurveResult {
        /** 利率曲线结果（零息自举 / 外汇隐含 / 曲线相减） */
        public List<IrCurve> irCurves = new ArrayList<>();
        /** 波动率曲面结果（VV 模型） */
        public List<DeltaTermVol> volPoints = new ArrayList<>();
        /** 生成失败的曲线错误信息 */
        public List<String> errors = new ArrayList<>();

        /**
         * 将曲线生成结果转换为 MarketData 格式
         * IrCurve → irSpot，DeltaTermVol → fxVol
         */
        public MarketData toMarketData() {
            MarketData md = new MarketData();
            fillIrSpot(md, true);
            fillFxVol(md, true);
            return md;
        }

        /**
         * 将曲线生成结果合并到已有的 MarketData 中
         * 相同 curveId 冲突时保留外部已有曲线（外部优先）
         */
        public void mergeInto(MarketData md) {
            fillIrSpot(md, false);
            fillFxVol(md, false);
        }

        /**
         * 将 IrCurve 列表按 curveId 分组，转换为 IrSpot.IrSpotInfo 并填入 MarketData
         */
        private void fillIrSpot(MarketData md, boolean overrideExisting) {
            Map<String, List<IrCurve>> grouped = new LinkedHashMap<>();
            for (IrCurve pt : irCurves) {
                grouped.computeIfAbsent(pt.curveId, k -> new ArrayList<>()).add(pt);
            }
            for (Map.Entry<String, List<IrCurve>> entry : grouped.entrySet()) {
                List<IrCurve> points = entry.getValue();
                IrCurve first = points.get(0);
                if (!overrideExisting && md.irSpot.containsKey(first.curveId)) {
                    // 外部曲线优先：遇到同 curveId 时跳过内部生成结果
                    continue;
                }

                IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
                info.curveCode = first.curveId;
                info.dataDate = first.dataDate;
                info.pDataDate = first.dataDate;
                info.dayCount = first.curveDaycount;
                info.freq = first.curveFreq;
                info.interpolateType = first.interpolateType != null ? first.interpolateType : "linear";

                Series<Integer, Double> curveData = new Series<>(Integer.class, Double.class);
                for (IrCurve pt : points) {
                    curveData.put((int) pt.termDays, pt.rate);
                }
                info.curveData = curveData;

                md.irSpot.put(first.curveId, info);
            }
        }

        /**
         * 将 DeltaTermVol 列表按 curveId 分组，转换为 FxVol.FxVolInfo 并填入 MarketData
         * 仅输出 OPTION_TERM / DELTA / VOLATILITY_RATE 三个标准字段
         */
        private void fillFxVol(MarketData md, boolean overrideExisting) {
            Map<String, List<DeltaTermVol>> grouped = new LinkedHashMap<>();
            for (DeltaTermVol pt : volPoints) {
                grouped.computeIfAbsent(pt.curveId, k -> new ArrayList<>()).add(pt);
            }
            for (Map.Entry<String, List<DeltaTermVol>> entry : grouped.entrySet()) {
                List<DeltaTermVol> points = entry.getValue();
                DeltaTermVol first = points.get(0);
                if (!overrideExisting && md.fxVol.containsKey(first.curveId)) {
                    // 外部曲线优先：遇到同 curveId 时跳过内部生成结果
                    continue;
                }

                FxVol.FxVolInfo info = new FxVol.FxVolInfo();
                info.curveCode = first.curveId;
                info.dataDate = first.dataDate;
                info.pDataDate = first.dataDate;

                List<Map<String, Object>> curveData = new ArrayList<>();
                for (DeltaTermVol pt : points) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("OPTION_TERM", (int) pt.termDays);
                    row.put("DELTA", pt.delta);
                    row.put("VOLATILITY_RATE", pt.fxVol);
                    curveData.add(row);
                }
                info.curveData = curveData;

                md.fxVol.put(first.curveId, info);
            }
        }
    }

    /**
     * 按依赖关系拓扑排序后执行曲线生成（无外部市场数据）
     */
    public CurveResult generate(List<CurveInput> inputs, Calendar calendar) {
        return generate(inputs, calendar, null);
    }

    /**
     * 按依赖关系拓扑排序后执行曲线生成
     * 当提供 MarketData 时，curvePool 优先使用 MarketData 中的利率曲线作为下游依赖输入；
     * 所有曲线仍正常生成并输出到结果中。
     *
     * @param inputs   曲线输入列表
     * @param calendar 日历对象（含节假日数据）
     * @param existing 已有市场数据（可为 null），其中的利率曲线优先用于下游依赖
     * @return 所有生成的曲线数据
     */
    public CurveResult generate(List<CurveInput> inputs, Calendar calendar, MarketData existing) {
        Map<String, List<IrCurve>> curvePool = new HashMap<>();
        CurveResult output = new CurveResult();

        // 将 MarketData 中的利率曲线预填充到 curvePool（优先级最高）
        if (existing != null) {
            for (Map.Entry<String, IrSpot.IrSpotInfo> entry : existing.irSpot.entrySet()) {
                IrSpot.IrSpotInfo info = entry.getValue();
                List<IrCurve> points = new ArrayList<>();
                for (Map.Entry<Integer, Double> pt : info.curveData.entrySet()) {
                    IrCurve ic = new IrCurve();
                    ic.curveId = info.curveCode;
                    ic.dataDate = info.dataDate;
                    ic.termDays = pt.getKey();
                    ic.rate = pt.getValue();
                    ic.curveFreq = info.freq;
                    ic.curveDaycount = info.dayCount;
                    ic.interpolateType = info.interpolateType;
                    points.add(ic);
                }
                curvePool.put(entry.getKey(), points);
            }
        }

        // 拓扑排序，确定执行顺序
        List<CurveInput> sorted = topologicalSort(inputs);

        // 按排序后的顺序依次执行
        ZeroCurveBootstrap zeroBoot = new ZeroCurveBootstrap();
        FxImpliedCurveConstruct fxImplied = new FxImpliedCurveConstruct();
        ZeroCurveSubtract subtract = new ZeroCurveSubtract();
        VolRrbf2Delta volConvert = new VolRrbf2Delta();

        for (CurveInput input : sorted) {
            try {
                switch (input.conversionType) {
                    case TYPE_ZERO_BOOTSTRAP: {
                        List<IrCurve> result = zeroBoot.bootstrap(input, calendar);
                        output.irCurves.addAll(result);
                        curvePool.putIfAbsent(input.curveId, result);
                        break;
                    }
                    case TYPE_FX_IMPLIED: {
                        List<IrCurve> result = fxImplied.construct(input, curvePool, calendar);
                        output.irCurves.addAll(result);
                        curvePool.putIfAbsent(input.curveId, result);
                        break;
                    }
                    case TYPE_ZERO_SUBTRACT: {
                        List<IrCurve> result = subtract.subtract(input, curvePool);
                        output.irCurves.addAll(result);
                        curvePool.putIfAbsent(input.curveId, result);
                        break;
                    }
                    case TYPE_VOL_RRBF: {
                        List<DeltaTermVol> result = volConvert.convert(input, curvePool, calendar);
                        output.volPoints.addAll(result);
                        break;
                    }
                    default: {
                        String msg = "[" + input.curveId + "] 未支持的 CONVERSION_TYPE: " + input.conversionType;
                        output.errors.add(msg);
                        logger.error("曲线生成跳过: {}", msg);
                        break;
                    }
                }
            } catch (Exception e) {
                // 记录错误，跳过当前曲线，继续处理后续曲线
                String msg = "[" + input.curveId + "] " + input.conversionType + " 失败: " + e.getMessage();
                output.errors.add(msg);
                logger.error("曲线生成跳过: {}", msg, e);
            }
        }

        return output;
    }

    /**
     * 对曲线输入列表进行拓扑排序
     * 无依赖的曲线优先执行，有依赖的曲线在其依赖项之后执行
     *
     * @param inputs 曲线输入列表
     * @return 排序后的列表
     * @throws IllegalStateException 存在循环依赖时抛出
     */
    private List<CurveInput> topologicalSort(List<CurveInput> inputs) {
        validateCurveIds(inputs);

        // curveId → CurveInput 映射
        Map<String, CurveInput> inputMap = new LinkedHashMap<>();
        for (CurveInput input : inputs) {
            inputMap.put(input.curveId, input);
        }

        // 计算每个节点的入度（仅统计本批次内的依赖）
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (CurveInput input : inputs) {
            inDegree.putIfAbsent(input.curveId, 0);
            for (String dep : input.getDependencies()) {
                if (inputMap.containsKey(dep)) {
                    // dep 是本批次的曲线，构成前驱依赖
                    inDegree.merge(input.curveId, 1, Integer::sum);
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(input.curveId);
                }
                // dep 不在本批次中（外部已有曲线），不构成依赖约束
            }
        }

        // BFS 拓扑排序（Kahn 算法）
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<CurveInput> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String curveId = queue.poll();
            sorted.add(inputMap.get(curveId));
            List<String> children = dependents.getOrDefault(curveId, Collections.emptyList());
            for (String child : children) {
                int newDegree = inDegree.merge(child, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(child);
                }
            }
        }

        if (sorted.size() != inDegree.size()) {
            // 找出入度始终大于 0 的节点，作为循环依赖候选
            List<String> cyclic = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            throw new IllegalStateException("曲线存在循环依赖: " + cyclic);
        }

        return sorted;
    }

    /**
     * 校验输入曲线ID：不允许为空，不允许重复
     */
    private void validateCurveIds(List<CurveInput> inputs) {
        List<Integer> blankIndexes = new ArrayList<>();
        Map<String, Integer> idCounter = new LinkedHashMap<>();

        for (int i = 0; i < inputs.size(); i++) {
            String curveId = inputs.get(i).curveId;
            if (curveId == null || curveId.trim().isEmpty()) {
                blankIndexes.add(i);
                continue;
            }
            idCounter.merge(curveId, 1, Integer::sum);
        }

        if (!blankIndexes.isEmpty()) {
            throw new IllegalArgumentException("存在空 CURVE_ID，输入索引: " + blankIndexes);
        }

        List<String> duplicatedIds = idCounter.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (!duplicatedIds.isEmpty()) {
            throw new IllegalArgumentException("存在重复 CURVE_ID: " + duplicatedIds);
        }
    }
}
