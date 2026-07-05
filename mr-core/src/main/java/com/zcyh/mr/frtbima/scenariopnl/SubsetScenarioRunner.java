package com.zcyh.mr.frtbima.scenariopnl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.scenario.ScenarioCache;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.core.Convert;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import com.zcyh.mr.frtbima.rfet.bucket.RfetModellableIndex;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IMA 可建模因子情景损益计算器。
 *
 * <p>职责：对每个 LH 子集 j（10/20/40/60/120天），将原始情景的市场数据冲击
 * 裁剪至 Q(P,j) 因子集后重定价，通过 Calc decomp 机制同时输出
 * IR/CS/FX/EQ/COMM/ALL 六类风险 PnL。
 *
 * <p>filterToSubset 核心规则（MAR33.4）：
 * <ul>
 *   <li>以 base market data 为底，完整深拷贝后选择性覆盖。
 *   <li>Q(P,j) 定义：LH(factor) &gt;= LH_j 的因子集（MAR33.4）。
 *       lhDays=10 包含全部因子（最宽），lhDays=120 仅含 LH=120 因子（最窄）。
 *   <li>对每个 (curveType, curveId, tenorDays)，仅当同时满足：
 *       (1) modellableIndex.isModellable(curveType, curveId, tenorDays) 为 true（RFET 可建模，
 *           以桶的期限范围 [tenorMin,tenorMax] 判断，非点集合匹配），
 *       (2) lhTable.getLhDays(curveType, curveId) &gt;= lhDays（因子 LH 满足 Q(P,j) 包含条件），
 *       才用情景值覆盖基准值；否则保留基准值。
 * </ul>
 *
 * <p>结果写入 TB_OUT_IMA_MODELLABLE_SCENARIO_PNL。
 */
public class SubsetScenarioRunner {
    private static final Logger log = LoggerFactory.getLogger(SubsetScenarioRunner.class);
    private static final String[] IMA_RISK_CLASSES = {"GIRR", "CSR", "FX", "EQ", "COMM", "ALL"};

    private final LiquidityHorizonTable lhTable;

    public SubsetScenarioRunner(LiquidityHorizonTable lhTable) {
        this.lhTable = lhTable;
    }

    public List<Loader.ScenarioEntry> buildModellableScenarioEntries(List<Loader.ScenarioEntry> scenarioEntries,
                                                                     String processType,
                                                                     String lhTagName,
                                                                     String riskClassTagName,
                                                                     String entryKeyPrefix) {
        List<Loader.ScenarioEntry> result = new ArrayList<>();
        if (scenarioEntries == null || scenarioEntries.isEmpty()) {
            return result;
        }
        Set<String> missingConfigLogged = new LinkedHashSet<>();
        for (int entryIndex = 0; entryIndex < scenarioEntries.size(); entryIndex++) {
            Loader.ScenarioEntry raw = scenarioEntries.get(entryIndex);
            if (raw == null) {
                continue;
            }
            for (int lhDays : ImaConstants.LH_DAYS_ARRAY) {
                String scenarioEntryKey = entryKeyPrefix + ":" + entryIndex + ":LH" + lhDays;
                for (String imaRiskClass : IMA_RISK_CLASSES) {
                    SubsetBuildResult subset = buildImaSubset(raw, lhDays, imaRiskClass, missingConfigLogged);
                    JSONObject tag = new JSONObject();
                    tag.put(lhTagName, lhDays);
                    tag.put(riskClassTagName, imaRiskClass);
                    Loader.ScenarioEntry entry = new Loader.ScenarioEntry(
                            raw == null ? null : raw.scenarioId,
                            raw == null ? null : raw.subScenarioId,
                            raw == null ? null : raw.scenarioName,
                            raw == null ? null : raw.scenarioType,
                            processType,
                            tag,
                            scenarioEntryKey,
                            subset.marketData,
                            subset.impactKeys);
                    result.add(entry);
                }
            }
        }
        return result;
    }

    private SubsetBuildResult buildImaSubset(Loader.ScenarioEntry entry,
                                             int lhDays,
                                             String imaRiskClass,
                                             Set<String> missingConfigLogged) {
        SubsetBuildResult result = new SubsetBuildResult();
        MarketData scenMD = entry == null ? null : entry.marketData;
        if (scenMD == null) {
            return result;
        }
        copyIrSpotSubset(result, scenMD.irSpot, lhDays, imaRiskClass, missingConfigLogged);
        copySeriesSpotSubset(result.marketData.eqSpot, result.impactKeys, scenMD.eqSpot,
                lhDays, imaRiskClass, missingConfigLogged, ImaConstants.RF_TYPE_EQ_SPOT);
        copySeriesSpotSubset(result.marketData.commSpot, result.impactKeys, scenMD.commSpot,
                lhDays, imaRiskClass, missingConfigLogged, ImaConstants.RF_TYPE_COMM_SPOT);
        copyFxSpotSubset(result, scenMD.fxSpot, lhDays, imaRiskClass, missingConfigLogged);
        copyVolSubset(result.marketData.irVol, result.impactKeys, scenMD.irVol,
                lhDays, imaRiskClass, missingConfigLogged, ImaConstants.RF_TYPE_IR_VOL);
        copyVolSubset(result.marketData.eqVol, result.impactKeys, scenMD.eqVol,
                lhDays, imaRiskClass, missingConfigLogged, ImaConstants.RF_TYPE_EQ_VOL);
        copyVolSubset(result.marketData.fxVol, result.impactKeys, scenMD.fxVol,
                lhDays, imaRiskClass, missingConfigLogged, ImaConstants.RF_TYPE_FX_VOL);
        copyVolSubset(result.marketData.commVol, result.impactKeys, scenMD.commVol,
                lhDays, imaRiskClass, missingConfigLogged, ImaConstants.RF_TYPE_COMM_VOL);
        return result;
    }

    private void copyIrSpotSubset(SubsetBuildResult result,
                                  HashMap<String, IrSpot.IrSpotInfo> scenIrSpot,
                                  int lhDays,
                                  String imaRiskClass,
                                  Set<String> missingConfigLogged) {
        if (scenIrSpot == null) {
            return;
        }
        for (Map.Entry<String, IrSpot.IrSpotInfo> e : scenIrSpot.entrySet()) {
            IrSpot.IrSpotInfo info = e.getValue();
            String curveType = info == null ? null : info.curveType;
            String curveId = e.getKey();
            if (!includeImaCurve(curveType, curveId, lhDays, imaRiskClass, missingConfigLogged)) {
                continue;
            }
            result.marketData.irSpot.put(curveId, copyIrSpotInfo(info));
            addImpactKey(result.impactKeys, curveType, curveId);
        }
    }

    private <T> void copySeriesSpotSubset(HashMap<String, T> target,
                                          Set<String> impactKeys,
                                          HashMap<String, T> source,
                                          int lhDays,
                                          String imaRiskClass,
                                          Set<String> missingConfigLogged,
                                          String curveType) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, T> e : source.entrySet()) {
            String curveId = e.getKey();
            if (!includeImaCurve(curveType, curveId, lhDays, imaRiskClass, missingConfigLogged)) {
                continue;
            }
            T copied = copySeriesSpotInfo(e.getValue());
            if (copied != null) {
                target.put(curveId, copied);
                addImpactKey(impactKeys, curveType, curveId);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T copySeriesSpotInfo(T source) {
        if (source instanceof EqSpot.EqSpotInfo) {
            return (T) copyEqSpotInfo((EqSpot.EqSpotInfo) source);
        }
        if (source instanceof CommSpot.CommSpotInfo) {
            return (T) copyCommSpotInfo((CommSpot.CommSpotInfo) source);
        }
        return null;
    }

    private void copyFxSpotSubset(SubsetBuildResult result,
                                  com.zcyh.mr.marketdata.FxSpot.FxSpotInfo source,
                                  int lhDays,
                                  String imaRiskClass,
                                  Set<String> missingConfigLogged) {
        if (source == null || source.curveData == null || source.curveData.isEmpty()) {
            return;
        }
        com.zcyh.mr.marketdata.FxSpot.FxSpotInfo copied = copyFxSpotInfo(source);
        copied.curveData.clear();
        for (Map.Entry<String, Double> e : source.curveData.entrySet()) {
            String curveId = e.getKey();
            if (!includeFxSpotCurve(curveId, lhDays, imaRiskClass)) {
                continue;
            }
            copied.curveData.put(curveId, e.getValue());
            addImpactKey(result.impactKeys, ImaConstants.RF_TYPE_FX_SPOT, curveId);
        }
        if (!copied.curveData.isEmpty()) {
            result.marketData.fxSpot = copied;
        }
    }

    @SuppressWarnings("unchecked")
    private <V> void copyVolSubset(HashMap<String, V> target,
                                   Set<String> impactKeys,
                                   HashMap<String, V> source,
                                   int lhDays,
                                   String imaRiskClass,
                                   Set<String> missingConfigLogged,
                                   String curveType) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, V> e : source.entrySet()) {
            String curveId = e.getKey();
            if (!includeImaCurve(curveType, curveId, lhDays, imaRiskClass, missingConfigLogged)) {
                continue;
            }
            V copied = copyVolInfo(e.getValue());
            if (copied != null) {
                target.put(curveId, copied);
                addImpactKey(impactKeys, curveType, curveId);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <V> V copyVolInfo(V source) {
        if (source instanceof IrVol.IrVolInfo) {
            return (V) copyIrVolInfo((IrVol.IrVolInfo) source);
        }
        if (source instanceof EqVol.EqVolInfo) {
            return (V) copyEqVolInfo((EqVol.EqVolInfo) source);
        }
        if (source instanceof FxVol.FxVolInfo) {
            return (V) copyFxVolInfo((FxVol.FxVolInfo) source);
        }
        if (source instanceof CommVol.CommVolInfo) {
            return (V) copyCommVolInfo((CommVol.CommVolInfo) source);
        }
        return null;
    }

    private boolean includeImaCurve(String curveType,
                                    String curveId,
                                    int lhDays,
                                    String imaRiskClass,
                                    Set<String> missingConfigLogged) {
        Integer configuredLh = readConfiguredLh(curveType, curveId, missingConfigLogged);
        if (configuredLh == null || configuredLh < lhDays) {
            return false;
        }
        String configuredRiskClass = readConfiguredImaRiskClass(curveType, curveId, missingConfigLogged);
        if (configuredRiskClass == null) {
            return false;
        }
        return "ALL".equals(imaRiskClass) || imaRiskClass.equals(configuredRiskClass);
    }

    private boolean includeFxSpotCurve(String currencyPair,
                                       int lhDays,
                                       String imaRiskClass) {
        if (!"ALL".equals(imaRiskClass) && !"FX".equals(imaRiskClass)) {
            return false;
        }
        return LiquidityHorizonTable.resolveFxLiquidityHorizonDays(currencyPair) >= lhDays;
    }

    private Integer readConfiguredLh(String curveType, String curveId, Set<String> missingConfigLogged) {
        try {
            return lhTable.getLhDays(curveType, curveId);
        } catch (RuntimeException ex) {
            logMissingConfig(missingConfigLogged, curveType, curveId, "缺少LH，从情景中删除");
            return null;
        }
    }

    private String readConfiguredImaRiskClass(String curveType, String curveId, Set<String> missingConfigLogged) {
        try {
            return lhTable.getImaRiskClass(curveType, curveId);
        } catch (RuntimeException ex) {
            logMissingConfig(missingConfigLogged, curveType, curveId, "缺少IMA_RISK_CLASS，从情景中删除");
            return null;
        }
    }

    private void logMissingConfig(Set<String> missingConfigLogged, String curveType, String curveId, String reason) {
        String key = String.valueOf(curveType) + "|" + String.valueOf(curveId) + "|" + reason;
        if (missingConfigLogged.add(key)) {
            log.warn("curve_type={}, curve_code={}, reason={}", curveType, curveId, reason);
        }
    }

    private void addImpactKey(Set<String> impactKeys, String curveType, String curveId) {
        if (curveType == null || curveId == null) {
            return;
        }
        impactKeys.add(curveType.trim().toUpperCase(java.util.Locale.ROOT)
                + ":" + curveId.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private IrSpot.IrSpotInfo copyIrSpotInfo(IrSpot.IrSpotInfo src) {
        IrSpot.IrSpotInfo copy = new IrSpot.IrSpotInfo();
        if (src == null) {
            return copy;
        }
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.dataDate = src.dataDate;
        copy.dayCount = src.dayCount;
        copy.freq = src.freq;
        copy.pDataDate = src.pDataDate;
        copy.interpolateType = src.interpolateType;
        copy.curveData = new Series<>(Integer.class, Double.class);
        if (src.curveData != null) {
            copy.curveData.putAll(src.curveData);
        }
        copy.shockCurveData = new Series<>(Integer.class, Double.class);
        if (src.shockCurveData != null) {
            copy.shockCurveData.putAll(src.shockCurveData);
        }
        return copy;
    }

    private com.zcyh.mr.marketdata.FxSpot.FxSpotInfo copyFxSpotInfo(
            com.zcyh.mr.marketdata.FxSpot.FxSpotInfo src) {
        com.zcyh.mr.marketdata.FxSpot.FxSpotInfo copy = new com.zcyh.mr.marketdata.FxSpot.FxSpotInfo();
        if (src == null) {
            return copy;
        }
        copy.curveType = src.curveType;
        copy.dataDate = src.dataDate;
        copy.pDataDate = src.pDataDate;
        copy.curveData = src.curveData == null ? new HashMap<>() : new HashMap<>(src.curveData);
        return copy;
    }

    private static class SubsetBuildResult {
        private final MarketData marketData = new MarketData();
        private final Set<String> impactKeys = new LinkedHashSet<>();
    }

    /**
     * 执行全部 LH 子集的情景重定价。
     *
     * @param baseCalcJsonTemplate       基准 Calc JSON（trades + baseMarketData，无情景部分）
     * @param baseMarketData             基准市场数据（完整）
     * @param scenarioEntries            原始情景条目列表（历史冲击，仅含变动部分）
     * @param modellableIndex            RFET 可建模索引（由 RfetModellableIndex.build 构建），
     *                                   用桶范围 [tenorMin,tenorMax] 判断可建模性
     * @param scenarioType               情景类型：STRESS_REDUCED / NORMAL_FULL / NORMAL_REDUCED
     * @param scenarioId                 情景集ID
     * @param batchId                    批次ID
     * @param jobId                      任务ID
     * @param requestId                  请求ID
     * @param dataDate                   估值日期字符串（yyyyMMdd）
     * @return 每笔交易在每条情景 × 每个 LH 子集下的损益列表
     */
    public List<SubsetPnlRecord> run(String baseCalcJsonTemplate,
                                     MarketData baseMarketData,
                                     List<Loader.ScenarioEntry> scenarioEntries,
                                     RfetModellableIndex modellableIndex,
                                     String scenarioType,
                                     String scenarioId,
                                     String batchId,
                                     String jobId,
                                     String requestId,
                                     String dataDate) {
        if (scenarioEntries == null || scenarioEntries.isEmpty()) {
            return new ArrayList<>();
        }

        // 构建 5×N 的 decomp 情景条目（每条原始情景 × 5个LH子集）
        // REDUCED 情景仅使用压力期 RFET 也通过的桶（is_reduced_set=true）
        boolean reducedSetOnly = scenarioType != null && scenarioType.endsWith("REDUCED");

        List<Loader.ScenarioEntry> decompEntries = new ArrayList<>();
        for (int lhDays : ImaConstants.LH_DAYS_ARRAY) {
            for (Loader.ScenarioEntry entry : scenarioEntries) {
                decompEntries.add(filterToSubset(entry, lhDays, baseMarketData, modellableIndex, reducedSetOnly));
            }
        }

        String decompCacheKey = "ima-subset-" + UUID.randomUUID().toString().replace("-", "");
        ScenarioCache.put(decompCacheKey, decompEntries);
        try {
            String calcJson = injectDecompCacheKey(baseCalcJsonTemplate, decompCacheKey);
            String resultJson = new Calc(calcJson, null).run();
            return parseResult(resultJson, scenarioType, scenarioId, batchId, jobId, requestId, dataDate);
        } finally {
            ScenarioCache.evict(decompCacheKey);
        }
    }

    // ==================== 过滤到子集 ====================

    /**
     * 裁剪情景为指定 LH 子集：以 base 为底，仅覆盖满足可建模 + LH 条件的期限点。
     */
    private Loader.ScenarioEntry filterToSubset(Loader.ScenarioEntry entry,
                                                 int lhDays,
                                                 MarketData baseMarketData,
                                                 RfetModellableIndex modellableIndex,
                                                 boolean reducedSetOnly) {
        // 深拷贝 base 作为基底
        MarketData merged = deepCopyBase(baseMarketData);

        MarketData scenMD = entry.marketData;
        if (scenMD != null) {
            applyIrSpotSubset(merged.irSpot, scenMD.irSpot, lhDays, modellableIndex, reducedSetOnly);
            applySeriesSpotSubset(merged.eqSpot, scenMD.eqSpot, lhDays, modellableIndex, reducedSetOnly,
                    ImaConstants.RF_TYPE_EQ_SPOT);
            applySeriesSpotSubset(merged.commSpot, scenMD.commSpot, lhDays, modellableIndex, reducedSetOnly,
                    ImaConstants.RF_TYPE_COMM_SPOT);
            applyFxSpotSubset(merged, scenMD, lhDays, modellableIndex, reducedSetOnly);
            applyIrVolSubset(merged.irVol, scenMD.irVol, lhDays, modellableIndex, reducedSetOnly);
            applyDeltaVolSubset(merged.eqVol, scenMD.eqVol, lhDays, modellableIndex, reducedSetOnly,
                    ImaConstants.RF_TYPE_EQ_VOL, info -> info.curveData, (info, d) -> info.curveData = d);
            applyDeltaVolSubset(merged.fxVol, scenMD.fxVol, lhDays, modellableIndex, reducedSetOnly,
                    ImaConstants.RF_TYPE_FX_VOL, info -> info.curveData, (info, d) -> info.curveData = d);
            applyDeltaVolSubset(merged.commVol, scenMD.commVol, lhDays, modellableIndex, reducedSetOnly,
                    ImaConstants.RF_TYPE_COMM_VOL, info -> info.curveData, (info, d) -> info.curveData = d);
        }

        // subScenarioId 追加 LH 编码，供解析时还原
        String encodedSubId = (entry.subScenarioId == null ? "" : entry.subScenarioId)
                + ImaConstants.LH_SUFFIX_SEP + lhDays;

        return new Loader.ScenarioEntry(
                entry.scenarioId,
                encodedSubId,
                entry.scenarioName,
                entry.scenarioType,
                merged,
                null);
    }

    /**
     * IrSpot 期限点级别覆盖。
     * 期限点是否可建模用桶范围判断（[tenorMin, tenorMax]），非样本点集合。
     */
    private void applyIrSpotSubset(HashMap<String, IrSpot.IrSpotInfo> mergedIrSpot,
                                    HashMap<String, IrSpot.IrSpotInfo> scenIrSpot,
                                    int lhDays,
                                    RfetModellableIndex modellableIndex,
                                    boolean reducedSetOnly) {
        if (scenIrSpot == null) return;
        for (Map.Entry<String, IrSpot.IrSpotInfo> e : scenIrSpot.entrySet()) {
            String curveId = e.getKey();
            IrSpot.IrSpotInfo scenInfo = e.getValue();
            if (scenInfo == null || scenInfo.curveData == null) continue;
            String rfType = resolveIrSpotRfType(curveId, scenInfo);
            // Q(P,j)：因子 LH >= lhDays 才属于当前子集（MAR33.4）
            if (lhTable.getLhDays(rfType, curveId) < lhDays) continue;

            IrSpot.IrSpotInfo mergedInfo = mergedIrSpot.get(curveId);
            if (mergedInfo == null) continue;

            for (Map.Entry<Integer, Double> td : scenInfo.curveData.entrySet()) {
                // 范围匹配：tenorDays 落在任意可建模桶的 [tenorMin, tenorMax] 内即覆盖
                if (modellableIndex.isModellable(rfType, curveId, td.getKey(), reducedSetOnly)) {
                    mergedInfo.curveData.put(td.getKey(), td.getValue());
                }
            }
        }
    }

    private String resolveIrSpotRfType(String curveId, IrSpot.IrSpotInfo scenInfo) {
        if (ImaConstants.RF_TYPE_IR_SPOT.equals(scenInfo.curveType)
                || ImaConstants.RF_TYPE_CREDIT_SPOT.equals(scenInfo.curveType)) {
            return scenInfo.curveType;
        }
        throw new IllegalArgumentException("IMA irSpot 情景缺少明确曲线类型: curveId=" + curveId);
    }

    /**
     * EqSpot / CommSpot 期限点级别覆盖（泛型：两者均含 curveData=Series<Integer,Double>）。
     * 期限点是否可建模用桶范围判断，非样本点集合。
     */
    private <T> void applySeriesSpotSubset(HashMap<String, T> mergedSpot,
                                            HashMap<String, T> scenSpot,
                                            int lhDays,
                                            RfetModellableIndex modellableIndex,
                                            boolean reducedSetOnly,
                                            String rfType) {
        if (scenSpot == null) return;
        for (Map.Entry<String, T> e : scenSpot.entrySet()) {
            String curveId = e.getKey();
            T scenInfo = e.getValue();
            if (scenInfo == null) continue;
            // Q(P,j)：因子 LH >= lhDays 才属于当前子集（MAR33.4）
            if (lhTable.getLhDays(rfType, curveId) < lhDays) continue;

            T mergedInfo = mergedSpot.get(curveId);
            if (mergedInfo == null) continue;

            Series<Integer, Double> scenCurve = getCurveData(scenInfo);
            Series<Integer, Double> mergedCurve = getCurveData(mergedInfo);
            if (scenCurve == null || mergedCurve == null) continue;

            for (Map.Entry<Integer, Double> td : scenCurve.entrySet()) {
                if (modellableIndex.isModellable(rfType, curveId, td.getKey(), reducedSetOnly)) {
                    mergedCurve.put(td.getKey(), td.getValue());
                }
            }
        }
    }

    /**
     * FxSpot：作为整体结构应用。
     * 若任意 FX 货币对在可建模集合中，且其 LH >= lhDays（属于 Q(P,j)），则替换整个 fxSpot。
     *
     * <p>注意：fxSpot.curveData 的 key 须与 modellableTenorsByCurve 的 curveId 命名规范一致
     * （例如均使用 "USD/CNY" 或均使用 "USDCNY"，由 RF_CONFIG 加载时统一）。
     */
    private void applyFxSpotSubset(MarketData merged,
                                    MarketData scenMD,
                                    int lhDays,
                                    RfetModellableIndex modellableIndex,
                                    boolean reducedSetOnly) {
        if (scenMD.fxSpot == null || scenMD.fxSpot.curveData == null
                || scenMD.fxSpot.curveData.isEmpty()) {
            return;
        }
        // Q(P,j)：因子 LH >= lhDays 才属于当前子集（MAR33.4）
        for (String pairKey : scenMD.fxSpot.curveData.keySet()) {
            if (modellableIndex.hasAnyModellableBucket(ImaConstants.RF_TYPE_FX_SPOT, pairKey, reducedSetOnly)
                    && LiquidityHorizonTable.resolveFxLiquidityHorizonDays(pairKey) >= lhDays) {
                merged.fxSpot = scenMD.fxSpot;
                return;
            }
        }
    }

    /**
     * IR vol 按 OPTION_TERM（1D）逐行过滤。
     *
     * <p>IR vol 曲面结构：每行 {OPTION_TERM, UNDERLYING_TERM, VOLATILITY_RATE}。
     * RFET 仅按 OPTION_TERM 分桶（Row C），UNDERLYING_TERM 整体随 OPTION_TERM 一起覆盖。
     * 若某 OPTION_TERM 落在可建模桶范围内，则该 OPTION_TERM 的全部行（含所有 UNDERLYING_TERM）用情景值替换。
     */
    private void applyIrVolSubset(HashMap<String, IrVol.IrVolInfo> mergedVol,
                                   HashMap<String, IrVol.IrVolInfo> scenVol,
                                   int lhDays,
                                   RfetModellableIndex modellableIndex,
                                   boolean reducedSetOnly) {
        if (scenVol == null) return;
        for (Map.Entry<String, IrVol.IrVolInfo> e : scenVol.entrySet()) {
            String curveId = e.getKey();
            IrVol.IrVolInfo scenInfo = e.getValue();
            if (scenInfo == null || scenInfo.curveData == null) continue;
            if (lhTable.getLhDays(ImaConstants.RF_TYPE_IR_VOL, curveId) < lhDays) continue;
            IrVol.IrVolInfo mergedInfo = mergedVol.get(curveId);
            if (mergedInfo == null) continue;
            mergedInfo.curveData = mergeIrVolRows(mergedInfo.curveData, scenInfo.curveData,
                    curveId, modellableIndex, reducedSetOnly);
        }
    }

    /**
     * EQ/FX/COMM vol 按 OPTION_TERM + DELTA（2D）逐行过滤。
     *
     * <p>vol 曲面结构：每行 {OPTION_TERM, DELTA, VOLATILITY_RATE}。
     * 仅当 (OPTION_TERM, DELTA) 同时落在某可建模桶的 [tenorMin,tenorMax] × [deltaMin,deltaMax] 内，
     * 才用情景行替换对应基准行；否则保留基准行。
     *
     * <p>通过 getter/setter 函数式参数统一处理三种类型（EqVolInfo/FxVolInfo/CommVolInfo），
     * 避免重复代码。
     */
    private <V> void applyDeltaVolSubset(HashMap<String, V> mergedVol,
                                          HashMap<String, V> scenVol,
                                          int lhDays,
                                          RfetModellableIndex modellableIndex,
                                          boolean reducedSetOnly,
                                          String rfType,
                                          java.util.function.Function<V, List<Map<String, Object>>> getter,
                                          java.util.function.BiConsumer<V, List<Map<String, Object>>> setter) {
        if (scenVol == null) return;
        for (Map.Entry<String, V> e : scenVol.entrySet()) {
            String curveId = e.getKey();
            V scenInfo = e.getValue();
            if (scenInfo == null || getter.apply(scenInfo) == null) continue;
            if (lhTable.getLhDays(rfType, curveId) < lhDays) continue;
            V mergedInfo = mergedVol.get(curveId);
            if (mergedInfo == null) continue;
            List<Map<String, Object>> merged = mergeDeltaVolRows(
                    getter.apply(mergedInfo), getter.apply(scenInfo), curveId, modellableIndex, reducedSetOnly, rfType);
            setter.accept(mergedInfo, merged);
        }
    }

    // ==================== vol 行合并工具 ====================

    /**
     * IR vol 行合并（按 OPTION_TERM 1D）。
     * 以 base 行列表为底，对情景中每个 OPTION_TERM：
     * 若该 OPTION_TERM 可建模，则用情景行（含该 OPTION_TERM 的全部 UNDERLYING_TERM 行）整体替换。
     */
    private List<Map<String, Object>> mergeIrVolRows(List<Map<String, Object>> baseRows,
                                                      List<Map<String, Object>> scenRows,
                                                      String curveId,
                                                      RfetModellableIndex index,
                                                      boolean reducedSetOnly) {
        // 按 OPTION_TERM 分组 base
        Map<Integer, List<Map<String, Object>>> baseByTerm = new LinkedHashMap<>();
        if (baseRows != null) {
            for (Map<String, Object> row : baseRows) {
                int optTerm = Convert.toInt(row.get("OPTION_TERM"));
                baseByTerm.computeIfAbsent(optTerm, k -> new ArrayList<>()).add(new HashMap<>(row));
            }
        }
        // 按 OPTION_TERM 分组 scene
        Map<Integer, List<Map<String, Object>>> scenByTerm = new LinkedHashMap<>();
        if (scenRows != null) {
            for (Map<String, Object> row : scenRows) {
                int optTerm = Convert.toInt(row.get("OPTION_TERM"));
                scenByTerm.computeIfAbsent(optTerm, k -> new ArrayList<>()).add(row);
            }
        }
        // 可建模的 OPTION_TERM 用情景行整体覆盖
        for (Map.Entry<Integer, List<Map<String, Object>>> se : scenByTerm.entrySet()) {
            if (index.isModellable(ImaConstants.RF_TYPE_IR_VOL, curveId, se.getKey(), reducedSetOnly)) {
                baseByTerm.put(se.getKey(), se.getValue());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        baseByTerm.values().forEach(result::addAll);
        return result;
    }

    /**
     * EQ/FX/COMM vol 行合并（按 OPTION_TERM + DELTA 2D）。
     * 以 base 行列表为底，对情景中每行：
     * 若 (OPTION_TERM, DELTA) 落在某可建模桶内，则用情景行替换对应基准行。
     */
    private List<Map<String, Object>> mergeDeltaVolRows(List<Map<String, Object>> baseRows,
                                                         List<Map<String, Object>> scenRows,
                                                         String curveId,
                                                         RfetModellableIndex index,
                                                         boolean reducedSetOnly,
                                                         String rfType) {
        // key = "optionTerm_delta"，保留插入顺序
        Map<String, Map<String, Object>> baseMap = new LinkedHashMap<>();
        if (baseRows != null) {
            for (Map<String, Object> row : baseRows) {
                String key = makeVolKey(row);
                baseMap.put(key, new HashMap<>(row));
            }
        }
        if (scenRows != null) {
            for (Map<String, Object> row : scenRows) {
                int optTerm = Convert.toInt(row.get("OPTION_TERM"));
                double delta = requiredFiniteDouble(row.get("DELTA"), rfType, curveId, optTerm);
                if (index.isModellable(rfType, curveId, optTerm, delta, reducedSetOnly)) {
                    baseMap.put(makeVolKey(row), row);
                }
            }
        }
        return new ArrayList<>(baseMap.values());
    }

    /** 生成 EQ/FX/COMM vol 行的唯一 key */
    private String makeVolKey(Map<String, Object> row) {
        int optionTerm = Convert.toInt(row.get("OPTION_TERM"));
        return optionTerm + "_" + requiredFiniteDouble(row.get("DELTA"), "VOL", null, optionTerm);
    }

    private double requiredFiniteDouble(Object raw, String rfType, String curveId, int optionTerm) {
        double value = Convert.toDouble(raw);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("波动率 RFET 判断缺少有效 DELTA: rfType="
                    + rfType + ", curveId=" + curveId + ", optionTerm=" + optionTerm);
        }
        return value;
    }

    // ==================== 深拷贝工具 ====================

    /**
     * 对 base market data 做深拷贝：
     * - irSpot 通过 updateMarketData(base, emptyScen) 获得深拷贝
     * - eqSpot/commSpot 手动深拷贝（updateMarketData 对二者只做浅拷贝）
     * - vol 类手动深拷贝，避免不同 decomp 条目之间共享同一份曲面对象
     */
    private MarketData deepCopyBase(MarketData base) {
        if (base == null) return new MarketData();
        // irSpot 深拷贝
        MarketData copy = MarketData.updateMarketData(base, new MarketData());
        // eqSpot/commSpot 补充深拷贝
        copy.eqSpot = deepCopyEqSpotMap(base.eqSpot);
        copy.commSpot = deepCopyCommSpotMap(base.commSpot);
        copy.irVol = deepCopyIrVolMap(base.irVol);
        copy.eqVol = deepCopyEqVolMap(base.eqVol);
        copy.fxVol = deepCopyFxVolMap(base.fxVol);
        copy.commVol = deepCopyCommVolMap(base.commVol);
        return copy;
    }

    private HashMap<String, IrVol.IrVolInfo> deepCopyIrVolMap(
            HashMap<String, IrVol.IrVolInfo> src) {
        HashMap<String, IrVol.IrVolInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, IrVol.IrVolInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyIrVolInfo(e.getValue()));
        }
        return copy;
    }

    private IrVol.IrVolInfo copyIrVolInfo(IrVol.IrVolInfo src) {
        IrVol.IrVolInfo copy = new IrVol.IrVolInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.termInterpolateType = src.termInterpolateType;
        copy.axis2Type = src.axis2Type;
        copy.axis2InterpolateType = src.axis2InterpolateType;
        copy.dataDate = src.dataDate;
        copy.pDataDate = src.pDataDate;
        copy.curveData = deepCopyCurveRows(src.curveData);
        copy.shockCurveData = deepCopyCurveRows(src.shockCurveData);
        return copy;
    }

    private HashMap<String, EqVol.EqVolInfo> deepCopyEqVolMap(
            HashMap<String, EqVol.EqVolInfo> src) {
        HashMap<String, EqVol.EqVolInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, EqVol.EqVolInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyEqVolInfo(e.getValue()));
        }
        return copy;
    }

    private EqVol.EqVolInfo copyEqVolInfo(EqVol.EqVolInfo src) {
        EqVol.EqVolInfo copy = new EqVol.EqVolInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.termInterpolateType = src.termInterpolateType;
        copy.axis2Type = src.axis2Type;
        copy.axis2InterpolateType = src.axis2InterpolateType;
        copy.dataDate = src.dataDate;
        copy.pDataDate = src.pDataDate;
        copy.curveData = deepCopyCurveRows(src.curveData);
        copy.shockCurveData = deepCopyCurveRows(src.shockCurveData);
        return copy;
    }

    private HashMap<String, FxVol.FxVolInfo> deepCopyFxVolMap(
            HashMap<String, FxVol.FxVolInfo> src) {
        HashMap<String, FxVol.FxVolInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, FxVol.FxVolInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyFxVolInfo(e.getValue()));
        }
        return copy;
    }

    private FxVol.FxVolInfo copyFxVolInfo(FxVol.FxVolInfo src) {
        FxVol.FxVolInfo copy = new FxVol.FxVolInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.termInterpolateType = src.termInterpolateType;
        copy.axis2Type = src.axis2Type;
        copy.axis2InterpolateType = src.axis2InterpolateType;
        copy.dataDate = src.dataDate;
        copy.pDataDate = src.pDataDate;
        copy.curveData = deepCopyCurveRows(src.curveData);
        copy.shockCurveData = deepCopyCurveRows(src.shockCurveData);
        return copy;
    }

    private HashMap<String, CommVol.CommVolInfo> deepCopyCommVolMap(
            HashMap<String, CommVol.CommVolInfo> src) {
        HashMap<String, CommVol.CommVolInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, CommVol.CommVolInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyCommVolInfo(e.getValue()));
        }
        return copy;
    }

    private CommVol.CommVolInfo copyCommVolInfo(CommVol.CommVolInfo src) {
        CommVol.CommVolInfo copy = new CommVol.CommVolInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.termInterpolateType = src.termInterpolateType;
        copy.axis2Type = src.axis2Type;
        copy.axis2InterpolateType = src.axis2InterpolateType;
        copy.dataDate = src.dataDate;
        copy.pDataDate = src.pDataDate;
        copy.curveData = deepCopyCurveRows(src.curveData);
        copy.shockCurveData = deepCopyCurveRows(src.shockCurveData);
        return copy;
    }

    private List<Map<String, Object>> deepCopyCurveRows(List<Map<String, Object>> src) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (src == null) return copy;
        for (Map<String, Object> row : src) {
            copy.add(row == null ? new HashMap<>() : new HashMap<>(row));
        }
        return copy;
    }

    private HashMap<String, EqSpot.EqSpotInfo> deepCopyEqSpotMap(
            HashMap<String, EqSpot.EqSpotInfo> src) {
        HashMap<String, EqSpot.EqSpotInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, EqSpot.EqSpotInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyEqSpotInfo(e.getValue()));
        }
        return copy;
    }

    private EqSpot.EqSpotInfo copyEqSpotInfo(EqSpot.EqSpotInfo src) {
        EqSpot.EqSpotInfo copy = new EqSpot.EqSpotInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.dataDate = src.dataDate;
        copy.currency = src.currency;
        copy.pDataDate = src.pDataDate;
        copy.interpolateType = src.interpolateType;
        copy.curveData = new Series<>(Integer.class, Double.class);
        if (src.curveData != null) copy.curveData.putAll(src.curveData);
        return copy;
    }

    private HashMap<String, CommSpot.CommSpotInfo> deepCopyCommSpotMap(
            HashMap<String, CommSpot.CommSpotInfo> src) {
        HashMap<String, CommSpot.CommSpotInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, CommSpot.CommSpotInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyCommSpotInfo(e.getValue()));
        }
        return copy;
    }

    private CommSpot.CommSpotInfo copyCommSpotInfo(CommSpot.CommSpotInfo src) {
        CommSpot.CommSpotInfo copy = new CommSpot.CommSpotInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.dataDate = src.dataDate;
        copy.currency = src.currency;
        copy.pDataDate = src.pDataDate;
        copy.interpolateType = src.interpolateType;
        copy.curveData = new Series<>(Integer.class, Double.class);
        if (src.curveData != null) copy.curveData.putAll(src.curveData);
        copy.shockCurveData = new Series<>(Integer.class, Double.class);
        if (src.shockCurveData != null) copy.shockCurveData.putAll(src.shockCurveData);
        return copy;
    }

    /**
     * 从 EqSpotInfo 或 CommSpotInfo 提取 curveData（反射替代方案）。
     */
    @SuppressWarnings("unchecked")
    private <T> Series<Integer, Double> getCurveData(T info) {
        if (info instanceof EqSpot.EqSpotInfo) {
            return ((EqSpot.EqSpotInfo) info).curveData;
        } else if (info instanceof CommSpot.CommSpotInfo) {
            return ((CommSpot.CommSpotInfo) info).curveData;
        }
        return null;
    }

    // ==================== Calc JSON 注入 ====================

    private String injectDecompCacheKey(String baseCalcJson, String decompCacheKey) {
        JSONObject json = JSON.parseObject(baseCalcJson);
        JSONArray items = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("cache_key", decompCacheKey);
        items.add(item);
        json.put(ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST, items);
        json.put("calc_mode", "PRICING");
        return json.toJSONString();
    }

    // ==================== 结果解析 ====================

    /**
     * 解析 Calc decomp 结果，subScenarioId 格式 "{原始subId}_LH{days}"。
     */
    private List<SubsetPnlRecord> parseResult(String resultJson,
                                               String scenarioType,
                                               String scenarioId,
                                               String batchId,
                                               String jobId,
                                               String requestId,
                                               String dataDate) {
        List<SubsetPnlRecord> records = new ArrayList<>();
        JSONObject root = JSON.parseObject(resultJson);
        if (root == null) return records;
        JSONObject data = root.getJSONObject("data");
        if (data == null) return records;
        JSONArray scenarioResults = data.getJSONArray("scenario_result");
        if (scenarioResults == null) return records;

        long seqNo = 0;
        for (int i = 0; i < scenarioResults.size(); i++) {
            JSONObject scenItem = scenarioResults.getJSONObject(i);
            if (scenItem == null) continue;

            String encodedSubId = scenItem.getString("SUBSCENARIO_ID");
            int lhDays = decodeLhDays(encodedSubId);
            String originalSubId = decodeOriginalSubId(encodedSubId);
            String scName = scenItem.getString("SCENARIO_NAME");

            JSONArray tradePnls = scenItem.getJSONArray("trade_data");
            if (tradePnls == null) continue;

            for (int j = 0; j < tradePnls.size(); j++) {
                JSONObject tp = tradePnls.getJSONObject(j);
                if (tp == null) continue;

                SubsetPnlRecord rec = new SubsetPnlRecord();
                rec.setRequestId(requestId);
                rec.setJobId(jobId);
                rec.setBatchId(batchId);
                rec.setSeqNo(++seqNo);
                rec.setDataDate(dataDate);
                rec.setScenarioId(scenarioId);
                rec.setSubscenarioId(originalSubId);
                rec.setScenarioName(scName);
                rec.setScenarioType(scenarioType);
                rec.setInstrumentId(tp.getString("INSTRUMENT_ID"));
                rec.setProductCode(tp.getString("PRODUCT_CODE"));
                rec.setLhDays(lhDays);
                rec.setBaseValuationCny(tp.getBigDecimal("BASE_VALUATION_CNY"));
                rec.setIrValuation(tp.getBigDecimal("IR_VALUATION"));
                rec.setIrPnl(tp.getBigDecimal("IR_PNL"));
                rec.setCsValuation(tp.getBigDecimal("CS_VALUATION"));
                rec.setCsPnl(tp.getBigDecimal("CS_PNL"));
                rec.setFxValuation(tp.getBigDecimal("FX_VALUATION"));
                rec.setFxPnl(tp.getBigDecimal("FX_PNL"));
                rec.setEqValuation(tp.getBigDecimal("EQ_VALUATION"));
                rec.setEqPnl(tp.getBigDecimal("EQ_PNL"));
                rec.setCommValuation(tp.getBigDecimal("COMM_VALUATION"));
                rec.setCommPnl(tp.getBigDecimal("COMM_PNL"));
                rec.setAllValuation(tp.getBigDecimal("ALL_VALUATION"));
                rec.setAllPnl(tp.getBigDecimal("ALL_PNL"));
                rec.setCreatedAt(System.currentTimeMillis());
                records.add(rec);
            }
        }
        return records;
    }

    private int decodeLhDays(String encodedSubId) {
        if (encodedSubId == null) return ImaConstants.LH_DAYS_ARRAY[0];
        int idx = encodedSubId.lastIndexOf(ImaConstants.LH_SUFFIX_SEP);
        if (idx < 0) return ImaConstants.LH_DAYS_ARRAY[0];
        try {
            return Integer.parseInt(encodedSubId.substring(idx + ImaConstants.LH_SUFFIX_SEP.length()));
        } catch (NumberFormatException e) {
            return ImaConstants.LH_DAYS_ARRAY[0];
        }
    }

    private String decodeOriginalSubId(String encodedSubId) {
        if (encodedSubId == null) return null;
        int idx = encodedSubId.lastIndexOf(ImaConstants.LH_SUFFIX_SEP);
        return idx >= 0 ? encodedSubId.substring(0, idx) : encodedSubId;
    }
}

