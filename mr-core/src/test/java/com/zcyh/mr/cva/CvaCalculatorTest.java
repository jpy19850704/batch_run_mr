package com.zcyh.mr.cva;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CvaCalculatorTest {

    @Test
    void noEligibleHedgeUsesReducedBaCva() {
        CvaPortfolioResult result = CvaCalculator.calculate(
                List.of(nettingSet("NS_1", 2.0, 140.0)),
                List.of(counterparty("CP_1", "FINANCIAL", "IG")),
                List.of(),
                800_000_000_000.0);

        assertEquals("REDUCED", result.calculationMode);
        assertEquals(result.kReduced, result.kFull, 1e-12);
        assertEquals(result.kReduced, result.kHedged, 1e-12);
        assertEquals(0, result.hedges.size());
        assertTrue(result.cvaRiskWeightedAssets > 0.0);
    }

    @Test
    void eligibleSingleNameHedgeUsesFullBaCva() {
        CvaHedge hedge = new CvaHedge();
        hedge.hedgeId = "H_1";
        hedge.hedgeType = "SINGLE_NAME_CDS";
        hedge.counterpartyId = "CP_1";
        hedge.relationType = "DIRECT";
        hedge.referenceIndustry = "FINANCIAL";
        hedge.referenceCreditQuality = "IG";
        hedge.remainingMaturity = 2.0;
        hedge.notional = 20.0;

        CvaPortfolioResult result = CvaCalculator.calculate(
                List.of(nettingSet("NS_1", 2.0, 140.0)),
                List.of(counterparty("CP_1", "FINANCIAL", "IG")),
                List.of(hedge),
                900_000_000_000.0);

        assertEquals("FULL", result.calculationMode);
        assertTrue(result.kHedged < result.kReduced);
        assertTrue(result.kFull < result.kReduced);
        assertEquals(1, result.hedges.size());
        assertTrue(result.counterparties.get(0).singleNameHedge > 0.0);
    }

    @Test
    void indirectHedgeRetainsMisalignmentCharge() {
        CvaHedge hedge = new CvaHedge();
        hedge.hedgeId = "H_2";
        hedge.hedgeType = "SINGLE_NAME_CDS";
        hedge.counterpartyId = "CP_1";
        hedge.relationType = "SAME_SECTOR_REGION";
        hedge.referenceIndustry = "FINANCIAL";
        hedge.referenceCreditQuality = "IG";
        hedge.remainingMaturity = 2.0;
        hedge.notional = 20.0;

        CvaPortfolioResult result = CvaCalculator.calculate(
                List.of(nettingSet("NS_1", 2.0, 140.0)),
                List.of(counterparty("CP_1", "FINANCIAL", "IG")),
                List.of(hedge),
                900_000_000_000.0);

        assertTrue(result.counterparties.get(0).hedgingMisalignment > 0.0);
        assertTrue(result.kHedged > 0.0);
    }

    private static CvaNettingSet nettingSet(String id, double maturity, double ead) {
        CvaNettingSet value = new CvaNettingSet();
        value.nettingSetId = id;
        value.counterpartyId = "CP_1";
        value.effectiveMaturity = maturity;
        value.ead = ead;
        return value;
    }

    private static CvaCounterparty counterparty(String id, String industry, String quality) {
        CvaCounterparty value = new CvaCounterparty();
        value.counterpartyId = id;
        value.industry = industry;
        value.creditQuality = quality;
        return value;
    }
}
