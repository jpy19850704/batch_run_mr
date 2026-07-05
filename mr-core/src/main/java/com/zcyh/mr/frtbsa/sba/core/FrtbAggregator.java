package com.zcyh.mr.frtbsa.sba.core;

import com.alibaba.fastjson2.JSON;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.core.eq.EqModule;
import com.zcyh.mr.frtbsa.sba.core.girr.GirrModule;
import com.zcyh.mr.frtbsa.sba.core.csrns.CsrnsModule;
import com.zcyh.mr.frtbsa.sba.core.csrnc.CsrncModule;
import com.zcyh.mr.frtbsa.sba.core.fx.FxModule;
import com.zcyh.mr.frtbsa.sba.core.cmty.CmtyModule;
import com.zcyh.mr.frtbsa.sba.core.csrctp.CsrctpModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * FRTB SA V2 总汇总器
 * 职责：
 * 1. 数据校验
 * 2. 按风险类别分组路由到各子模块
 * 3. 汇总所有风险类别的结果
 * 4. 支持多线程批量计算
 *
 * @author system
 * @version 2.0
 */
public class FrtbAggregator {

    private static final Logger log = LoggerFactory.getLogger(FrtbAggregator.class);

    private static final String RISK_CLASS_ALL = "ALL";
    private static final String[] CLASS_RESULT_SENS_TYPES = { "Delta", "Vega", "Curvature" };
    private final EqModule eqModule = new EqModule();
    private final GirrModule girrModule = new GirrModule();
    private final CsrnsModule csrnsModule = new CsrnsModule();
    private final CsrncModule csrncModule = new CsrncModule();
    private final FxModule fxModule = new FxModule();
    private final CmtyModule cmtyModule = new CmtyModule();
    private final CsrctpModule csrctpModule = new CsrctpModule();
    private final ExecutorService batchExecutor;
    private static final List<String> VALID_SENS_TYPES = Arrays.asList(
            FrtbConstants.SENS_DELTA,
            FrtbConstants.SENS_VEGA,
            FrtbConstants.SENS_CURVATURE_UP,
            FrtbConstants.SENS_CURVATURE_DOWN);
    private static final List<String> CALC_RISK_CLASSES = Arrays.asList(
            FrtbConstants.RISK_CLASS_GIRR,
            FrtbConstants.RISK_CLASS_CSRNS,
            FrtbConstants.RISK_CLASS_CSRNC,
            FrtbConstants.RISK_CLASS_EQ,
            FrtbConstants.RISK_CLASS_FX,
            FrtbConstants.RISK_CLASS_CMTY,
            FrtbConstants.RISK_CLASS_CSRCTP);
    private static final Set<String> SUPPORTED_RISK_CLASSES = new HashSet<>(CALC_RISK_CLASSES);
    private static final Set<String> CMTY_TENORS = new HashSet<>(Arrays.asList(
            "0", "0.25", "0.5", "1", "2", "3", "5", "10", "15", "20", "30"));

    public FrtbAggregator() {
        this(null);
    }

    public FrtbAggregator(ExecutorService batchExecutor) {
        this.batchExecutor = batchExecutor;
    }

    /**
     * 主入口：计算FRTB SA资本（默认执行分解）
     */
    public String calculate(List<FrtbInput> rawList) {
        return calculate(rawList, true);
    }

    /**
     * 主入口：计算FRTB SA资本，返回 JSON
     */
    public String calculate(List<FrtbInput> rawList, Boolean needDecompose) {
        return JSON.toJSONString(calculateAsMap(rawList, needDecompose));
    }

    /**
     * 计算FRTB SA资本，返回 Map 结构（供批量模式和 POJO 转换使用）
     *
     * @param rawList       原始数据列表
     * @param needDecompose 是否需要分解计算
     * @return 按风险类别组织的计算结果
     */
    public Map<String, Object> calculateAsMap(List<FrtbInput> rawList, Boolean needDecompose) {
        Map<String, Object> resultMap = calculateRiskClassMap(rawList, needDecompose);
        appendAllRiskClassResult(resultMap);
        return resultMap;
    }

    /**
     * 只计算真实风险大类，不生成 ALL 汇总行。
     */
    private Map<String, Object> calculateRiskClassMap(List<FrtbInput> rawList, Boolean needDecompose) {
        // 1. 数据校验
        Map<String, Object> checkResult = moduleCheck(rawList);
        List<FrtbInput> validData = castFrtbList(checkResult.get("checked"));
        List<FrtbInput> errorData = castFrtbList(checkResult.get("errors"));
        List<Map<String, Object>> errorDetails = castMapList(checkResult.get("errorDetails"));

        // 2. 按风险类别分组
        Map<String, List<FrtbInput>> groupedByClass = validData.stream()
                .collect(Collectors.groupingBy(FrtbInput::getRiskFactorClass));

        // 3. 计算各风险类别
        Map<String, Object> resultMap = new LinkedHashMap<>();

        if (groupedByClass.containsKey(FrtbConstants.RISK_CLASS_GIRR)) {
            resultMap.put(FrtbConstants.RISK_CLASS_GIRR,
                    girrModule.calc(groupedByClass.get(FrtbConstants.RISK_CLASS_GIRR), needDecompose));
        }
        if (groupedByClass.containsKey(FrtbConstants.RISK_CLASS_CSRNS)) {
            resultMap.put(FrtbConstants.RISK_CLASS_CSRNS,
                    csrnsModule.calc(groupedByClass.get(FrtbConstants.RISK_CLASS_CSRNS), needDecompose));
        }
        if (groupedByClass.containsKey(FrtbConstants.RISK_CLASS_CSRNC)) {
            resultMap.put(FrtbConstants.RISK_CLASS_CSRNC,
                    csrncModule.calc(groupedByClass.get(FrtbConstants.RISK_CLASS_CSRNC), needDecompose));
        }
        if (groupedByClass.containsKey(FrtbConstants.RISK_CLASS_EQ)) {
            resultMap.put(FrtbConstants.RISK_CLASS_EQ,
                    eqModule.calc(groupedByClass.get(FrtbConstants.RISK_CLASS_EQ), needDecompose));
        }
        if (groupedByClass.containsKey(FrtbConstants.RISK_CLASS_FX)) {
            resultMap.put(FrtbConstants.RISK_CLASS_FX,
                    fxModule.calc(groupedByClass.get(FrtbConstants.RISK_CLASS_FX), needDecompose));
        }
        if (groupedByClass.containsKey(FrtbConstants.RISK_CLASS_CMTY)) {
            resultMap.put(FrtbConstants.RISK_CLASS_CMTY,
                    cmtyModule.calc(groupedByClass.get(FrtbConstants.RISK_CLASS_CMTY), needDecompose));
        }
        if (groupedByClass.containsKey(FrtbConstants.RISK_CLASS_CSRCTP)) {
            resultMap.put(FrtbConstants.RISK_CLASS_CSRCTP,
                    csrctpModule.calc(groupedByClass.get(FrtbConstants.RISK_CLASS_CSRCTP), needDecompose));
        }

        // 结构化返回校验错误（保留错误笔数，便于上游记录）
        if (errorData != null && !errorData.isEmpty()) {
            resultMap.put("ERROR_COUNT", errorData.size());
            resultMap.put("ERRORS", errorDetails);
        }

        return resultMap;
    }

    // 默认线程数：CPU 核心数的 80%，至少为 1
    private static final int DEFAULT_THREAD_COUNT = Math.max(1,
            (int) (Runtime.getRuntime().availableProcessors() * 0.8));

    // 单批次提交数量，避免一次性提交过多任务压满队列
    private static final int BATCH_CHUNK_SIZE = 500;

    // 单个任务超时时间（秒）
    private static final long TASK_TIMEOUT_SECONDS = 60;

    /**
     * 使用默认线程数执行批量计算（CPU 核心数的 80%）
     *
     * @param tasks         key=分组键，value=分组后的输入数据
     * @param needDecompose 是否执行资本分解
     * @return key=分组键，value=对应的计算结果
     */
    public Map<String, Map<String, Object>> calculateBatch(
            Map<String, List<FrtbInput>> tasks,
            boolean needDecompose) {
        return calculateBatch(tasks, needDecompose, DEFAULT_THREAD_COUNT);
    }

    /**
     * 使用指定线程数执行批量计算。
     * 优化点：
     * 1. 通过 ThreadLocal 复用 Aggregator 实例，避免大批量创建对象
     * 2. 分块提交任务，控制内存和队列压力
     * 3. 为单个任务增加超时保护
     *
     * @param tasks         key=分组键，value=分组后的输入数据
     * @param needDecompose 是否执行资本分解
     * @param threadCount   线程数（<=0 使用默认值，=1 时退化为串行执行）
     * @return key=分组键，value=对应的计算结果
     */
    public Map<String, Map<String, Object>> calculateBatch(
            Map<String, List<FrtbInput>> tasks,
            boolean needDecompose,
            int threadCount) {

        if (threadCount <= 0) {
            threadCount = DEFAULT_THREAD_COUNT;
        }

        // 串行模式
        if (threadCount == 1 || tasks.size() <= 1) {
            Map<String, Map<String, Object>> results = new LinkedHashMap<>();
            for (Map.Entry<String, List<FrtbInput>> e : tasks.entrySet()) {
                results.put(e.getKey(), calculateRiskClassMap(e.getValue(), needDecompose));
            }
            if (needDecompose) {
                reassignDecompByTotalPder(results);
            }
            appendAllRiskClassResults(results);
            return results;
        }

        // ThreadLocal：每个线程复用自己的 Aggregator 实例
        ThreadLocal<FrtbAggregator> localAgg = ThreadLocal.withInitial(FrtbAggregator::new);
        ExecutorService pool = requireBatchExecutor();

        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        List<Map.Entry<String, List<FrtbInput>>> entries = new ArrayList<>(tasks.entrySet());
        int chunkSize = Math.max(1, Math.min(BATCH_CHUNK_SIZE, threadCount));

        try {
            // 分块提交任务
            for (int offset = 0; offset < entries.size(); offset += chunkSize) {
                int end = Math.min(offset + chunkSize, entries.size());
                List<Map.Entry<String, List<FrtbInput>>> chunk = entries.subList(offset, end);

                // 提交当前分块
                Map<String, Future<Map<String, Object>>> futures = new LinkedHashMap<>();
                for (Map.Entry<String, List<FrtbInput>> e : chunk) {
                    final List<FrtbInput> data = e.getValue();
                    futures.put(e.getKey(), pool.submit(() -> localAgg.get().calculateRiskClassMap(data, needDecompose)));
                }

                // 带超时控制地收集结果
                for (Map.Entry<String, Future<Map<String, Object>>> fe : futures.entrySet()) {
                    try {
                        results.put(fe.getKey(),
                                fe.getValue().get(TASK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (java.util.concurrent.TimeoutException te) {
                        log.warn("任务超时: {}", fe.getKey());
                        fe.getValue().cancel(true);
                        results.put(fe.getKey(), buildBatchErrorResult("TIMEOUT",
                                "任务执行超过 " + TASK_TIMEOUT_SECONDS + " 秒"));
                    }
                }
            }
        } catch (InterruptedException | ExecutionException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("批量计算异常", ex);
        } finally {
            localAgg.remove();
        }

        // 用 TOTAL 的 pder 重新计算子维度的 decompRslt，保证可加性分解
        if (needDecompose) {
            reassignDecompByTotalPder(results);
        }
        appendAllRiskClassResults(results);
        return results;
    }

    private ExecutorService requireBatchExecutor() {
        if (batchExecutor == null) {
            throw new IllegalStateException("frtb batch executor 未配置，请通过 Spring 应用层注入批量执行器");
        }
        return batchExecutor;
    }

    /**
     * 数据校验
     * 规则：
     * 1. 敏感性类型检查：Delta/Vega/Curvature Up/Curvature Down
     * 2. Vertex 仅允许空或标准数字年
     * 3. Curvature配对检查：Curvature Up 和 Curvature Down 必须成对出现
     */
    private Map<String, Object> moduleCheck(List<FrtbInput> dataList) {
        List<FrtbInput> validData = new ArrayList<>();
        List<FrtbInput> errorData = new ArrayList<>();
        List<Map<String, Object>> errorDetails = new ArrayList<>();
        // 使用对象身份记录原始输入行号，避免后续配对校验阶段丢失真实定位信息
        Map<FrtbInput, Integer> sourceRowNoMap = new IdentityHashMap<>();

        if (dataList == null || dataList.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("checked", validData);
            result.put("errors", errorData);
            result.put("errorDetails", errorDetails);
            return result;
        }

        // 第一步：基础校验（敏感性类型 + 必填 bucket + GIRR tenor）
        int rowNo = 0;
        for (FrtbInput model : dataList) {
            rowNo++;
            boolean invalid = false;

            if (model == null) {
                errorDetails.add(buildError("NULL_INPUT", "输入记录为空", null, rowNo));
                continue;
            }
            sourceRowNoMap.put(model, rowNo);

            // 统一 FX/GIRR 货币桶口径：CNH -> CNY
            model.setRiskFactorBucket(FrtbConstants.normalizeBucketForRiskClass(
                    model.getRiskFactorClass(), model.getRiskFactorBucket()));

            if (!FrtbConstants.isValidRiskClass(model.getRiskFactorClass())) {
                invalid = true;
                errorDetails.add(buildError("INVALID_RISK_FACTOR_CLASS",
                        "风险类别缺失或非法",
                        model, rowNo));
            }

            String sensType = model.getSensitivityType();
            if (sensType == null || !VALID_SENS_TYPES.contains(sensType)) {
                invalid = true;
                errorDetails.add(buildError("INVALID_SENSITIVITY_TYPE",
                        "敏感性类型必须为 Delta、Vega、Curvature Up 或 Curvature Down",
                        model, rowNo));
            }

            if (isBlank(model.getRiskFactorBucket())) {
                invalid = true;
                errorDetails.add(buildError("MISSING_RISK_FACTOR_BUCKET",
                        "风险因子桶不能为空",
                        model, rowNo));
            }

            if (!invalid && !validateStandardVertices(model, rowNo, errorDetails)) {
                invalid = true;
            }

            if (isGirr(model) && !invalid) {
                invalid = !validateGirrTenor(model, rowNo, errorDetails);
            }

            if (invalid) {
                errorData.add(model);
                continue;
            }

            validData.add(model);
        }

        // Step 2: Curvature配对检查
        Map<String, List<FrtbInput>> curvatureGrouped = validData.stream()
                .filter(e -> e.getSensitivityType().startsWith("Curvature"))
                .collect(Collectors.groupingBy(this::buildCurvaturePairKey));

        for (String key : curvatureGrouped.keySet()) {
            List<FrtbInput> group = curvatureGrouped.get(key);
            boolean hasUp = group.stream()
                    .anyMatch(e -> FrtbConstants.SENS_CURVATURE_UP.equals(e.getSensitivityType()));
            boolean hasDown = group.stream()
                    .anyMatch(e -> FrtbConstants.SENS_CURVATURE_DOWN.equals(e.getSensitivityType()));

            if (!(hasUp && hasDown)) {
                for (FrtbInput item : group) {
                    int sourceRowNo = sourceRowNoMap.getOrDefault(item, -1);
                    errorDetails.add(buildError("CURVATURE_PAIR_MISSING",
                            "Curvature Up 与 Curvature Down 必须成对出现，分组键=" + key,
                            item, sourceRowNo));
                }
                errorData.addAll(group);
                validData.removeAll(group);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("checked", validData);
        result.put("errors", errorData);
        result.put("errorDetails", errorDetails);
        return result;
    }

    // ===================== POJO 转换 =====================

    /**
     * 计算并返回 POJO 结构的结果
     *
     * @param rawList       原始数据列表
     * @param needDecompose 是否需要资本分解
     * @param ruleId        规则 ID
     * @param groupType     维度类型（如 "TOTAL", "portfolio"）
     * @param groupValue    维度值（如 "ALL", "Book_A"）
     * @return Map 包含 "classResults", "bucketResults", "posResults" 三个列表
     */
    public Map<String, List<?>> calculateAsPojo(
            List<FrtbInput> rawList, boolean needDecompose,
            String ruleId, String groupType, String groupValue) {

        Map<String, Object> mapResult = calculateAsMap(rawList, needDecompose);
        return buildResults(mapResult, ruleId, groupType, groupValue);
    }

    /**
     * 将 Map 结构的计算结果转换为 POJO 列表
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<?>> buildResults(
            Map<String, Object> mapResult,
            String ruleId, String groupType, String groupValue) {

        List<com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult> classResults = new ArrayList<>();
        List<com.zcyh.mr.frtbsa.sba.pojo.FRTBBucketResult> bucketResults = new ArrayList<>();
        List<com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult> posResults = new ArrayList<>();
        String selectedScenarioName = selectResultScenarioName(mapResult);

        // 遍历标准输出风险大类，包含真实风险大类和 ALL 汇总结果。
        for (Map.Entry<String, Object> classEntry : mapResult.entrySet()) {
            String riskClass = classEntry.getKey();
            if (!isSupportedRiskClass(riskClass) || !(classEntry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> sensTypeMap = (Map<String, Object>) classEntry.getValue();

            // 构建 ClassResult
            com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult cr = new com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult();
            cr.setRuleId(ruleId);
            cr.setGroupType(groupType);
            cr.setGroupValue(groupValue);
            cr.setRiskFactorClass(riskClass);

            // 遍历每个 sensType（Delta/Vega/Curvature）
            for (String sensType : CLASS_RESULT_SENS_TYPES) {
                if (!sensTypeMap.containsKey(sensType))
                    continue;
                Map<String, Object> stResult = (Map<String, Object>) sensTypeMap.get(sensType);
                if (stResult == null || stResult.isEmpty())
                    continue;

                // class 级数据
                Map<String, Object> classData = (Map<String, Object>) stResult.get("class");
                if (classData != null) {
                    double capNormal = toDouble(classData.get("capital_normal"));
                    double capHigh = toDouble(classData.get("capital_high"));
                    double capLow = toDouble(classData.get("capital_low"));

                    switch (sensType) {
                        case "Delta":
                            cr.setNormalDelta(toBigDecimal(capNormal));
                            cr.setHighDelta(toBigDecimal(capHigh));
                            cr.setLowDelta(toBigDecimal(capLow));
                            break;
                        case "Vega":
                            cr.setNormalVega(toBigDecimal(capNormal));
                            cr.setHighVega(toBigDecimal(capHigh));
                            cr.setLowVega(toBigDecimal(capLow));
                            break;
                        case "Curvature":
                            cr.setNormalCurvature(toBigDecimal(capNormal));
                            cr.setHighCurvature(toBigDecimal(capHigh));
                            cr.setLowCurvature(toBigDecimal(capLow));
                            break;
                    }
                }

                // bucket 级数据
                List<Map<String, Object>> bucketList = (List<Map<String, Object>>) stResult.get("bucket");
                if (bucketList != null) {
                    for (Map<String, Object> bkt : bucketList) {
                        com.zcyh.mr.frtbsa.sba.pojo.FRTBBucketResult br = new com.zcyh.mr.frtbsa.sba.pojo.FRTBBucketResult();
                        br.setRuleId(ruleId);
                        br.setGroupType(groupType);
                        br.setGroupValue(groupValue);
                        br.setRiskFactorClass(riskClass);
                        br.setRiskFactorBucket(str(bkt.get("riskFactorBucket")));
                        br.setSensitivityType(sensType);

                        // Normal(M), High(H), Low(L) 场景
                        br.setKbM(toBigDecimal(toDouble(bkt.get("Kb_MM"))));
                        br.setSbM(toBigDecimal(toDouble(bkt.get("Sb_M"))));
                        br.setSbbM(toBigDecimal(toDouble(bkt.get("Sbb_M"))));
                        br.setKbH(toBigDecimal(toDouble(bkt.get("Kb_HH"))));
                        br.setSbH(toBigDecimal(toDouble(bkt.get("Sb_H"))));
                        br.setSbbH(toBigDecimal(toDouble(bkt.get("Sbb_H"))));
                        br.setKbL(toBigDecimal(toDouble(bkt.get("Kb_LL"))));
                        br.setSbL(toBigDecimal(toDouble(bkt.get("Sb_L"))));
                        br.setSbbL(toBigDecimal(toDouble(bkt.get("Sbb_L"))));

                        bucketResults.add(br);
                    }
                }

                // pos 级 + decomp 数据
                List<Map<String, Object>> posList = (List<Map<String, Object>>) stResult.get("pos");
                List<Map<String, Object>> decompList = (List<Map<String, Object>>) stResult.get("decompRslt");

                // 建立 decomp 索引并累计各场景分摊资本
                Map<String, Map<String, Object>> decompIndex = new HashMap<>();
                double allocSumNormal = 0.0;
                double allocSumHigh = 0.0;
                double allocSumLow = 0.0;
                if (decompList != null) {
                    for (Map<String, Object> d : decompList) {
                        String key = buildDecompKey(d, sensType);
                        decompIndex.put(key, d);
                        allocSumNormal += toDouble(d.get("allocatedCapital_normal"));
                        allocSumHigh += toDouble(d.get("allocatedCapital_high"));
                        allocSumLow += toDouble(d.get("allocatedCapital_low"));
                    }
                }

                // 将 decomp 分摊资本按 sensType 聚合到 ClassResult
                switch (sensType) {
                    case "Delta":
                        cr.setAllocDeltaNormal(toBigDecimal(allocSumNormal));
                        cr.setAllocDeltaHigh(toBigDecimal(allocSumHigh));
                        cr.setAllocDeltaLow(toBigDecimal(allocSumLow));
                        break;
                    case "Vega":
                        cr.setAllocVegaNormal(toBigDecimal(allocSumNormal));
                        cr.setAllocVegaHigh(toBigDecimal(allocSumHigh));
                        cr.setAllocVegaLow(toBigDecimal(allocSumLow));
                        break;
                    case "Curvature":
                        cr.setAllocCurvatureNormal(toBigDecimal(allocSumNormal));
                        cr.setAllocCurvatureHigh(toBigDecimal(allocSumHigh));
                        cr.setAllocCurvatureLow(toBigDecimal(allocSumLow));
                        break;
                }

                if (posList != null) {
                    for (Map<String, Object> pos : posList) {
                        // 从 decomp 匹配 contribution（按风险因子完整主键精确匹配）
                        String dKey = buildDecompKey(pos, sensType);
                        Map<String, Object> decomp = decompIndex.get(dKey);
                        Double contribution = null;
                        Double activeCvr = null;
                        if (decomp != null) {
                            contribution = toDouble(decomp.get("allocatedCapital_" + selectedScenarioName));
                            if (FrtbConstants.SENS_CURVATURE.equals(sensType)) {
                                activeCvr = requireCurvatureActiveCvr(decomp, selectedScenarioName, dKey);
                            }
                        }

                        if ("Curvature".equals(sensType)) {
                            addCurvaturePosResult(posResults, ruleId, groupType, groupValue, riskClass, pos,
                                    FrtbConstants.SENS_CURVATURE, activeCvr, contribution);
                            continue;
                        }

                        double origSens = toDouble(pos.get("sensitivityValRptCurrCny"));
                        double rw = toDouble(pos.get("riskWeight"));
                        double ws = toDouble(pos.get("ws"));

                        com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult pr = buildBasePosResult(
                                ruleId, groupType, groupValue, riskClass, sensType, pos);
                        pr.setSensitivityValRptCurrCny(toBigDecimal(origSens));
                        pr.setRiskWeight(toBigDecimal(rw));
                        pr.setWs(toBigDecimal(ws));
                        fillContribution(pr, contribution, origSens);
                        posResults.add(pr);
                    }
                }
            }

            fillClassRiskSummary(cr);
            classResults.add(cr);
        }

        Map<String, List<?>> result = new LinkedHashMap<>();
        result.put("classResults", classResults);
        result.put("bucketResults", bucketResults);
        result.put("posResults", posResults);
        return result;
    }

    /**
     * 将 ALL 作为标准 SBA 输出风险大类追加到 Map 结果中。
     */
    private void appendAllRiskClassResults(Map<String, Map<String, Object>> batchResult) {
        if (batchResult == null || batchResult.isEmpty()) {
            return;
        }
        for (Map<String, Object> resultMap : batchResult.values()) {
            appendAllRiskClassResult(resultMap);
        }
    }

    /**
     * 基于真实风险大类生成 ALL 汇总结果。
     */
    private void appendAllRiskClassResult(Map<String, Object> resultMap) {
        if (resultMap == null || resultMap.isEmpty()) {
            return;
        }
        resultMap.remove(RISK_CLASS_ALL);
        Map<String, Object> allResult = new LinkedHashMap<>();
        for (String sensType : CLASS_RESULT_SENS_TYPES) {
            Map<String, Object> sensResult = buildAllSensitivityResult(resultMap, sensType);
            if (!sensResult.isEmpty()) {
                allResult.put(sensType, sensResult);
            }
        }
        if (!allResult.isEmpty()) {
            resultMap.put(RISK_CLASS_ALL, allResult);
        }
    }

    /**
     * 汇总某个敏感性类型下所有真实风险大类的 class 资本与分解结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAllSensitivityResult(Map<String, Object> resultMap, String sensType) {
        Map<String, Object> sensResult = new LinkedHashMap<>();
        Map<String, Object> classCapital = new LinkedHashMap<>();
        List<Map<String, Object>> allDecomp = new ArrayList<>();
        double capitalNormal = 0.0;
        double capitalHigh = 0.0;
        double capitalLow = 0.0;
        boolean hasClassCapital = false;

        for (String riskClass : CALC_RISK_CLASSES) {
            Object classObj = resultMap.get(riskClass);
            if (!(classObj instanceof Map)) {
                continue;
            }
            Object sensObj = ((Map<String, Object>) classObj).get(sensType);
            if (!(sensObj instanceof Map)) {
                continue;
            }
            Map<String, Object> riskSensResult = (Map<String, Object>) sensObj;
            Object classDataObj = riskSensResult.get("class");
            if (classDataObj instanceof Map) {
                Map<String, Object> classData = (Map<String, Object>) classDataObj;
                capitalNormal += toDouble(classData.get("capital_normal"));
                capitalHigh += toDouble(classData.get("capital_high"));
                capitalLow += toDouble(classData.get("capital_low"));
                hasClassCapital = true;
            }

            Object decompObj = riskSensResult.get("decompRslt");
            if (decompObj instanceof List) {
                for (Object item : (List<?>) decompObj) {
                    if (item instanceof Map) {
                        allDecomp.add((Map<String, Object>) item);
                    }
                }
            }
        }

        if (hasClassCapital) {
            classCapital.put("riskFactorClass", RISK_CLASS_ALL);
            classCapital.put("capital_normal", capitalNormal);
            classCapital.put("capital_high", capitalHigh);
            classCapital.put("capital_low", capitalLow);
            classCapital.put("capital", Math.max(Math.max(capitalNormal, capitalHigh), capitalLow));
            sensResult.put("class", classCapital);
        }
        if (!allDecomp.isEmpty()) {
            sensResult.put("decompRslt", allDecomp);
        }
        return sensResult;
    }

    /**
     * 根据 class 结果的三场景资本填充总资本、相关性情景和分摊资本。
     */
    private void fillClassRiskSummary(com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult cr) {
        double totalNormal = safeAdd(cr.getNormalDelta(), cr.getNormalVega(), cr.getNormalCurvature());
        double totalHigh = safeAdd(cr.getHighDelta(), cr.getHighVega(), cr.getHighCurvature());
        double totalLow = safeAdd(cr.getLowDelta(), cr.getLowVega(), cr.getLowCurvature());
        double maxTotal = Math.max(Math.max(totalNormal, totalHigh), totalLow);
        cr.setRiskCharge(toBigDecimal(maxTotal));

        if (maxTotal == totalHigh)
            cr.setMaxSign("high");
        else if (maxTotal == totalLow)
            cr.setMaxSign("low");
        else
            cr.setMaxSign("normal");

        double allocNormal = safeAdd(cr.getAllocDeltaNormal(), cr.getAllocVegaNormal(), cr.getAllocCurvatureNormal());
        double allocHigh = safeAdd(cr.getAllocDeltaHigh(), cr.getAllocVegaHigh(), cr.getAllocCurvatureHigh());
        double allocLow = safeAdd(cr.getAllocDeltaLow(), cr.getAllocVegaLow(), cr.getAllocCurvatureLow());
        cr.setAllocatedCapital(toBigDecimal(Math.max(Math.max(allocNormal, allocHigh), allocLow)));
    }

    @SuppressWarnings("unchecked")
    private static String selectResultScenarioName(Map<String, Object> mapResult) {
        Object allObj = mapResult == null ? null : mapResult.get(RISK_CLASS_ALL);
        if (!(allObj instanceof Map)) {
            throw new IllegalStateException("FRTB SBA 结果缺少 ALL 汇总，无法确定 decomp 最终情景");
        }
        Map<String, Object> allMap = (Map<String, Object>) allObj;
        double totalNormal = 0.0;
        double totalHigh = 0.0;
        double totalLow = 0.0;
        boolean found = false;
        for (String sensType : CLASS_RESULT_SENS_TYPES) {
            Object sensObj = allMap.get(sensType);
            if (!(sensObj instanceof Map)) {
                continue;
            }
            Object classObj = ((Map<String, Object>) sensObj).get("class");
            if (!(classObj instanceof Map)) {
                continue;
            }
            Map<String, Object> classData = (Map<String, Object>) classObj;
            totalNormal += toDouble(classData.get("capital_normal"));
            totalHigh += toDouble(classData.get("capital_high"));
            totalLow += toDouble(classData.get("capital_low"));
            found = true;
        }
        if (!found) {
            throw new IllegalStateException("FRTB SBA ALL 汇总缺少 class 资本，无法确定 decomp 最终情景");
        }
        return selectMaxScenarioName(totalNormal, totalHigh, totalLow);
    }

    private void addCurvaturePosResult(
            List<com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult> posResults,
            String ruleId, String groupType, String groupValue, String riskClass,
            Map<String, Object> pos, String sensitivityType, Double curvatureValue,
            Double contribution) {
        com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult pr = buildBasePosResult(
                ruleId, groupType, groupValue, riskClass, sensitivityType, pos);
        if (curvatureValue != null) {
            pr.setSensitivityValRptCurrCny(toBigDecimal(curvatureValue));
            pr.setWs(toBigDecimal(curvatureValue));
            fillContribution(pr, contribution, curvatureValue);
        }
        pr.setRiskWeight(null);
        posResults.add(pr);
    }

    private static String selectMaxScenarioName(double normal, double high, double low) {
        if (high >= normal && high >= low) {
            return "high";
        }
        if (low >= normal && low >= high) {
            return "low";
        }
        return "normal";
    }

    private static Double requireCurvatureActiveCvr(Map<String, Object> decomp, String scenarioName, String key) {
        String fieldName = "activeCvr_" + scenarioName;
        Object value = decomp.get(fieldName);
        if (value == null) {
            throw new IllegalStateException("Curvature 分解结果缺少最终方向 CVR: key=" + key + ", field=" + fieldName);
        }
        return parseRequiredDouble(value, fieldName, key);
    }

    private static double parseRequiredDouble(Object value, String fieldName, String key) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Curvature 分解结果字段无法解析为数字: key=" + key + ", field=" + fieldName
                    + ", value=" + value, e);
        }
    }

    private com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult buildBasePosResult(
            String ruleId, String groupType, String groupValue,
            String riskClass, String sensitivityType, Map<String, Object> pos) {
        com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult pr = new com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult();
        pr.setRuleId(ruleId);
        pr.setGroupType(groupType);
        pr.setGroupValue(groupValue);
        pr.setRiskFactorId(str(pos.get("riskFactorId")));
        pr.setRiskFactorBucket(str(pos.get("riskFactorBucket")));
        pr.setRiskFactorClass(riskClass);
        pr.setRiskFactorType(nullableStr(pos.get("riskFactorType")));
        pr.setRiskFactorVertex1(nullableStr(pos.get("riskFactorVertex1")));
        pr.setRiskFactorVertex2(nullableStr(pos.get("riskFactorVertex2")));
        pr.setSensitivityType(sensitivityType);
        return pr;
    }

    private void fillContribution(
            com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult pr,
            Double contribution, double sensitivity) {
        if (contribution == null) {
            return;
        }
        pr.setContribution(toBigDecimal(contribution));
        if (Math.abs(sensitivity) > 1e-12) {
            pr.setUnitContribution(toBigDecimal(contribution / sensitivity));
        }
    }

    // ===================== 辅助方法 =====================

    private static double toDouble(Object v) {
        if (v == null)
            return 0.0;
        if (v instanceof Number)
            return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static java.math.BigDecimal toBigDecimal(double v) {
        return java.math.BigDecimal.valueOf(v);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String nullableStr(Object v) {
        return v == null ? null : v.toString();
    }

    private static double safeAdd(java.math.BigDecimal... values) {
        double sum = 0;
        for (java.math.BigDecimal v : values) {
            if (v != null)
                sum += v.doubleValue();
        }
        return sum;
    }

    @SuppressWarnings("unchecked")
    private List<FrtbInput> castFrtbList(Object value) {
        if (value instanceof List) {
            return (List<FrtbInput>) value;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return new ArrayList<>();
    }

    private static Map<String, Object> buildBatchErrorResult(String errorCode, String errorMessage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ERROR_CODE", errorCode);
        result.put("ERROR_MESSAGE", errorMessage);
        return result;
    }

    private boolean isSupportedRiskClass(String riskClass) {
        return SUPPORTED_RISK_CLASSES.contains(riskClass) || RISK_CLASS_ALL.equals(riskClass);
    }

    // ===================== DECOM 后处理：子维度使用 TOTAL 的 pder =====================

    /**
     * 用 TOTAL 维度的偏导数（pder）重新计算子维度的 decompRslt。
     * FRTB Euler 分配规则：TOTAL 级做完整 Euler 分解得到每个风险因子的 pder，
     * 子维度的 allocatedCapital = 子维度的 ws × TOTAL 的 pder，
     * 保证子维度向上可加且 TOTAL 级总和不变。
     */
    @SuppressWarnings("unchecked")
    private void reassignDecompByTotalPder(Map<String, Map<String, Object>> batchResult) {
        // 1. 找到 TOTAL key
        String totalKey = null;
        for (String key : batchResult.keySet()) {
            if (key.endsWith("|TOTAL")) {
                totalKey = key;
                break;
            }
        }
        if (totalKey == null) return;

        Map<String, Object> totalResult = batchResult.get(totalKey);

        // 2. 对每个子维度 key 做处理
        for (Map.Entry<String, Map<String, Object>> entry : batchResult.entrySet()) {
            if (entry.getKey().equals(totalKey)) continue;
            Map<String, Object> subResult = entry.getValue();

            // 遍历每个 riskClass
            for (String rc : CALC_RISK_CLASSES) {
                Object totalRcObj = totalResult.get(rc);
                Object subRcObj = subResult.get(rc);
                if (!(totalRcObj instanceof Map) || !(subRcObj instanceof Map)) continue;

                Map<String, Object> totalRc = (Map<String, Object>) totalRcObj;
                Map<String, Object> subRc = (Map<String, Object>) subRcObj;

                // 遍历每个 sensType
                for (String st : CLASS_RESULT_SENS_TYPES) {
                    Object totalStObj = totalRc.get(st);
                    Object subStObj = subRc.get(st);
                    if (!(totalStObj instanceof Map) || !(subStObj instanceof Map)) continue;

                    Map<String, Object> totalSt = (Map<String, Object>) totalStObj;
                    Map<String, Object> subSt = (Map<String, Object>) subStObj;

                    // 从 TOTAL 的 decompRslt 建立 pder 索引
                    List<Map<String, Object>> totalDecomp = (List<Map<String, Object>>) totalSt.get("decompRslt");
                    if (totalDecomp == null || totalDecomp.isEmpty()) continue;

                    Map<String, Map<String, Object>> pderIndex = new HashMap<>();
                    for (Map<String, Object> d : totalDecomp) {
                        String dKey = buildDecompKey(d, st);
                        pderIndex.put(dKey, d);
                    }

                    // 从子维度的 pos 重新计算 decompRslt
                    List<Map<String, Object>> subPos = (List<Map<String, Object>>) subSt.get("pos");
                    if (subPos == null || subPos.isEmpty()) continue;

                    List<Map<String, Object>> newDecomp = new ArrayList<>();
                    for (Map<String, Object> pos : subPos) {
                        String posKey = buildDecompKey(pos, st);
                        Map<String, Object> totalPder = pderIndex.get(posKey);
                        if (totalPder == null) continue;

                        Map<String, Object> newD = new HashMap<>(pos);
                        if (FrtbConstants.SENS_CURVATURE.equals(st)) {
                            reassignCurvatureScenario(newD, totalPder, "normal");
                            reassignCurvatureScenario(newD, totalPder, "high");
                            reassignCurvatureScenario(newD, totalPder, "low");
                        } else {
                            reassignWeightedScenario(newD, totalPder, "normal");
                            reassignWeightedScenario(newD, totalPder, "high");
                            reassignWeightedScenario(newD, totalPder, "low");
                        }
                        newDecomp.add(newD);
                    }

                    // 替换子维度的 decompRslt
                    subSt.put("decompRslt", newDecomp);
                }
            }
        }
    }

    /**
     * 构建 decompRslt / pos 匹配键。
     * CSRNS/CSRCTP Curvature 的风险因子只有 name 维度，不使用 riskFactorType。
     */
    private static void reassignWeightedScenario(Map<String, Object> target, Map<String, Object> total,
            String scenarioName) {
        double totalBase = toDouble(total.get("ws"));
        double childBase = toDouble(target.get("ws"));
        double totalAllocated = toDouble(total.get("allocatedCapital_" + scenarioName));
        double unit = Math.abs(totalBase) > 1e-12 ? totalAllocated / totalBase : 0.0;
        target.put("pder_" + scenarioName, unit);
        target.put("allocatedCapital_" + scenarioName, childBase * unit);
    }

    private static void reassignCurvatureScenario(Map<String, Object> target, Map<String, Object> total,
            String scenarioName) {
        double totalBase = requireCurvatureActiveCvr(total, scenarioName, buildDecompKey(total, FrtbConstants.SENS_CURVATURE));
        double childBase = copyCurvatureActiveCvr(target, total, scenarioName);
        double totalAllocated = toDouble(total.get("allocatedCapital_" + scenarioName));
        double unit = Math.abs(totalBase) > 1e-12 ? totalAllocated / totalBase : 0.0;
        target.put("pder_" + scenarioName, unit);
        target.put("allocatedCapital_" + scenarioName, childBase * unit);
    }

    private static double copyCurvatureActiveCvr(Map<String, Object> target, Map<String, Object> source,
            String scenarioName) {
        String sideField = "activeCvrSide_" + scenarioName;
        Object sideValue = source.get(sideField);
        if (sideValue == null) {
            throw new IllegalStateException("Curvature TOTAL 分解结果缺少方向字段: field=" + sideField);
        }
        String side = sideValue.toString();
        double activeCvr;
        if ("UP".equals(side)) {
            activeCvr = toDouble(target.get("CVR_up"));
        } else if ("DOWN".equals(side)) {
            activeCvr = toDouble(target.get("CVR_down"));
        } else {
            throw new IllegalStateException("Curvature TOTAL 分解结果方向字段非法: field=" + sideField + ", value=" + side);
        }
        target.put("activeCvr_" + scenarioName, activeCvr);
        target.put(sideField, side);
        return activeCvr;
    }

    private static String buildDecompKey(Map<String, Object> m, String sensType) {
        String key = str(m.get("riskFactorBucket")) + "|"
                + str(m.get("riskFactorId")) + "|"
                + str(m.get("riskFactorVertex1")) + "|"
                + str(m.get("riskFactorVertex2"));
        if (useRiskFactorTypeInDecompKey(m, sensType)) {
            key = key + "|" + str(m.get("riskFactorType"));
        }
        return key;
    }

    private static boolean useRiskFactorTypeInDecompKey(Map<String, Object> m, String sensType) {
        if (!FrtbConstants.SENS_CURVATURE.equals(sensType)) {
            return true;
        }
        String riskClass = str(m.get("riskFactorClass"));
        return !FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)
                && !FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isGirr(FrtbInput model) {
        return FrtbConstants.RISK_CLASS_GIRR.equals(model.getRiskFactorClass());
    }

    private boolean validateStandardVertices(FrtbInput model, int rowNo, List<Map<String, Object>> errorDetails) {
        if (!isBlank(model.getRiskFactorVertex1()) && parseStandardVertexStrict(model.getRiskFactorVertex1()) == null) {
            errorDetails.add(buildError("INVALID_VERTEX1",
                    "风险因子期限1必须为数字年",
                    model, rowNo));
            return false;
        }
        if (!isBlank(model.getRiskFactorVertex2()) && parseStandardVertexStrict(model.getRiskFactorVertex2()) == null) {
            errorDetails.add(buildError("INVALID_VERTEX2",
                    "风险因子期限2必须为数字年",
                    model, rowNo));
            return false;
        }
        String riskClass = model.getRiskFactorClass();
        String sensType = model.getSensitivityType();
        if (FrtbConstants.SENS_VEGA.equals(sensType)) {
            if (!requirePositiveVertex(model.getRiskFactorVertex1(), "MISSING_VERTEX1",
                    "Vega 风险因子期限1不能为空且必须大于0",
                    model, rowNo, errorDetails)) {
                return false;
            }
            if (FrtbConstants.RISK_CLASS_GIRR.equals(riskClass)
                    && !requirePositiveVertex(model.getRiskFactorVertex2(), "MISSING_VERTEX2",
                    "GIRR Vega 风险因子期限2不能为空且必须大于0",
                    model, rowNo, errorDetails)) {
                return false;
            }
        }
        if (FrtbConstants.SENS_DELTA.equals(sensType)
                && (FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRNC.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass))) {
            if (!requirePositiveVertex(model.getRiskFactorVertex1(), "MISSING_VERTEX1",
                    "CSR Delta 风险因子期限1不能为空且必须大于0",
                    model, rowNo, errorDetails)) {
                return false;
            }
        }
        if (FrtbConstants.SENS_DELTA.equals(sensType)
                && FrtbConstants.RISK_CLASS_CMTY.equals(riskClass)) {
            Double tenor1 = parseStandardVertexStrict(model.getRiskFactorVertex1());
            if (tenor1 == null || !CMTY_TENORS.contains(tenorKey(tenor1))) {
                errorDetails.add(buildError("INVALID_CMTY_VERTEX1",
                        "CMTY Delta 风险因子期限1不能为空且必须为监管标准期限",
                        model, rowNo));
                return false;
            }
        }
        return true;
    }

    private boolean requirePositiveVertex(String vertex, String errorCode, String message,
                                          FrtbInput model, int rowNo, List<Map<String, Object>> errorDetails) {
        Double parsed = parseStandardVertexStrict(vertex);
        if (parsed == null || parsed <= 0) {
            errorDetails.add(buildError(errorCode, message, model, rowNo));
            return false;
        }
        return true;
    }

    private boolean validateGirrTenor(FrtbInput model, int rowNo, List<Map<String, Object>> errorDetails) {
        String sensType = model.getSensitivityType();
        String riskType = model.getRiskFactorType() == null ? "" : model.getRiskFactorType().toUpperCase();

        if (FrtbConstants.SENS_CURVATURE_UP.equals(sensType)
                || FrtbConstants.SENS_CURVATURE_DOWN.equals(sensType)) {
            return true;
        }

        boolean needsVertex1 = FrtbConstants.SENS_VEGA.equals(sensType)
                || (!riskType.contains("INFLA") && !riskType.contains("BASIS"));

        if (needsVertex1) {
            Double tenor1 = parseStandardVertexStrict(model.getRiskFactorVertex1());
            if (tenor1 == null) {
                errorDetails.add(buildError("INVALID_GIRR_VERTEX1",
                        "GIRR 风险因子期限1不能为空且必须为数字年",
                        model, rowNo));
                return false;
            }
            if (tenor1 <= 0) {
                errorDetails.add(buildError("INVALID_GIRR_VERTEX1",
                        "GIRR 风险因子期限1必须大于0",
                        model, rowNo));
                return false;
            }
        }
        return true;
    }

    private Double parseStandardVertexStrict(String vertex) {
        if (isBlank(vertex)) {
            return null;
        }
        String normalized = vertex.trim();
        if (!normalized.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        try {
            double value = Double.parseDouble(normalized);
            return Double.isFinite(value) && value >= 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String tenorKey(double tenor) {
        if (tenor == (long) tenor) {
            return String.valueOf((long) tenor);
        }
        return String.valueOf(tenor);
    }

    private Map<String, Object> buildError(String code, String message, FrtbInput model, int rowNo) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        err.put("rowNo", rowNo);
        if (model != null) {
            err.put("riskFactorClass", model.getRiskFactorClass());
            err.put("sensitivityType", model.getSensitivityType());
            err.put("riskFactorId", model.getRiskFactorId());
            err.put("riskFactorBucket", model.getRiskFactorBucket());
            err.put("riskFactorVertex1", model.getRiskFactorVertex1());
            err.put("riskFactorVertex2", model.getRiskFactorVertex2());
            err.put("groupType", model.getGroupType());
            err.put("groupValue", model.getGroupValue());
            err.put("dataDate", model.getDataDate());
        }
        return err;
    }

    private String buildCurvaturePairKey(FrtbInput input) {
        String normalizedBucket = FrtbConstants.normalizeBucketForRiskClass(
                input.getRiskFactorClass(), input.getRiskFactorBucket());
        if (FrtbConstants.RISK_CLASS_GIRR.equals(input.getRiskFactorClass())) {
            // GIRR Curvature 按 bucket 配对
            return input.getRiskFactorClass() + "@" + normalizedBucket;
        }
        return input.getRiskFactorClass() + "@" + input.getRiskFactorId() + "@" + normalizedBucket;
    }
}
