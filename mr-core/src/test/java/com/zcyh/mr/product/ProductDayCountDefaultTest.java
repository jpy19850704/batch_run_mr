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
        Assertions.assertEquals("actual/365", new StepUpOptBase.StepUpBaseInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Cds.CdsInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Trs.TrsInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Bond.BondInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new CapFloor.CapFloorInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new Swaption.SwaptionInfo().fixedDayCountBasis);
        Assertions.assertEquals("actual/365", new StdIrs.StdIrsInfo().dayCountBasis);
        Assertions.assertEquals("actual/365", new IrsCcs.IrsCcsInfo().payDayCountBasis);
        Assertions.assertEquals("actual/365", new IrsCcs.IrsCcsInfo().recDayCountBasis);
    }
}
