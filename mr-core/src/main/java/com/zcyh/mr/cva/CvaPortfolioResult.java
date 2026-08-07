package com.zcyh.mr.cva;

import java.util.List;

public class CvaPortfolioResult {
    public String calculationMode;
    public String reductionReason;
    public double derivativeNotionalCny;
    public double kReduced;
    public double kHedged;
    public double kFull;
    public double cvaCapitalRequirement;
    public double cvaRiskWeightedAssets;
    public double indexHedge;
    public List<CvaCounterpartyResult> counterparties;
    public List<CvaNettingSetResult> nettingSets;
    public List<CvaHedgeResult> hedges;
}
