package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.structure.RangeAccureOptBase;
import com.zcyh.mr.product.basic.structure.StepUpOptBase;
import com.zcyh.mr.product.basic.structure.WeddingCakeBase;
import com.zcyh.mr.product.credit.Cds;
import com.zcyh.mr.product.credit.Trs;
import com.zcyh.mr.product.ir.IrsCcs;
import com.zcyh.mr.product.ir.Swaption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

public class CreditInputValidationTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2025, 12, 31);

    @Test
    public void testCreditAndIrInputDatesUseFieldFormats() {
        Cds.CdsTradeInfo cds = JSON.parseObject(
                "{\"START_DATE\":\"20260101\",\"MATURITY_DATE\":\"20261231\"}", Cds.CdsTradeInfo.class);
        Trs.TrsTradeInfo trs = JSON.parseObject(
                "{\"START_DATE\":\"20260101\",\"MATURITY_DATE\":\"20261231\"}", Trs.TrsTradeInfo.class);
        IrsCcs.IrsCcsTradeInfo irsCcs = JSON.parseObject(
                "{\"START_DATE\":\"20260101\",\"MATURITY_DATE\":\"20261231\"}", IrsCcs.IrsCcsTradeInfo.class);
        Swaption.SwaptionTradeInfo swaption = JSON.parseObject(
                "{\"MATURITY_DATE\":\"20260331\",\"UNDERLYING_START_DATE\":\"20260401\","
                        + "\"UNDERLYING_MATURITY_DATE\":\"20270401\"}", Swaption.SwaptionTradeInfo.class);

        Assertions.assertEquals(LocalDate.of(2026, 1, 1), cds.startDate);
        Assertions.assertEquals(LocalDate.of(2026, 12, 31), cds.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 1, 1), trs.startDate);
        Assertions.assertEquals(LocalDate.of(2026, 12, 31), trs.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 1, 1), irsCcs.startDate);
        Assertions.assertEquals(LocalDate.of(2026, 12, 31), irsCcs.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 3, 31), swaption.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 4, 1), swaption.underlyingStartDate);
        Assertions.assertEquals(LocalDate.of(2027, 4, 1), swaption.underlyingMaturityDate);
    }

    @Test
    public void testTrsRequiresIndependentUnderlyingNotional() {
        Trs.TrsTradeInfo info = buildTrsInfo();
        info.underlyingNotional = null;

        Trs.TrsMeasure result = new Trs(
                DATA_DATE, info, new MarketData(), new Calendar(), new JSONObject()).calc();

        Assertions.assertEquals("ERROR", result.status);
        Assertions.assertTrue(result.logs.get(0).message.contains("UNDERLYING_NOTIONAL"));
    }

    @Test
    public void testTrsAllowsZeroNotionals() {
        Trs.TrsTradeInfo info = buildTrsInfo();
        info.notional = 0.0;
        info.underlyingNotional = 0.0;

        Trs.TrsMeasure result = new Trs(
                DATA_DATE, info, new MarketData(), new Calendar(), new JSONObject()).calc();

        Assertions.assertEquals("ERROR", result.status);
        Assertions.assertTrue(result.logs.get(0).message.contains("标的债券数据不存在"));
    }

    @Test
    public void testStructuredNotionalMetadataAllowsZero() throws NoSuchFieldException {
        assertZeroAllowed(RangeAccureOptBase.RangeAccureBaseTradeInfo.class);
        assertZeroAllowed(StepUpOptBase.StepUpBaseTradeInfo.class);
        assertZeroAllowed(WeddingCakeBase.WeddingCakeBaseTradeInfo.class);
    }

    private void assertZeroAllowed(Class<?> infoClass) throws NoSuchFieldException {
        ProductInputField metadata = infoClass.getField("notional").getAnnotation(ProductInputField.class);
        Assertions.assertNotNull(metadata);
        Assertions.assertEquals("0", metadata.min());
        Assertions.assertTrue(metadata.minInclusive());
    }

    private Trs.TrsTradeInfo buildTrsInfo() {
        Trs.TrsTradeInfo info = new Trs.TrsTradeInfo();
        info.instrumentId = "UT_TRS_001";
        info.productCode = EngineConstants.PRODUCT_CODE.TRS;
        info.buyOrSell = "B";
        info.startDate = DATA_DATE.plusDays(1);
        info.maturityDate = DATA_DATE.plusYears(1);
        info.notional = 100.0;
        info.underlyingNotional = 100.0;
        info.currencyCode = "USD";
        info.underlyingCurrencyCode = "USD";
        info.discountCurve = "IR_USD";
        info.underlyingCurrencyDiscountCurve = "IR_USD";
        info.underlyingBondId = "UT_BOND_ID_001";
        info.recoveryRate = 0.4;
        info.interestRate = 0.03;
        info.interestType = "FIXED";
        info.payFreq = "3M";
        info.interestStub = "ShortStart";
        return info;
    }
}
