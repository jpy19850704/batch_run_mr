package com.zcyh.mr.var;

import java.math.BigDecimal;

/**
 * VaR 单一情景下的风险大类损益汇总。
 */
public class VarScenarioPnlAggregate {
    private BigDecimal allPnl = BigDecimal.ZERO;
    private BigDecimal irPnl = BigDecimal.ZERO;
    private BigDecimal fxPnl = BigDecimal.ZERO;
    private BigDecimal eqPnl = BigDecimal.ZERO;
    private BigDecimal commPnl = BigDecimal.ZERO;

    public void add(BigDecimal allPnl,
                    BigDecimal irPnl,
                    BigDecimal fxPnl,
                    BigDecimal eqPnl,
                    BigDecimal commPnl) {
        this.allPnl = this.allPnl.add(safePnl(allPnl));
        this.irPnl = this.irPnl.add(safePnl(irPnl));
        this.fxPnl = this.fxPnl.add(safePnl(fxPnl));
        this.eqPnl = this.eqPnl.add(safePnl(eqPnl));
        this.commPnl = this.commPnl.add(safePnl(commPnl));
    }

    public BigDecimal readByColumn(String pnlColumn) {
        if (VarPnlColumns.ALL_PNL.equalsIgnoreCase(pnlColumn)) {
            return allPnl;
        }
        if (VarPnlColumns.IR_PNL.equalsIgnoreCase(pnlColumn)) {
            return irPnl;
        }
        if (VarPnlColumns.FX_PNL.equalsIgnoreCase(pnlColumn)) {
            return fxPnl;
        }
        if (VarPnlColumns.EQ_PNL.equalsIgnoreCase(pnlColumn)) {
            return eqPnl;
        }
        if (VarPnlColumns.COMM_PNL.equalsIgnoreCase(pnlColumn)) {
            return commPnl;
        }
        throw new IllegalArgumentException("不支持的 VaR 损益列: " + pnlColumn);
    }

    private static BigDecimal safePnl(BigDecimal pnl) {
        return pnl == null ? BigDecimal.ZERO : pnl;
    }
}
