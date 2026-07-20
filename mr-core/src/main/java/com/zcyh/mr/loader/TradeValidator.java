package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.product.basic.validation.TradeValidationCollector;
import com.zcyh.mr.product.basic.validation.TradeValidationRuleEnhancer;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 未映射为TradeInfo的交易子节点附加校验入口。
 */
public class TradeValidator {

    /**
     * 校验数据对象的字段
     *
     * @param data        待校验的 JSON 对象（交易或底层资产数据）
     * @param productCode 产品类型代码
     * @param node        数据节点名（TRADE / UNDERLYING_DATA）
     * @return 错误信息列表，空列表表示校验通过
     */
    public static List<String> validate(JSONObject data, String productCode, String node) {
        TradeValidationCollector errors = new TradeValidationCollector();
        if (data == null) {
            return errors.getErrors();
        }

        if ("TRADE".equals(node)) {
            validateRequiredText(data, "INSTRUMENT_ID", errors);
            validateRequiredText(data, "PRODUCT_CODE", errors);
        }
        TradeValidationRuleEnhancer.validatePresentFields(
                data,
                productCode,
                node,
                new LinkedHashSet<String>(data.keySet()),
                errors);
        return errors.getErrors();
    }

    private static void validateRequiredText(JSONObject data, String field, TradeValidationCollector errors) {
        Object val = data.get(field);
        String strVal = (val != null) ? val.toString().trim() : "";
        if (val == null || strVal.isEmpty()) {
            errors.add(field, "不能为空");
        }
    }
}
