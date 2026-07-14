package com.zcyh.mr.calc.scenario;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.frtbima.scenariopnl.SubsetScenarioRunner;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Calc 情景预处理服务：将输入情景统一转换为标准 ScenarioEntry。
 */
public final class CalcScenarioProcessService {
    private static final String RISK_CLASS_ALL = "ALL";
    private static final String[] VAR_RISK_CLASSES = {"IR", "FX", "EQ", "COMM", "ALL"};
    private static final ScenarioMarketDataSlicer MARKET_DATA_SLICER = new ScenarioMarketDataSlicer();

    private CalcScenarioProcessService() {
    }

    public static List<Loader.ScenarioEntry> resolveScenarioData(String jsonData, Loader loader) {
        return resolveScenarioData(jsonData, loader, null);
    }

    public static List<Loader.ScenarioEntry> resolveScenarioData(
            String jsonData,
            Loader loader,
            LiquidityHorizonTable imaRiskFactorConfig) {
        List<Loader.ScenarioEntry> result = new ArrayList<>();
        List<Loader.ScenarioEntry> inline = loader.getScenarioDataList();
        if (inline != null && !inline.isEmpty()) {
            int index = 0;
            for (Loader.ScenarioEntry entry : inline) {
                result.add(copyScenarioEntry(entry, ScenarioProcessConstants.REGULAR, new JSONObject(),
                        "inline:" + index));
                index++;
            }
        }
        JSONObject jo = JSON.parseObject(jsonData);
        if (jo != null) {
            appendScenarioRefList(result, jo, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST,
                    ScenarioProcessConstants.REGULAR, imaRiskFactorConfig);
            appendScenarioRefList(result, jo, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST,
                    ScenarioProcessConstants.VAR, imaRiskFactorConfig);
            appendScenarioRefList(result, jo, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST,
                    ScenarioProcessConstants.IMA_MODELLABLE, imaRiskFactorConfig);
            appendScenarioRefList(result, jo, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST,
                    ScenarioProcessConstants.IMA_NMRF, imaRiskFactorConfig);
        }
        validateScenarioRequiredKeys(result);
        return result;
    }

    private static void appendScenarioRefList(List<Loader.ScenarioEntry> target,
                                               JSONObject payload,
                                               String fieldName,
                                               String processType,
                                               LiquidityHorizonTable imaRiskFactorConfig) {
        JSONArray items = payload == null ? null : payload.getJSONArray(fieldName);
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            String cacheKey = item == null ? null : item.getString("cache_key");
            if (!hasText(cacheKey)) {
                throw new IllegalArgumentException(fieldName + "[" + i + "].cache_key 必填");
            }
            List<Loader.ScenarioEntry> cached = ScenarioCache.get(cacheKey.trim());
            if (cached == null) {
                throw new IllegalArgumentException("ScenarioCache 未找到场景数据: cache_key=" + cacheKey.trim());
            }
            appendScenarioRefEntries(target, cached, processType, item, i, imaRiskFactorConfig);
        }
    }

    private static void appendScenarioRefEntries(List<Loader.ScenarioEntry> target,
                                                  List<Loader.ScenarioEntry> cached,
                                                  String processType,
                                                  JSONObject item,
                                                  int itemIndex,
                                                  LiquidityHorizonTable imaRiskFactorConfig) {
        switch (processType) {
            case ScenarioProcessConstants.REGULAR:
                appendCopiedEntries(target, cached, ScenarioProcessConstants.REGULAR, itemIndex);
                return;
            case ScenarioProcessConstants.VAR:
                appendVarEntries(target, cached, itemIndex);
                return;
            case ScenarioProcessConstants.IMA_MODELLABLE:
                appendImaModellableEntries(target, cached, item, itemIndex, imaRiskFactorConfig);
                return;
            case ScenarioProcessConstants.IMA_NMRF:
                appendImaNmrfEntries(target, cached, itemIndex, imaRiskFactorConfig);
                return;
            default:
                throw new IllegalArgumentException("情景处理类型无效: itemIndex=" + itemIndex
                        + ", processType=" + processType);
        }
    }

    private static void appendCopiedEntries(List<Loader.ScenarioEntry> target,
                                            List<Loader.ScenarioEntry> entries,
                                            String processType,
                                            int itemIndex) {
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == null) {
                continue;
            }
            target.add(copyScenarioEntry(entries.get(i), processType, new JSONObject(),
                    processType + ":" + itemIndex + ":" + i));
        }
    }

    private static void appendVarEntries(List<Loader.ScenarioEntry> target,
                                         List<Loader.ScenarioEntry> entries,
                                         int itemIndex) {
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Loader.ScenarioEntry raw = entries.get(i);
            if (raw == null) {
                continue;
            }
            String entryKey = ScenarioProcessConstants.VAR + ":" + itemIndex + ":" + i;
            for (String riskClass : VAR_RISK_CLASSES) {
                MarketData sliced = MARKET_DATA_SLICER.slice(raw.marketData, riskClass);
                Set<String> impactKeys = RISK_CLASS_ALL.equals(riskClass)
                        ? raw.impactKeys
                        : MARKET_DATA_SLICER.deriveImpactKeys(sliced, riskClass);
                JSONObject tag = new JSONObject();
                tag.put(ScenarioProcessConstants.TAG_RISK_CLASS, riskClass);
                target.add(copyScenarioEntry(raw, ScenarioProcessConstants.VAR, tag, entryKey,
                        sliced, impactKeys));
            }
        }
    }

    private static void appendImaModellableEntries(List<Loader.ScenarioEntry> target,
                                                    List<Loader.ScenarioEntry> entries,
                                                    JSONObject item,
                                                    int itemIndex,
                                                    LiquidityHorizonTable imaRiskFactorConfig) {
        LiquidityHorizonTable lhTable = requireImaRiskFactorConfig(imaRiskFactorConfig);
        String imaScenarioType = requireImaScenarioType(item, itemIndex);
        SubsetScenarioRunner runner = new SubsetScenarioRunner(lhTable);
        List<Loader.ScenarioEntry> processed = runner.buildModellableScenarioEntries(entries,
                ScenarioProcessConstants.IMA_MODELLABLE,
                ScenarioProcessConstants.TAG_LH,
                ScenarioProcessConstants.TAG_IMA_RISK_CLASS,
                ScenarioProcessConstants.IMA_MODELLABLE + ":" + itemIndex);
        for (Loader.ScenarioEntry entry : processed) {
            if (entry == null || entry.processMetadata == null) {
                continue;
            }
            if (entry.processMetadata.tag == null) {
                entry.processMetadata.tag = new JSONObject();
            }
            entry.processMetadata.tag.put(ScenarioProcessConstants.TAG_IMA_SCENARIO_TYPE, imaScenarioType);
        }
        target.addAll(processed);
    }

    private static void appendImaNmrfEntries(List<Loader.ScenarioEntry> target,
                                              List<Loader.ScenarioEntry> entries,
                                              int itemIndex,
                                              LiquidityHorizonTable imaRiskFactorConfig) {
        LiquidityHorizonTable lhTable = requireImaRiskFactorConfig(imaRiskFactorConfig);
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Loader.ScenarioEntry raw = entries.get(i);
            if (raw == null) {
                continue;
            }
            validateNmrfSubScenarioId(raw);
            CurveKey curveKey = singleImpactKey(raw);
            String imaRiskClass = lhTable.getImaRiskClass(curveKey.curveType, curveKey.curveCode);
            Loader.ScenarioEntry copied = copyScenarioEntry(raw,
                    ScenarioProcessConstants.IMA_NMRF,
                    new JSONObject(),
                    ScenarioProcessConstants.IMA_NMRF + ":" + itemIndex + ":" + i);
            copied.processMetadata.nmrfRiskFactorId = curveKey.curveCode;
            copied.processMetadata.nmrfType = nmrfType(imaRiskClass);
            target.add(copied);
        }
    }

    private static LiquidityHorizonTable requireImaRiskFactorConfig(
            LiquidityHorizonTable imaRiskFactorConfig) {
        if (imaRiskFactorConfig == null) {
            throw new IllegalStateException("IMA 计量缺少风险因子配置");
        }
        return imaRiskFactorConfig;
    }

    private static Loader.ScenarioEntry copyScenarioEntry(Loader.ScenarioEntry source,
                                                         String processType,
                                                         JSONObject tag,
                                                         String entryKey) {
        return copyScenarioEntry(source, processType, tag, entryKey,
                source == null ? null : source.marketData,
                source == null ? null : source.impactKeys);
    }

    private static Loader.ScenarioEntry copyScenarioEntry(Loader.ScenarioEntry source,
                                                         String processType,
                                                         JSONObject tag,
                                                         String entryKey,
                                                         MarketData marketData,
                                                         Set<String> impactKeys) {
        if (source == null) {
            return null;
        }
        Loader.ScenarioEntry copied = new Loader.ScenarioEntry(
                source.scenarioId,
                source.subScenarioId,
                source.scenarioName,
                source.scenarioType,
                processType,
                tag == null ? new JSONObject() : new JSONObject(tag),
                entryKey,
                marketData,
                impactKeys == null ? new LinkedHashSet<>() : new LinkedHashSet<>(impactKeys));
        if (source.processMetadata != null) {
            copied.processMetadata.nmrfRiskFactorId = source.processMetadata.nmrfRiskFactorId;
            copied.processMetadata.nmrfType = source.processMetadata.nmrfType;
        }
        return copied;
    }

    private static void validateScenarioRequiredKeys(List<Loader.ScenarioEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (Loader.ScenarioEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            String scenarioId = trimToNull(entry.scenarioId);
            String subScenarioId = trimToNull(entry.subScenarioId);
            if (scenarioId == null || subScenarioId == null) {
                throw new IllegalArgumentException("场景数据缺少 SCENARIO_ID 或 SUBSCENARIO_ID");
            }
        }
    }

    private static void validateNmrfSubScenarioId(Loader.ScenarioEntry entry) {
        String subScenarioId = trimToNull(entry.subScenarioId);
        if (subScenarioId == null
                || !(subScenarioId.endsWith("_UP") || subScenarioId.endsWith("_DOWN"))) {
            throw new IllegalArgumentException("IMA_NMRF 情景 SUBSCENARIO_ID 必须为 {rfetBucketId}_UP 或 {rfetBucketId}_DOWN: "
                    + subScenarioId);
        }
    }

    private static CurveKey singleImpactKey(Loader.ScenarioEntry entry) {
        if (entry.impactKeys == null || entry.impactKeys.size() != 1) {
            throw new IllegalArgumentException("IMA_NMRF 情景必须只包含一个 curve_id: scenario_id="
                    + entry.scenarioId + ", sub_scenario_id=" + entry.subScenarioId
                    + ", impactKeys=" + entry.impactKeys);
        }
        String value = entry.impactKeys.iterator().next();
        int index = value == null ? -1 : value.indexOf(':');
        if (index <= 0 || index == value.length() - 1) {
            throw new IllegalArgumentException("IMA_NMRF impactKey 格式必须为 CURVE_TYPE:CURVE_CODE: " + value);
        }
        String curveType = trimToNull(value.substring(0, index));
        String curveCode = trimToNull(value.substring(index + 1));
        if (curveType == null || curveCode == null) {
            throw new IllegalArgumentException("IMA_NMRF impactKey 格式必须为 CURVE_TYPE:CURVE_CODE: " + value);
        }
        return new CurveKey(
                curveType.toUpperCase(Locale.ROOT),
                curveCode.toUpperCase(Locale.ROOT));
    }

    private static String nmrfType(String imaRiskClass) {
        String safe = trimToNull(imaRiskClass);
        if (safe == null) {
            throw new IllegalArgumentException("IMA_NMRF 缺少 IMA_RISK_CLASS");
        }
        String normalized = safe.toUpperCase(Locale.ROOT);
        if ("CSR".equals(normalized)) {
            return ImaConstants.NMRF_TYPE_IDIO_CREDIT;
        }
        if ("EQ".equals(normalized)) {
            return ImaConstants.NMRF_TYPE_IDIO_EQUITY;
        }
        if ("GIRR".equals(normalized) || "FX".equals(normalized) || "COMM".equals(normalized)) {
            return ImaConstants.NMRF_TYPE_OTHER;
        }
        throw new IllegalArgumentException("IMA_NMRF 不支持的 IMA_RISK_CLASS: " + imaRiskClass);
    }

    private static String requireImaScenarioType(JSONObject item, int itemIndex) {
        JSONObject scenarioMeta = item == null ? null : item.getJSONObject(ScenarioProcessConstants.SCENARIO_META);
        String value = trimToNull(scenarioMeta == null
                ? null
                : scenarioMeta.getString(ScenarioProcessConstants.TAG_IMA_SCENARIO_TYPE));
        if (isValidImaScenarioType(value)) {
            return value;
        }
        throw new IllegalArgumentException("IMA 可建模情景缺少合法 scenario_meta."
                + ScenarioProcessConstants.TAG_IMA_SCENARIO_TYPE + ": itemIndex=" + itemIndex);
    }

    private static boolean isValidImaScenarioType(String value) {
        return ImaConstants.SCENARIO_TYPE_NORMAL_FULL.equals(value)
                || ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED.equals(value)
                || ImaConstants.SCENARIO_TYPE_STRESS_REDUCED.equals(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean hasText(String value) {
        return trimToNull(value) != null;
    }

    private static class CurveKey {
        private final String curveType;
        private final String curveCode;

        private CurveKey(String curveType, String curveCode) {
            this.curveType = curveType;
            this.curveCode = curveCode;
        }
    }
}
