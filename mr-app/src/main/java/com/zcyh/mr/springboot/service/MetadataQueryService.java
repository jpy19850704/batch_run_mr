package com.zcyh.mr.springboot.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用元数据查询服务。
 * 从 engine_result_db 结果表中查询下拉选项、维度域值等前端所需的元数据。
 * 按 scope 分发：batches / dimDomains / scenarios / tradeIds
 */
@Service
public class MetadataQueryService {

    private final JdbcTemplate jdbcTemplate;

    public MetadataQueryService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询可用的批次列表（从敏感性明细表聚合）。
     * 返回：[{batch_id, data_date, count}]
     */
    public List<Map<String, Object>> listBatches() {
        String sql = "SELECT BATCH_ID, DATA_DATE, COUNT(*) AS ROW_COUNT "
                + "FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL "
                + "GROUP BY BATCH_ID, DATA_DATE "
                + "ORDER BY BATCH_ID DESC";
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * 查询指定批次下维度域值（PORTFOLIO / DESK / PRODUCT_CODE 等）。
     * 从结果表和敏感性明细表联合查询。
     * 返回：[{col, label, domains:[...]}]
     */
    public List<Map<String, Object>> listDimDomains(String batchId) {
        List<Map<String, Object>> result = new ArrayList<>();
        // 维度定义：字段名 → 显示名 → 来源表别名
        String[][] dims = {
                {"PORTFOLIO", "组合", "r"},
                {"DESK", "交易台", "r"},
                {"PRODUCT_CODE", "产品类型", "d"},
                {"RISK_FACTOR_CLASS", "风险类别", "d"},
                {"RISK_FACTOR_TYPE", "风险因子类型", "d"},
                {"SENSITIVITY_TYPE", "敏感性类型", "d"},
        };
        for (String[] dim : dims) {
            String field = dim[0];
            String label = dim[1];
            String alias = dim[2];
            String table = "r".equals(alias)
                    ? "TB_OUT_TRADE_RESULT_DETAIL"
                    : "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL";
            String sql = "SELECT DISTINCT " + field + " FROM " + table
                    + " WHERE BATCH_ID = ? AND " + field + " IS NOT NULL ORDER BY " + field;
            List<String> domains = jdbcTemplate.queryForList(sql, String.class, batchId);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("col", field);
            entry.put("label", label);
            entry.put("domains", domains);
            result.add(entry);
        }
        return result;
    }

    /**
     * 查询指定批次下的情景 ID 列表。
     * 返回：[{scenario_id, scenario_name, count}]
     */
    public List<Map<String, Object>> listScenarios(String batchId) {
        String sql = "SELECT SCENARIO_ID, SCENARIO_NAME, COUNT(*) AS ROW_COUNT "
                + "FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL "
                + "WHERE BATCH_ID = ? "
                + "GROUP BY SCENARIO_ID, SCENARIO_NAME "
                + "ORDER BY SCENARIO_ID";
        return jdbcTemplate.queryForList(sql, batchId);
    }

    /**
     * 查询指定批次下的交易 ID 列表。
     * 返回：[{instrument_id, product_code}]
     */
    public List<Map<String, Object>> listTradeIds(String batchId) {
        String sql = "SELECT DISTINCT INSTRUMENT_ID, PRODUCT_CODE "
                + "FROM TB_OUT_TRADE_RESULT_DETAIL "
                + "WHERE BATCH_ID = ? "
                + "ORDER BY INSTRUMENT_ID";
        return jdbcTemplate.queryForList(sql, batchId);
    }

    /**
     * 按 scope 分发查询。
     */
    public Object query(String scope, Map<String, String> params) {
        if (scope == null || scope.isEmpty()) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        switch (scope) {
            case "batches":
                return listBatches();
            case "dimDomains":
                return listDimDomains(requireParam(params, "batch_id"));
            case "scenarios":
                return listScenarios(requireParam(params, "batch_id"));
            case "tradeIds":
                return listTradeIds(requireParam(params, "batch_id"));
            default:
                throw new IllegalArgumentException("不支持的 metadata scope: " + scope);
        }
    }

    private static String requireParam(Map<String, String> params, String key) {
        String value = params == null ? null : params.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少必选参数: " + key);
        }
        return value.trim();
    }
}
