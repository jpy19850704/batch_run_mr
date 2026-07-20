package com.zcyh.mr.product.basic.validation;

import com.alibaba.fastjson2.JSONObject;

/**
 * 产品交易输入统一契约。
 */
public interface TradeInfo {

    default TradeValidationCollector validateInput(JSONObject source, String productCode) {
        TradeValidationCollector errors = new TradeValidationCollector();
        ProductInputFieldValidator.validate(this, productCode, errors);
        validateBusinessRules(errors);
        TradeValidationRuleEnhancer.validate(this, source, productCode, "TRADE", errors);
        return errors;
    }

    default void validateBusinessRules(TradeValidationCollector errors) {
    }
}
