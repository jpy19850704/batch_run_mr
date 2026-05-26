# FRTB SA Basel Rulebook (SBM + DRC only)

## 1. Purpose

This file is a local baseline for future code checks in this repository.
Scope is limited to FRTB Standardised Approach:

- SBM (Sensitivities-Based Method), MAR21
- DRC (Default Risk Capital), MAR22

Out of scope:

- RRAO (MAR23)
- IMA (MAR30+)
- Trading book boundary rules (RBC25), except where needed for context

## 2. Authoritative Sources

Primary sources (official Basel / BIS):

- Basel market risk standard (d457, Jan 2019 rev Feb 2019):  
  https://www.bis.org/bcbs/publ/d457.pdf
- Basel market risk standard with FAQs (d457_faq):  
  https://www.bis.org/bcbs/publ/d457_faq.pdf
- Consolidated framework chapter links (reference only):  
  https://www.bis.org/basel_framework/chapter/MAR/21.htm  
  https://www.bis.org/basel_framework/chapter/MAR/22.htm

## 3. SA Structure (high level)

Per MAR20, SA capital is a simple sum of three blocks:

- SBM capital
- DRC capital
- RRAO capital

For this file, only SBM + DRC are normative.

Reference:

- d457: MAR20.4

## 4. SBM Normative Rules (MAR21)

## 4.1 Core concept

SBM uses sensitivities to prescribed risk factors:

- Delta
- Vega
- Curvature

Aggregation order:

1. Within bucket
2. Across buckets in same risk class
3. Across risk classes

References:

- d457: MAR21.1, MAR21.4, MAR21.5, MAR21.7

## 4.2 Risk classes and parameters

Risk-factor and bucket definitions are prescribed per risk class.

- Delta parameter sections: MAR21.39 to MAR21.89
- Vega parameter sections: MAR21.90 to MAR21.95
- Curvature parameter sections: MAR21.96 to MAR21.101

## 4.3 Correlation scenarios

SBM must be computed under 3 correlation scenarios:

- Medium
- High
- Low

Capital is the max across the three scenario totals.

Reference:

- d457: MAR21.6, MAR21.7

## 4.4 Computation sequence for delta/vega

For each risk class:

1. Determine sensitivities to prescribed risk factors.
2. Apply prescribed risk weights to sensitivities.
3. Aggregate weighted sensitivities to bucket capital.
4. Aggregate bucket capital across buckets using prescribed gamma correlations.

Reference:

- d457: MAR21.4

## 4.5 Curvature

Curvature uses up/down shocks by risk factor and aggregates with prescribed curvature correlations.

Reference:

- d457: MAR21.5, MAR21.96 to MAR21.101

## 5. DRC Normative Rules (MAR22)

## 5.1 Objective and scope

DRC captures JTD risk not captured by SBM credit spread shocks and allows limited hedging recognition.

Portfolios in scope:

- Non-securitisation
- Securitisation non-CTP
- Securitisation CTP

References:

- d457: MAR22.1, MAR22.2

## 5.2 Standard step-by-step structure

For each DRC risk class:

1. Compute gross JTD per exposure.
2. Offset and derive net long / net short JTD positions (subject to rule limits).
3. Apply risk weights to net JTD.
4. Aggregate bucket and total DRC with prescribed hedge recognition.

Reference:

- d457: MAR22.3

## 5.3 Non-securitisation DRC

Gross JTD is based on LGD, notional and cumulative P&L:

- JTD(long) = max(LGD * notional + P&L, 0)
- JTD(short) = min(LGD * notional + P&L, 0)

LGD baseline:

- Equity / non-senior debt: 100%
- Senior debt: 75%
- Covered bonds: 25%

Hedge benefit ratio:

- HBR = sum(net JTD long) / (sum(net JTD long) + sum(abs(net JTD short)))

Default risk weights (Table 2):

- AAA 0.5%
- AA 2%
- A 3%
- BBB 6%
- BB 15%
- B 30%
- CCC 50%
- Unrated 15%
- Defaulted 100%

References:

- d457: MAR22.11, MAR22.12, MAR22.23, MAR22.24, MAR22.25

## 5.4 Securitisation non-CTP DRC

Important constraints:

- Offsetting is limited to the same securitisation exposure / same underlying asset pool.
- No offset between different asset pools.

Bucketing:

- Corporate bucket
- Other buckets by asset class x region

Risk weights:

- Tranche-based, aligned to Basel securitisation framework references.

References:

- d457: MAR22.29, MAR22.31, MAR22.34

## 5.5 Securitisation CTP DRC

Bucketing:

- Each index is its own bucket.
- Bespoke tranches map to their reference index bucket.

Distinct feature:

- Bucket-level D_b can be negative (no floor at zero at bucket level).
- Total DRC for CTP aggregates bucket terms with a 0.5 treatment on negative-side contribution.

References:

- d457: MAR22.40, MAR22.41, MAR22.45

## 6. FAQ usage policy in this repo

Use d457_faq to clarify interpretation only.

Priority order:

1. Basel text in d457 / consolidated framework (normative)
2. d457_faq clarifications (interpretive)
3. Local implementation rules in this repository

If local implementation intentionally deviates from Basel, document the deviation explicitly in local rule files.

## 7. Local check checklist (for next coding sessions)

When changing SBM/DRC code, verify at least:

1. Scope alignment:
- SBM logic maps to MAR21 paragraphs.
- DRC logic maps to MAR22 paragraphs.

2. Aggregation sequence:
- SBM: risk factor -> bucket -> risk class -> scenario max.
- DRC: gross JTD -> net JTD -> weighted JTD -> bucket/total.

3. Special constraints:
- DRC offsetting constraints by portfolio type are respected.
- CTP bucket/aggregation handling is not mixed with non-CTP/non-sec rules.

4. Parameter governance:
- Every configurable parameter is either used in formula paths or removed.
- No dead Basel-looking parameters remain in config/cache.

5. Cross-file consistency:
- `frtbsense_rule.md` (input semantics)
- `frtbagg_rule.md` (aggregation semantics)
- code in `frtbsa/sba/*` and `frtbsa/drc/*`

## 8. Revision tag

- Created: 2026-02-26
- Maintainer context: local engineering baseline for Codex cross-checks
