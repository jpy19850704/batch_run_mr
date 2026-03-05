package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import com.zcyh.mr.product.bond.Bond;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredCashflowTest {
    @Test
    void testNoneNotionalExchangeSkipsPrincipalFlowButKeepsNotionalChange() {
        String data = FileUtils.loadData("data/trade/amortBond_test.json");
        Loader loader = new Loader(data);
        HashMap<String, Object> trade = loader.getTrades().get(0);
        Bond.BondInfo bondInfo = JSONObject.parseObject(new JSONObject(trade).toString(), Bond.BondInfo.class);

        StructuredCashflow.ScfInfo scfInfo = new StructuredCashflow.ScfInfo();
        scfInfo.issueDate = bondInfo.issueDate;
        scfInfo.maturityDate = bondInfo.maturityDate;
        scfInfo.interestStub = bondInfo.interestStub;
        scfInfo.interestType = bondInfo.interestType;
        scfInfo.interestRate = bondInfo.interestRate;
        scfInfo.payFreq = bondInfo.payFreq;
        scfInfo.dayCountBasis = bondInfo.dayCountBasis;
        scfInfo.settleCalendar = bondInfo.settleCalendar;
        scfInfo.settleRule = bondInfo.settleRule;
        scfInfo.settleDayoff = bondInfo.settleDayoff;
        scfInfo.discountCurve = bondInfo.discountCurve;
        scfInfo.notional = bondInfo.notional;
        scfInfo.nationalFlag = "none";
        scfInfo.includeTodayCashflow = bondInfo.includeTodayCashflow;
        scfInfo.couponProrated = bondInfo.couponProrated;
        scfInfo.amortizationSchedule = bondInfo.amortizationSchedule;

        assertNotNull(scfInfo.amortizationSchedule);
        assertFalse(scfInfo.amortizationSchedule.isEmpty());

        StructuredCashflow scf = new StructuredCashflow(loader.getDataDate(), scfInfo, loader.getMarketData(), new Calendar());
        scf.calc();
        List<StructuredCashflow.Cashflow> cashflows = scf.getCashflowList();

        assertFalse(cashflows.isEmpty());

        long principalCount = cashflows.stream()
                .filter(cf -> "PRINCIPAL".equalsIgnoreCase(cf.cashType))
                .count();
        assertEquals(0, principalCount);

        boolean hasNotionalDecrease = cashflows.stream()
                .filter(cf -> "interest".equalsIgnoreCase(cf.cashType))
                .anyMatch(cf -> cf.startNotional != null
                        && cf.endNotional != null
                        && cf.endNotional < cf.startNotional);
        assertTrue(hasNotionalDecrease);
    }
}
