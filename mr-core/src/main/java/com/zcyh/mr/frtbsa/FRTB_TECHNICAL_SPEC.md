# FRTB 标准法技术实现方案

> 当前文档以 `com.zcyh.mr.frtbsa` 代码为准，覆盖 FRTB SBA 敏感度法和 DRC 违约风险资本。历史 README 中的旧包名、Java 8、javac 脚本、旧 DRC 输出结构等口径已不再作为当前状态。

---

## 一、系统总体架构

### 1.1 模块全景

```text
frtbsa/
├── sba/                              # SBA 敏感度法
│   ├── common/
│   │   ├── FileUtils.java            # 资源读取
│   │   ├── FrtbConstants.java        # 风险类别、敏感性类型、场景常量
│   │   └── FrtbParamsCache.java      # param.json 参数缓存与相关性矩阵构建
│   ├── core/
│   │   ├── FrtbAggregator.java       # SBA 计算总入口
│   │   ├── girr/                     # GIRR
│   │   ├── csrns/                    # CSR (non-sec)
│   │   ├── csrnc/                    # CSR (non-ctp)
│   │   ├── csrctp/                   # CSR (ctp)
│   │   ├── eq/                       # EQ
│   │   ├── fx/                       # FX
│   │   └── cmty/                     # CMTY
│   └── pojo/
│       ├── FrtbInput.java
│       ├── FRTBClassResult.java
│       ├── FRTBBucketResult.java
│       └── FRTBPosResult.java
└── drc/
    ├── DRCModule.java                # DRC 模块入口与输出格式化
    └── DrcCalculator.java            # NS/NCTP/CTP DRC 计算器
```

资源文件：

```text
mr-core/src/main/resources/com/zcyh/mr/frtbsa/param.json
```

当前 `frtbsa` 源码目录包含 38 个 Java 文件，构建随 engine Maven 多模块统一执行。

### 1.2 技术栈

| 组件 | 当前口径 |
|------|----------|
| JDK | 17 |
| 构建 | Maven 多模块，`mr-core` + `mr-app` |
| JSON | fastjson2 |
| 数学计算 | JDK 数学函数 + BigDecimal |
| Spring 集成 | `mr-app` 中 `frtb_sba` / `frtb_drc` engine adapter |

### 1.3 核心设计原则

1. 参数外置：风险权重、相关性标量、期限点等可调参数来自 `param.json`。
2. 三场景计算：SBA 同时计算 Normal / High / Low 相关性场景，并取最大资本。
3. 结构化输出：SBA 输出 class、bucket、pos、decomp；DRC 输出资本、法人分解、明细分解。
4. 批量并行：`FrtbAggregator` 支持批量计算，线程池由 Spring 应用层注入。
5. 分解可加：批量模式下子维度使用 TOTAL 的 pder 重算分解资本，保证向上可加。

---

## 二、SBA 敏感度法

### 2.1 风险类别与敏感性类型

当前支持的风险类别：

| 风险类别 | 常量值 |
|----------|--------|
| GIRR | `GIRR` |
| CSR non-sec | `CSR (non-sec)` |
| CSR non-ctp | `CSR (non-ctp)` |
| CSR ctp | `CSR (ctp)` |
| EQ | `EQ` |
| FX | `FX` |
| CMTY | `CMTY` |

当前支持的敏感性类型：

| 类型 | 常量值 |
|------|--------|
| Delta | `Delta` |
| Vega | `Vega` |
| Curvature Up | `Curvature Up` |
| Curvature Down | `Curvature Down` |

`Curvature Up` 和 `Curvature Down` 必须配对输入。聚合输出中 Curvature 作为统一敏感性类型展示。

### 2.2 输入模型

核心 Java 模型为 `com.zcyh.mr.frtbsa.sba.pojo.FrtbInput`：

| 字段 | 含义 |
|------|------|
| `ruleId` | 规则 ID |
| `groupType` | 汇总维度，如 `TOTAL`、`PORTFOLIO`、`TRADER` |
| `groupValue` | 维度值 |
| `riskFactorId` | 风险因子 ID |
| `riskFactorVertex1` | 期限 1 |
| `riskFactorVertex2` | 期限 2 |
| `riskFactorClass` | 风险类别 |
| `riskFactorBucket` | 桶编号或货币桶 |
| `riskFactorType` | 风险因子类型 |
| `sensitivityType` | 敏感性类型 |
| `sensitivityValRptCurrCny` | 原始敏感度，人民币报告口径，未乘风险权重 |
| `dataDate` | 数据日期 |
| `modifier` | 修改人 |

Spring Boot 同步接口输入使用 snake_case：

```json
{
  "need_decompose": true,
  "frtb_input_list": [
    {
      "rule_id": "20251231_EOD_RULE",
      "group_type": "TOTAL",
      "group_value": "TOTAL",
      "risk_factor_class": "GIRR",
      "risk_factor_bucket": "CNY",
      "risk_factor_id": "IR_CURVE_CNY",
      "risk_factor_type": "yield",
      "risk_factor_vertex_1": "0.25",
      "risk_factor_vertex_2": "",
      "sensitivity_type": "Delta",
      "sensitivity_val_rpt_curr_cny": 1000000,
      "data_date": "20251231"
    }
  ]
}
```

### 2.3 校验与归一化

`FrtbAggregator.moduleCheck` 当前执行以下校验：

1. 输入为空时返回空结果。
2. `sensitivityType` 必须为 `Delta`、`Vega`、`Curvature Up`、`Curvature Down`。
3. `riskFactorBucket` 必填。
4. GIRR tenor 必须可解析为正数，支持 `M`、`Y` 后缀或纯年数。
5. Curvature Up / Down 按配对键检查完整性。
6. FX 与 GIRR 的货币桶归一化：`CNH` 统一映射为 `CNY`。

校验失败的记录不参与资本计算，输出中保留：

```text
ERROR_COUNT
ERRORS
```

### 2.4 计算流程

```text
FrtbAggregator.calculateAsMap(rawList, needDecompose)
  ├── moduleCheck
  ├── 按 riskFactorClass 分组
  ├── 路由到各风险模块
  │   ├── GirrModule
  │   ├── CsrnsModule
  │   ├── CsrncModule
  │   ├── CsrctpModule
  │   ├── EqModule
  │   ├── FxModule
  │   └── CmtyModule
  ├── 生成各风险类别结果
  └── appendAllRiskClassResult 生成 ALL 汇总风险类
```

批量计算：

```text
FrtbBatchCalculator.calculateBatch(tasks, needDecompose, threadCount)
  ├── threadCount <= 0 使用默认线程数
  ├── threadCount == 1 或任务数 <= 1 时串行
  ├── 多线程模式使用 Spring 注入的 ExecutorService
  ├── 每个任务最多等待 60 秒
  ├── needDecompose=true 时，子维度使用 TOTAL 的 pder 重算 decompRslt
  └── 每个分组结果追加 ALL 汇总风险类
```

默认线程数为 `CPU 核数 × 80%`，至少为 1。每批提交任务数上限当前为 500。

### 2.5 三场景相关性

场景常量：

| 场景 | 代码 | 输出名称 | 相关性调整 |
|------|------|----------|------------|
| Normal | `M` | `normal` | `rho` |
| High | `H` | `high` | `min(rho * 1.25, 1.0)` |
| Low | `L` | `low` | `max(2 * rho - 1, rho * 0.75)` |

三场景资本均计算，最终资本取最大值。

### 2.6 通用公式

Delta / Vega：

```text
WS_k = sensitivity_k * RW_k

Kb^2 = sum_k sum_l rho_kl * WS_k * WS_l
Kb   = sqrt(max(Kb^2, 0))

Sb = sum_k WS_k

Capital^2 = sum_b Kb_b^2 + sum_{b != c} gamma_bc * Sb_b * Sb_c
Capital   = sqrt(max(Capital^2, 0))
```

Curvature：

```text
CVR_up   = V(x + RW) - V(x) - sensitivity * RW
CVR_down = V(x - RW) - V(x) + sensitivity * RW

输入直接提供 Curvature Up / Curvature Down 值。
模块内部按风险类别规则进行桶内和跨桶聚合。
```

### 2.7 参数管理

参数文件：

```text
mr-core/src/main/resources/com/zcyh/mr/frtbsa/param.json
```

`FrtbParamsCache` 首次读取后缓存，支持：

```java
FrtbParamsCache.reload();
```

主要参数分为：

| 节点 | 内容 |
|------|------|
| `Weights` | 各风险类别风险权重 |
| `Scalar` | gamma、rho、tenor lambda、bucketCount 等标量参数 |

相关性矩阵不直接维护在 JSON 中，由 `FrtbParamsCache` 基于标量动态构建：

| 方法 | 作用 |
|------|------|
| `buildGirrRhoMatrix()` | GIRR 期限相关性矩阵 |
| `buildEqRhoMap()` | EQ 桶内相关性 |
| `buildEqGammaMatrix()` | EQ 跨桶相关性 |
| `buildCsrnsDeltaGammaMatrix()` | CSRNS Delta 跨桶相关性，隔离桶 16 |
| `buildCsrncDeltaGammaMatrix()` | CSRNC Delta 跨桶相关性，隔离桶 25 |
| `buildCtpGammaMatrix()` | CSR CTP 跨桶相关性 |
| `buildCmtyRhoMap()` | CMTY 桶内相关性 |
| `buildCmtyGammaMatrix()` | CMTY 跨桶相关性 |

### 2.8 风险类别参数口径

| 类别 | 关键口径 |
|------|----------|
| GIRR | 桶为货币，标准期限由 `Scalar.GIRR.tenors` 配置；利率期限相关性按指数衰减并受 floor 约束；basis/inflation 使用专属 RW |
| EQ | 桶数由 `Scalar.EQ.bucketCount` 配置；桶 11、桶 12 使用特殊 rho |
| FX | 无传统多桶结构；主要货币集合为 EUR、USD、GBP、AUD、JPY、SEK、CAD、CNY；主要货币 RW 使用折扣系数 |
| CSRNS | 按桶计算；Delta 跨桶相关性中 bucket 16 与其他桶隔离 |
| CSRNC | 按桶计算；Delta 跨桶相关性中 bucket 25 与其他桶隔离 |
| CSRCTP | 已接入 SBA Delta / Vega / Curvature 模块 |
| CMTY | 桶数由 `Scalar.CMTY.bucketCount` 配置，rho/gamma 由 Scalar 提供 |

### 2.9 输出结构

`calculateAsMap` 输出按风险类别组织：

```json
{
  "GIRR": {
    "Delta": {
      "class": {},
      "bucket": [],
      "pos": [],
      "decompRslt": [],
      "kl": []
    },
    "Vega": {},
    "Curvature": {}
  },
  "FX": {},
  "ALL": {}
}
```

`ALL` 不是输入风险类别，而是 `FrtbAggregator` 追加的汇总风险类。它按 Delta、Vega、Curvature 汇总真实风险大类的 class 资本和分解结果。

`calculateAsPojo` 输出：

```text
classResults  -> List<FRTBClassResult>
bucketResults -> List<FRTBBucketResult>
posResults    -> List<FRTBPosResult>
```

`FRTBClassResult` 主要字段：

| 字段 | 含义 |
|------|------|
| `riskFactorClass` | 风险类别，包含真实风险大类和 `ALL` |
| `normalDelta/highDelta/lowDelta` | Delta 三场景资本 |
| `normalVega/highVega/lowVega` | Vega 三场景资本 |
| `normalCurvature/highCurvature/lowCurvature` | Curvature 三场景资本 |
| `riskCharge` | 三类敏感性合计后按三场景取最大 |
| `maxSign` | `normal`、`high` 或 `low` |
| `allocatedCapital` | 分解资本最大场景值 |

`FRTBBucketResult` 主要字段：

| 字段 | 含义 |
|------|------|
| `KbM/KbH/KbL` | Normal/High/Low 桶资本 |
| `SbM/SbH/SbL` | 桶净敏感度 |
| `SbbM/SbbH/SbbL` | 跨桶组合贡献 |

`FRTBPosResult` 主要字段：

| 字段 | 含义 |
|------|------|
| `sensitivityValRptCurrCny` | 原始敏感度 |
| `riskWeight` | 风险权重 |
| `ws` | 加权敏感度 |
| `contribution` | 分配资本 |
| `unitContribution` | 单位贡献度 |

### 2.10 分解口径

`need_decompose` 默认 `true`。

单组计算时，各模块按 Euler / pder 逻辑生成 `decompRslt`。

批量计算时，`FrtbAggregator.reassignDecompByTotalPder` 会：

1. 查找 key 以 `|TOTAL` 结尾的 TOTAL 维度。
2. 从 TOTAL 维度的 `decompRslt` 建立 `bucket|riskFactorId|vertex1|vertex2` 的 pder 索引。
3. 对子维度用自身 `ws` 乘以 TOTAL pder，重算子维度 allocatedCapital。

该口径保证子维度贡献向上可加，并避免每个子维度各自重算 pder 后出现不可加。

---

## 三、DRC 违约风险资本

### 3.1 模块入口

DRC 当前入口：

```java
com.zcyh.mr.frtbsa.drc.DRCModule.calc(List<DrcDetail> data, LocalDate dataDate)
```

底层计算器：

```java
com.zcyh.mr.frtbsa.drc.DrcCalculator
```

Spring Boot 同步接口 engine code：

```text
frtb_drc
```

### 3.2 输入模型

DRC 输入模型为 `com.zcyh.mr.product.basic.frtb.DrcDetail`：

| 字段 | 当前计算口径 |
|------|--------------|
| `dataDate` | 数据日期 |
| `portfolioCode` | 投组代码 |
| `productCode` | 产品代码 |
| `instrumentId` | 工具 ID |
| `securityId` | 证券 ID |
| `securityType` | DRC 类型，决定 NS/NCTP/CTP 分流 |
| `legalEntity` | 法人或义务人 |
| `drcBucket` | DRC 桶 |
| `jtdType` | JTD 类型 |
| `seniority` | 优先级 |
| `riskWeight` | 风险权重 |
| `jtd` | 原始 JTD 字段，当前不作为主计算值 |
| `jtdCny` | 当前 DRC 主计算值 |
| `instrumentValue` | 工具市值 |
| `frtbLgd` | LGD |
| `notional` | 名义本金 |

当前 DRC 主计算字段是 `JTD_CNY`。`JTD_CNY` 为空的记录会被过滤，并输出 warn 日志；不会用 `JTD` fallback 代替。

Spring Boot 同步接口输入：

```json
{
  "data_date": "20251231",
  "drc_detail_list": [
    {
      "security_type": "JTD (non-sec)",
      "legal_entity": "IssuerA",
      "drc_bucket": "BBB",
      "jtd_type": "BOND",
      "seniority": 1,
      "risk_weight": 0.06,
      "jtd": 1000000,
      "jtd_cny": 1000000
    }
  ]
}
```

### 3.3 DRC 类型

`DRCModule` 按 `securityType` 分流：

| 类型 | 当前状态 |
|------|----------|
| `JTD (non-sec)` | 已实现，标准 NS 计算 |
| `JTD (non-ctp)` | 已实现，标准 NCTP 计算 |
| `JTD (ctp)` | 已实现，独立 CTP 计算路径 |

CTP 不再是预留或抛异常状态。

### 3.4 NS / NCTP 计算流程

```text
DrcCalculator.doCalculateStandard(details)
  ├── 过滤 JTD_CNY 为空记录
  ├── 按 RiskFactorKey 净额轧差
  ├── 拆分 longPos / shortPos
  ├── 按 HedgeGroupKey 汇总空头并保留 seniority 层级
  ├── 计算 PA/PB/PC/PD
  ├── 按 bucket 计算 HBR、加权 JTD、DRC
  ├── 计算 PDER
  └── 生成 legalEntityDecomp / detailDecomp
```

核心分组键：

```text
RiskFactorKey = securityType + legalEntity + drcBucket + jtdType + seniority + riskWeight
HedgeGroupKey = legalEntity + drcBucket
```

优先级对冲规则：

```text
同一 legalEntity + drcBucket 内，同级或更低优先级空头可对冲当前多头。
代码使用 TreeMap.tailMap(seniority, true) 实现。
```

标准 DRC 桶级公式：

```text
NetJTD_Long  = sum(long net JTD_CNY)
NetJTD_Short = sum(abs(short net JTD_CNY))

HBR = NetJTD_Long / (NetJTD_Long + NetJTD_Short)

WtdJTD_Long  = sum(long JTD_CNY * RW)
WtdJTD_Short = sum(abs(short JTD_CNY) * RW)

DRC_bucket = max(WtdJTD_Long - HBR * WtdJTD_Short, 0)
```

PDER 公式：

```text
d = NetJTD_Long + NetJTD_Short
v = NetJTD_Long * WtdJTD_Short / d^2

PDER = PC - PA * (WtdJTD_Short / d - v) + PB * v - PD * NetJTD_Long / d

Contribution_i = JTD_CNY_i * PDER_i
```

### 3.5 CTP 计算流程

CTP 当前使用独立路径 `doCalculateCtp`：

1. 仍按 `RiskFactorKey` 净额轧差。
2. CTP 的 HBR 在全体 CTP 头寸层面统一计算，而不是逐 bucket 计算。
3. bucket 级 `D_b` 允许为负，不做 bucket 级 0 下限。
4. 总资本聚合时，负 bucket 以 0.5 系数计入。
5. 总资本经 0 下限后若为 0，全部 pder 置 0。

CTP 关键公式：

```text
HBR_ctp = totalLong / (totalLong + totalShort)

D_b = WtdJTD_Long_b - HBR_ctp * WtdJTD_Short_b

factor_b = 1.0  if D_b >= 0
factor_b = 0.5  if D_b < 0

Capital = sum_b factor_b * D_b
```

### 3.6 DRC 输出结构

当前 `DRCModule.formatResult` 输出三个数组：

```json
{
  "DRC_VALUE": [],
  "DECOMP_LEGALENTITY": [],
  "DECOMP_DETAIL": []
}
```

`DRC_VALUE`：

| 字段 | 含义 |
|------|------|
| `AGG_LEVEL` | `LEGAL_ENTITY`、`BUCKET`、`DRC_TYPE` |
| `LEGAL_ENTITY` | 法人或 `TOTAL` |
| `DRC_TYPE` | `JTD (non-sec)`、`JTD (non-ctp)`、`JTD (ctp)` |
| `DRC_BUCKET` | 桶或 `TOTAL` |
| `DRC_VALUE` | DRC 资本 |
| `DATA_DATE` | 数据日期 |

`DECOMP_LEGALENTITY`：

| 字段 | 含义 |
|------|------|
| `AGG_LEVEL` | `LEGAL_ENTITY`、`BUCKET`、`DRC_TYPE` |
| `DRC_TYPE` | DRC 类型 |
| `LEGAL_ENTITY` | 法人或 `TOTAL` |
| `DRC_BUCKET` | 桶或 `TOTAL` |
| `CONTRIBUTION` | 分解贡献 |
| `DATA_DATE` | 数据日期 |

`DECOMP_DETAIL`：

| 字段 | 含义 |
|------|------|
| `SECURITY_TYPE` | DRC 类型 |
| `LEGAL_ENTITY` | 法人 |
| `DRC_BUCKET` | 桶 |
| `JTD_TYPE` | JTD 类型 |
| `SENIORITY` | 优先级 |
| `RISK_WEIGHT` | 风险权重 |
| `JTD_CNY` | 净 JTD CNY |
| `CONTRIBUTION` | 明细贡献 |
| `DATA_DATE` | 数据日期 |

---

## 四、Spring Boot 集成与调度

### 4.1 Engine Adapter

| engine code | 适配器 | 核心模块 |
|-------------|--------|----------|
| `frtb_sba` | `FrtbSaEngineAdapter` | `FrtbAggregator` |
| `frtb_drc` | `FrtbDrcEngineAdapter` | `DRCModule` |

`FrtbSaEngineAdapter` 要求：

```text
frtb_input_list 必填且非空
need_decompose 默认 true
```

`FrtbDrcEngineAdapter` 要求：

```text
drc_detail_list 必填且非空
data_date 必填，格式 yyyyMMdd
JTD_CNY 必填
```

### 4.2 数据库汇总路径

除同步接口外，`mr-app` 还提供数据库驱动的汇总服务：

| 服务 | 作用 |
|------|------|
| `FrtbSbaDbRunnerService` | 从结果库读取敏感性明细，按规则组批后调用 `FrtbAggregator` |
| `FrtbSbaSummaryService` | SBA 汇总入口，支持 rule_id 或 inline rule |
| `FrtbSbaResultPersistService` | SBA 汇总结果落库 |
| `FrtbDrcDbRunnerService` | 从 DRC 明细表读取 `DrcDetail`，按规则计算 DRC |
| `FrtbDrcSummaryService` | DRC 汇总入口，支持 rule_id 或 inline rule |
| `FrtbDrcResultPersistService` | DRC 汇总结果落库 |

批量工作流只生成 FRTB 敏感性与 DRC 明细，不执行汇总。SBA、DRC 汇总由独立汇总接口按需触发。

---

## 五、测试与验证口径

当前不再使用 README 中的历史手工 `javac` 脚本作为发布验证口径。

代码级验证优先使用 Maven：

```powershell
cd E:\zcyh_mr\engine
mvn -pl mr-core -am compile -q
```

服务发布验证按 engine 项目规则执行：

```powershell
cd E:\zcyh_mr\engine
mvn clean package -pl mr-app -am -q "-DskipTests"
java -jar mr-app\target\mr-app.jar
```

FRTB SBA / DRC 行为验证优先通过：

1. Spring Boot engine adapter 同步接口。
2. 数据库汇总服务。
3. 批量工作流调度。
4. 有明确回归目标时补充 JUnit 或临时诊断程序。

---

## 六、与 README 的合并结论

`frtbsa/README.md` 与 `frtbsa/sba/README.md` 内容完全一致，均为 SA 模块说明。它们保留了有价值的接口、输出、并发和参数管理描述，但存在以下过期点：

| README/旧规格口径 | 当前正确口径 |
|-------------------|--------------|
| Java 8 / javac 命令行构建 | Java 17 / Maven 多模块构建 |
| 旧包名 `com.zcyh.mr.module.frtb.sa` | 当前包名 `com.zcyh.mr.frtbsa.sba` |
| 34 个 Java 文件 | 当前 38 个 Java 文件 |
| DRC 输出 `RESULT / DECOMP_PORTFOLIO / DECOMP_RISKFACTOR` | 当前 `DRC_VALUE / DECOMP_LEGALENTITY / DECOMP_DETAIL` |
| DRC CTP 暂未实现 | 当前 CTP 有独立计算路径 |
| DRC 使用 `JTD` | 当前主计算字段为 `JTD_CNY` |
| 历史手工测试脚本 | 当前以 Maven、Spring 接口、数据库汇总和批量调度验证为准 |

本技术规格已将 README 中仍有效的 SBA 内容合并，并按当前代码修正 SA + DRC 的最终状态。
