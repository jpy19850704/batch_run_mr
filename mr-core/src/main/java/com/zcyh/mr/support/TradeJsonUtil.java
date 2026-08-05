package com.zcyh.mr.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * @author xujg
 * @date 2025-01-02 17:46
 */
public class TradeJsonUtil {

    /**
     * 按 productModel.json 补齐产品可运行的默认参数。
     * 这些默认值是引擎为避免非关键字段阻碍计量配置的合理口径，不属于字段兼容回退。
     */
    public static JSONObject mergeTrade(JSONObject trade, String productCode, String node) {
        JSONObject model = JSON.parseObject(FileUtils.loadData("data/model/productModel.json"));
        JSONObject proMo = (JSONObject) model.get(productCode);
        if (!Objects.isNull(proMo)) {
            JSONObject proNode = (JSONObject) proMo.getOrDefault(node, new JSONObject());
            for (String s : proNode.keySet()) {
                if (!trade.containsKey(s) || StringUtils.isBlank(Objects.toString(trade.get(s), ""))) {
                    trade.put(s, proNode.get(s));
                }
            }
        }
        return normalizeMissingFields(trade);
    }

    public static JSONObject normalizeMissingFields(JSONObject input) {
        if (input != null) {
            normalizeMap(input);
        }
        return input;
    }

    private static void normalizeMap(Map<?, ?> values) {
        Iterator<? extends Map.Entry<?, ?>> iterator = values.entrySet().iterator();
        while (iterator.hasNext()) {
            Object value = iterator.next().getValue();
            if (value == null || value instanceof CharSequence && StringUtils.isBlank(value.toString())) {
                iterator.remove();
            } else if (value instanceof Map<?, ?>) {
                normalizeMap((Map<?, ?>) value);
            } else if (value instanceof Collection<?>) {
                Collection<?> collection = (Collection<?>) value;
                normalizeCollection(collection);
                if (collection.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    private static void normalizeCollection(Collection<?> values) {
        for (Object value : values) {
            if (value instanceof Map<?, ?>) {
                normalizeMap((Map<?, ?>) value);
            } else if (value instanceof Collection<?>) {
                normalizeCollection((Collection<?>) value);
            }
        }
    }
}
