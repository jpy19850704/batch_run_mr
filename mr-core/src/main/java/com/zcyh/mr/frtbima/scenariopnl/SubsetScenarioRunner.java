package com.zcyh.mr.frtbima.scenariopnl;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolSurfacePoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IMA 可建模因子情景损益计算器。
 *
 * <p>职责：对每个 LH 子集 j（10/20/40/60/120天）和 IMA 风险类别，
 * 将原始情景的市场数据冲击裁剪为当前子集的情景条目，供统一情景计量链路重定价。
 *
 * <p>子集构造规则（MAR33.4）：
 * <ul>
 *   <li>Q(P,j) 定义：LH(factor) &gt;= LH_j 的因子集（MAR33.4）。
 *       lhDays=10 包含全部因子（最宽），lhDays=120 仅含 LH=120 因子（最窄）。
 *   <li>对每个风险因子，只有监管 LH 满足当前子集且 IMA 风险类别匹配时，才纳入该子集情景。
 *   <li>无法解析监管 LH 或风险类别的风险因子不纳入情景，并按风险因子维度记录 warning。
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
            if (!includeFxSpotCurve(curveId, lhDays, imaRiskClass, missingConfigLogged)) {
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
                                       String imaRiskClass,
                                       Set<String> missingConfigLogged) {
        if (!"ALL".equals(imaRiskClass) && !"FX".equals(imaRiskClass)) {
            return false;
        }
        Integer fxLhDays = resolveFxSpotLhDays(currencyPair, missingConfigLogged);
        return fxLhDays != null && fxLhDays >= lhDays;
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

    private Integer resolveFxSpotLhDays(String currencyPair, Set<String> missingConfigLogged) {
        try {
            return LiquidityHorizonTable.resolveFxLiquidityHorizonDays(currencyPair);
        } catch (RuntimeException ex) {
            logMissingConfig(missingConfigLogged, ImaConstants.RF_TYPE_FX_SPOT, currencyPair,
                    "FX货币对无法解析LH，从情景中删除: " + ex.getMessage());
            return null;
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

    private List<VolSurfacePoint> deepCopyCurveRows(List<VolSurfacePoint> src) {
        return src == null ? new ArrayList<VolSurfacePoint>() : new ArrayList<VolSurfacePoint>(src);
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

}

