package com.zcyh.mr.product.basic.frtb;

/**
 * FRTB 重估结果最小估值对象。
 * 仅保留公共 builder 真正需要的估值与人民币估值。
 */
public class MeasureValuation {

    public double valuation;
    public double valuationCny;

    public MeasureValuation() {
    }

    public MeasureValuation(double valuation, double valuationCny) {
        this.valuation = valuation;
        this.valuationCny = valuationCny;
    }

    public static MeasureValuation of(double valuation, double valuationCny) {
        return new MeasureValuation(valuation, valuationCny);
    }
}
