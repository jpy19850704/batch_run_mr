package com.zcyh.mr.springboot.input.db;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计量输入查询服务。
 * 从 engine_result_db 结果表中查询计量使用的批次、情景、交易和维度输入。
 * 按 scope 分发：batches / dimDomains / scenarios / instrumentIds
 */
@Service
public class MeasurementInputQueryService {

    private final MeasurementInputQueryRepository measurementInputQueryRepository;

    public MeasurementInputQueryService(MeasurementInputQueryRepository measurementInputQueryRepository) {
        this.measurementInputQueryRepository = measurementInputQueryRepository;
    }

    /**
     * 查询可用的批次列表（从敏感性明细表聚合）。
     * 返回：[{batch_id, data_date, count}]
     */
    public List<Map<String, Object>> listBatches() {
        return measurementInputQueryRepository.listBatches();
    }

    /**
     * 查询指定批次下维度域值（PORTFOLIO / DESK / PRODUCT_CODE 等）。
     * 从结果表和敏感性明细表联合查询。
     * 返回：[{col, label, domains:[...]}]
     */
    public List<Map<String, Object>> listDimDomains(String batchId, String dataDate) {
        String normalizedDataDate = normalizeDataDate(dataDate);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MeasurementInputDomainRegistry.MeasurementInputDomainDef def : MeasurementInputDomainRegistry.listDomainDefs()) {
            List<String> domains = measurementInputQueryRepository.listDomains(
                    def.getTableName(),
                    def.getColumnName(),
                    batchId,
                    normalizedDataDate
            );
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("col", def.getColumnName());
            entry.put("label", def.getLabel());
            entry.put("domains", domains);
            result.add(entry);
        }
        return result;
    }

    /**
     * 查询指定批次下的情景 ID 列表。
     * 返回：[{scenario_id, scenario_name, count}]
     */
    public List<Map<String, Object>> listScenarios(String batchId, String dataDate) {
        return measurementInputQueryRepository.listScenarios(batchId, normalizeDataDate(dataDate));
    }

    /**
     * 查询指定批次下的 instrumentId 列表。
     * 返回：[{instrument_id, product_code}]
     */
    public List<Map<String, Object>> listInstrumentIds(String batchId, String dataDate) {
        return measurementInputQueryRepository.listInstrumentIds(batchId, normalizeDataDate(dataDate));
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
                return listDimDomains(requireParam(params, "batch_id"), requireParam(params, "data_date"));
            case "scenarios":
                return listScenarios(requireParam(params, "batch_id"), requireParam(params, "data_date"));
            case "instrumentIds":
                return listInstrumentIds(requireParam(params, "batch_id"), requireParam(params, "data_date"));
            default:
                throw new IllegalArgumentException("不支持的计量输入查询范围: " + scope);
        }
    }

    private static String requireParam(Map<String, String> params, String key) {
        String value = params == null ? null : params.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少必选参数: " + key);
        }
        return value.trim();
    }

    private static String normalizeDataDate(String value) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("缺少必选参数: data_date");
        }
        if (text.matches("\\d{8}")) {
            return text;
        }
        if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return text.replace("-", "");
        }
        throw new IllegalArgumentException("data_date 格式必须为 yyyyMMdd 或 yyyy-MM-dd: " + text);
    }
}
