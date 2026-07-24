package com.zcyh.mr.springboot.measurement.saccr;

import java.time.LocalDate;

/**
 * SA-CCR 押品计量审计输出行。
 */
public class SaccrCollateralOutputRow {
    public String batchId;
    public LocalDate dataDate;
    public String collateralId;
    public String collateralScope;
    public String nettingSetId;
    public String instrumentId;
    public String collateralType;
    public String direction;
    public String collateralCcy;
    public double marketValue;
    public double fxRateToCny;
    public double haircutRate;
    public double adjustedValueCny;
}
