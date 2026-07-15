package com.zcyh.mr.frtbsa.sba.core;

import com.alibaba.fastjson2.JSON;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.core.cmty.CmtyModule;
import com.zcyh.mr.frtbsa.sba.core.csrnc.CsrncModule;
import com.zcyh.mr.frtbsa.sba.core.csrns.CsrnsModule;
import com.zcyh.mr.frtbsa.sba.core.csrctp.CsrctpModule;
import com.zcyh.mr.frtbsa.sba.core.eq.EqModule;
import com.zcyh.mr.frtbsa.sba.core.fx.FxModule;
import com.zcyh.mr.frtbsa.sba.core.girr.GirrModule;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FRTB SBA单组资本聚合器。
 */
public class FrtbAggregator {
    private final EqModule eqModule = new EqModule();
    private final GirrModule girrModule = new GirrModule();
    private final CsrnsModule csrnsModule = new CsrnsModule();
    private final CsrncModule csrncModule = new CsrncModule();
    private final FxModule fxModule = new FxModule();
    private final CmtyModule cmtyModule = new CmtyModule();
    private final CsrctpModule csrctpModule = new CsrctpModule();
    private final FrtbInputValidator inputValidator = new FrtbInputValidator();
    private final FrtbAllRiskClassAssembler allRiskClassAssembler = new FrtbAllRiskClassAssembler();

    public String calculate(List<FrtbInput> rawList) {
        return calculate(rawList, true);
    }

    public String calculate(List<FrtbInput> rawList, Boolean needDecompose) {
        return JSON.toJSONString(calculateAsMap(rawList, needDecompose));
    }

    public Map<String, Object> calculateAsMap(List<FrtbInput> rawList, Boolean needDecompose) {
        Map<String, Object> resultMap = calculateRiskClassMap(rawList, needDecompose);
        allRiskClassAssembler.append(resultMap);
        return resultMap;
    }

    Map<String, Object> calculateRiskClassMap(List<FrtbInput> rawList, Boolean needDecompose) {
        Map<String, Object> checkResult = inputValidator.validate(rawList);
        List<FrtbInput> validData = castFrtbList(checkResult.get("checked"));
        List<FrtbInput> errorData = castFrtbList(checkResult.get("errors"));
        List<Map<String, Object>> errorDetails = castMapList(checkResult.get("errorDetails"));
        Map<String, List<FrtbInput>> groupedByClass = validData.stream()
                .collect(Collectors.groupingBy(FrtbInput::getRiskFactorClass));
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();

        calculateRiskClass(groupedByClass, resultMap, FrtbConstants.RISK_CLASS_GIRR,
                values -> girrModule.calc(values, needDecompose));
        calculateRiskClass(groupedByClass, resultMap, FrtbConstants.RISK_CLASS_CSRNS,
                values -> csrnsModule.calc(values, needDecompose));
        calculateRiskClass(groupedByClass, resultMap, FrtbConstants.RISK_CLASS_CSRNC,
                values -> csrncModule.calc(values, needDecompose));
        calculateRiskClass(groupedByClass, resultMap, FrtbConstants.RISK_CLASS_EQ,
                values -> eqModule.calc(values, needDecompose));
        calculateRiskClass(groupedByClass, resultMap, FrtbConstants.RISK_CLASS_FX,
                values -> fxModule.calc(values, needDecompose));
        calculateRiskClass(groupedByClass, resultMap, FrtbConstants.RISK_CLASS_CMTY,
                values -> cmtyModule.calc(values, needDecompose));
        calculateRiskClass(groupedByClass, resultMap, FrtbConstants.RISK_CLASS_CSRCTP,
                values -> csrctpModule.calc(values, needDecompose));

        if (!errorData.isEmpty()) {
            resultMap.put("ERROR_COUNT", errorData.size());
            resultMap.put("ERRORS", errorDetails);
        }
        return resultMap;
    }

    private static void calculateRiskClass(
            Map<String, List<FrtbInput>> groupedByClass,
            Map<String, Object> resultMap,
            String riskClass,
            RiskClassCalculator calculator) {
        List<FrtbInput> values = groupedByClass.get(riskClass);
        if (values != null) {
            resultMap.put(riskClass, calculator.calculate(values));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<FrtbInput> castFrtbList(Object value) {
        if (value instanceof List) {
            return (List<FrtbInput>) value;
        }
        return new ArrayList<FrtbInput>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return new ArrayList<Map<String, Object>>();
    }

    @FunctionalInterface
    private interface RiskClassCalculator {
        Object calculate(List<FrtbInput> values);
    }
}
