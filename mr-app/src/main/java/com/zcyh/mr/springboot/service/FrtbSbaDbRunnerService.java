package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FRTB SBA 数据库输入执行服务。
 */
@Service
public class FrtbSbaDbRunnerService {
    private final FrtbSbaInputQueryService inputQueryService;
    private final FrtbAggregator aggregator;

    public FrtbSbaDbRunnerService(FrtbSbaInputQueryService inputQueryService, FrtbAggregator aggregator) {
        this.inputQueryService = inputQueryService;
        this.aggregator = aggregator;
    }

    public String calculate(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        boolean needDecompose = parseNeedDecompose(req);
        int threadCount = parseThreadCount(req);
        String dataDate = requireTopLevelString(req, "data_date");

        JSONObject query = req.getJSONObject("query");
        if (query == null) {
            query = new JSONObject();
        }

        List<String> treeIdList = parseStringList(query.getJSONArray("tree_id_list"), "query.tree_id_list");
        List<String> groupTypeList = parseStringList(query.getJSONArray("group_type_list"), "query.group_type_list");
        List<FrtbInput> inputList = inputQueryService.queryInputs(dataDate, treeIdList, groupTypeList);
        if (inputList == null || inputList.isEmpty()) {
            throw new IllegalArgumentException("未查到 frtb_sba 输入数据");
        }

        // DB 模式直接消费上游按 tree_id / group_type / group_value 组织好的输入。
        // 其中 TOTAL 维度由上游保证存在，这里不再额外合成 tree 级总输入，只负责按既有维度执行汇总。
        Map<String, List<FrtbInput>> tasks = inputQueryService.groupByTreeIdAndGroupValue(inputList);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("未生成有效的 frtb_sba 组批任务");
        }

        return JSON.toJSONString(
                aggregator.calculateBatch(tasks, needDecompose, threadCount),
                JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static boolean parseNeedDecompose(JSONObject req) {
        Boolean needDecompose = req.getBoolean("need_decompose");
        return needDecompose == null ? Boolean.TRUE : needDecompose;
    }

    private static int parseThreadCount(JSONObject req) {
        Integer threadCount = req.getInteger("thread_count");
        if (threadCount == null) {
            return 0;
        }
        return Math.max(1, threadCount);
    }

    private static String requireTopLevelString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static List<String> parseStringList(JSONArray array, String path) {
        List<String> values = new ArrayList<String>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(path + "[" + i + "] must be a string");
            }
            String value = trimToNull((String) item);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
