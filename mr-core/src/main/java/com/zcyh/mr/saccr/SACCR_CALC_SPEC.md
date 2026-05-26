# SA-CCR 计量规范文档

> **依据标准**：Basel III BCBS 279 / CRR2 / 《商业银行资本管理办法》
> **实现版本**：v1.0
> **包路径**：`com.zcyh.mr.saccr`

---

## 目录

1. [模块结构](#一模块结构)
2. [输入数据结构](#二输入数据结构)
3. [五层计量流程](#三五层计量流程)
4. [第一层：逐笔有效名义本金](#四第一层逐笔有效名义本金)
5. [第二层：资产类别 AddOn](#五第二层资产类别-addon)
6. [第三层：RC 与 PFE](#六第三层rc-与-pfe)
7. [第四层：EAD](#七第四层ead)
8. [第五层：资本计算](#八第五层资本计算)
9. [监管参数速查](#九监管参数速查)
10. [关键设计决策](#十关键设计决策)
11. [接口说明](#十一接口说明)

---

## 一、模块结构

```
com.zcyh.mr.saccr/
├── SaccrCalculator.java          主入口（五层编排）
├── params/
│   └── SaccrSupervisoryParams.java   监管参数表（SF/ρ/σ/MPOR 常量）
├── model/
│   ├── SaccrTrade.java           单笔交易输入 POJO
│   ├── SaccrNettingSet.java      净额结算集合输入 POJO
│   └── SaccrResult.java          计算输出 POJO（含全部中间值）
├── delta/
│   └── DeltaCalc.java            步骤4：监管 Delta 计算
├── addon/
│   ├── IrAddOnCalc.java          利率 AddOn
│   ├── FxAddOnCalc.java          外汇 AddOn
│   ├── CreditAddOnCalc.java      信用 AddOn
│   ├── EquityAddOnCalc.java      权益 AddOn
│   ├── CommodityAddOnCalc.java   大宗商品 AddOn
│   └── AddOnAggregator.java      五类合计
└── rc/
    └── RcCalc.java               替代成本（RC）三条计算路径
```

**外部依赖（复用现有模块）：**

| 依赖类 | 复用点 |
|--------|--------|
| `com.zcyh.mr.product.basic.option.OptUtil` | 正态分布 CDF（期权 Delta 计算） |

**应用层（mr-app）：**

| 类 | 职责 |
|----|------|
| `springboot.engine.SaccrEngineAdapter` | engineCode=`sa_ccr`，JSON 解析 → 调用 SaccrCalculator |
| `springboot.service.SaccrResultPersistService` | 结果落库至 `TB_OUT_SACCR_RESULT`（Doris） |

---

## 二、输入数据结构

### 2.1 SaccrTrade（交易级）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tradeId | String | 是 | 交易唯一标识 |
| assetClass | String | 是 | IR / FX / Credit / Equity / Commodity |
| direction | int | 是 | +1 多头 / -1 空头 |
| notional | double | 是 | 名义本金（已折算报告货币） |
| currency | String | 是 | 计价货币（ISO 4217） |
| startDate | LocalDate | 是 | 标的资产起始日（S_i） |
| endDate | LocalDate | 是 | 标的资产到期日（E_i） |
| optionExpiry | LocalDate | 期权必填 | 期权到期日（T_i） |
| productType | String | 是 | Swap / Forward / Option / CDS / TRS 等 |
| mtmValue | double | 是 | 当前 MTM（V_i），可为负 |
| isOption | boolean | 是 | 是否期权类产品 |
| optionLong | boolean | 期权必填 | true=多头（λ=+1），false=空头（λ=-1） |
| strikePrice | double | 期权必填 | 行权价 K |
| underlyingPrice | double | 期权/权益/商品必填 | 标的当前价格 P |
| referenceEntity | String | 信用/权益必填 | 参考主体或权益代码 |
| creditRating | String | 信用必填 | 单一主体传评级（AAA/AA/A/BBB/BB/B/CCC，支持 +/-）；指数传 IG/SG；异常降级 SG 并告警 |
| isIndex | boolean | 信用/权益必填 | 是否指数产品 |
| commodityBucket | String | 商品必填 | Power / Energy / Metal / Agriculture 等 |
| commodityType | String | 商品必填 | 具体品种（如 WTI / Gold） |
| quantity | double | 权益/商品必填 | 合约数量 Q_i |
| currencyPair | String | 外汇必填 | 货币对（如 USD/CNY） |

**方向约定（利率互换）：**

| 方向 | direction | 含义 |
|------|-----------|------|
| 支付固定（收取浮动） | -1 | 银行接受固定利率风险 |
| 收取固定（支付浮动） | +1 | Basel 规定：支付浮动=多头 |

> 注意：此约定与部分系统相反，需在数据接入层统一对齐。

### 2.2 SaccrNettingSet（净额结算集合级）

| 字段 | 类型 | 说明 |
|------|------|------|
| nettingSetId | String | 净额结算集合唯一标识 |
| counterpartyId | String | 交易对手标识 |
| counterpartyType | String | Sovereign / Bank / Corporate / QCCP / NonQCCP |
| counterpartyRating | String | AAA / AA+ / A+ / BBB+ 等，用于第五层风险权重 |
| isMargined | boolean | 是否有保证金协议 |
| isCleared | boolean | 是否集中清算 |
| isQccp | boolean | 是否合格中央对手方（QCCP） |
| isClientClearing | boolean | 是否为间接清算客户 |
| meetsPortabilityConditions | boolean | 客户清算是否满足可转移性条件 |
| marginType | String | Bilateral（双向）/ OneWayBank / None |
| threshold | double | 保证金触发阈值 TH |
| mta | double | 最低转让金额 MTA |
| nica | double | 净独立抵押品 NICA（银行净收取为正） |
| collateralC | double | 净收取抵押品 C（含 VM 和折价后 IM） |
| mporDays | int | 自定义 MPOR 工作日数；0 = 自动推算 |
| hasDisputeHistory | boolean | 是否有争议历史（→ MPOR 翻倍） |
| trades | List\<SaccrTrade\> | 归属交易列表 |

---

## 三、五层计量流程

```
┌─────────────────────────────────────────────────────────────┐
│   SaccrCalculator.calculate(nettingSets, dataDate, calcCapital)   │
└──────────────────────┬──────────────────────────────────────┘
                       │ 逐个 NettingSet
                       ▼
         ┌─────────────────────────┐
         │   1. 确定 MPOR          │  resolveMpor(ns)
         │      清算5天/双边10天    │
         │      争议历史→翻倍       │
         └──────────┬──────────────┘
                    │
                    ▼
         ┌─────────────────────────┐
         │   2. 逐笔计算 D_i       │  calcEffectiveNotional()
         │   D_i = δ_i×Mf_i×d_i   │  同时累计 ΣV_i（MTM之和）
         └──────────┬──────────────┘
                    │ 按资产类别分流
          ┌─────────┼──────────────┐
          ▼         ▼              ▼
    IR桶分组   FX货币对分组   Credit/Equity/Commodity参考主体分组
          │         │              │
          └─────────┴──────────────┘
                    │
                    ▼
         ┌─────────────────────────┐
         │   3. 各类别 AddOn 聚合  │  IrAddOnCalc / FxAddOnCalc /
         │                         │  CreditAddOnCalc / EquityAddOnCalc /
         │   AddOn_agg = ΣAddOn    │  CommodityAddOnCalc → AddOnAggregator
         └──────────┬──────────────┘
                    │
                    ▼
         ┌─────────────────────────┐
         │   4. RC 计算            │  RcCalc（三路径）
         │   5. multiplier         │  calcMultiplier()
         │   6. PFE = mul×AddOn    │
         └──────────┬──────────────┘
                    │
                    ▼
         ┌─────────────────────────┐
         │   7. EAD = 1.4×(RC+PFE)│
         └──────────┬──────────────┘
                    │ calcCapital=true
                    ▼
         ┌─────────────────────────┐
         │   8. 资本计算（可选）    │  resolveRiskWeight()
         │   RWA = EAD × RW        │  Capital = RWA × 8%
         │   Capital = RWA × 8%    │
         └─────────────────────────┘
```

---

## 四、第一层：逐笔有效名义本金

### 4.1 时间参数（年，ACT/365）

代码入口：`SaccrCalculator.calcEffectiveNotional()`

$$S_i = \frac{\text{startDate} - \text{dataDate}}{365}, \quad E_i = \frac{\text{endDate} - \text{dataDate}}{365}, \quad T_i = \frac{\text{optionExpiry} - \text{dataDate}}{365}$$

已过期的参数取 0（`max(x, 0)`）。

**产品类型 → E_i 取值规则：**

| 产品 | E_i 取值 |
|------|---------|
| 利率/信用互换（即期） | 合同到期日 |
| 外汇远期 | 结算日 |
| 期权（现金结算） | 期权到期日 |
| 期权（实物交割，标的为互换） | **标的互换**到期日 |
| 百慕大期权 | 最晚行权日 |

### 4.2 调整后名义本金 d_i

代码入口：`SaccrCalculator.calcAdjustedNotional()`

| 资产类别 | 公式 | 说明 |
|---------|------|------|
| IR | `d_i = Notional × (E_i - S_i)` | 合同期限（年）加权，捕捉久期敞口 |
| FX | `d_i = Notional` | 外币腿本金（已折报告货币） |
| Credit | `d_i = Notional` | 信用名义本金 |
| Equity | `d_i = P × Q`（或 Notional） | 有价格时用市值，否则用名义本金 |
| Commodity | `d_i = P × Q` | 当前商品价格 × 合约数量 |

### 4.3 期限调整因子 Mf_i

代码入口：`SaccrCalculator.calcMf()`

**有保证金协议（Margined）：**

$$Mf_i = \sqrt{\frac{MPOR}{250}}$$

**无保证金协议（Unmargined）：**

$$Mf_i = \sqrt{\frac{3}{2}} \times \sqrt{\min(E_i,\ 1\text{年})}$$

取值范围：$[0,\ \sqrt{3/2}] \approx [0,\ 1.2247]$

**MPOR 推算逻辑（`SaccrCalculator.resolveMpor()`）：**

| 场景 | MPOR（工作日） |
|------|-------------|
| 集中清算（QCCP） | 5 |
| 双边，日频抵押品更新 | 10 |
| `mporDays` 自定义 > 0 | 使用自定义值 |
| 存在争议历史（非集中清算） | 以上基础值 × 2 |

### 4.4 监管 Delta δ_i

代码入口：`DeltaCalc.calc()`

**线性产品（互换、远期、CDS）：**

$$\delta_i = \text{direction} \in \{+1, -1\}$$

**期权产品（`DeltaCalc.calcOptionDelta()`）：**

$$\delta_i = \lambda \times \Phi\!\left(\lambda \times \frac{\ln(P/K) + 0.5\sigma^2 T_i}{\sigma\sqrt{T_i}}\right)$$

其中：
- $\lambda = +1$（多头期权），$\lambda = -1$（空头期权）
- $\sigma$ = 监管规定波动率（查 `SaccrSupervisoryParams.getSigma(assetClass)`）
- $\Phi$ = 标准正态 CDF，调用 `OptUtil.cdf()`

> 边界处理：$T_i \leq 0$ 时按内在价值方向取值（深实值 → ±1，平值 → ±0.5）

### 4.5 有效名义本金 D_i

$$D_i = \delta_i \times Mf_i \times d_i$$

$D_i$ 带符号：正值 = 多头敞口，负值 = 空头敞口，可在对冲集合内相互抵消。

---

## 五、第二层：资产类别 AddOn

### 5.1 利率（IR）— `IrAddOnCalc`

**分组：** 货币 → 期限桶（3桶）

| 桶 | 到期时间 E_i |
|----|------------|
| 桶 1 | ≤ 1 年 |
| 桶 2 | 1 < E_i ≤ 5 年 |
| 桶 3 | > 5 年 |

$$\text{EffNotional}_b = \sum_{i \in \text{桶}b} D_i \quad \text{（带符号，同桶完全净额）}$$

$$\text{AddOn}(\text{ccy}) = \text{SF}_{\text{IR}} \times \sqrt{EN_1^2 + EN_2^2 + EN_3^2 + 2\rho_{12}EN_1EN_2 + 2\rho_{23}EN_2EN_3 + 2\rho_{13}EN_1EN_3}$$

桶间相关系数：$\rho_{12}=\rho_{23}=0.70$，$\rho_{13}=0.30$

$$\text{AddOn}(\text{IR}) = \sum_{\text{ccy}} \text{AddOn}(\text{ccy}) \quad \text{（跨货币不净额，直接加总）}$$

> 实现细节：`variance` 理论上非负，但因 $D_i$ 带符号可能极端为负，代码中加了 `max(0, variance)` 保护。

### 5.2 外汇（FX）— `FxAddOnCalc`

**分组：** 货币对（如 USD/CNY、EUR/USD）

$$\text{AddOn}(\text{pair}) = \text{SF}_{\text{FX}} \times \left|\sum_{i \in \text{pair}} D_i\right|$$

$$\text{AddOn}(\text{FX}) = \sum_{\text{pair}} \text{AddOn}(\text{pair}) \quad \text{（跨货币对不净额）}$$

### 5.3 信用（Credit）— `CreditAddOnCalc`

**分组：** 参考主体（Reference Entity）

$$\text{WEN}_k = \text{SF}_k \times \sum_{i \in \text{entity}_k} D_i$$

$$\text{AddOn}(\text{Credit}) = \sqrt{\left(\sum_k \rho_k \cdot \text{WEN}_k\right)^2 + \sum_k (1-\rho_k^2) \cdot \text{WEN}_k^2}$$

- 第一项（括号内平方）= **系统性项**，反映共同信用市场驱动
- 第二项（求和）= **特质性项**，反映各主体独立残差风险

SF_k 和 ρ_k 按评级和类型查 `SaccrSupervisoryParams`：

| 子类型 | SF_k | ρ_k |
|--------|------|-----|
| 单一主体，AAA | 0.38% | 50% |
| 单一主体，AA | 0.38% | 50% |
| 单一主体，A | 0.42% | 50% |
| 单一主体，BBB | 0.54% | 50% |
| 单一主体，BB | 1.06% | 50% |
| 单一主体，B | 1.60% | 50% |
| 单一主体，CCC | 6.00% | 50% |
| 指数，IG | 0.38% | 80% |
| 指数，SG | 1.06% | 80% |

> 输入识别规则：单一主体按评级分档；指数仅识别 IG/SG。识别异常时按 SG 降级并记录 `warn` 日志。

### 5.4 权益（Equity）— `EquityAddOnCalc`

结构与信用类完全对称，将参考主体替换为**参考权益**：

$$\text{WEN}_k = \text{SF}_k(\text{Eq}) \times \sum_{i \in \text{equity}_k} D_i$$

$$\text{AddOn}(\text{Equity}) = \sqrt{\left(\sum_k \rho_k \cdot \text{WEN}_k\right)^2 + \sum_k (1-\rho_k^2) \cdot \text{WEN}_k^2}$$

| 子类型 | SF_k | ρ_k |
|--------|------|-----|
| 单一股票 | 32% | 50% |
| 指数 | 20% | 80% |

### 5.5 大宗商品（Commodity）— `CommodityAddOnCalc`

**分组：** 商品桶（Bucket）→ 具体品种（Type）

**桶内聚合（单因子模型）：**

$$\text{AddOn}(\text{bucket}) = \sqrt{\left(\sum_k \rho \cdot \text{WEN}_k\right)^2 + \sum_k (1-\rho^2) \cdot \text{WEN}_k^2}$$

其中 $\rho = 40\%$（桶内跨品种相关系数）

| Bucket | SF |
|--------|-----|
| Power（电力） | 40% |
| 其他 | 18% |

**跨桶聚合（取绝对值相加，无跨桶净额）：**

$$\text{AddOn}(\text{Commodity}) = \sum_{\text{bucket}} \text{AddOn}(\text{bucket})$$

### 5.6 五类合计 — `AddOnAggregator`

$$\text{AddOn\_aggregate} = \text{AddOn}(\text{IR}) + \text{AddOn}(\text{FX}) + \text{AddOn}(\text{Credit}) + \text{AddOn}(\text{Equity}) + \text{AddOn}(\text{Commodity})$$

---

## 六、第三层：RC 与 PFE

### 6.1 替代成本 RC — `RcCalc`

代码入口：`SaccrCalculator.calcRc()` 依据 `ns.marginType` 选路径

**路径 A — 无保证金（Unmargined / marginType=None）：**

$$RC = \max(\sum V_i - C,\ 0)$$

**路径 B — 有保证金（Margined，双向）：**

$$RC = \max\!\left(\sum V_i - C,\ TH + MTA - NICA,\ 0\right)$$

第二项 $TH + MTA - NICA$ 为保证金追加前最大可能暴露的下限，防止 RC 被低估。

**路径 C — 单向保证金（OneWayBank，银行只缴不收）：**

$$RC = \max(\sum V_i - C,\ 0) \quad \text{（等同路径A，但 } C \leq 0\text{）}$$

### 6.2 乘数 multiplier

代码入口：`SaccrCalculator.calcMultiplier()`

$$\text{multiplier} = \min\!\left(1,\ 0.05 + 0.95 \times e^{\frac{\sum V_i - C}{2 \times 0.95 \times \text{AddOn\_agg}}}\right)$$

| 情形 | 乘数 |
|------|------|
| $\sum V_i - C = 0$ | $0.05 + 0.95 = 1.0$ |
| $\sum V_i - C > 0$（欠款方） | → 1.0 |
| $\sum V_i - C < 0$（超额抵押） | < 1.0，最小值 **0.05**（监管强制下限） |

> AddOn = 0 时，`calcMultiplier()` 直接返回 1.0，避免除零。

### 6.3 潜在未来风险暴露 PFE

$$PFE = \text{multiplier} \times \text{AddOn\_aggregate}$$

---

## 七、第四层：EAD

$$EAD = \alpha \times (RC + PFE), \quad \alpha = 1.4 \text{（监管固定参数）}$$

**多保证金分组时：**

$$EAD(\text{NettingSet}) = \alpha \times \left(RC + \sum_{\text{margin group}} PFE_g\right)$$

> 当前实现：每个 `SaccrNettingSet` 对应一个保证金分组。如需多分组，在 `SaccrNettingSet` 层面拆分后分别计算 PFE，再汇总。

---

## 八、第五层：资本计算

代码入口：`SaccrCalculator.resolveRiskWeight()`，仅在 `calcCapital=true` 时执行。

### 8.1 CCR 资本

$$RWA(\text{CCR}) = EAD \times RW$$

$$\text{Capital}(\text{CCR}) = RWA \times 8\%$$

**风险权重 RW 查表（`resolveRiskWeight()`）：**

| 交易对手类型 | 评级 | RW |
|-------------|------|-----|
| QCCP | — | 2% |
| non-QCCP（`isCleared=true, isQccp=false`） | — | 150% |
| Sovereign（主权） | AAA~AA- | 0% |
| Sovereign | A+~A- | 20% |
| Bank | AAA~AA- | 20% |
| Bank | BBB+~BB- | 50% |
| Corporate（默认） | AAA~AA- | 20% |
| Corporate | BBB+~BB- | 100% |

> 评级分档：`isAaaToAaMinus()` / `isAPlusToAMinus()` / `isBbbPlusToBbMinus()`
> 实际生产建议对接完整评级映射表，扩展该方法。

### 8.2 CVA 资本（规则参考，未在计算器内实现）

满足豁免条件（名义本金 < 1000 亿欧元）时可选：

$$\text{CVA Capital} = 100\% \times \text{CCR Capital}$$

否则使用 BA-CVA：

$$\text{SCVA}_c = \frac{RW_c \times EAD_c^{disc} \times DF_c}{2}, \quad DF_c = \frac{1 - e^{-0.05 M_c}}{0.05 M_c}$$

$$K(\text{reduced}) = \sqrt{(\rho_{\text{CVA}} \cdot \sum \text{SCVA}_c)^2 + (1-\rho_{\text{CVA}}^2) \cdot \sum \text{SCVA}_c^2}$$

其中 $\rho_{\text{CVA}} = 50\%$（监管固定）。

> **对 QCCP 的交易豁免 CVA 资本**，这是 G20 推动集中清算的核心激励。

---

## 九、监管参数速查

**类路径：** `com.zcyh.mr.saccr.params.SaccrSupervisoryParams`

### 监管因子（SF）

| 资产类别 | 子类 | 常量名 | 值 |
|---------|------|-------|-----|
| IR | 所有 | `SF_IR` | 0.50% |
| FX | 所有 | `SF_FX` | 4.00% |
| Credit | 单一主体 AAA | `SF_CREDIT_SINGLE_AAA` | 0.38% |
| Credit | 单一主体 AA | `SF_CREDIT_SINGLE_AA` | 0.38% |
| Credit | 单一主体 A | `SF_CREDIT_SINGLE_A` | 0.42% |
| Credit | 单一主体 BBB | `SF_CREDIT_SINGLE_BBB` | 0.54% |
| Credit | 单一主体 BB | `SF_CREDIT_SINGLE_BB` | 1.06% |
| Credit | 单一主体 B | `SF_CREDIT_SINGLE_B` | 1.60% |
| Credit | 单一主体 CCC | `SF_CREDIT_SINGLE_CCC` | 6.00% |
| Credit | 指数 IG | `SF_CREDIT_IG_INDEX` | 0.38% |
| Credit | 指数 SG | `SF_CREDIT_SG_INDEX` | 1.06% |
| Equity | 单一股票 | `SF_EQUITY_SINGLE` | 32% |
| Equity | 指数 | `SF_EQUITY_INDEX` | 20% |
| Commodity | 电力 | `SF_COMMODITY_POWER` | 40% |
| Commodity | 其他 | `SF_COMMODITY_OTHER` | 18% |

### 相关系数（ρ）

| 资产类别 | 常量名 | 值 |
|---------|-------|-----|
| Credit 单一主体 | `RHO_CREDIT_SINGLE` | 50% |
| Credit 指数 | `RHO_CREDIT_INDEX` | 80% |
| Equity 单一股票 | `RHO_EQUITY_SINGLE` | 50% |
| Equity 指数 | `RHO_EQUITY_INDEX` | 80% |
| Commodity 桶内 | `RHO_COMMODITY_INTRA` | 40% |

### 监管波动率（σ，用于期权 Delta）

| 资产类别 | 常量名 | 值 |
|---------|-------|-----|
| IR | `SIGMA_IR` | 50% |
| FX | `SIGMA_FX` | 15% |
| Credit | `SIGMA_CREDIT` | 100% |
| Equity | `SIGMA_EQUITY` | 120% |
| Commodity | `SIGMA_COMMODITY` | 70% |

### 全局固定参数

| 参数 | 常量名 | 值 |
|------|-------|-----|
| EAD 乘数 α | `ALPHA` | 1.4 |
| multiplier 下限 | `MULTIPLIER_FLOOR` | 0.05 |
| BA-CVA ρ | `CVA_RHO` | 50% |
| 集中清算 MPOR | `MPOR_CLEARED_DAYS` | 5 天 |
| 双边 MPOR | `MPOR_BILATERAL_DAYS` | 10 天 |

---

## 十、关键设计决策

### 10.1 纯计算层（mr-core）与框架层（mr-app）解耦

`SaccrCalculator` 及所有子计算类均为无框架依赖的纯 Java（无 Spring 注解），可独立单元测试，也可被其他 Java 环境直接调用。框架层（适配器、落库）仅在 mr-app 中。

### 10.2 监管参数硬编码

所有 SF、ρ、σ、MPOR 均硬编码在 `SaccrSupervisoryParams`，不从数据库读取，不允许运行期覆盖。依据 BCBS 279：这些参数由监管机构规定，不允许内部估算。如监管修订，升级代码常量并重新发布。

### 10.3 D_i 累加数据结构

D_i 按资产类别分流到 5 个 Map，Map 的 key 设计：

| 资产类别 | Map key | 说明 |
|---------|---------|------|
| IR | `"CCY_桶号"` 如 `"CNY_2"` | 货币+桶，同桶完全净额 |
| FX | `"USD/CNY"` 等货币对 | 货币对内完全净额 |
| Credit | 参考主体 ID | 每个主体独立对冲集合 |
| Equity | 参考权益 ID | 每个权益独立对冲集合 |
| Commodity | `"桶_品种"` 如 `"Power_WTI"` | 同品种可净额，跨品种不净额 |

### 10.4 multiplier 除零保护

当 `AddOn_aggregate = 0`（净额结算集合内无任何有效敞口）时，直接返回 `multiplier = 1.0`，避免除零异常。

### 10.5 外汇货币对标准化

`normalizeCcyPair()` 将货币对统一为大写，确保 `USD/CNY` 和 `USD/cny` 归入同一对冲集合。若 `currencyPair` 字段为空，退化为 `"CCY/RPT"` 的代理键。

### 10.6 已售出期权（EAD=0）

文档 8.3 节规定：不在净额结算和保证金协议范围内的已售出期权 EAD=0。此场景处理方式：在上游将该交易从 `trades` 列表中排除，不传入 `SaccrNettingSet`，而非在计算器内判断。

---

## 十一、接口说明

### 11.1 Java API（直接调用）

```java
import com.zcyh.mr.saccr.SaccrCalculator;
import com.zcyh.mr.saccr.model.*;

// 构造输入
SaccrTrade trade = new SaccrTrade();
trade.tradeId = "T001";
trade.assetClass = "IR";
trade.direction = 1;          // 收取固定
trade.notional = 10_000_000;
trade.currency = "CNY";
trade.startDate = LocalDate.of(2026, 4, 8);
trade.endDate = LocalDate.of(2031, 4, 8);
trade.productType = "Swap";
trade.mtmValue = 50_000;
trade.isOption = false;

SaccrNettingSet ns = new SaccrNettingSet();
ns.nettingSetId = "NS001";
ns.counterpartyId = "CP001";
ns.counterpartyType = "Bank";
ns.counterpartyRating = "A+";
ns.isMargined = true;
ns.marginType = "Bilateral";
ns.threshold = 0;
ns.mta = 0;
ns.nica = 0;
ns.collateralC = 100_000;
ns.trades = List.of(trade);

// 执行计算
List<SaccrResult> results = SaccrCalculator.calculate(
        List.of(ns),
        LocalDate.of(2026, 4, 8),
        true           // 计算第五层资本
);

SaccrResult r = results.get(0);
System.out.printf("EAD = %.2f, Capital = %.2f%n", r.ead, r.capitalCcr);
```

### 11.2 REST API（通过引擎适配器）

**端点：** `POST /api/v1/engine/run`
**engineCode：** `sa_ccr`

**请求示例：**

```json
{
  "engine_code": "sa_ccr",
  "payload": {
    "data_date": "20260408",
    "calc_capital": true,
    "netting_sets": [
      {
        "netting_set_id": "NS001",
        "counterparty_id": "CP001",
        "counterparty_type": "Bank",
        "counterparty_rating": "A+",
        "is_margined": true,
        "margin_type": "Bilateral",
        "threshold": 0,
        "mta": 0,
        "nica": 0,
        "collateral_c": 100000,
        "mpor_days": 0,
        "has_dispute_history": false,
        "trades": [
          {
            "trade_id": "T001",
            "asset_class": "IR",
            "direction": 1,
            "notional": 10000000,
            "currency": "CNY",
            "start_date": "20260408",
            "end_date": "20310408",
            "product_type": "Swap",
            "mtm_value": 50000,
            "is_option": false
          }
        ]
      }
    ]
  }
}
```

**响应示例：**

```json
{
  "data_date": "20260408",
  "results": [
    {
      "nettingSetId": "NS001",
      "counterpartyId": "CP001",
      "sumMtm": 50000.0,
      "collateralC": 100000.0,
      "rc": 0.0,
      "addonIr": 31623.0,
      "addonFx": 0.0,
      "addonCredit": 0.0,
      "addonEquity": 0.0,
      "addonCommodity": 0.0,
      "addonAggregate": 31623.0,
      "multiplier": 0.9539,
      "pfe": 30169.0,
      "ead": 42237.0,
      "riskWeight": 0.2,
      "rwaCcr": 8447.4,
      "capitalCcr": 675.8,
      "capitalCalculated": true
    }
  ]
}
```

### 11.3 输出表（engine_result_db）

**表名：** `TB_OUT_SACCR_RESULT`
**DDL 位置：** `mr-app/src/main/resources/db/mr_output_schema_doris.sql`
**写入服务：** `SaccrResultPersistService.persist(jobId, dataDate, results, nsMetaMap)`

| 字段 | 类型 | 说明 |
|------|------|------|
| JOB_ID | VARCHAR(64) | 任务 ID |
| DATA_DATE | VARCHAR(16) | 计算基准日期 |
| NETTING_SET_ID | VARCHAR(128) | 净额结算集合 ID |
| COUNTERPARTY_ID | VARCHAR(128) | 交易对手 ID |
| IS_MARGINED | TINYINT | 1=有保证金协议 |
| IS_CLEARED | TINYINT | 1=集中清算 |
| IS_QCCP | TINYINT | 1=QCCP |
| SUM_MTM | DECIMAL(38,10) | ΣV_i |
| COLLATERAL_C | DECIMAL(38,10) | 净收取抵押品 C |
| RC | DECIMAL(38,10) | 替代成本 |
| ADDON_IR | DECIMAL(38,10) | 利率 AddOn |
| ADDON_FX | DECIMAL(38,10) | 外汇 AddOn |
| ADDON_CREDIT | DECIMAL(38,10) | 信用 AddOn |
| ADDON_EQUITY | DECIMAL(38,10) | 权益 AddOn |
| ADDON_COMMODITY | DECIMAL(38,10) | 商品 AddOn |
| ADDON_AGGREGATE | DECIMAL(38,10) | AddOn 合计 |
| MULTIPLIER | DECIMAL(38,10) | 乘数 |
| PFE | DECIMAL(38,10) | 潜在未来风险暴露 |
| EAD | DECIMAL(38,10) | 风险敞口 |
| RISK_WEIGHT | DECIMAL(38,10) | 风险权重 RW |
| RWA_CCR | DECIMAL(38,10) | CCR 风险权重资产 |
| CAPITAL_CCR | DECIMAL(38,10) | CCR 资本要求 |
| CREATE_TIME | VARCHAR(32) | 落库时间 |

---

*文档版本 v1.0 | 依据 BCBS 279 编制 | 与代码实现同步维护*
