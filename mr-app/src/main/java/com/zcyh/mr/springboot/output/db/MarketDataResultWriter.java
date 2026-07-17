package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 写入计量使用的市场数据明细。
 */
@Service
public class MarketDataResultWriter {
    private static final String TARGET_TABLE = "TB_OUT_MARKET_DATA_DETAIL";
    private static final List<String> COLUMN_LIST = Arrays.asList(
            "BATCH_ID",
            "DATA_DATE",
            "CURVE_TYPE",
            "CURVE_ID",
            "CURVE_DATA_JSON",
            "CREATED_AT",
            "UPDATED_AT"
    );
    private static final String COLUMNS = String.join(",", COLUMN_LIST);

    private final DorisStreamLoadService dorisStreamLoadService;

    public MarketDataResultWriter(DorisStreamLoadService dorisStreamLoadService) {
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    String tableName() {
        return TARGET_TABLE;
    }

    List<String> writeColumns() {
        return COLUMN_LIST;
    }

    void write(CalcPersistContext context) {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<String, JSONObject>();
        appendMarketDataByPriority(merged, context == null ? null : context.inputMarketData, true, "INPUT");
        appendMarketDataByPriority(merged, context == null ? null : context.generatedMarketData, false, "GENERATED");
        if (merged.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                COLUMNS,
                "market_data_" + context.batchId + "_" + context.jobId,
                CalcResultPersistSupport.DEFAULT_BATCH_SIZE);
        for (JSONObject curve : merged.values()) {
            if (curve == null) {
                continue;
            }
            buffer.appendRow(
                    context.batchId,
                    CalcResultPersistSupport.normalizeDataDate(context.dataDate),
                    resolveCurveType(curve),
                    resolveCurveId(curve),
                    CalcResultPersistSupport.toJsonString(curve),
                    context.createdAt,
                    context.updatedAt
            );
        }
        buffer.flush();
    }

    private void appendMarketDataByPriority(LinkedHashMap<String, JSONObject> merged, JSONArray marketData,
                                            boolean overrideOnConflict, String sourceTag) {
        if (merged == null || marketData == null || marketData.isEmpty()) {
            return;
        }
        for (int i = 0; i < marketData.size(); i++) {
            JSONObject curve = marketData.getJSONObject(i);
            if (curve == null) {
                continue;
            }
            String key = buildCurveMergeKey(curve, sourceTag, i);
            if (overrideOnConflict || !merged.containsKey(key)) {
                merged.put(key, curve);
            }
        }
    }

    private String buildCurveMergeKey(JSONObject curve, String sourceTag, int index) {
        if (curve == null) {
            throw new IllegalArgumentException("市场数据为空，无法构建合并键: source=" + sourceTag + ", index=" + index);
        }
        String curveType = resolveCurveType(curve);
        String curveId = resolveCurveId(curve);
        if (curveType == null || curveId == null) {
            throw new IllegalArgumentException("市场数据缺少 CURVE_TYPE 或 CURVE_ID/FIXING_ID，无法构建合并键: source="
                    + sourceTag + ", index=" + index);
        }
        return curveType + "|" + curveId;
    }

    private static String resolveCurveType(JSONObject curve) {
        if (curve == null) {
            return null;
        }
        return CalcResultPersistSupport.trimToNull(curve.getString("CURVE_TYPE"));
    }

    private static String resolveCurveId(JSONObject curve) {
        if (curve == null) {
            return null;
        }
        String curveId = CalcResultPersistSupport.trimToNull(curve.getString("CURVE_ID"));
        if (curveId == null) {
            curveId = CalcResultPersistSupport.trimToNull(curve.getString("FIXING_ID"));
        }
        return curveId;
    }
}
