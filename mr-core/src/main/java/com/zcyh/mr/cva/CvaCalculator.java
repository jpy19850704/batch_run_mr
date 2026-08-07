package com.zcyh.mr.cva;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CvaCalculator {

    private CvaCalculator() {
    }

    public static CvaPortfolioResult calculate(List<CvaNettingSet> nettingSets,
                                                List<CvaCounterparty> counterparties,
                                                List<CvaHedge> hedges,
                                                double derivativeNotionalCny) {
        if (derivativeNotionalCny < 0.0) {
            throw new IllegalArgumentException("衍生工具名义本金合计不能小于0");
        }
        Map<String, CvaCounterparty> counterpartyMap = indexCounterparties(counterparties);
        List<CvaNettingSetResult> nettingSetResults = new ArrayList<>();
        Map<String, CvaCounterpartyResult> resultMap = new LinkedHashMap<>();
        for (CvaNettingSet input : requireList(nettingSets, "CVA 净额结算组合")) {
            String nettingSetId = requireText(input.nettingSetId, "NETTING_SET_ID");
            String counterpartyId = requireText(input.counterpartyId, "COUNTERPARTY_ID");
            CvaCounterparty counterparty = counterpartyMap.get(counterpartyId);
            if (counterparty == null) {
                throw new IllegalArgumentException("CVA 缺少交易对手静态信息: " + counterpartyId);
            }
            requirePositive(input.effectiveMaturity, "M_NS", nettingSetId);
            requireNonNegative(input.ead, "EAD_NS", nettingSetId);

            double riskWeight = CvaSupervisoryParams.riskWeight(
                    counterparty.industry, counterparty.creditQuality);
            double discountFactor = discountFactor(input.effectiveMaturity);
            double contribution = riskWeight * input.effectiveMaturity * input.ead
                    * discountFactor / CvaSupervisoryParams.ALPHA;
            CvaNettingSetResult result = new CvaNettingSetResult();
            result.nettingSetId = nettingSetId;
            result.counterpartyId = counterpartyId;
            result.effectiveMaturity = input.effectiveMaturity;
            result.ead = input.ead;
            result.riskWeight = riskWeight;
            result.discountFactor = discountFactor;
            result.scvaContribution = contribution;
            nettingSetResults.add(result);

            CvaCounterpartyResult counterpartyResult = resultMap.computeIfAbsent(
                    counterpartyId, key -> {
                        CvaCounterpartyResult value = new CvaCounterpartyResult();
                        value.counterpartyId = key;
                        return value;
                    });
            counterpartyResult.scva += contribution;
        }

        List<CvaHedgeResult> hedgeResults = new ArrayList<>();
        double indexHedge = 0.0;
        if (hedges == null) {
            throw new IllegalArgumentException("CVA 对冲不能为空");
        }
        for (CvaHedge hedge : hedges) {
            CvaHedgeResult result = calculateHedge(hedge, counterpartyMap);
            hedgeResults.add(result);
            indexHedge += result.indexHedge;
            if (result.counterpartyId != null) {
                CvaCounterpartyResult counterpartyResult = resultMap.get(result.counterpartyId);
                if (counterpartyResult == null) {
                    throw new IllegalArgumentException("单名对冲对应的交易对手不在CVA计量范围: "
                            + result.counterpartyId);
                }
                counterpartyResult.singleNameHedge += result.singleNameHedge;
                counterpartyResult.hedgingMisalignment += result.hedgingMisalignment;
            }
        }

        List<CvaCounterpartyResult> counterpartyResults = new ArrayList<>(resultMap.values());
        double kReduced = reducedCapital(counterpartyResults);
        boolean belowThreshold = derivativeNotionalCny
                <= CvaSupervisoryParams.SIMPLIFIED_NOTIONAL_THRESHOLD_CNY;
        boolean fullMode = !belowThreshold && !hedgeResults.isEmpty();
        double kHedged = fullMode ? hedgedCapital(counterpartyResults, indexHedge) : kReduced;
        double kFull = fullMode
                ? CvaSupervisoryParams.FULL_REDUCED_WEIGHT * kReduced
                + CvaSupervisoryParams.FULL_HEDGED_WEIGHT * kHedged
                : kReduced;

        CvaPortfolioResult result = new CvaPortfolioResult();
        result.calculationMode = fullMode ? "FULL" : "REDUCED";
        result.reductionReason = fullMode
                ? "FULL_BA_CVA"
                : belowThreshold ? "NOTIONAL_THRESHOLD" : "NO_ELIGIBLE_HEDGE";
        result.derivativeNotionalCny = derivativeNotionalCny;
        result.kReduced = kReduced;
        result.kHedged = kHedged;
        result.kFull = kFull;
        result.cvaCapitalRequirement = kFull;
        result.cvaRiskWeightedAssets = CvaSupervisoryParams.DISCOUNT_SCALAR * 12.5 * kFull;
        result.indexHedge = indexHedge;
        result.counterparties = counterpartyResults;
        result.nettingSets = nettingSetResults;
        result.hedges = hedgeResults;
        return result;
    }

    private static CvaHedgeResult calculateHedge(CvaHedge hedge,
                                                 Map<String, CvaCounterparty> counterpartyMap) {
        String hedgeId = requireText(hedge.hedgeId, "HEDGE_ID");
        String hedgeType = requireText(hedge.hedgeType, "HEDGE_TYPE").toUpperCase();
        requirePositive(hedge.remainingMaturity, "对冲剩余期限", hedgeId);
        requirePositive(hedge.notional, "对冲名义本金", hedgeId);
        double discountFactor = discountFactor(hedge.remainingMaturity);
        CvaHedgeResult result = new CvaHedgeResult();
        result.hedgeId = hedgeId;
        result.hedgeType = hedgeType;
        result.remainingMaturity = hedge.remainingMaturity;
        result.notional = hedge.notional;
        result.discountFactor = discountFactor;
        if ("SINGLE_NAME_CDS".equals(hedgeType)
                || "SINGLE_NAME_CONTINGENT_CDS".equals(hedgeType)) {
            String counterpartyId = requireText(hedge.counterpartyId, "单名对冲交易对手");
            CvaCounterparty counterparty = counterpartyMap.get(counterpartyId);
            if (counterparty == null) {
                throw new IllegalArgumentException("单名对冲交易对手未纳入CVA范围: " + counterpartyId);
            }
            String referenceIndustry = requireText(hedge.referenceIndustry, "对冲参考主体行业");
            String referenceQuality = requireText(hedge.referenceCreditQuality, "对冲参考主体信用水平");
            double riskWeight = CvaSupervisoryParams.riskWeight(referenceIndustry, referenceQuality);
            double correlation = CvaSupervisoryParams.hedgeCorrelation(hedge.relationType);
            if ("DIRECT".equalsIgnoreCase(hedge.relationType)
                    && (!referenceIndustry.equalsIgnoreCase(counterparty.industry)
                    || !referenceQuality.equalsIgnoreCase(counterparty.creditQuality))) {
                throw new IllegalArgumentException("直接单名对冲的参考主体静态信息必须与交易对手一致: " + hedgeId);
            }
            double base = riskWeight * hedge.remainingMaturity * hedge.notional * discountFactor;
            result.counterpartyId = counterpartyId;
            result.riskWeight = riskWeight;
            result.correlation = correlation;
            result.singleNameHedge = correlation * base;
            result.hedgingMisalignment = (1.0 - correlation * correlation) * base * base;
            return result;
        }
        if ("INDEX_CDS".equals(hedgeType)) {
            requirePositive(hedge.indexBaseRiskWeight, "指数对冲基础风险权重", hedgeId);
            result.riskWeight = hedge.indexDiversified
                    ? hedge.indexBaseRiskWeight * CvaSupervisoryParams.INDEX_DIVERSIFICATION_FACTOR
                    : hedge.indexBaseRiskWeight;
            result.indexHedge = result.riskWeight * hedge.remainingMaturity
                    * hedge.notional * discountFactor;
            return result;
        }
        throw new IllegalArgumentException("CVA 对冲类型不支持: " + hedge.hedgeType);
    }

    private static double reducedCapital(List<CvaCounterpartyResult> results) {
        double sum = 0.0;
        double sumSquares = 0.0;
        for (CvaCounterpartyResult result : results) {
            sum += result.scva;
            sumSquares += result.scva * result.scva;
        }
        return Math.sqrt(Math.pow(CvaSupervisoryParams.RHO * sum, 2.0)
                + (1.0 - Math.pow(CvaSupervisoryParams.RHO, 2.0)) * sumSquares);
    }

    private static double hedgedCapital(List<CvaCounterpartyResult> results, double indexHedge) {
        double netSystematic = 0.0;
        double idiosyncratic = 0.0;
        double misalignment = 0.0;
        for (CvaCounterpartyResult result : results) {
            double net = result.scva - result.singleNameHedge;
            netSystematic += net;
            idiosyncratic += net * net;
            misalignment += result.hedgingMisalignment;
        }
        double systematic = CvaSupervisoryParams.RHO * netSystematic - indexHedge;
        return Math.sqrt(systematic * systematic
                + (1.0 - Math.pow(CvaSupervisoryParams.RHO, 2.0)) * idiosyncratic
                + misalignment);
    }

    private static double discountFactor(double maturity) {
        double denominator = CvaSupervisoryParams.DISCOUNT_RATE * maturity;
        return (1.0 - Math.exp(-denominator)) / denominator;
    }

    private static Map<String, CvaCounterparty> indexCounterparties(List<CvaCounterparty> values) {
        Map<String, CvaCounterparty> result = new LinkedHashMap<>();
        for (CvaCounterparty value : requireList(values, "CVA 交易对手")) {
            String id = requireText(value.counterpartyId, "COUNTERPARTY_ID");
            if (result.put(id, value) != null) {
                throw new IllegalArgumentException("CVA 交易对手重复: " + id);
            }
        }
        return result;
    }

    private static <T> List<T> requireList(List<T> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return values;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private static void requirePositive(double value, String field, String owner) {
        if (!(value > 0.0)) {
            throw new IllegalArgumentException(field + "必须大于0: " + owner);
        }
    }

    private static void requireNonNegative(double value, String field, String owner) {
        if (value < 0.0) {
            throw new IllegalArgumentException(field + "不能小于0: " + owner);
        }
    }
}
