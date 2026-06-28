package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IMA 风险因子配置读取服务。
 */
@Service
public class ImaRiskFactorConfigService {
    private final JdbcTemplate engineDbJdbcTemplate;

    public ImaRiskFactorConfigService(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public LiquidityHorizonTable loadImaRiskFactorConfig(LocalDate dataDate) {
        if (dataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList("""
                SELECT curve_code,
                       curve_type,
                       ima_risk_class,
                       liquidity_horizon_days
                FROM MR_IMA_RISK_FACTOR_CONFIG
                WHERE data_date=?
                ORDER BY curve_type, curve_code
                """, Date.valueOf(dataDate));
        if (rows.isEmpty()) {
            throw new IllegalStateException("MR_IMA_RISK_FACTOR_CONFIG 未找到当前估值日配置，dataDate=" + dataDate);
        }
        Map<String, Integer> lhDaysByCurveKey = new LinkedHashMap<String, Integer>();
        Map<String, String> imaRiskClassByCurveKey = new LinkedHashMap<String, String>();
        for (Map<String, Object> row : rows) {
            String curveCode = normalizeText(row.get("curve_code"));
            String curveType = normalizeText(row.get("curve_type"));
            String imaRiskClass = normalizeText(row.get("ima_risk_class"));
            Integer lhDays = toInteger(row.get("liquidity_horizon_days"));
            if (curveCode == null || curveType == null) {
                throw new IllegalStateException("IMA风险因子配置缺少curve_type/curve_code，dataDate=" + dataDate);
            }
            if (!isSupportedImaRiskClass(imaRiskClass)) {
                throw new IllegalStateException("IMA风险因子配置ima_risk_class仅支持GIRR/CSR/FX/EQ/COMM，dataDate="
                        + dataDate + ", curve_type=" + curveType + ", curve_code=" + curveCode);
            }
            if (lhDays == null) {
                throw new IllegalStateException("IMA风险因子配置缺少liquidity_horizon_days，dataDate="
                        + dataDate + ", curve_code=" + curveCode);
            }
            String curveKey = LiquidityHorizonTable.curveKey(curveType, curveCode);
            if (lhDaysByCurveKey.put(curveKey, lhDays) != null) {
                throw new IllegalStateException("IMA风险因子配置存在重复曲线，dataDate="
                        + dataDate + ", curve_type=" + curveType + ", curve_code=" + curveCode);
            }
            imaRiskClassByCurveKey.put(curveKey, imaRiskClass);
        }
        return LiquidityHorizonTable.fromCurveConfig(lhDaysByCurveKey, imaRiskClassByCurveKey);
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = trimToNull(String.valueOf(value));
        return text == null ? null : Integer.valueOf(text);
    }

    private static String normalizeText(Object value) {
        String text = value == null ? null : trimToNull(String.valueOf(value));
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static boolean isSupportedImaRiskClass(String value) {
        return "GIRR".equals(value)
                || "CSR".equals(value)
                || "FX".equals(value)
                || "EQ".equals(value)
                || "COMM".equals(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
