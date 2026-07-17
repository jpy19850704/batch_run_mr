package com.zcyh.mr.springboot.measurement.var;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.var.VarMeasure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * VaR 执行结果组装器。
 */
final class VarResultAssembler {
    private static final String DETAIL_FETCH_API = "/api/engine/var/detail";

    private VarResultAssembler() {
    }

    static List<JSONObject> initQuantileGroups(List<BigDecimal> quantiles) {
        List<JSONObject> groups = new ArrayList<>();
        for (BigDecimal quantile : quantiles) {
            JSONObject group = new JSONObject();
            group.put("quantile", quantile.stripTrailingZeros().toPlainString());
            group.put("rule_results", new JSONArray());
            groups.add(group);
        }
        return groups;
    }

    static String assemble(String batchId,
                           String dataDate,
                           List<BigDecimal> quantiles,
                           List<VarMeasure> measures,
                           List<JSONObject> quantileGroups,
                           boolean includeDetail,
                           boolean includeDetailRequested,
                           String requestId,
                           int detailCacheCount,
                           Long detailCacheTtlSeconds) {
        JSONObject summaryFile = new JSONObject();
        summaryFile.put("batch_id", batchId);
        summaryFile.put("data_date", dataDate);
        summaryFile.put("quantiles", toQuantileArray(quantiles));
        summaryFile.put("measure", toMeasureArray(measures));
        summaryFile.put("quantile_groups", toJsonArray(quantileGroups));

        JSONObject detailFile = new JSONObject();
        detailFile.put("enabled", includeDetail);
        detailFile.put("requested", includeDetailRequested);
        detailFile.put("request_id", requestId);
        detailFile.put("fetch_api", DETAIL_FETCH_API);
        detailFile.put("cache_entries", detailCacheCount);
        if (detailCacheTtlSeconds != null) {
            detailFile.put("ttl_seconds", detailCacheTtlSeconds);
        }

        JSONObject result = new JSONObject();
        result.put("request_id", requestId);
        result.put("summary_file", summaryFile);
        result.put("detail_file", detailFile);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static JSONArray toQuantileArray(List<BigDecimal> quantiles) {
        JSONArray array = new JSONArray();
        for (BigDecimal quantile : quantiles) {
            array.add(quantile.stripTrailingZeros().toPlainString());
        }
        return array;
    }

    private static JSONArray toMeasureArray(List<VarMeasure> values) {
        JSONArray array = new JSONArray();
        if (values == null) {
            return array;
        }
        for (VarMeasure value : values) {
            if (value != null) {
                array.add(value.code());
            }
        }
        return array;
    }

    private static JSONArray toJsonArray(List<JSONObject> jsonObjects) {
        JSONArray array = new JSONArray();
        array.addAll(jsonObjects);
        return array;
    }
}
