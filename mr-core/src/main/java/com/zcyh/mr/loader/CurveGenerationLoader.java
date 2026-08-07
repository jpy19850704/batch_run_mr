package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * CurveGeneration 输入加载器。
 */
public class CurveGenerationLoader {
    /**
     * 加载 curve_generation 输入。
     */
    public List<CurveGeneration.CurveInput> load(JSONArray curveGenerationArray) {
        if (curveGenerationArray == null || curveGenerationArray.isEmpty()) {
            return new ArrayList<>();
        }
        List<CurveGeneration.CurveInput> inputs = new ArrayList<>(curveGenerationArray.size());
        for (int i = 0; i < curveGenerationArray.size(); i++) {
            JSONObject item = curveGenerationArray.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("curve_generation[" + i + "] 必须为JSON对象");
            }
            String curveId = item.getString("CURVE_ID");
            String dataDate = item.getString("DATA_DATE");
            if (dataDate != null) {
                try {
                    LocalDate.parse(dataDate, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException ex) {
                    throw new IllegalArgumentException(curveLabel(curveId, i)
                            + " 的 DATA_DATE 格式必须为 yyyy-MM-dd: " + dataDate, ex);
                }
            }
            try {
                inputs.add(JSON.parseObject(item.toJSONString(), CurveGeneration.CurveInput.class));
            } catch (JSONException ex) {
                throw new IllegalArgumentException(curveLabel(curveId, i) + " 输入字段解析失败", ex);
            }
        }
        return inputs;
    }

    private String curveLabel(String curveId, int index) {
        if (curveId == null || curveId.trim().isEmpty()) {
            return "curve_generation[" + index + "]";
        }
        return "曲线 " + curveId;
    }
}
