package com.zcyh.mr.saccr;

import com.zcyh.mr.saccr.addon.AddOnAggregator;
import com.zcyh.mr.saccr.addon.CommodityAddOnCalc;
import com.zcyh.mr.saccr.addon.CreditAddOnCalc;
import com.zcyh.mr.saccr.addon.EquityAddOnCalc;
import com.zcyh.mr.saccr.addon.FxAddOnCalc;
import com.zcyh.mr.saccr.addon.IrAddOnCalc;
import com.zcyh.mr.saccr.delta.DeltaCalc;
import com.zcyh.mr.saccr.model.SaccrNettingSet;
import com.zcyh.mr.saccr.model.SaccrResult;
import com.zcyh.mr.saccr.model.SaccrTrade;
import com.zcyh.mr.saccr.params.SaccrSupervisoryParams;
import com.zcyh.mr.saccr.rc.RcCalc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SA-CCR 主计算引擎。
 *
 * <p>core 只负责 EAD 计量，不读取数据库、不重新估值、不计算资本。
 */
public final class SaccrCalculator {

    private static final Logger log = LoggerFactory.getLogger(SaccrCalculator.class);

    /** 每年工作日（MPOR 年化用，BCBS 279 口径） */
    private static final double TRADING_DAYS_PER_YEAR = 250.0;

    /** 监管折现率（用于 supervisory duration 公式），BCBS 279 固定值 5%。 */
    private static final double SD_RATE = 0.05;

    private SaccrCalculator() {
    }

    /**
     * 批量计算多个净额结算集合的 SA-CCR EAD。
     *
     * @param nettingSets 净额结算集合列表
     * @param dataDate    计算基准日期
     */
    public static List<SaccrResult> calculate(List<SaccrNettingSet> nettingSets, LocalDate dataDate) {
        List<SaccrResult> results = new ArrayList<>();
        for (SaccrNettingSet ns : nettingSets) {
            try {
                results.add(calcNettingSet(ns, dataDate));
            } catch (Exception e) {
                log.error("净额结算集合 {} 计算失败: {}", ns.nettingSetId, e.getMessage(), e);
                throw e;
            }
        }
        return results;
    }

    private static SaccrResult calcNettingSet(SaccrNettingSet ns, LocalDate dataDate) {
        SaccrResult result = new SaccrResult();
        result.nettingMode = requireText(ns.nettingMode, "NETTING_MODE", ns.nettingSetId);
        result.nettingSetId = requireText(ns.nettingSetId, "NETTING_SET_ID", ns.nettingSetId);
        result.counterpartyId = requireText(ns.counterpartyId, "COUNTERPARTY_ID", ns.nettingSetId);
        result.tradeCount = ns.trades == null ? 0 : ns.trades.size();
        result.marginType = requireText(ns.marginType, "MARGIN_TYPE", ns.nettingSetId);
        result.collateralC = ns.collateralC;
        result.thresholdCny = ns.threshold;
        result.mtaCny = ns.mta;
        result.nicaCny = ns.nica;

        int mporDays = normalizeMpor(ns);

        double[] sumMtmHolder = {0.0};
        DiMaps maps = collectDiMaps(ns, dataDate, mporDays, ns.isMargined, sumMtmHolder, true);
        double sumMtm = sumMtmHolder[0];
        result.sumMtm = sumMtm;

        double addonIr = IrAddOnCalc.calc(maps.irBucketMap);
        double addonFx = FxAddOnCalc.calc(maps.fxPairMap);
        double addonCredit = CreditAddOnCalc.calc(maps.creditEntityMap, maps.creditInfoMap);
        double addonEquity = EquityAddOnCalc.calc(maps.equityEntityMap, maps.equityInfoMap);
        double addonCommodity = CommodityAddOnCalc.calc(maps.commBucketTypeMap);
        double addonAggregate = AddOnAggregator.aggregate(addonIr, addonFx, addonCredit,
                addonEquity, addonCommodity);

        result.addonIr = addonIr;
        result.addonFx = addonFx;
        result.addonCredit = addonCredit;
        result.addonEquity = addonEquity;
        result.addonCommodity = addonCommodity;
        result.addonAggregate = addonAggregate;

        double rc = calcRc(ns, sumMtm);
        double multiplier = calcMultiplier(sumMtm, ns.collateralC, addonAggregate);
        double pfe = multiplier * addonAggregate;

        result.rc = rc;
        result.multiplier = multiplier;
        result.pfe = pfe;

        double ead = SaccrSupervisoryParams.ALPHA * (rc + pfe);
        if (ns.isMargined) {
            DiMaps unmMaps = collectDiMaps(ns, dataDate, 0, false, new double[1], false);
            double unmAddonAgg = AddOnAggregator.aggregate(
                    IrAddOnCalc.calc(unmMaps.irBucketMap),
                    FxAddOnCalc.calc(unmMaps.fxPairMap),
                    CreditAddOnCalc.calc(unmMaps.creditEntityMap, unmMaps.creditInfoMap),
                    EquityAddOnCalc.calc(unmMaps.equityEntityMap, unmMaps.equityInfoMap),
                    CommodityAddOnCalc.calc(unmMaps.commBucketTypeMap));
            double unmRc = RcCalc.calcUnmargined(sumMtm, ns.collateralC);
            double unmMultiplier = calcMultiplier(sumMtm, ns.collateralC, unmAddonAgg);
            double unmEad = SaccrSupervisoryParams.ALPHA * (unmRc + unmMultiplier * unmAddonAgg);
            ead = Math.min(ead, unmEad);
        }

        result.ead = ead;
        return result;
    }

    /** 按资产类别分组存放 D_i 的容器 */
    private static class DiMaps {
        final Map<String, Double> irBucketMap = new LinkedHashMap<>();
        final Map<String, Double> fxPairMap = new LinkedHashMap<>();
        final Map<String, Double> creditEntityMap = new LinkedHashMap<>();
        final Map<String, Double> equityEntityMap = new LinkedHashMap<>();
        final Map<String, Double> commBucketTypeMap = new LinkedHashMap<>();
        final Map<String, CreditAddOnCalc.EntityInfo> creditInfoMap = new LinkedHashMap<>();
        final Map<String, EquityAddOnCalc.EquityInfo> equityInfoMap = new LinkedHashMap<>();
    }

    private static DiMaps collectDiMaps(SaccrNettingSet ns, LocalDate dataDate,
                                        int mporDays, boolean isMargined,
                                        double[] sumMtmHolder, boolean writeTradeFactors) {
        DiMaps maps = new DiMaps();
        double sumMtm = 0.0;

        if (ns.trades == null || ns.trades.isEmpty()) {
            throw new IllegalArgumentException("净额集合 " + ns.nettingSetId + " 下没有交易");
        }

        for (SaccrTrade trade : ns.trades) {
            sumMtm += trade.mtmValue;
            double di = calcEffectiveNotional(trade, dataDate, mporDays, isMargined, writeTradeFactors);
            accumulateDi(trade, di, maps, dataDate);
        }

        sumMtmHolder[0] = sumMtm;
        return maps;
    }

    private static double calcEffectiveNotional(SaccrTrade trade, LocalDate dataDate,
                                                int mporDays, boolean isMargined,
                                                boolean writeTradeFactors) {
        double startYears = Math.max(yearFraction(dataDate, trade.startDate), 0.0);
        double endYears = Math.max(yearFraction(dataDate, trade.endDate), 0.0);
        double optionTYears = trade.optionExpiry != null
                ? Math.max(yearFraction(dataDate, trade.optionExpiry), 0.0) : 0.0;

        double supervisoryDuration = needsSupervisoryDuration(trade)
                ? supervisoryDuration(startYears, endYears) : 0.0;
        double adjustedNotional = calcAdjustedNotional(trade, supervisoryDuration);
        double maturityFactor = calcMf(isMargined, mporDays, endYears);
        double delta = DeltaCalc.calc(trade, optionTYears);
        double effectiveNotional = delta * maturityFactor * adjustedNotional;

        if (writeTradeFactors) {
            trade.startYears = startYears;
            trade.endYears = endYears;
            trade.optionTYears = optionTYears;
            trade.supervisoryDuration = supervisoryDuration;
            trade.adjustedNotional = adjustedNotional;
            trade.maturityFactor = maturityFactor;
            trade.delta = delta;
            trade.effectiveNotional = effectiveNotional;
            trade.mporDays = isMargined ? mporDays : 0;
        }
        return effectiveNotional;
    }

    private static boolean needsSupervisoryDuration(SaccrTrade trade) {
        String assetClass = requireText(trade.assetClass, "ASSET_CLASS", trade.tradeId).toUpperCase();
        return "IR".equals(assetClass) || "CREDIT".equals(assetClass);
    }

    private static double calcAdjustedNotional(SaccrTrade trade, double supervisoryDuration) {
        String assetClass = requireText(trade.assetClass, "ASSET_CLASS", trade.tradeId).toUpperCase();
        switch (assetClass) {
            case "IR":
            case "CREDIT":
                return trade.notional * supervisoryDuration;
            case "FX":
                return trade.notional;
            case "EQUITY":
            case "COMMODITY":
                if (trade.underlyingPrice <= 0 || trade.quantity <= 0) {
                    throw new IllegalArgumentException("交易 " + trade.tradeId
                            + " 的 UNDERLYING_PRICE 和 QUANTITY 必须大于 0");
                }
                return trade.underlyingPrice * trade.quantity;
            default:
                throw new IllegalArgumentException("未知资产类别: " + trade.assetClass + "，交易 " + trade.tradeId);
        }
    }

    private static double supervisoryDuration(double startYears, double endYears) {
        double s = Math.max(startYears, 0.0);
        double e = Math.max(endYears, s);
        if (e <= s) {
            return 0.0;
        }
        return (Math.exp(-SD_RATE * s) - Math.exp(-SD_RATE * e)) / SD_RATE;
    }

    private static double calcMf(boolean isMargined, int mporDays, double endYears) {
        if (isMargined) {
            return Math.sqrt(mporDays / TRADING_DAYS_PER_YEAR);
        }
        double mi = Math.min(endYears, 1.0);
        return Math.sqrt(1.5) * Math.sqrt(mi);
    }

    private static void accumulateDi(SaccrTrade trade, double di, DiMaps maps, LocalDate dataDate) {
        String assetClass = requireText(trade.assetClass, "ASSET_CLASS", trade.tradeId).toUpperCase();
        switch (assetClass) {
            case "IR": {
                String currency = requireText(trade.currency, "CURRENCY", trade.tradeId).toUpperCase();
                double endYears = Math.max(yearFraction(dataDate, trade.endDate), 0.0);
                int bucket = IrAddOnCalc.getBucket(endYears);
                String key = IrAddOnCalc.buildKey(currency, bucket);
                maps.irBucketMap.merge(key, di, Double::sum);
                break;
            }
            case "FX": {
                String pair = requireText(trade.currencyPair, "CURRENCY_PAIR", trade.tradeId).toUpperCase();
                maps.fxPairMap.merge(pair, di, Double::sum);
                break;
            }
            case "CREDIT": {
                String entity = requireText(trade.referenceEntity, "REFERENCE_ENTITY", trade.tradeId);
                String creditRating = requireText(trade.creditRating, "CREDIT_RATING", trade.tradeId);
                maps.creditEntityMap.merge(entity, di, Double::sum);
                CreditAddOnCalc.EntityInfo existing = maps.creditInfoMap.get(entity);
                CreditAddOnCalc.EntityInfo current = new CreditAddOnCalc.EntityInfo(creditRating, trade.isIndex);
                if (existing == null) {
                    maps.creditInfoMap.put(entity, current);
                } else if (existing.isIndex != current.isIndex
                        || !Objects.equals(normalizeText(existing.creditQuality), normalizeText(current.creditQuality))) {
                    throw new IllegalArgumentException("信用主体 " + entity + " 的 IS_INDEX 或 CREDIT_RATING 不一致");
                }
                break;
            }
            case "EQUITY": {
                String entity = requireText(trade.referenceEntity, "REFERENCE_ENTITY", trade.tradeId);
                maps.equityEntityMap.merge(entity, di, Double::sum);
                EquityAddOnCalc.EquityInfo existing = maps.equityInfoMap.get(entity);
                EquityAddOnCalc.EquityInfo current = new EquityAddOnCalc.EquityInfo(trade.isIndex);
                if (existing == null) {
                    maps.equityInfoMap.put(entity, current);
                } else if (existing.isIndex != current.isIndex) {
                    throw new IllegalArgumentException("权益主体 " + entity + " 的 IS_INDEX 不一致");
                }
                break;
            }
            case "COMMODITY": {
                String bucket = requireText(trade.commodityBucket, "COMMODITY_BUCKET", trade.tradeId);
                String type = requireText(trade.commodityType, "COMMODITY_TYPE", trade.tradeId);
                String key = CommodityAddOnCalc.buildKey(bucket, type);
                maps.commBucketTypeMap.merge(key, di, Double::sum);
                break;
            }
            default:
                throw new IllegalArgumentException("未知资产类别: " + trade.assetClass + "，交易 " + trade.tradeId);
        }
    }

    private static double calcRc(SaccrNettingSet ns, double sumMtm) {
        String marginType = ns.marginType == null ? "" : ns.marginType.trim().toUpperCase();
        if (!ns.isMargined || "NONE".equals(marginType)) {
            return RcCalc.calcUnmargined(sumMtm, ns.collateralC);
        }
        if ("ONE_WAY_BANK".equals(marginType) || "ONEWAYBANK".equals(marginType)) {
            return RcCalc.calcOneWayBank(sumMtm, ns.collateralC);
        }
        if (!"BILATERAL".equals(marginType)) {
            throw new IllegalArgumentException("未知保证金协议类型: " + ns.marginType
                    + "，净额集合 " + ns.nettingSetId);
        }
        return RcCalc.calcMargined(sumMtm, ns.collateralC, ns.threshold, ns.mta, ns.nica);
    }

    private static double calcMultiplier(double sumMtm, double collateralC, double addonAggregate) {
        if (addonAggregate <= 0) {
            return 1.0;
        }
        double netValue = sumMtm - collateralC;
        double exponent = netValue / (2.0 * 0.95 * addonAggregate);
        double raw = SaccrSupervisoryParams.MULTIPLIER_FLOOR
                + (1.0 - SaccrSupervisoryParams.MULTIPLIER_FLOOR) * Math.exp(exponent);
        return Math.min(1.0, Math.max(SaccrSupervisoryParams.MULTIPLIER_FLOOR, raw));
    }

    private static int normalizeMpor(SaccrNettingSet ns) {
        if (!ns.isMargined) {
            return 0;
        }
        if (ns.mporDays <= 0) {
            throw new IllegalArgumentException("有保证金净额集合必须显式输入 MPOR_DAYS: " + ns.nettingSetId);
        }
        return ns.mporDays;
    }

    private static double yearFraction(LocalDate from, LocalDate to) {
        if (to == null) {
            return 0.0;
        }
        return ChronoUnit.DAYS.between(from, to) / 365.0;
    }

    private static String requireText(String value, String field, String ownerId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填，对象: " + ownerId);
        }
        return value.trim();
    }

    private static String normalizeText(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }
}
