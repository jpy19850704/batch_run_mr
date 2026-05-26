# FRTB Aggregation Rules (frtbagg_rule.md)

## 1. Scope

- This document defines aggregation and pairing rules for `FrtbAggregator` and `sba/core/*Module`.
- It covers SBA only (`Delta / Vega / Curvature`), not DRC.
- It is the implementation rulebook for:
  - input validation;
  - risk-factor netting keys;
  - intra-bucket and cross-bucket aggregation conventions;
  - CSR `riskFactorType` basis correlation handling.

## 2. Authoritative Sources (Basel)

- BCBS, *Minimum capital requirements for market risk* (Jan 2019, rev Feb 2019):  
  `https://www.bis.org/bcbs/publ/d457.pdf`
- Same text with FAQs (recommended for interpretation):  
  `https://www.bis.org/bcbs/publ/d457_faq.pdf`

Key paragraphs used in this rule file:

- `MAR21.3` to `MAR21.7`: SBA process and class-level aggregation.
- `MAR21.4`: Delta/Vega step-by-step aggregation (`WS`, `Kb`, `Sb`, `gamma`).
- `MAR21.5`: Curvature step-by-step aggregation.
- `MAR21.9`: CSR non-securitisation risk factor dimensions.
- `MAR21.54` / `MAR21.55`: CSR non-securitisation delta within-bucket correlations.
- `MAR21.60`: CSR CTP delta basis correlation modification.
- `MAR21.100`: CSR curvature correlation treatment (basis/tenor terms not applied).
- FAQ (in `d457_faq`) clarifying bond-CDS basis meaning under `MAR21.54/55`.

## 3. Canonical SBA Pipeline

For each risk class, follow Basel sequence:

1. Build sensitivities by prescribed risk factors (`MAR21.8` to `MAR21.14`).
2. Net same risk factor sensitivities to `s_k`.
3. Apply risk weight: `WS_k = RW_k * s_k`.
4. Aggregate within bucket to `Kb` using `rho_kl`.
5. Aggregate across buckets using `gamma_bc` and `Sb`.
6. Compute class capital for `M/H/L`, then select max scenario.
7. Portfolio SBA capital is the sum across risk classes (`MAR21.6/21.7` framework).

## 4. Aggregation Key Rules (Engine)

All products keep a unified input schema including `riskFactorVertex1` and `riskFactorVertex2`.
Whether a dimension is used depends on risk class/sensitivity.

### 4.1 GIRR

- Delta key: `bucket + riskFactorId + vertex1`.
- Vega key: `bucket + riskFactorId + vertex1 + vertex2`.
- Curvature key: `bucket` only (aggregate at currency bucket level).

### 4.2 CSR (CSRNS / CSRNC / CSRCTP)

- Delta key: `bucket + riskFactorId + vertex1`.
- `riskFactorType` is used for basis correlation logic (bond/CDS distinction), not as an independent bucket axis.
- Curvature follows `MAR21.100`: basis and tenor correlation components from delta formula are not applied in curvature correlation.

### 4.3 EQ / FX / CMTY

- Use product-specific keys per Basel risk factor definition, with unified field structure retained.
- If a risk class defines only one dimension in Basel, extra dimensions in payload must not create artificial diversification.

## 5. CSR `riskFactorType` and Basis Correlation

## 5.1 Mapping

- `riskFactorType` must be normalised to uppercase.
- Supported types for basis handling:
  - `BOND`
  - `CDS`
- Any other value should be mapped by product layer before SBA, or treated as invalid according to validation policy.

## 5.2 Basel Basis Correlation Factors

- CSR non-securitisation (`MAR21.54/55`):
  - if sensitivities are on same curve type: `rho_basis = 1.0`
  - otherwise (bond vs CDS): `rho_basis = 99.90%` (`0.999`)
- CSR securitisation CTP (`MAR21.60`):
  - if sensitivities are on same curve type: `rho_basis = 1.0`
  - otherwise: `rho_basis = 99.00%` (`0.99`)

Note:

- `0.99` is Basel value for CSR CTP basis in `MAR21.60`.
- CSR non-securitisation basis is `0.999` per `MAR21.54/55`.

## 5.3 Delta Correlation Form (CSR)

Within bucket (`MAR21.54/55`):

- `rho_kl = rho_name * rho_tenor * rho_basis`
- `rho_name`:
  - buckets 1-15: same name `1`, else `35%`
  - buckets 17-18: same name `1`, else `80%`
- `rho_tenor`: same tenor `1`, else `65%`

## 5.4 Curvature Exception (CSR)

- For CSR non-securitisation and CSR CTP curvature, `rho_basis` and `rho_tenor` terms from delta formula are not applied (`MAR21.100` + FAQ).
- Curvature within-bucket correlation is driven by name correlation term only (squared, per curvature construction).

## 6. Validation and Pairing Rules

- `riskFactorBucket` must be non-empty.
- `sensitivityType` must be one of:
  - `Delta`
  - `Vega`
  - `Curvature Up`
  - `Curvature Down`
- Curvature pairing:
  - GIRR: pair by `riskClass + bucket`.
  - Others: pair by `riskClass + riskFactorId + bucket`.

## 7. Output Requirements

- Aggregator must return structured validation diagnostics:
  - `ERROR_COUNT`
  - `ERRORS` (array of objects with code/message/context fields)
- Validation failures should not be silently converted to zero-risk outputs.

## 8. Local Override Policy

If a local model intentionally deviates from Basel defaults (for example using `0.99` instead of Basel `0.999` in CSR non-securitisation basis):

1. Record the deviation in this file under a dedicated "Local Override" section.
2. Mark impacted code paths and test cases.
3. Keep a switchable parameter in `param.json` or dedicated config to avoid hard-coded hidden overrides.

