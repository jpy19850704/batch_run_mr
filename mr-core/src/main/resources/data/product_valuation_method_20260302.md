# Product 估值方法说明（2026-03-02）

## 1. 估值主流程
- 统一入口：`calc/Calc.run()`。
- 分发逻辑：按 `PRODUCT_CODE` 分组后，路由到对应 `*Calc`，再进入各产品 `calc()`。
- 估值输出：统一落到 `Measure/OptionMeasure`（`valuation/valuationCny/pv01/greeks/FRTB sensitivity`）。

## 2. 产品大类与 valuation 方法

### 2.1 线性产品（Forward/Swap/Bond/IRS）

1. FX Forward / FX Swap（`FxFwd`, `FxSwap`）
- 核心：远期价差 + 折现（或双腿净额）。
- 典型形式：
  - Forward：`V = (F - K) * DF * Position`
  - Swap：`V = V_near_leg + V_far_leg`
- 合理性：符合标准无套利定价框架。

2. 商品 Forward / Swap（`CommFwd`, `CommSwap`）
- 核心：商品远期价格与成交价/两腿价差。
- 计量：与 FX 线性产品同结构，差异在 `F` 来自商品价格曲线。
- 合理性：模型简洁、可解释；前提是曲线口径一致（spot/fwd节点定义一致）。

3. 利率互换（`StdIrs`, `IrsCcs`）
- `StdIrs`：按远期利率与成交价差乘以 `Notional*DCF` 计价。
- `IrsCcs`：分 PAY/REC 两腿现金流贴现，再统一到 REC 币种净额。
- 合理性：现金流贴现法是主流实现；跨币种净额折算清晰。

4. 债券/债券期货（`Bond`, `BondFuture`）
- `Bond`：未来现金流贴现求和；可叠加信用利差曲线；可计算 SOY、久期、凸性。
- `BondFuture`：基于 CTD / 转换因子 / 合约乘数口径做净值。
- 合理性：固定收益标准方法；风险分解可直接对应曲线冲击。

### 2.2 香草期权（Vanilla）

1. FX Vanilla（`FxVanillaOpt`）
- 欧式：Black（Garman-Kohlhagen口径）。
- 美式：Bjerksund-Stensland 近似。
- 核心输入：`spot/strike/rd/rf/vol/t`。
- 合理性：主流、可审计，适合批量估值。

2. 商品 Vanilla（`CommVanillaOpt`, `CommEurOpt`）
- 欧式：Black；美式路径使用近似美式模型。
- 核心：从商品曲线提取 spot/fwd，再反推出 carry（`rf`）。
- 合理性：商品期权常用方案；关键在 spot 节点与 fwd 节点定义要严格一致。

3. IR Vol 产品（`CapFloor`, `Swaption`）
- `CapFloor`：逐现金流 caplet/floorlet 定价后贴现汇总；支持 `Black76/Bachelier`。
- `Swaption`：先构建底层 swap 的 annuity 与 forward swap rate，再做期权定价；支持 `Black76/Bachelier`。
- 合理性：与市场主流一致，且支持 Normal/Lognormal 双口径。

### 2.3 数字/障碍期权

1. Digital（`DigOptBase` + Fx/Eq/Comm/Ir 子类）
- 核心：二元支付函数，使用 `DigOptUtil` 解析解/近似。
- 合理性：实现简单、运行效率高。

2. Barrier（`BarOptBase` + Fx/Eq/Comm/Ir 子类）
- 核心：单障碍/双障碍解析定价（`BarOptUtil`），可选 VV 调整。
- 合理性：对典型障碍结构适配度高；VV 可提升 smile 环境下拟合能力。

### 2.4 结构化期权（路径/状态型）

1. AutoCall（`AutoCallBase` + 各资产子类）
- 核心：`AutoCallUtil` 蒙特卡洛路径模拟，处理敲出观察日与支付映射。
- 合理性：对于强路径依赖结构，MC 是合理选择。

2. SharkFin / Spread / WeddingCake / RangeAccure / StepUp
- `SharkFinBase`：分段收益结构，解析+有限差分 Greek；`UP_DOWN/DOWN_UP` 两段型使用“前半段不触碰概率 × T1远期起点的 future-start SharkFin”近似。
- `SpreadOptBase`：价差期权解析框架。
- `WeddingCakeBase`：多区间障碍+历史 fixing 状态判定，输出期望收益率再贴现。
- `RangeAccureOptBase`：按观察日区间命中累积收益，支持多市场敏感度。
- `StepUpOptBase`：分段台阶收益（通常以 digital 组合近似），按区间条件聚合。
- 合理性：将复杂条款拆成可复用基类，工程上可维护性较好。

### 2.5 信用产品

1. CDS（`Cds`）
- 核心拆分：
  - 违约支付腿（Default Leg）
  - 保费腿（Premium Leg）
- 违约概率近似：通过折现曲线与“折现+信用利差”合并曲线的 DF 比值推导。
- 总估值：两腿期望现金流贴现后求和（含买卖方向）。
- 合理性：工程实现遵循“生存/违约分解”主线，且可衔接 DRC/JTD。

## 3. 合理性评估（总体）

### 优点
- 产品覆盖完整，线性/期权/结构化/信用均有统一入口。
- 大多数产品估值路径与市场常用模型一致（贴现现金流、Black/Bachelier、MC）。
- FRTB 敏感度接口已嵌入到产品层，便于监管报送链路落地。

### 主要风险点
- 部分产品在场景估值中未完全使用“传入场景市场数据”（FX 汇率口径问题）。
- 个别类存在编译级问题（变量名错误、方法缺失）。
- 少数产品输入校验不足，异常时更容易出现 NPE 而不是可解释错误。
- Greek 是否按持仓缩放在不同产品实现不一致，口径解释成本高。

## 4. 建议的统一治理方向
- 统一 `calc(MarketData)` 约束：禁止使用构造期缓存市场对象（尤其 FX）。
- 统一输入校验模板：先校验必填与曲线存在，再进入计量。
- 统一 Greek 输出口径：明确“单位头寸”或“持仓后”，并全产品一致。
- 将编译阻断问题先修复，再做一轮回归（含敏感度回归基准）。
