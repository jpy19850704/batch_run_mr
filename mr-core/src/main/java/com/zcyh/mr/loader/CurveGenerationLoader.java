package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration;

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
        return JSON.parseArray(curveGenerationArray.toJSONString(), CurveGeneration.CurveInput.class);
    }
}
