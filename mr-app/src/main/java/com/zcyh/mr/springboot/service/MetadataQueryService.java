package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.metadata.MetadataDomainRegistry;
import com.zcyh.mr.springboot.mybatis.engineresultdb.MetadataQueryMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用元数据查询服务。
 * 从 engine_result_db 结果表中查询下拉选项、维度域值等前端所需的元数据。
 * 按 scope 分发：batches / dimDomains / scenarios / instrumentIds
 */
@Service
public class MetadataQueryService {

    private final MetadataQueryMapper metadataQueryMapper;

    public MetadataQueryService(MetadataQueryMapper metadataQueryMapper) {
        this.metadataQueryMapper = metadataQueryMapper;
    }

    /**
     * 查询可用的批次列表（从敏感性明细表聚合）。
     * 返回：[{batch_id, data_date, count}]
     */
    public List<Map<String, Object>> listBatches() {
        return metadataQueryMapper.listBatches();
    }

    /**
     * 查询指定批次下维度域值（PORTFOLIO / DESK / PRODUCT_CODE 等）。
     * 从结果表和敏感性明细表联合查询。
     * 返回：[{col, label, domains:[...]}]
     */
    public List<Map<String, Object>> listDimDomains(String batchId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetadataDomainRegistry.MetadataDomainDef def : MetadataDomainRegistry.listDomainDefs()) {
            List<String> domains = metadataQueryMapper.listDomains(
                    def.getTableName(),
                    def.getColumnName(),
                    batchId
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
    public List<Map<String, Object>> listScenarios(String batchId) {
        return metadataQueryMapper.listScenarios(batchId);
    }

    /**
     * 查询指定批次下的 instrumentId 列表。
     * 返回：[{instrument_id, product_code}]
     */
    public List<Map<String, Object>> listInstrumentIds(String batchId) {
        return metadataQueryMapper.listInstrumentIds(batchId);
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
            case "instrumentIds":
                return listInstrumentIds(requireParam(params, "batch_id"));
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
