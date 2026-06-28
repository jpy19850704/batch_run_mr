package com.zcyh.mr.calc.scenario;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.frtbima.scenariopnl.SubsetScenarioRunner;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.scenario.ScenarioCache;

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
    private static final String[] RISK_DECOMP_CLASSES = {"IR", "FX", "EQ", "COMM", "ALL"};

    private CalcScenarioProcessService() {
    }

    public static List<Loader.ScenarioEntry> resolveScenarioData(String jsonData, Loader loader) {
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
                    ScenarioProcessConstants.REGULAR);
            appendScenarioRefList(result, jo, ScenarioProcessConstants.RISK_DECOMP_SCENARIO_REF_LIST,
                    ScenarioProcessConstants.RISK_DECOMP);
            appendScenarioRefList(result, jo, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST,
                    ScenarioProcessConstants.IMA_MODELLABLE);
            appendScenarioRefList(result, jo, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST,
                    ScenarioProcessConstants.IMA_NMRF);
        }
        validateScenarioRequiredKeys(result);
        return result;
    }

    private static void appendScenarioRefList(List<Loader.ScenarioEntry> target,
                                              JSONObject payload,
                                              String fieldName,
                                              String processType) {
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
            appendScenarioRefEntries(target, cached, processType, payload, i);
        }
    }

    private static void appendScenarioRefEntries(List<Loader.ScenarioEntry> target,
                                                 List<Loader.ScenarioEntry> cached,
                                                 String processType,
                                                 JSONObject payload,
                                                 int itemIndex) {
        switch (processType) {
            case ScenarioProcessConstants.REGULAR:
                appendCopiedEntries(target, cached, ScenarioProcessConstants.REGULAR, itemIndex);
                return;
            case ScenarioProcessConstants.RISK_DECOMP:
                appendRiskDecompEntries(target, cached, itemIndex);
                return;
            case ScenarioProcessConstants.IMA_MODELLABLE:
                appendImaModellableEntries(target, cached, payload, itemIndex);
                return;
            case ScenarioProcessConstants.IMA_NMRF:
                appendImaNmrfEntries(target, cached, payload, itemIndex);
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

    private static void appendRiskDecompEntries(List<Loader.ScenarioEntry> target,
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
            String entryKey = ScenarioProcessConstants.RISK_DECOMP + ":" + itemIndex + ":" + i;
            for (String riskClass : RISK_DECOMP_CLASSES) {
                MarketData sliced = ScenarioCache.sliceByGroup(raw.marketData, riskClass);
                Set<String> impactKeys = RISK_CLASS_ALL.equals(riskClass)
                        ? raw.impactKeys
                        : ScenarioCache.deriveKeysFromSlice(sliced, riskClass);
                JSONObject tag = new JSONObject();
                tag.put(ScenarioProcessConstants.TAG_RISK_CLASS, riskClass);
                target.add(copyScenarioEntry(raw, ScenarioProcessConstants.RISK_DECOMP, tag, entryKey,
                        sliced, impactKeys));
            }
        }
    }

    private static void appendImaModellableEntries(List<Loader.ScenarioEntry> target,
                                                   List<Loader.ScenarioEntry> entries,
                                                   JSONObject payload,
                                                   int itemIndex) {
        LiquidityHorizonTable lhTable = loadImaRiskFactorConfig(payload);
        SubsetScenarioRunner runner = new SubsetScenarioRunner(lhTable);
        List<Loader.ScenarioEntry> processed = runner.buildModellableScenarioEntries(entries,
                ScenarioProcessConstants.IMA_MODELLABLE,
                ScenarioProcessConstants.TAG_LH,
                ScenarioProcessConstants.TAG_IMA_RISK_CLASS,
                ScenarioProcessConstants.IMA_MODELLABLE + ":" + itemIndex);
        target.addAll(processed);
    }

    private static void appendImaNmrfEntries(List<Loader.ScenarioEntry> target,
                                             List<Loader.ScenarioEntry> entries,
                                             JSONObject payload,
                                             int itemIndex) {
        LiquidityHorizonTable lhTable = loadImaRiskFactorConfig(payload);
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

    private static LiquidityHorizonTable loadImaRiskFactorConfig(JSONObject payload) {
        String dataDate = payload == null ? null : payload.getString("data_date");
        if (!hasText(dataDate)) {
            throw new IllegalArgumentException("data_date 必填，无法读取 IMA 风险因子配置");
        }
        return requiredObject(
                ScenarioProcessConstants.imaRiskFactorConfigCacheKey(dataDate.trim()),
                LiquidityHorizonTable.class);
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

    private static <T> T requiredObject(String cacheKey, Class<T> type) {
        Object value = ScenarioCache.getObject(cacheKey);
        if (value == null) {
            throw new IllegalStateException("ScenarioCache 对象不存在: key=" + cacheKey);
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("ScenarioCache 对象类型不匹配: key=" + cacheKey
                    + ", expected=" + type.getSimpleName());
        }
        return type.cast(value);
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
