package com.zcyh.mr.springboot.measurement.var;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.output.cache.VarDetailCacheService;
import com.zcyh.mr.var.VarDimensionGroup;
import com.zcyh.mr.var.VarPnlColumns;
import com.zcyh.mr.var.VarScenarioKey;
import com.zcyh.mr.var.VarScenarioPnlAggregate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.zcyh.mr.springboot.measurement.var.VarRequestParser.nullSafe;

/**
 * VaR 维度明细构建与缓存写入器。
 */
final class VarDetailWriter {
    private final VarDetailCacheService cacheService;

    VarDetailWriter(VarDetailCacheService cacheService) {
        this.cacheService = cacheService;
    }

    boolean isAvailable() {
        return cacheService != null;
    }

    Long getTtlSeconds() {
        return cacheService == null ? null : cacheService.getTtlSeconds();
    }

    boolean write(String requestId,
                  BigDecimal quantile,
                  String ruleId,
                  String scenarioId,
                  VarDimensionGroup dimensionGroup) {
        if (cacheService == null) {
            return false;
        }
        JSONObject detail = buildDetail(requestId, quantile, ruleId, scenarioId, dimensionGroup);
        return cacheService.putDimensionDetail(
                requestId,
                quantile.stripTrailingZeros().toPlainString(),
                ruleId,
                scenarioId,
                dimensionGroup.getGroupType(),
                dimensionGroup.getGroupValue(),
                detail);
    }

    private JSONObject buildDetail(String requestId,
                                   BigDecimal quantile,
                                   String ruleId,
                                   String scenarioId,
                                   VarDimensionGroup dimensionGroup) {
        if (dimensionGroup == null || dimensionGroup.getScenarioPnls() == null) {
            throw new IllegalArgumentException("维度明细为空，无法构建 detail_file");
        }
        List<ScenarioDetailRow> detailRows = toScenarioDetailRows(dimensionGroup.getScenarioPnls());
        Map<VarScenarioKey, Integer> rankAll = rankByPnlColumn(detailRows, VarPnlColumns.ALL_PNL);
        Map<VarScenarioKey, Integer> rankIr = rankByPnlColumn(detailRows, VarPnlColumns.IR_PNL);
        Map<VarScenarioKey, Integer> rankFx = rankByPnlColumn(detailRows, VarPnlColumns.FX_PNL);
        Map<VarScenarioKey, Integer> rankEq = rankByPnlColumn(detailRows, VarPnlColumns.EQ_PNL);
        Map<VarScenarioKey, Integer> rankComm = rankByPnlColumn(detailRows, VarPnlColumns.COMM_PNL);

        detailRows.sort(detailComparator(VarPnlColumns.ALL_PNL));
        JSONArray rows = new JSONArray();
        for (ScenarioDetailRow row : detailRows) {
            JSONObject item = new JSONObject();
            item.put("scenario_id", row.scenarioKey.getScenarioId());
            item.put("subscenario_id", row.scenarioKey.getSubScenarioId());
            item.put("scenario_name", row.scenarioKey.getScenarioName());
            item.put("all_pnl", row.aggregate.readByColumn(VarPnlColumns.ALL_PNL));
            item.put("ir_pnl", row.aggregate.readByColumn(VarPnlColumns.IR_PNL));
            item.put("fx_pnl", row.aggregate.readByColumn(VarPnlColumns.FX_PNL));
            item.put("eq_pnl", row.aggregate.readByColumn(VarPnlColumns.EQ_PNL));
            item.put("comm_pnl", row.aggregate.readByColumn(VarPnlColumns.COMM_PNL));
            item.put("rank_all", rankAll.get(row.scenarioKey));
            item.put("rank_ir", rankIr.get(row.scenarioKey));
            item.put("rank_fx", rankFx.get(row.scenarioKey));
            item.put("rank_eq", rankEq.get(row.scenarioKey));
            item.put("rank_comm", rankComm.get(row.scenarioKey));
            rows.add(item);
        }

        JSONObject detail = new JSONObject();
        detail.put("request_id", requestId);
        detail.put("quantile", quantile.stripTrailingZeros().toPlainString());
        detail.put("rule_id", ruleId);
        detail.put("scenario_id", scenarioId);
        detail.put("group_type", dimensionGroup.getGroupType());
        detail.put("group_value", dimensionGroup.getGroupValue());
        detail.put("total_rows", dimensionGroup.getScenarioPnls().size());
        detail.put("rows", rows);
        return detail;
    }

    private static List<ScenarioDetailRow> toScenarioDetailRows(
            Map<VarScenarioKey, VarScenarioPnlAggregate> scenarioPnls) {
        List<ScenarioDetailRow> rows = new ArrayList<>();
        for (Map.Entry<VarScenarioKey, VarScenarioPnlAggregate> entry : scenarioPnls.entrySet()) {
            rows.add(new ScenarioDetailRow(entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    private static Map<VarScenarioKey, Integer> rankByPnlColumn(List<ScenarioDetailRow> rows,
                                                                 String pnlColumn) {
        List<ScenarioDetailRow> sorted = new ArrayList<>(rows);
        sorted.sort(detailComparator(pnlColumn));
        Map<VarScenarioKey, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.put(sorted.get(i).scenarioKey, i + 1);
        }
        return rank;
    }

    private static Comparator<ScenarioDetailRow> detailComparator(String pnlColumn) {
        return Comparator
                .comparing((ScenarioDetailRow row) -> safePnl(row.aggregate.readByColumn(pnlColumn)))
                .thenComparing(row -> nullSafe(row.scenarioKey.getScenarioId()))
                .thenComparing(row -> nullSafe(row.scenarioKey.getSubScenarioId()))
                .thenComparing(row -> nullSafe(row.scenarioKey.getScenarioName()));
    }

    private static BigDecimal safePnl(BigDecimal pnl) {
        return pnl == null ? BigDecimal.ZERO : pnl;
    }

    private static final class ScenarioDetailRow {
        private final VarScenarioKey scenarioKey;
        private final VarScenarioPnlAggregate aggregate;

        private ScenarioDetailRow(VarScenarioKey scenarioKey, VarScenarioPnlAggregate aggregate) {
            this.scenarioKey = scenarioKey;
            this.aggregate = aggregate;
        }
    }
}
