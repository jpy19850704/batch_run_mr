package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.TradeInfo;
import com.zcyh.mr.product.basic.validation.TradeValidationCollector;
import com.zcyh.mr.product.fx.FxFwd;
import com.zcyh.mr.product.ir.Bond;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeInfoValidationTest {

    @Test
    void validatesCodeDefinedRequiredFields() {
        FxFwd.FxFwdTradeInfo info = validFxFwdTradeInfo();
        info.baseDiscountCurve = null;
        JSONObject source = JSON.parseObject(JSON.toJSONString(info));

        TradeValidationCollector errors = info.validateInput(source, "FXFWD");

        assertTrue(errors.getErrors().stream().anyMatch(error -> error.startsWith("BASE_DISCOUNT_CURVE:")));
    }

    @Test
    void domainValidationIgnoresCase() {
        FxFwd.FxFwdTradeInfo info = validFxFwdTradeInfo();
        info.buyOrSell = "b";
        JSONObject source = JSON.parseObject(JSON.toJSONString(info));

        TradeValidationCollector errors = info.validateInput(source, "FXFWD");

        assertFalse(errors.getErrors().stream().anyMatch(error -> error.startsWith("BUY_OR_SELL:")));
    }

    @Test
    void codeDefaultDoesNotRequireSourceField() {
        Bond.BondTradeInfo info = new Bond.BondTradeInfo();
        JSONObject source = new JSONObject();

        TradeValidationCollector errors = info.validateInput(source, "BOND");

        assertFalse(errors.getErrors().stream().anyMatch(error -> error.startsWith("DAY_COUNT_BASIS:")));
    }

    @Test
    void optionalConfigurationDoesNotCreateRequiredField() {
        OptionalConfiguredTradeInfo info = new OptionalConfiguredTradeInfo();

        TradeValidationCollector errors = info.validateInput(new JSONObject(), "BOND");

        assertTrue(errors.getErrors().isEmpty());
    }

    private static FxFwd.FxFwdTradeInfo validFxFwdTradeInfo() {
        FxFwd.FxFwdTradeInfo info = new FxFwd.FxFwdTradeInfo();
        info.productCode = "FXFWD";
        info.instrumentId = "T_FXFWD_001";
        info.buyOrSell = "B";
        info.underlyingCurrencyCode = "USD";
        info.baseCurrencyCode = "CNY";
        info.underlyingCurrencyNotional = 100.0;
        info.baseCurrencyNotional = 720.0;
        info.settleDate = LocalDate.of(2026, 12, 31);
        info.underlyingDiscountCurve = "IR_USD";
        info.baseDiscountCurve = "IR_CNY";
        return info;
    }

    private static final class OptionalConfiguredTradeInfo implements TradeInfo {
        @JSONField(name = "CURRENCY_CODE")
        private String currencyCode;
    }
}
