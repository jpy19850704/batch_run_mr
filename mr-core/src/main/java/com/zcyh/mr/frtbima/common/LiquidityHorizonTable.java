package com.zcyh.mr.frtbima.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * MAR33.6 流动性期限映射表。
 *
 * <p>IMA批量主流程使用风险因子树快照中的曲线级显式配置。
 */
public class LiquidityHorizonTable {

    private final Map<String, Integer> lhDaysByCurveKey;
    private final Map<String, String> imaRiskClassByCurveKey;

    private LiquidityHorizonTable(Map<String, Integer> lhDaysByCurveId) {
        this.lhDaysByCurveKey = normalizeLhMap(lhDaysByCurveId);
        this.imaRiskClassByCurveKey = new HashMap<>();
    }

    private LiquidityHorizonTable(Map<String, Integer> lhDaysByCurveKey,
                                  Map<String, String> imaRiskClassByCurveKey) {
        Map<String, Integer> normalizedLhDaysByCurveKey = normalizeLhMap(lhDaysByCurveKey);
        Map<String, String> normalizedImaRiskClassByCurveKey = normalizeImaRiskClassMap(imaRiskClassByCurveKey);
        if (!normalizedLhDaysByCurveKey.keySet().equals(normalizedImaRiskClassByCurveKey.keySet())) {
            throw new IllegalArgumentException("IMA风险因子配置中的LH和风险大类曲线集合不一致");
        }
        this.lhDaysByCurveKey = normalizedLhDaysByCurveKey;
        this.imaRiskClassByCurveKey = normalizedImaRiskClassByCurveKey;
    }

    public static LiquidityHorizonTable fromCurveLiquidityHorizonDays(Map<String, Integer> lhDaysByCurveId) {
        return new LiquidityHorizonTable(lhDaysByCurveId);
    }

    public static LiquidityHorizonTable fromCurveConfig(Map<String, Integer> lhDaysByCurveKey,
                                                        Map<String, String> imaRiskClassByCurveKey) {
        return new LiquidityHorizonTable(lhDaysByCurveKey, imaRiskClassByCurveKey);
    }

    public static String curveKey(String curveType, String curveId) {
        String normalizedType = normalizeKey(curveType);
        String normalizedCurveId = normalizeKey(curveId);
        if (normalizedType == null || normalizedCurveId == null) {
            return null;
        }
        return normalizedType + "|" + normalizedCurveId;
    }

    public static int resolveFxLiquidityHorizonDays(String currencyPair) {
        String[] currencies = parseFxCurrencyPair(currencyPair);
        return ImaConstants.FX_LH_10_CURRENCIES.contains(currencies[0])
                && ImaConstants.FX_LH_10_CURRENCIES.contains(currencies[1])
                ? ImaConstants.LH_BASE
                : 20;
    }

    /**
     * 获取指定曲线对应的流动性期限天数。
     *
     * @param curveId 曲线ID
     * @return LH 天数
     */
    public int getLhDays(String curveId) {
        String normalizedCurveId = normalizeKey(curveId);
        Integer lhDays = lhDaysByCurveKey.get(normalizedCurveId);
        if (lhDays == null) {
            throw new IllegalStateException("IMA风险因子流动性期限未配置: curveId=" + curveId);
        }
        return lhDays;
    }

    public int getLhDays(String curveType, String curveId) {
        String key = curveKey(curveType, curveId);
        Integer lhDays = lhDaysByCurveKey.get(key);
        if (lhDays == null) {
            throw new IllegalStateException("IMA风险因子流动性期限未配置: curveType="
                    + curveType + ", curveId=" + curveId);
        }
        return lhDays;
    }

    public String getImaRiskClass(String curveType, String curveId) {
        String key = curveKey(curveType, curveId);
        String riskClass = imaRiskClassByCurveKey.get(key);
        if (riskClass == null) {
            throw new IllegalStateException("IMA风险因子监管风险大类未配置: curveType="
                    + curveType + ", curveId=" + curveId);
        }
        return riskClass;
    }

    private static Map<String, Integer> normalizeLhMap(Map<String, Integer> raw) {
        Map<String, Integer> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String key = normalizeKey(entry.getKey());
            Integer value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            validateLhDays(value, key);
            result.put(key, value);
        }
        return result;
    }

    private static Map<String, String> normalizeImaRiskClassMap(Map<String, String> raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = normalizeKey(entry.getKey());
            String value = normalizeKey(entry.getValue());
            if (key == null || value == null) {
                continue;
            }
            validateImaRiskClass(value, key);
            result.put(key, value);
        }
        return result;
    }

    private static void validateLhDays(int value, String curveId) {
        if (!(value == 10 || value == 20 || value == 40 || value == 60 || value == 120)) {
            throw new IllegalArgumentException("流动性期限仅支持10/20/40/60/120: curveId=" + curveId);
        }
    }

    private static void validateImaRiskClass(String value, String curveKey) {
        if (!("GIRR".equals(value) || "CSR".equals(value) || "FX".equals(value)
                || "EQ".equals(value) || "COMM".equals(value))) {
            throw new IllegalArgumentException("IMA风险大类仅支持GIRR/CSR/FX/EQ/COMM: curveKey=" + curveKey);
        }
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String[] parseFxCurrencyPair(String currencyPair) {
        String normalizedPair = normalizeKey(currencyPair);
        if (normalizedPair == null) {
            throw new IllegalArgumentException("FX货币对不能为空");
        }
        String[] parts = normalizedPair.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("FX货币对格式必须为AAA/BBB: currencyPair=" + currencyPair);
        }
        String left = parts[0].trim();
        String right = parts[1].trim();
        if (!isCurrencyCode(left) || !isCurrencyCode(right)) {
            throw new IllegalArgumentException("FX货币对币种必须为3位代码: currencyPair=" + currencyPair);
        }
        return new String[]{left, right};
    }

    private static boolean isCurrencyCode(String value) {
        if (value == null || value.length() != 3) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 'A' || ch > 'Z') {
                return false;
            }
        }
        return true;
    }
}
