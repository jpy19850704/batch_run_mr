# CurveGeneration 曲线生成模块

## 一、模块概述

`curvegeneration` 模块负责从市场原始报价（零息利率、互换利率、外汇远期、波动率报价等）构建标准化的利率曲线和波动率曲面，并输出为引擎内部 `MarketData` 格式供估值计算使用。

**核心能力：**
- 零息曲线自举（Bootstrap）
- 外汇隐含曲线构建（利率平价）
- 曲线相减（信用利差提取）
- RRBF 波动率 → Delta 网格波动率（Vanna-Volga 模型）
- 自动解析曲线间依赖关系（拓扑排序）

---

## 二、架构设计

```
CurveGeneration.java          ← 门面类 / 编排器
├── CurveInput                ← 统一输入模型
├── IrCurve                   ← 利率曲线输出模型
├── DeltaTermVol              ← 波动率曲面输出模型
├── CurveResult               ← 结果容器 + MarketData 转换
├── generate()                ← 拓扑排序 → 依次执行转换器
└── topologicalSort()         ← Kahn 算法 BFS 拓扑排序

converter/
├── ZeroCurveBootstrap.java   ← 零息曲线自举
├── FxImpliedCurveConstruct.java ← 外汇隐含曲线
├── ZeroCurveSubtract.java    ← 曲线相减
└── VolRrbf2Delta.java        ← VV 模型 RRBF → Delta 网格
```

### 设计原则

| 原则 | 说明 |
|------|------|
| **门面模式** | 外部只需调用 `CurveGeneration.generate()`，内部自动路由到对应转换器 |
| **拓扑排序** | 自动解析曲线间依赖，无需手动排列输入顺序 |
| **curvePool 共享** | 先生成的曲线进入池中，后续曲线可依赖使用 |
| **容错隔离** | 单条曲线失败不阻塞其余曲线，错误记录到 `CurveResult.errors` |
| **freq/dcb 归一化** | 内部运算统一使用 `cont + actual/365`，输出时按需转换 |

---

## 三、数据流

```
┌─────────────────────────────────────────────────────────┐
│                   JSON 输入数据                          │
│  List<CurveInput>  (每条含 CONVERSION_TYPE + CURVE_DATA) │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
              ┌──── topologicalSort ────┐
              │ 分析依赖关系，确定执行顺序  │
              └──────────┬──────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
   ZeroCurveBootstrap  FxImplied   VolRrbf2Delta
   (无依赖，先执行)   (依赖基准曲线)  (依赖两条利率曲线)
          │              │              │
          └────→ curvePool ←────────────┘
                  (中间结果池)
                         │
                         ▼
              ┌── CurveResult ──┐
              │ irCurves         │  → fillIrSpot()  → MarketData.irSpot
              │ volPoints        │  → fillFxVol()   → MarketData.fxVol
              │ errors           │  → 错误日志
              └─────────────────┘
```

---

## 四、输入格式（CurveInput）

### 4.1 公共字段

| 字段 | JSON Key | 类型 | 说明 |
|------|----------|------|------|
| `conversionType` | `CONVERSION_TYPE` | String | 转换器类型（见下方四种） |
| `curveId` | `CURVE_ID` | String | 曲线唯一标识 |
| `dataDate` | `DATA_DATE` | LocalDate | 数据日期（格式 `yyyyMMdd`） |
| `curveDayCount` | `CURVE_DAYCOUNT` | String | 输出日算规则，默认 `actual/365` |
| `curveFreq` | `CURVE_FREQ` | String | 输出频率，默认 `cont` |
| `calendar` | `CALENDAR` | String | 日历名称（节假日调整） |
| `interpolateType` | `INTERPOLATE_TYPE` | String | 插值类型，默认 `linear` |
| `outputTermDays` | `OUTPUT_TERM_DAYS` | String | 自定义输出期限天数，逗号分隔（如 `"1,7,30,90,365,1825,3650"`） |

#### OUTPUT_TERM_DAYS 说明

| 配置 | 行为 | 适用转换器 |
|------|------|-----------|
| 为空（默认） | 按输入 CURVE_DATA 的原始期限点输出 | ZeroCurveBootstrap / FxImpliedCurveConstruct |
| 逗号分隔天数 | 在指定天数上重新采样输出 | ZeroCurveBootstrap / FxImpliedCurveConstruct |

`ZeroCurveSubtract` 始终按内置标准期限点输出，不受此参数影响。

**重采样管线（在转换器内部执行）：**

```
转换器内部运算 (cont/365)
    ↓
在指定天数上直接插值 spotRate (仍在 cont/365 下)
    ↓
按目标 freq/dcb 转换利率 + 计算 DF
```

无需通过 DF 反推 cont 利率，消除浮点往返误差。

**使用示例：**

```json
{
  "CONVERSION_TYPE": "ZeroCurveBootstrap",
  "CURVE_ID": "CNY_SHIBOR",
  "DATA_DATE": "2024-12-31",
  "OUTPUT_TERM_DAYS": "1,7,30,90,180,365,730,1825,3650,7300",
  "CURVE_DATA": [ ... ]
}
```

### 4.2 各转换器专属字段

#### `ZeroCurveBootstrap`

无额外顶层字段。`CURVE_DATA` 子数组格式：

```json
{
  "TERM_CODE": "3M",
  "TERM_TYPE": "ZERO",
  "TERM_VALUE": 0.035,
  "TERM_DAYCOUNT": "actual/360",
  "TERM_FRQ": "6M",
  "START_TERM": ""
}
```

| TERM_TYPE | 说明 |
|-----------|------|
| `ZERO` | 零息利率（即期或远期） |
| `SWAP` | 互换利率（需要 bootstrap） |

`START_TERM` 非空表示远期利率，如 `"3M"` 表示从 3M 开始的远期利率。

#### `FxImpliedCurveConstruct`

| 字段 | JSON Key | 说明 |
|------|----------|------|
| `baseDiscountCurve` | `BASE_DISCOUNT_CURVE` | 基准零息曲线 ID（依赖） |
| `baseCurrencyCode` | `BASE_CURRENCY_CODE` | 基准货币 |
| `fxSpot` | `FX_SPOT` | 即期汇率 |
| `dayOff` | `DAY_OFF` | 起息日偏移（默认 CAD=1, 其余=2） |

`CURVE_DATA` 子数组格式：

```json
{
  "TERM_CODE": "1M",
  "FWD_RATE": 7.5123
}
```

#### `ZeroCurveSubtract`

| 字段 | JSON Key | 说明 |
|------|----------|------|
| `ycCurveCode` | `YC_CURVE_CODE` | 收益率曲线 ID（依赖） |
| `rfCurveCode` | `RF_CURVE_CODE` | 无风险曲线 ID（依赖） |

无 `CURVE_DATA`，输出在标准期限点上。

#### `VolRrbf2Delta`

| 字段 | JSON Key | 说明 |
|------|----------|------|
| `baseDiscountCurve` | `BASE_DISCOUNT_CURVE` | 基准利率曲线 ID（依赖） |
| `underlyingDiscountCurve` | `UNDERLYING_DISCOUNT_CURVE` | 标的利率曲线 ID（依赖） |
| `fxSpot` | `FX_SPOT` | 即期汇率 |

`CURVE_DATA` 子数组格式：

```json
{
  "TERM_CODE": "1M",
  "ATM_VOL": 0.08,
  "RR_VOL": -0.005,
  "BF_VOL": 0.002
}
```

---

## 五、转换器逻辑详解

### 5.1 ZeroCurveBootstrap — 零息曲线自举

**输入：** 市场报价（ZERO 即期/远期利率 + SWAP 互换利率）
**输出：** 零息利率 + 折现因子

#### 计算流程

```
CURVE_DATA 解析
    │
    ▼
按 termDays 排序
    │
    ▼
插入 SWAP 中间付息节点    ← 根据 TERM_FRQ 按频率补全
    │
    ▼
插值填充中间节点利率       ← 对非原始节点进行线性插值
    │
    ▼
计算各节点间隔 interval
    │
    ▼
三轮自举:
  ① ZERO 即期: spotRate = ln(1 + tf × r) / termYear
  ② ZERO 远期: DF(0,T2) = DF(0,T1) × DF(T1,T2) → 反推 spotRate
  ③ SWAP Bootstrap: 逐步剥离累计 DF
    │
    ▼
按目标 freq/dcb 转换输出
```

#### 内部运算约定

- 内部统一以 **连续复利 (cont) + actual/365** 进行计算
- 折现因子 `DF = exp(-spotRate × termYear)`，在任何 freq/dcb 下恒等
- 输出时通过 `CurveFunc.convertIrRate()` 转换到目标 freq/dcb

#### 远期利率处理

当 `START_TERM` 非空时（如 `START_TERM="3M", TERM_CODE="6M"`）：

```
T1 = 3M (startDays)      T2 = 9M (termDays)
  │                         │
  │← 远期利率覆盖这段 →│
```

- **有即期覆盖（T1 之前存在已计算的点）：** 通过 `DF(0,T1) × DF(T1,T2)` 推导
- **无即期覆盖：** 降级处理，将远期利率近似为即期利率

### 5.2 FxImpliedCurveConstruct — 外汇隐含曲线

**原理：** 覆盖利率平价 (Covered Interest Rate Parity)

```
r_implied = r_base - ln(F/S) / T
```

其中：
- `r_base` = 基准货币零息利率（从 curvePool 获取，线性插值到目标期限）
- `F` = 远期汇率
- `S` = 即期汇率（取 ON 期限的远期汇率或全局 `FX_SPOT`）
- `T` = 期限年化

#### 日期调整

| termCode | 处理方式 |
|----------|----------|
| `ON` (Overnight) | 返回 dataDate 本身（仅用于确定 spot 基准） |
| `TN` (Tom-Next) | +1 个工作日（dayOff=2 时） |
| `SN` (Spot-Next) | +3 个工作日（dayOff=2 时） |
| 标准期限（1W/1M/...） | 先跳 dayOff 个工作日到起息日，再加期限 |

### 5.3 ZeroCurveSubtract — 曲线相减

**用途：** 提取信用利差 = 收益率曲线 - 无风险曲线

```
spread(T) = ycRate(T) - rfRate(T)
```

- 两条源曲线先分别转换到输出目标的 freq/dcb
- 在 **标准期限点**（1D~40Y，共 25 个点）上插值后相减
- 标准期限点列表与 `ZeroCurveBootstrap.STANDARD_TERM_DAYS` 一致

### 5.4 VolRrbf2Delta — Vanna-Volga 波动率曲面构建

**输入：** ATM / RR (Risk Reversal) / BF (Butterfly) 市场报价
**输出：** 19 × N 期限的 delta-term 波动率网格

#### 数学原理

基于 Castagna & Mercurio (2007) 二阶 Vanna-Volga 闭式方法：

**1. 支柱波动率分解：**

```
σ_25DP = σ_ATM + σ_BF - σ_RR / 2    (25-Delta Put)
σ_25DC = σ_ATM + σ_BF + σ_RR / 2    (25-Delta Call)
```

**2. 支柱执行价（Spot Delta 约定）：**

```
K_ATM = F × exp(σ² T / 2)                         (Delta-Neutral Straddle)
K_25DC = deltaToStrike(0.25, F, T, σ_25DC, call)   (25DC)
K_25DP = deltaToStrike(0.25, F, T, σ_25DP, put)    (25DP)
```

**3. VV 闭式解：**

```
σ(K) = σ_ATM + [ -σ_ATM + √(σ² + d₁d₂(2σP + Q)) ] / (d₁d₂)
```

其中 `P`, `Q` 为 log-strike Lagrange 权重加权的偏差累积。

**4. σ-K 耦合求解：**
- 对每个目标 delta(0.05 ~ 0.95)，通过 Newton-Raphson 在 K 空间迭代
- Newton 未收敛时回退到有界二分法
- 输出 `[σ_VV, K]` 对

#### Delta 网格

固定 19 个 call delta 点：`0.05, 0.10, 0.15, ..., 0.90, 0.95`

---

## 六、输出格式

### 6.1 IrCurve（利率曲线输出点）

| 字段 | 说明 |
|------|------|
| `curveId` | 曲线标识 |
| `dataDate` | 数据日期 |
| `termCode` | 期限代码（如 1M, 90D） |
| `termDays` | 期限天数 |
| `termYear` | 期限年化 (termDays/365) |
| `rate` | 零息利率（按输出 freq/dcb 转换后） |
| `discountFactor` | 折现因子 |
| `curveDaycount` | 日算规则 |
| `curveFreq` | 利率频率 |
| `interpolateType` | 插值类型 |

### 6.2 DeltaTermVol（波动率曲面输出点）

| 字段 | 说明 |
|------|------|
| `curveId` | 曲线标识 |
| `dataDate` | 数据日期 |
| `termCode` | 期限代码 |
| `termDays` / `termYear` | 期限 |
| `delta` | Call Delta（0.05~0.95） |
| `fxVol` | VV 模型波动率 |
| `fxForward` | 远期汇率 F |
| `strike` | 执行价 K |

### 6.3 转换为 MarketData

```java
// 方式一：生成新的 MarketData
CurveResult result = curveGen.generate(inputs, calendar);
MarketData md = result.toMarketData();

// 方式二：合并到已有 MarketData（外部优先：相同 curveId 不覆盖外部）
result.mergeInto(existingMarketData);
```

转换映射：
- `IrCurve` → `MarketData.irSpot`（按 curveId 分组 → `IrSpot.IrSpotInfo`）
- `DeltaTermVol` → `MarketData.fxVol`（按 curveId 分组 → `FxVol.FxVolInfo`）

---

## 七、使用示例

### 7.1 基本用法

```java
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.*;

// 1. 准备输入
List<CurveInput> inputs = JSON.parseArray(jsonStr, CurveInput.class);

// 2. 准备日历
Calendar calendar = new Calendar();

// 3. 执行曲线生成
CurveGeneration curveGen = new CurveGeneration();
CurveResult result = curveGen.generate(inputs, calendar);

// 4. 检查错误
if (!result.errors.isEmpty()) {
    result.errors.forEach(e -> logger.warn("曲线生成警告: {}", e));
}

// 5. 转换为 MarketData
MarketData md = result.toMarketData();
```

### 7.2 与已有 MarketData 联合使用

```java
// 已有市场数据（如从 Loader 加载）
MarketData existing = loader.loadMarketData(jsonData);

// 曲线生成时，MarketData 中的利率曲线优先用于下游依赖
CurveResult result = curveGen.generate(inputs, calendar, existing);

// 合并生成结果到已有数据中（同 curveId 保留 existing，不覆盖）
result.mergeInto(existing);
```

### 7.3 输入 JSON 完整示例

```json
[
  {
    "CONVERSION_TYPE": "ZeroCurveBootstrap",
    "CURVE_ID": "CNY_SHIBOR",
    "DATA_DATE": "2024-12-31",
    "CURVE_DAYCOUNT": "actual/365",
    "CURVE_FREQ": "cont",
    "INTERPOLATE_TYPE": "linear",
    "CURVE_DATA": [
      { "TERM_CODE": "1W", "TERM_TYPE": "ZERO", "TERM_VALUE": 0.018, "TERM_DAYCOUNT": "actual/360" },
      { "TERM_CODE": "1M", "TERM_TYPE": "ZERO", "TERM_VALUE": 0.020, "TERM_DAYCOUNT": "actual/360" },
      { "TERM_CODE": "3M", "TERM_TYPE": "ZERO", "TERM_VALUE": 0.022, "TERM_DAYCOUNT": "actual/360" },
      { "TERM_CODE": "1Y", "TERM_TYPE": "SWAP", "TERM_VALUE": 0.025, "TERM_DAYCOUNT": "actual/365", "TERM_FRQ": "3M" },
      { "TERM_CODE": "5Y", "TERM_TYPE": "SWAP", "TERM_VALUE": 0.030, "TERM_DAYCOUNT": "actual/365", "TERM_FRQ": "3M" }
    ]
  },
  {
    "CONVERSION_TYPE": "FxImpliedCurveConstruct",
    "CURVE_ID": "USD_IMPLIED",
    "DATA_DATE": "2024-12-31",
    "BASE_DISCOUNT_CURVE": "CNY_SHIBOR",
    "BASE_CURRENCY_CODE": "USD",
    "FX_SPOT": 7.2988,
    "CURVE_DATA": [
      { "TERM_CODE": "ON", "FWD_RATE": 7.2990 },
      { "TERM_CODE": "1M", "FWD_RATE": 7.3050 },
      { "TERM_CODE": "3M", "FWD_RATE": 7.3200 },
      { "TERM_CODE": "1Y", "FWD_RATE": 7.3800 }
    ]
  },
  {
    "CONVERSION_TYPE": "ZeroCurveSubtract",
    "CURVE_ID": "CNY_CREDIT_SPREAD",
    "DATA_DATE": "2024-12-31",
    "YC_CURVE_CODE": "CNY_CORP_YIELD",
    "RF_CURVE_CODE": "CNY_SHIBOR"
  },
  {
    "CONVERSION_TYPE": "VolRrbf2Delta",
    "CURVE_ID": "EURCNY_VOL",
    "DATA_DATE": "2024-12-31",
    "BASE_DISCOUNT_CURVE": "CNY_SHIBOR",
    "UNDERLYING_DISCOUNT_CURVE": "EUR_CURVE",
    "FX_SPOT": 7.85,
    "CURVE_DATA": [
      { "TERM_CODE": "1M", "ATM_VOL": 0.065, "RR_VOL": -0.005, "BF_VOL": 0.002 },
      { "TERM_CODE": "3M", "ATM_VOL": 0.070, "RR_VOL": -0.008, "BF_VOL": 0.003 },
      { "TERM_CODE": "1Y", "ATM_VOL": 0.080, "RR_VOL": -0.012, "BF_VOL": 0.004 }
    ]
  }
]
```

---

## 八、依赖关系与拓扑排序

模块通过分析 `CurveInput` 的字段自动收集依赖关系：

| 字段 | 产生依赖 |
|------|----------|
| `BASE_DISCOUNT_CURVE` | 依赖该 curveId 的曲线先生成 |
| `UNDERLYING_DISCOUNT_CURVE` | 依赖该 curveId 的曲线先生成 |
| `YC_CURVE_CODE` | 依赖该 curveId 的曲线先生成 |
| `RF_CURVE_CODE` | 依赖该 curveId 的曲线先生成 |

排序算法使用 **Kahn 算法（BFS 拓扑排序）**：

1. 计算每个节点的入度（仅统计本批次内的依赖）
2. 入度为 0 的节点入队
3. 逐一出队执行，将下游节点入度减 1
4. 若所有节点均已处理完，排序成功；否则存在循环依赖

**外部依赖（如 MarketData 中已有的曲线）** 不计入入度，而是通过 `curvePool` 预填充直接可用。

---

## 九、freq / daycount 转换规则

### 支持的频率（freq）

| 值 | 含义 |
|----|------|
| `cont` | 连续复利 |
| `annual` | 年付 |
| `semi` | 半年付 |
| `quarterly` | 季付 |

### 支持的日算规则（daycount）

| 值 | 含义 |
|----|------|
| `actual/360` | 实际天数 / 360 |
| `actual/365` | 实际天数 / 365 |
| `30/360` | 30天月 / 360 |

### 转换原则

折现因子 `DF` 在任何 freq/dcb 组合下恒等：

```
DF = exp(-r_cont × T_365)  （连续复利 + actual/365）
   = 1 / (1 + r_annual × T_dcb)^n  （离散复利）
```

内部计算统一用 `cont + actual/365`，输出时通过 `CurveFunc.convertIrRate()` 转换。

---

## 十、测试

### 运行集成测试

```bash
javac -encoding UTF-8 -cp "engine/src/main/java;engine/target/classes;engine/lib/*" \
  -d engine/target/classes \
  engine/src/main/java/com/zcyh/mr/marketdata/curvegeneration/CurveGenerationTest.java

java -cp "engine/target/classes;engine/src/main/java;engine/lib/*" \
  com.zcyh.mr.marketdata.curvegeneration.CurveGenerationTest
```

测试内容：
1. 加载 `test_curve_data.json`
2. 解析为 `CurveInput` 列表
3. 执行 `CurveGeneration.generate()`
4. 按曲线类型和 CURVE_ID 分组输出
5. 验证不同 freq/dcb 下的 DF 一致性
