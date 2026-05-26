package com.zcyh.mr.saccr;

import com.zcyh.mr.saccr.addon.*;
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
import java.util.*;

/**
 * SA-CCR 主计算引擎（五层编排入口）。
 *
 * <p>计量层次（自下而上）：
 * <ol>
 *   <li>逐笔计算有效名义本金 D_i = δ_i × Mf_i × d_i</li>
 *   <li>各资产类别 AddOn 聚合</li>
 *   <li>RC 和 PFE = multiplier × AddOn_aggregate</li>
 *   <li>EAD = α × (RC + PFE)，α = 1.4；有保证金时加盖无保证金 EAD 上限</li>
 *   <li>资本 = EAD × RW × 8%（可选）</li>
 * </ol>
 */
public final class SaccrCalculator {

    private static final Logger log = LoggerFactory.getLogger(SaccrCalculator.class);

    /** 每年工作日（MPOR 年化用，BCBS 279 口径） */
    private static final double TRADING_DAYS_PER_YEAR = 250.0;

    /**
     * 监管折现率（用于 supervisory duration 公式），BCBS 279 固定值 5%。
     * SD_i = (exp(-0.05×S_i) − exp(-0.05×E_i)) / 0.05
     */
    private static final double SD_RATE = 0.05;

    private SaccrCalculator() {
    }

    // ==================== 公开入口 ====================

    /**
     * 批量计算多个净额结算集合的 SA-CCR EAD。
     *
     * @param nettingSets 净额结算集合列表
     * @param dataDate    计算基准日期
     * @param calcCapital 是否计算第五层资本
     */
    public static List<SaccrResult> calculate(List<SaccrNettingSet> nettingSets,
                                              LocalDate dataDate,
                                              boolean calcCapital) {
        List<SaccrResult> results = new ArrayList<>();
        for (SaccrNettingSet ns : nettingSets) {
            try {
                results.add(calcNettingSet(ns, dataDate, calcCapital));
            } catch (Exception e) {
                log.error("净额结算集合 {} 计算失败: {}", ns.nettingSetId, e.getMessage(), e);
                throw e;
            }
        }
        return results;
    }

    // ==================== 单个净额结算集合 ====================

    private static SaccrResult calcNettingSet(SaccrNettingSet ns, LocalDate dataDate,
                                               boolean calcCapital) {
        SaccrResult result = new SaccrResult();
        result.nettingSetId = ns.nettingSetId;
        result.counterpartyId = ns.counterpartyId;
        result.collateralC = ns.collateralC;

        // ---- 确定 MPOR ----
        int mporDays = resolveMpor(ns);

        // ---- 第一层：逐笔计算 D_i，收集分组 Map + ΣV_i ----
        double[] sumMtmHolder = {0.0};
        DiMaps maps = collectDiMaps(ns, dataDate, mporDays, ns.isMargined, sumMtmHolder);
        double sumMtm = sumMtmHolder[0];
        result.sumMtm = sumMtm;

        // ---- 第二层：各资产类别 AddOn ----
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

        // ---- 第三层：RC + multiplier + PFE ----
        double rc = calcRc(ns, sumMtm);
        double multiplier = calcMultiplier(sumMtm, ns.collateralC, addonAggregate);
        double pfe = multiplier * addonAggregate;

        result.rc = rc;
        result.multiplier = multiplier;
        result.pfe = pfe;

        // ---- 第四层：EAD ----
        double ead = SaccrSupervisoryParams.ALPHA * (rc + pfe);

        // ---- 有保证金 EAD 上限：EAD_margined ≤ EAD_unmargined（BCBS 279 para 186）----
        if (ns.isMargined) {
            // 用无保证金 MF 重新计算 D_i（mporDays 无效，isMargined=false）
            DiMaps unmMaps = collectDiMaps(ns, dataDate, 0, false, new double[1]);
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

        // ---- 第五层：资本（可选）----
        if (calcCapital) {
            double rw = resolveRiskWeight(ns);
            double rwaCcr = ead * rw;
            result.riskWeight = rw;
            result.rwaCcr = rwaCcr;
            result.capitalCcr = rwaCcr * 0.08;
            result.capitalCalculated = true;
        }

        return result;
    }

    // ==================== D_i 收集（内部数据结构）====================

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

    /**
     * 遍历净额结算集合内的所有交易，计算各笔 D_i 并分类累加。
     *
     * @param sumMtmHolder 长度为 1 的数组，用于回传 ΣV_i
     */
    private static DiMaps collectDiMaps(SaccrNettingSet ns, LocalDate dataDate,
                                         int mporDays, boolean isMargined,
                                         double[] sumMtmHolder) {
        DiMaps maps = new DiMaps();
        double sumMtm = 0.0;

        for (SaccrTrade trade : ns.trades) {
            sumMtm += trade.mtmValue;
            double di = calcEffectiveNotional(trade, dataDate, mporDays, isMargined);
            accumulateDi(trade, di, maps, dataDate);
        }

        sumMtmHolder[0] = sumMtm;
        return maps;
    }

    // ==================== 第一层：逐笔计算 ====================

    private static double calcEffectiveNotional(SaccrTrade trade, LocalDate dataDate,
                                                 int mporDays, boolean isMargined) {
        double startYears = Math.max(yearFraction(dataDate, trade.startDate), 0.0);
        double endYears = Math.max(yearFraction(dataDate, trade.endDate), 0.0);
        double optionTYears = trade.optionExpiry != null
                ? Math.max(yearFraction(dataDate, trade.optionExpiry), 0.0) : 0.0;

        double di = calcAdjustedNotional(trade, startYears, endYears);
        double mf = calcMf(isMargined, mporDays, endYears);
        double delta = DeltaCalc.calc(trade, optionTYears);

        return delta * mf * di;
    }

    /**
     * 步骤 2：调整后名义本金 d_i。
     *
     * <p>IR / Credit：使用监管久期（Supervisory Duration）：
     * <pre>SD_i = (exp(-0.05×S_i) − exp(-0.05×E_i)) / 0.05</pre>
     * 其中 5% 为 BCBS 279 规定的代理折现率，固定不估算。
     */
    private static double calcAdjustedNotional(SaccrTrade trade, double startYears,
                                                double endYears) {
        switch (trade.assetClass.toUpperCase()) {
            case "IR":
                return trade.notional * supervisoryDuration(startYears, endYears);
            case "CREDIT":
                return trade.notional * supervisoryDuration(startYears, endYears);
            case "FX":
                return trade.notional;
            case "EQUITY":
                return (trade.underlyingPrice > 0 && trade.quantity > 0)
                        ? trade.underlyingPrice * trade.quantity : trade.notional;
            case "COMMODITY":
                return (trade.underlyingPrice > 0 && trade.quantity > 0)
                        ? trade.underlyingPrice * trade.quantity : trade.notional;
            default:
                throw new IllegalArgumentException("未知资产类别: " + trade.assetClass
                        + "，交易 " + trade.tradeId);
        }
    }

    /**
     * BCBS 279 监管久期公式（Supervisory Duration）。
     *
     * <p>SD = (exp(-0.05×S) − exp(-0.05×E)) / 0.05
     * <p>E = S 时 SD = 0（已到期或零期限）。
     */
    private static double supervisoryDuration(double startYears, double endYears) {
        double s = Math.max(startYears, 0.0);
        double e = Math.max(endYears, s);       // E ≥ S
        if (e <= s) return 0.0;
        return (Math.exp(-SD_RATE * s) - Math.exp(-SD_RATE * e)) / SD_RATE;
    }

    /**
     * 步骤 3：期限调整因子 Mf_i。
     *
     * <p><b>有保证金（Margined）：</b>
     * <pre>Mf = sqrt(MPOR_days / 250)</pre>
     * MPOR 下限由 {@link #resolveMpor} 强制执行（双边≥10天，清算≥5天）。
     *
     * <p><b>无保证金（Unmargined）：</b>
     * <pre>Mf_i = sqrt(3/2) × sqrt(min(E_i, 1年))</pre>
     * 其中 sqrt(3/2) ≈ 1.2247 为线性敞口路径修正因子。
     */
    private static double calcMf(boolean isMargined, int mporDays, double endYears) {
        if (isMargined) {
            // MPOR 下限已在 resolveMpor 强制；此处直接使用
            return Math.sqrt(mporDays / TRADING_DAYS_PER_YEAR);
        }
        double mi = Math.min(endYears, 1.0);
        return Math.sqrt(1.5) * Math.sqrt(mi);
    }

    /** 将单笔 D_i 按资产类别分流到对应的分组 Map */
    private static void accumulateDi(SaccrTrade trade, double di, DiMaps maps,
                                      LocalDate dataDate) {
        switch (trade.assetClass.toUpperCase()) {
            case "IR": {
                double endYears = Math.max(yearFraction(dataDate, trade.endDate), 0.0);
                int bucket = IrAddOnCalc.getBucket(endYears);
                String key = IrAddOnCalc.buildKey(trade.currency, bucket);
                maps.irBucketMap.merge(key, di, Double::sum);
                break;
            }
            case "FX": {
                String pair = normalizeCcyPair(trade.currencyPair, trade.currency);
                maps.fxPairMap.merge(pair, di, Double::sum);
                break;
            }
            case "CREDIT": {
                String entity = trade.referenceEntity != null ? trade.referenceEntity : trade.tradeId;
                maps.creditEntityMap.merge(entity, di, Double::sum);
                // 信用参数入口统一按主体维度记录：单一主体传评级，指数传 IG/SG。
                CreditAddOnCalc.EntityInfo existing = maps.creditInfoMap.get(entity);
                if (existing == null) {
                    maps.creditInfoMap.put(entity,
                            new CreditAddOnCalc.EntityInfo(trade.creditRating, trade.isIndex));
                } else {
                    String oldQ = normalizeCreditQuality(existing.creditQuality);
                    String newQ = normalizeCreditQuality(trade.creditRating);
                    if (existing.isIndex != trade.isIndex || !Objects.equals(oldQ, newQ)) {
                        log.warn("信用主体 {} 输入属性不一致，已保留首笔定义 [isIndex={}, creditQuality={}]，"
                                        + "忽略交易 {} 的后续定义 [isIndex={}, creditQuality={}]",
                                entity, existing.isIndex, existing.creditQuality,
                                trade.tradeId, trade.isIndex, trade.creditRating);
                    }
                }
                break;
            }
            case "EQUITY": {
                String entity = trade.referenceEntity != null ? trade.referenceEntity : trade.tradeId;
                maps.equityEntityMap.merge(entity, di, Double::sum);
                maps.equityInfoMap.putIfAbsent(entity,
                        new EquityAddOnCalc.EquityInfo(trade.isIndex));
                break;
            }
            case "COMMODITY": {
                String bucket = trade.commodityBucket != null ? trade.commodityBucket : "Other";
                String type = trade.commodityType != null ? trade.commodityType : "default";
                String key = CommodityAddOnCalc.buildKey(bucket, type);
                maps.commBucketTypeMap.merge(key, di, Double::sum);
                break;
            }
            default:
                log.warn("跳过未知资产类别交易: {}", trade.tradeId);
        }
    }

    // ==================== 第三层辅助 ====================

    private static double calcRc(SaccrNettingSet ns, double sumMtm) {
        String marginType = ns.marginType;
        if (!ns.isMargined || marginType == null || marginType.equalsIgnoreCase("None")) {
            return RcCalc.calcUnmargined(sumMtm, ns.collateralC);
        }
        if (marginType.equalsIgnoreCase("OneWayBank")) {
            return RcCalc.calcOneWayBank(sumMtm, ns.collateralC);
        }
        return RcCalc.calcMargined(sumMtm, ns.collateralC, ns.threshold, ns.mta, ns.nica);
    }

    /**
     * 计算乘数 multiplier。
     *
     * <p>公式：multiplier = min(1, 0.05 + 0.95 × exp((ΣV−C) / (1.9 × AddOn)))
     * <p>下限 0.05（监管强制，不可低于此值）。
     * <p>AddOn = 0 时直接返回 1.0，避免除零。
     */
    private static double calcMultiplier(double sumMtm, double collateralC,
                                          double addonAggregate) {
        if (addonAggregate <= 0) return 1.0;
        double netValue = sumMtm - collateralC;
        double exponent = netValue / (2.0 * 0.95 * addonAggregate);
        double raw = SaccrSupervisoryParams.MULTIPLIER_FLOOR
                + (1.0 - SaccrSupervisoryParams.MULTIPLIER_FLOOR) * Math.exp(exponent);
        return Math.min(1.0, Math.max(SaccrSupervisoryParams.MULTIPLIER_FLOOR, raw));
    }

    // ==================== 第五层：风险权重查询 ====================

    private static double resolveRiskWeight(SaccrNettingSet ns) {
        if (ns.isQccp) return 0.02;
        if (!ns.isQccp && ns.isCleared) return 1.50;

        String type = ns.counterpartyType != null ? ns.counterpartyType.toUpperCase() : "CORPORATE";
        String rating = ns.counterpartyRating != null ? ns.counterpartyRating.toUpperCase() : "BBB+";

        switch (type) {
            case "SOVEREIGN":
                if (isAaaToAaMinus(rating)) return 0.00;
                if (isAPlusToAMinus(rating)) return 0.20;
                return 0.50;
            case "BANK":
                if (isAaaToAaMinus(rating)) return 0.20;
                if (isBbbPlusToBbMinus(rating)) return 0.50;
                return 1.50;
            case "QCCP":
                return 0.02;
            default: // CORPORATE
                if (isAaaToAaMinus(rating)) return 0.20;
                if (isBbbPlusToBbMinus(rating)) return 1.00;
                return 1.50;
        }
    }

    // ==================== MPOR 推算 ====================

    /**
     * 确定净额结算集合适用的 MPOR（工作日）。
     *
     * <p>优先级：自定义值 → 规则推算。
     * <p>无论来源，最终均执行监管下限：
     * <ul>
     *   <li>集中清算：≥ 5 天</li>
     *   <li>双边（非清算）：≥ 10 天</li>
     *   <li>双边，大型组合（交易笔数 ≥ 5,000）：≥ 20 天</li>
     *   <li>存在争议历史（非集中清算）：以上下限基数 × 2</li>
     * </ul>
     */
    private static int resolveMpor(SaccrNettingSet ns) {
        int base;
        if (ns.mporDays > 0) {
            base = ns.mporDays;
        } else if (ns.isCleared) {
            base = SaccrSupervisoryParams.MPOR_CLEARED_DAYS;
        } else {
            base = SaccrSupervisoryParams.MPOR_BILATERAL_DAYS;
        }

        // 大型组合加码（仅双边）
        if (!ns.isCleared && ns.trades != null
                && ns.trades.size() >= SaccrSupervisoryParams.LARGE_PORTFOLIO_THRESHOLD) {
            base = Math.max(base, SaccrSupervisoryParams.MPOR_LARGE_PORTFOLIO_DAYS);
        }

        // 争议历史翻倍（仅双边）
        if (ns.hasDisputeHistory && !ns.isCleared) {
            base *= 2;
        }

        // 强制下限
        int floor = ns.isCleared
                ? SaccrSupervisoryParams.MPOR_CLEARED_DAYS
                : SaccrSupervisoryParams.MPOR_BILATERAL_DAYS;
        return Math.max(base, floor);
    }

    // ==================== 工具方法 ====================

    /** 日期差转年数（ACT/365）。to 为 null 时返回 0。 */
    private static double yearFraction(LocalDate from, LocalDate to) {
        if (to == null) return 0.0;
        return ChronoUnit.DAYS.between(from, to) / 365.0;
    }

    /** 标准化货币对 key（大写，缺失时用单货币代理） */
    private static String normalizeCcyPair(String currencyPair, String currency) {
        if (currencyPair != null && !currencyPair.isBlank()) {
            return currencyPair.toUpperCase();
        }
        return (currency != null ? currency.toUpperCase() : "UNKNOWN") + "/RPT";
    }

    /** 归一化信用质量文本，便于比较同一主体多笔交易的输入一致性。 */
    private static String normalizeCreditQuality(String quality) {
        if (quality == null) return null;
        String txt = quality.trim().toUpperCase();
        return txt.isEmpty() ? null : txt;
    }

    // 交易对手风险权重评级分档
    private static boolean isAaaToAaMinus(String r) {
        return r.startsWith("AAA") || r.startsWith("AA");
    }

    private static boolean isAPlusToAMinus(String r) {
        return r.equals("A+") || r.equals("A") || r.equals("A-");
    }

    private static boolean isBbbPlusToBbMinus(String r) {
        return r.startsWith("BBB") || r.startsWith("BB");
    }
}
