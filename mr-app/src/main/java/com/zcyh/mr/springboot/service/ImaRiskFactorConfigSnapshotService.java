package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IMA 风险因子配置快照读取服务。
 */
@Service
public class ImaRiskFactorConfigSnapshotService {
    private final JdbcTemplate engineDbJdbcTemplate;

    public ImaRiskFactorConfigSnapshotService(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public LiquidityHorizonTable loadLiquidityHorizonTable(String batchId) {
        String safeBatchId = trimToNull(batchId);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList("""
                SELECT curve_code,
                       curve_type,
                       liquidity_horizon_days
                FROM MR_IMA_RISK_FACTOR_CONFIG_SNAPSHOT
                WHERE batch_id=?
                ORDER BY curve_type, curve_code
                """, safeBatchId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("MR_IMA_RISK_FACTOR_CONFIG_SNAPSHOT 未找到当前批次快照，batchId=" + safeBatchId);
        }
        Map<String, Integer> lhDaysByCurveId = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> row : rows) {
            String curveCode = normalizeText(row.get("curve_code"));
            Integer lhDays = toInteger(row.get("liquidity_horizon_days"));
            if (curveCode == null) {
                throw new IllegalStateException("IMA风险因子配置快照缺少curve_code，batchId=" + safeBatchId);
            }
            if (lhDays == null) {
                throw new IllegalStateException("IMA风险因子配置快照缺少liquidity_horizon_days，batchId="
                        + safeBatchId + ", curve_code=" + curveCode);
            }
            if (lhDaysByCurveId.put(curveCode, lhDays) != null) {
                throw new IllegalStateException("IMA风险因子配置快照存在重复曲线，batchId="
                        + safeBatchId + ", curve_code=" + curveCode);
            }
        }
        return LiquidityHorizonTable.fromCurveLiquidityHorizonDays(lhDaysByCurveId);
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
