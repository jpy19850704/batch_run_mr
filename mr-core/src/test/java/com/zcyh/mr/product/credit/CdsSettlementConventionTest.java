package com.zcyh.mr.product.credit;

import com.zcyh.mr.product.ir.Bond;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CdsSettlementConventionTest {

    @Test
    void appliesCdsSettlementConventionToGeneratedBondCashflows() {
        Cds.CdsTradeInfo cdsInfo = new Cds.CdsTradeInfo();
        cdsInfo.settleCalendar = "BEJNYC";
        cdsInfo.settleRule = "Modified_Following";
        cdsInfo.settleDayoff = 2;
        Bond.BondTradeInfo bondInfo = new Bond.BondTradeInfo();

        Cds.applySettlementConvention(bondInfo, cdsInfo);

        Assertions.assertEquals("BEJNYC", bondInfo.settleCalendar);
        Assertions.assertEquals("Modified_Following", bondInfo.settleRule);
        Assertions.assertEquals(2, bondInfo.settleDayoff);
    }

    @Test
    void leavesSettlementConventionDisabledWhenRuleIsNull() {
        Cds.CdsTradeInfo cdsInfo = new Cds.CdsTradeInfo();
        cdsInfo.settleRule = null;
        cdsInfo.settleDayoff = null;
        Bond.BondTradeInfo bondInfo = new Bond.BondTradeInfo();

        Cds.applySettlementConvention(bondInfo, cdsInfo);

        Assertions.assertNull(bondInfo.settleRule);
        Assertions.assertEquals(0, bondInfo.settleDayoff);
    }
}
