package com.zcyh.mr.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * @author xujg
 * @date 2025-01-02 17:46
 */
public class TradeJsonUtil {

    public static JSONObject mergeTrade(JSONObject trade, String productCode, String node) {
        JSONObject model = JSON.parseObject(FileUtils.loadData("data/model/productModel.json"));
        JSONObject proMo = (JSONObject) model.get(productCode);
        if (Objects.isNull(proMo)) return trade;
        JSONObject proNode = (JSONObject) proMo.getOrDefault(node, new JSONObject());
        for (String s : proNode.keySet()) {
            if (!trade.containsKey(s) || StringUtils.isBlank(Objects.toString(trade.get(s),""))){
                trade.put(s,proNode.get(s));
            }
        }
        return trade;
    }
}
