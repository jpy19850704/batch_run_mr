package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FRTB SA (SENS) 引擎适配器。
 */
public class FrtbSaEngineAdapter implements EngineAdapter {
    public static final String CODE = "frtb_sba";
    private final FrtbAggregator aggregator;

    public FrtbSaEngineAdapter(FrtbAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "FRTB SA (SENS) engine adapter based on FrtbAggregator";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }

        if (req.containsKey("batch_tasks")) {
            throw new IllegalArgumentException("batch_tasks 已废弃，请改用 source_type=db 由系统内部组批");
        }

        boolean needDecompose = parseNeedDecompose(req);
        JSONArray inputListJson = req.getJSONArray("frtb_input_list");
        if (inputListJson == null || inputListJson.isEmpty()) {
            throw new IllegalArgumentException("frtb_input_list is required and cannot be empty");
        }
        List<FrtbInput> inputList = parseFrtbInputList(inputListJson, "frtb_input_list");
        Map<String, Object> result = aggregator.calculateAsMap(inputList, needDecompose);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static boolean parseNeedDecompose(JSONObject req) {
        Boolean needDecompose = req.getBoolean("need_decompose");
        return needDecompose == null ? Boolean.TRUE : needDecompose;
    }

    private static List<FrtbInput> parseFrtbInputList(JSONArray array, String path) {
        List<FrtbInput> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) {
                throw new IllegalArgumentException(path + "[" + i + "] must be an object");
            }
            list.add(parseFrtbInput((JSONObject) item, path, i));
        }
        return list;
    }

    private static FrtbInput parseFrtbInput(JSONObject obj, String path, int index) {
        FrtbInput input = new FrtbInput();
        input.setTreeId(trimToNull(obj.getString("tree_id")));
        input.setGroupType(trimToNull(obj.getString("group_type")));
        input.setGroupValue(trimToNull(obj.getString("group_value")));
        input.setRiskFactorId(requireString(obj, "risk_factor_id", path, index));
        input.setRiskFactorVertex1(trimToNull(obj.getString("risk_factor_vertex_1")));
        input.setRiskFactorVertex2(trimToNull(obj.getString("risk_factor_vertex_2")));
        input.setRiskFactorClass(requireString(obj, "risk_factor_class", path, index));
        input.setRiskFactorBucket(requireString(obj, "risk_factor_bucket", path, index));
        input.setRiskFactorType(trimToNull(obj.getString("risk_factor_type")));
        input.setSensitivityType(requireString(obj, "sensitivity_type", path, index));
        input.setSensitivityValRptCurrCny(requireBigDecimal(obj, "sensitivity_val_rpt_curr_cny", path, index));
        input.setDataDate(trimToNull(obj.getString("data_date")));
        input.setModifier(trimToNull(obj.getString("modifier")));
        return input;
    }

    private static String requireString(JSONObject obj, String key, String path, int index) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(path + "[" + index + "]." + key + " is required");
        }
        return value;
    }

    private static BigDecimal requireBigDecimal(JSONObject obj, String key, String path, int index) {
        BigDecimal value = obj.getBigDecimal(key);
        if (value == null) {
            throw new IllegalArgumentException(path + "[" + index + "]." + key + " is required");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

