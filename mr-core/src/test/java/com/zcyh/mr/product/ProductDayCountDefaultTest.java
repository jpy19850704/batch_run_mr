package com.zcyh.mr.product;

import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import com.zcyh.mr.product.basic.structure.StepUpOptBase;
import com.zcyh.mr.product.credit.Cds;
import com.zcyh.mr.product.credit.Trs;
import com.zcyh.mr.product.ir.Bond;
import com.zcyh.mr.product.ir.CapFloor;
import com.zcyh.mr.product.ir.IrsCcs;
import com.zcyh.mr.product.ir.StdIrs;
import com.zcyh.mr.product.ir.Swaption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ProductDayCountDefaultTest {

    @Test
    public void testAllProductDayCountDefaults() {
        Assertions.assertEquals("actual/365", new StructuredCashflow.ScfInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new StepUpOptBase.StepUpBaseTradeInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Cds.CdsTradeInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Trs.TrsTradeInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Bond.BondTradeInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new CapFloor.CapFloorTradeInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Swaption.SwaptionTradeInfo().fixedDayCountBasis);
        Assertions.assertEquals("actual/365", new StdIrs.StdIrsTradeInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new IrsCcs.IrsCcsTradeInfo().payDayCountBasis);
        Assertions.assertEquals("actual/365", new IrsCcs.IrsCcsTradeInfo().recDayCountBasis);
    }
}
