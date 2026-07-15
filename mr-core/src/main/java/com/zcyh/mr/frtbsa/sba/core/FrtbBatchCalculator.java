package com.zcyh.mr.frtbsa.sba.core;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * FRTB SBA批量计算器。
 */
public final class FrtbBatchCalculator {
    private static final Logger log = LoggerFactory.getLogger(FrtbBatchCalculator.class);
    private static final int DEFAULT_THREAD_COUNT = Math.max(1,
            (int) (Runtime.getRuntime().availableProcessors() * 0.8));
    private static final int BATCH_CHUNK_SIZE = 500;
    private static final long TASK_TIMEOUT_SECONDS = 60;
    private static final List<String> SENSITIVITY_TYPES = Arrays.asList("Delta", "Vega", "Curvature");
    private static final List<String> RISK_CLASSES = Arrays.asList(
            FrtbConstants.RISK_CLASS_GIRR,
            FrtbConstants.RISK_CLASS_CSRNS,
            FrtbConstants.RISK_CLASS_CSRNC,
            FrtbConstants.RISK_CLASS_EQ,
            FrtbConstants.RISK_CLASS_FX,
            FrtbConstants.RISK_CLASS_CMTY,
            FrtbConstants.RISK_CLASS_CSRCTP);

    private final ExecutorService batchExecutor;
    private final Supplier<FrtbAggregator> aggregatorFactory;
    private final FrtbAllRiskClassAssembler allRiskClassAssembler = new FrtbAllRiskClassAssembler();

    public FrtbBatchCalculator(ExecutorService batchExecutor) {
        this(batchExecutor, FrtbAggregator::new);
    }

    FrtbBatchCalculator(ExecutorService batchExecutor, Supplier<FrtbAggregator> aggregatorFactory) {
        if (batchExecutor == null) {
            throw new IllegalArgumentException("batchExecutor 不能为空");
        }
        if (aggregatorFactory == null) {
            throw new IllegalArgumentException("aggregatorFactory 不能为空");
        }
        this.batchExecutor = batchExecutor;
        this.aggregatorFactory = aggregatorFactory;
    }

    public Map<String, Map<String, Object>> calculateBatch(
            Map<String, List<FrtbInput>> tasks,
            boolean needDecompose) {
        return calculateBatch(tasks, needDecompose, DEFAULT_THREAD_COUNT);
    }

    public Map<String, Map<String, Object>> calculateBatch(
            Map<String, List<FrtbInput>> tasks,
            boolean needDecompose,
            int threadCount) {
        if (tasks == null) {
            throw new IllegalArgumentException("tasks 不能为空");
        }
        int effectiveThreadCount = threadCount <= 0 ? DEFAULT_THREAD_COUNT : threadCount;
        Map<String, Map<String, Object>> results;
        if (effectiveThreadCount == 1 || tasks.size() <= 1) {
            results = calculateSerial(tasks, needDecompose);
        } else {
            results = calculateParallel(tasks, needDecompose, effectiveThreadCount);
        }
        if (needDecompose) {
            reassignDecompByTotalPder(results);
        }
        allRiskClassAssembler.appendBatch(results);
        return results;
    }

    private Map<String, Map<String, Object>> calculateSerial(
            Map<String, List<FrtbInput>> tasks,
            boolean needDecompose) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<String, Map<String, Object>>();
        FrtbAggregator aggregator = aggregatorFactory.get();
        for (Map.Entry<String, List<FrtbInput>> entry : tasks.entrySet()) {
            results.put(entry.getKey(), calculateTask(
                    aggregator, entry.getKey(), entry.getValue(), needDecompose));
        }
        return results;
    }

    private Map<String, Map<String, Object>> calculateParallel(
            Map<String, List<FrtbInput>> tasks,
            boolean needDecompose,
            int threadCount) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<String, Map<String, Object>>();
        List<Map.Entry<String, List<FrtbInput>>> entries =
                new ArrayList<Map.Entry<String, List<FrtbInput>>>(tasks.entrySet());
        int chunkSize = Math.max(1, Math.min(BATCH_CHUNK_SIZE, threadCount));
        for (int offset = 0; offset < entries.size(); offset += chunkSize) {
            int end = Math.min(offset + chunkSize, entries.size());
            Map<String, Future<Map<String, Object>>> futures = new LinkedHashMap<String, Future<Map<String, Object>>>();
            for (Map.Entry<String, List<FrtbInput>> entry : entries.subList(offset, end)) {
                String taskKey = entry.getKey();
                List<FrtbInput> data = entry.getValue();
                futures.put(taskKey, batchExecutor.submit(
                        () -> aggregatorFactory.get().calculateRiskClassMap(data, needDecompose)));
            }
            collectFutures(futures, results);
        }
        return results;
    }

    private void collectFutures(
            Map<String, Future<Map<String, Object>>> futures,
            Map<String, Map<String, Object>> results) {
        for (Map.Entry<String, Future<Map<String, Object>>> entry : futures.entrySet()) {
            String taskKey = entry.getKey();
            Future<Map<String, Object>> future = entry.getValue();
            try {
                results.put(taskKey, future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (TimeoutException ex) {
                future.cancel(true);
                log.warn("FRTB SBA批量任务超时，已排除: taskKey={}", taskKey);
                results.put(taskKey, buildBatchErrorResult(
                        "TIMEOUT", "任务执行超过 " + TASK_TIMEOUT_SECONDS + " 秒"));
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                log.error("FRTB SBA批量任务异常，已排除: taskKey={}", taskKey, cause);
                results.put(taskKey, buildBatchErrorResult("CALC_FAILED", errorMessage(cause)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FRTB SBA批量计算线程被中断", ex);
            }
        }
    }

    private Map<String, Object> calculateTask(
            FrtbAggregator aggregator,
            String taskKey,
            List<FrtbInput> data,
            boolean needDecompose) {
        try {
            return aggregator.calculateRiskClassMap(data, needDecompose);
        } catch (RuntimeException ex) {
            log.error("FRTB SBA批量任务异常，已排除: taskKey={}", taskKey, ex);
            return buildBatchErrorResult("CALC_FAILED", errorMessage(ex));
        }
    }

    @SuppressWarnings("unchecked")
    private void reassignDecompByTotalPder(Map<String, Map<String, Object>> batchResult) {
        String totalKey = null;
        for (String key : batchResult.keySet()) {
            if (key.endsWith("|TOTAL")) {
                totalKey = key;
                break;
            }
        }
        if (totalKey == null) {
            return;
        }
        Map<String, Object> totalResult = batchResult.get(totalKey);
        if (totalResult == null || totalResult.containsKey("ERROR_CODE")) {
            log.warn("FRTB SBA缺少有效TOTAL结果，跳过子维度资本分解重分配: taskKey={}", totalKey);
            return;
        }

        for (Map.Entry<String, Map<String, Object>> entry : batchResult.entrySet()) {
            if (entry.getKey().equals(totalKey)) {
                continue;
            }
            Map<String, Object> subResult = entry.getValue();
            if (subResult == null || subResult.containsKey("ERROR_CODE")) {
                continue;
            }
            for (String riskClass : RISK_CLASSES) {
                Object totalClassObject = totalResult.get(riskClass);
                Object subClassObject = subResult.get(riskClass);
                if (!(totalClassObject instanceof Map) || !(subClassObject instanceof Map)) {
                    continue;
                }
                Map<String, Object> totalClass = (Map<String, Object>) totalClassObject;
                Map<String, Object> subClass = (Map<String, Object>) subClassObject;
                for (String sensitivityType : SENSITIVITY_TYPES) {
                    try {
                        reassignSensitivity(
                                entry.getKey(), riskClass, sensitivityType, totalClass, subClass);
                    } catch (RuntimeException ex) {
                        log.warn("FRTB SBA分解重分配异常，已排除当前风险类别: taskKey={}, riskClass={}, sensitivityType={}",
                                entry.getKey(), riskClass, sensitivityType, ex);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void reassignSensitivity(
            String taskKey,
            String riskClass,
            String sensitivityType,
            Map<String, Object> totalClass,
            Map<String, Object> subClass) {
        Object totalSensitivityObject = totalClass.get(sensitivityType);
        Object subSensitivityObject = subClass.get(sensitivityType);
        if (!(totalSensitivityObject instanceof Map) || !(subSensitivityObject instanceof Map)) {
            return;
        }
        Map<String, Object> totalSensitivity = (Map<String, Object>) totalSensitivityObject;
        Map<String, Object> subSensitivity = (Map<String, Object>) subSensitivityObject;
        Object totalDecompObject = totalSensitivity.get("decompRslt");
        Object subPositionObject = subSensitivity.get("pos");
        if (!(totalDecompObject instanceof List) || !(subPositionObject instanceof List)) {
            return;
        }

        Map<String, Map<String, Object>> pderIndex = new HashMap<String, Map<String, Object>>();
        for (Object item : (List<?>) totalDecompObject) {
            if (item instanceof Map) {
                Map<String, Object> decomp = (Map<String, Object>) item;
                pderIndex.put(buildDecompKey(decomp, sensitivityType), decomp);
            }
        }

        List<Map<String, Object>> newDecomp = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) subPositionObject) {
            if (!(item instanceof Map)) {
                if (item != null) {
                    log.warn("FRTB SBA分解重分配排除异常头寸: taskKey={}, riskClass={}, sensitivityType={}, value={}",
                            taskKey, riskClass, sensitivityType, item);
                }
                continue;
            }
            Map<String, Object> position = (Map<String, Object>) item;
            String positionKey = buildDecompKey(position, sensitivityType);
            Map<String, Object> totalPder = pderIndex.get(positionKey);
            if (totalPder == null) {
                continue;
            }
            Map<String, Object> newDecompRow = new HashMap<String, Object>(position);
            boolean valid = FrtbConstants.SENS_CURVATURE.equals(sensitivityType)
                    ? reassignCurvatureScenarios(newDecompRow, totalPder, taskKey, positionKey)
                    : reassignWeightedScenarios(newDecompRow, totalPder, taskKey, positionKey);
            if (valid) {
                newDecomp.add(newDecompRow);
            }
        }
        subSensitivity.put("decompRslt", newDecomp);
    }

    private boolean reassignWeightedScenarios(
            Map<String, Object> target,
            Map<String, Object> total,
            String taskKey,
            String positionKey) {
        for (String scenarioName : Arrays.asList("normal", "high", "low")) {
            Double totalBase = readNumber(total.get("ws"), taskKey, positionKey, "ws");
            Double childBase = readNumber(target.get("ws"), taskKey, positionKey, "ws");
            Double totalAllocated = readNumber(total.get("allocatedCapital_" + scenarioName),
                    taskKey, positionKey, "allocatedCapital_" + scenarioName);
            if (totalBase == null || childBase == null || totalAllocated == null) {
                return false;
            }
            double unit = Math.abs(totalBase) > 1e-12 ? totalAllocated / totalBase : 0.0;
            target.put("pder_" + scenarioName, unit);
            target.put("allocatedCapital_" + scenarioName, childBase * unit);
        }
        return true;
    }

    private boolean reassignCurvatureScenarios(
            Map<String, Object> target,
            Map<String, Object> total,
            String taskKey,
            String positionKey) {
        for (String scenarioName : Arrays.asList("normal", "high", "low")) {
            Double totalBase = readNumber(total.get("activeCvr_" + scenarioName),
                    taskKey, positionKey, "activeCvr_" + scenarioName);
            Double childBase = copyCurvatureActiveCvr(
                    target, total, scenarioName, taskKey, positionKey);
            Double totalAllocated = readNumber(total.get("allocatedCapital_" + scenarioName),
                    taskKey, positionKey, "allocatedCapital_" + scenarioName);
            if (totalBase == null || childBase == null || totalAllocated == null) {
                return false;
            }
            double unit = Math.abs(totalBase) > 1e-12 ? totalAllocated / totalBase : 0.0;
            target.put("pder_" + scenarioName, unit);
            target.put("allocatedCapital_" + scenarioName, childBase * unit);
        }
        return true;
    }

    private Double copyCurvatureActiveCvr(
            Map<String, Object> target,
            Map<String, Object> source,
            String scenarioName,
            String taskKey,
            String positionKey) {
        String sideField = "activeCvrSide_" + scenarioName;
        Object sideValue = source.get(sideField);
        if (sideValue == null) {
            log.warn("FRTB SBA曲率分解排除缺少方向的记录: taskKey={}, positionKey={}, field={}",
                    taskKey, positionKey, sideField);
            return null;
        }
        String side = sideValue.toString();
        String valueField;
        if ("UP".equals(side)) {
            valueField = "CVR_up";
        } else if ("DOWN".equals(side)) {
            valueField = "CVR_down";
        } else {
            log.warn("FRTB SBA曲率分解排除方向非法的记录: taskKey={}, positionKey={}, field={}, value={}",
                    taskKey, positionKey, sideField, sideValue);
            return null;
        }
        Double activeCvr = readNumber(target.get(valueField), taskKey, positionKey, valueField);
        if (activeCvr != null) {
            target.put("activeCvr_" + scenarioName, activeCvr);
            target.put(sideField, side);
        }
        return activeCvr;
    }

    private static Double readNumber(Object value, String taskKey, String positionKey, String field) {
        if (!(value instanceof Number)) {
            log.warn("FRTB SBA分解重分配排除异常数值: taskKey={}, positionKey={}, field={}, value={}",
                    taskKey, positionKey, field, value);
            return null;
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) {
            log.warn("FRTB SBA分解重分配排除非有限数值: taskKey={}, positionKey={}, field={}, value={}",
                    taskKey, positionKey, field, value);
            return null;
        }
        return number;
    }

    private static Map<String, Object> buildBatchErrorResult(String errorCode, String errorMessage) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ERROR_CODE", errorCode);
        result.put("ERROR_MESSAGE", errorMessage);
        return result;
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String buildDecompKey(Map<String, Object> values, String sensitivityType) {
        String key = text(values.get("riskFactorBucket")) + "|"
                + text(values.get("riskFactorId")) + "|"
                + text(values.get("riskFactorVertex1")) + "|"
                + text(values.get("riskFactorVertex2"));
        if (useRiskFactorTypeInDecompKey(values, sensitivityType)) {
            key = key + "|" + text(values.get("riskFactorType"));
        }
        return key;
    }

    private static boolean useRiskFactorTypeInDecompKey(Map<String, Object> values, String sensitivityType) {
        if (!FrtbConstants.SENS_CURVATURE.equals(sensitivityType)) {
            return true;
        }
        String riskClass = text(values.get("riskFactorClass"));
        return !FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)
                && !FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
