# FRTB Sensitivity 赋值规则（唯一基准）

## 1. 使用范围

- 本文件用于统一 `riskFactorId`、`riskFactorBucket`、`riskFactorType` 的赋值规则。
- 适用范围：`mr` 目录内全部 FRTB 敏感性生成逻辑（Delta / Vega / Curvature）。
- 若产品实现与本文件不一致，先记录差异，再按需求调整。

## 1.1 交易自身 Greek 与 FRTB 敏感度的边界

- 本文件约束的是 **FRTB 监管敏感度** 的生成规则，不直接约束交易自身 `delta / gamma / vega / pv01` 等 greek 的产品内实现。
- 适用范围扩展到：
  - 全部交易类型
  - 全部标的类型
  - 全部风险类别（`GIRR / FX / EQ / CMTY / CSR`）
- 交易自身 greek 可以继续服务于产品定价、对冲、报表和日常风险分析；FRTB 敏感度单独服务于监管计量与 SBA 聚合。
- 因此，**交易自身 greek 与 FRTB 敏感度不要求数值一致，也不要求 shock 方式一致**。
- 只要 FRTB 输出满足本文件定义的 `riskFactorId / riskFactorBucket / riskFactorType / vertex` 规则，就不因为交易自身 greek 口径不同而视为 FRTB 缺陷。
- 典型允许分叉场景包括但不限于：
  - 交易自身 `vega` 使用产品本地整面波动率 shift；
  - FRTB Vega 使用标准 tenor shock；
  - 交易自身二阶 greek 使用产品本地差分；
  - FRTB Curvature 使用监管 shock 与监管字段口径。
- `Swaption` 只是上述原则的一个具体例子：
  - 交易本身 `vega` 可继续保留本地整面 IR vol shift 口径；
  - FRTB GIRR Vega 按 FRTB tenor shock 与第二维期限规则单独计量；
  - 两者无需强制统一。

## 1.2 敏感性发生规则

- 线性产品仅产生 `Delta`，不产生 `Vega / Curvature`。
- 含权产品仅对主标的风险类别产生 `Delta / Vega / Curvature`。
- 含权产品的其他真实依赖风险类别仅产生 `Delta`。
- `FX Delta` 与 `GIRR Delta` 可适用于全部产品类型；只要交易真实依赖汇率或利率曲线，即可产生对应 `Delta`。
- `Bond` 仅在含权债场景下允许产生 `GIRR Curvature`；`Bond` 不产生 `CSR Curvature`。
- `Cds` 不产生任何 `Curvature`，仅产生 `CSR Delta`。

典型适用方式：

- `FXFWD / FXSWAP`
  - 属于线性产品；
  - 可产生：`FX Delta`、`GIRR Delta`；
  - 不产生：`FX Vega / FX Curvature / GIRR Vega / GIRR Curvature`。
- `AUTO_CALL(UNDERLYING_TYPE=FX)`、`FX_BARRIER`、`FX_DIGITAL`、`FX_SPREADOPT`、`FX_WEDDING_CAKE`
  - 属于含权产品，主标的风险类别为 `FX`；
  - `FX` 产生：`Delta / Vega / Curvature`；
  - `GIRR` 如交易真实依赖贴现或利率曲线，仅产生：`Delta`。
- `IR_BARRIER`、`IR_DIGITAL`、`IR_WEDDING_CAKE`、`IR_SHARKFIN`、`IR_SPREADOPT`、`IR_RANGE_ACCURE`、`IR_STEP_UP`
  - 属于含权产品，主标的风险类别为 `GIRR`；
  - `GIRR` 产生：`Delta / Vega / Curvature`；
  - `FX` 如交易存在外币结算或汇率换算依赖，仅产生：`Delta`。
- `EQ / CMTY` 含权结构产品同理：
  - 主标的风险类别产生：`Delta / Vega / Curvature`；
  - 其他真实依赖风险类别仅产生：`Delta`。

## 2. 统一规则表

| 风险因子大类 | 敏感性类型 | riskFactorId | riskFactorBucket | riskFactorType |
|---|---|---|---|---|
| GIRR | Delta | 利率曲线名（如 `DISCOUNT_CURVE` / `REFERENCE_CURVE`）；CCS Basis 取 `CCS_BASIS_<ccy>_OVER_USD/EUR` 风险因子名 | 曲线对应币种（来自曲线-币种映射）；CCS Basis 使用对应币种桶，`CNH` 在 bucket 层统一归一为 `CNY` | `Interest Rate`（常规） / `Basis`（仅 `swapType=ccs` 的 Basis 敏感性） |
| GIRR | Curvature Up / Curvature Down | 桶币种（CCY） | 桶币种（CCY） | 空字符串 `""` |
| GIRR | Vega | IR 波动率曲面名（`VOLATILITY_SURFACE`） | GIRR Vega 对应币种桶 | 空字符串 `""` |
| FX | Delta | `币种 + "/CNY"` | 被冲击币种 | 空字符串 `""` |
| FX | Curvature Up / Curvature Down | `币种 + "/CNY"` | 被冲击币种 | 空字符串 `""` |
| FX | Vega | `FX_undccy_baseccy_VOL` | `undccy/baseccy` | 空字符串 `""` |
| EQ | Delta | `REFERENCE_CURVE` | `EQ_BUCKET`（空值默认 `11`） | `Spot` |
| EQ | Curvature Up / Curvature Down | `REFERENCE_CURVE` | `EQ_BUCKET`（空值默认 `11`） | `Spot` |
| EQ | Vega | EQ 波动率曲面名 | `EQ_BUCKET`（空值默认 `11`） | `Spot` |
| CMTY | Delta | `FRTB_COMM_ASSET`；`location` 仅做拼接（有则 `asset&location`） | `FRTB_COMM_BUCKET`（为空则不计算 CMTY 敏感性并记录 WARNING LOGS） | 空字符串 `""` |
| CMTY | Curvature Up / Curvature Down | 同 CMTY Delta | 同 CMTY Delta | 空字符串 `""` |
| CMTY | Vega | `FRTB_COMM_ASSET`；不拼接 `location` | 同 CMTY Delta | 空字符串 `""` |
| CSR（non-sec / non-ctp / ctp） | Delta | `issuer` | `CSR_BUCKET` | 默认 `BOND`；信用类交易在交易层显式改为 `CDS` |
| CSR（non-sec / non-ctp / ctp） | Curvature Up / Curvature Down | 同 CSR Delta | 同 CSR Delta | 同 CSR Delta |
| CSR（non-sec / non-ctp / ctp） | Vega | 同 CSR Delta（若该产品启用） | 同 CSR Delta | 同 CSR Delta |

## 3. 落地约束

- `riskFactorBucket` 必须非空，避免 SBA 聚合阶段空指针风险。
- CMTY / CSR 可在产品层覆盖 `riskFactorId`、`riskFactorBucket`、`riskFactorType`，但口径必须满足本文件规则。
- CSR 的 `riskFactorType` 默认值为 `BOND`；仅信用类交易在交易层改写为 `CDS`，不直接按 `productCode` 透传。
- CMTY 输入字段命名统一使用 `FRTB_COMM_BUCKET`；若出现 `FRTB_COMM_BUKET`（拼写冲突），按 `FRTB_COMM_BUCKET` 缺失处理并记录 WARNING LOGS。
- 当 `FRTB_COMM_BUCKET` 或 `FRTB_COMM_ASSET` 缺失时，仅跳过相关 CMTY 敏感性，不影响估值主流程，交易计量状态仍为成功。
- CMTY 的 `riskFactorId` 统一使用 `FRTB_COMM_ASSET`，不使用 `UNDERLYING_CODE`、`REFERENCE_CURVE` 或 `instrumentId` 兜底。
- `UNDERLYING_CODE` 是商品交易估值入参依赖，不参与 FRTB CMTY `riskFactorId` 选取。
- `FRTB_COMM_ASSET` 是 CMTY FRTB 敏感性的必需主标识；缺失时记录 WARNING LOGS 并跳过 CMTY 敏感性。
- `location` 字段仅用于 `riskFactorId` 字符串拼接，不参与主标识（base）选取判断。
- CMTY Vega 的 `riskFactorId` 不拼接 `location`；仅 CMTY Delta/Curvature 使用 `location` 拼接。
- CMTY Delta 统一按标准 tenor 点定义 `shock ratio = 0.01`：
  - 仅在被 shock tenor 的相邻两个区间内线性衰减；
  - 若被 shock 的是全局最小 tenor，则小于最小 tenor 的期限直接使用 `0.01`；
  - 若被 shock 的是全局最大 tenor，则大于最大 tenor 的期限直接使用 `0.01`；
  - 交易最终使用价格为：`usedPrice = basePrice * (1 + shockRatio(days))`；
  - 最终数值统一按 `(valuation_shocked - valuation_base) / 0.01` 计算。
- FRTB Vega 统一按标准 tenor shock 口径计量，不再使用旧的整面统一 shock 接口。
- FRTB Vega 单期限 shock 在标准 tenor 点上定义 `shock ratio = 0.001`，并按交易真实请求期限对该 ratio 做线性插值。
- FRTB Vega 的交易最终使用波动率为：`usedVol = baseVol * (1 + shockRatio(days))`。
- FRTB Vega 最终数值统一按 `(valuation_shocked - valuation_base) / 0.001` 计算。
- GIRR Delta 的 `Basis` 仅用于 CCS 交易的 Basis 风险因子，不替代常规利率曲线的 `Interest Rate` 类型。
- CCS Basis 统一由 GIRR builder 生成，不再由产品层手工拼装 `FrtbSenes`。
- CCS Basis 生成规则如下：
  - 输入参数为两条腿的 `currency1 / curve1 / currency2 / curve2`；
  - 若一边为 `USD`，仅生成另一边 `CCS_BASIS_<ccy>_OVER_USD`；
  - 若不存在 `USD` 但一边为 `EUR`，仅生成另一边 `CCS_BASIS_<ccy>_OVER_EUR`；
  - 若两边都不是 `USD/EUR`，两边均生成 `CCS_BASIS_<ccy>_OVER_USD`；
  - Basis 数值按目标曲线整条平移 `1bp` 后重估得到，属于平移 PV01，不按期限点拆分；
  - Basis 不生成 `vertex1 / vertex2`，对应输出字段保持空字符串；
  - bucket 仅做 `CNH -> CNY` 归一化，不改写 `riskFactorId` 中的币种字符串。
- EQ 的 `riskFactorType` 统一使用 `Spot`（若输入为空也按 `Spot` 处理）。
- FX Vega 的 `riskFactorId` 与 `riskFactorBucket` 由产品层按规则提供原始业务信息，最终由公共 builder 生成并输出。
- FX Curvature（仅 FX Curvature Up / Down）是否除以 `1.5`，统一由公共 builder 判断：
  - 仅当 `fxPair` 可解析，且货币对两边都不是 `CNY/CNH` 时才除以 `1.5`；
  - `fxPair` 为空或无法解析时，不除以 `1.5`，也不报错。
- FRTB 聚合前，`FX` 与 `GIRR` 的 `riskFactorBucket` 统一执行币种归一化：`CNH -> CNY`。
- `FrtbSenes` 是当前唯一对外输出格式；公共 builder 与公共门面内部直接生成 `FrtbSenes`，产品层统一直接调用 `FrtbSensitivityBuilder` 的正式输出入口。
- `MeasureValuation` 当前只保留顶层公共类 `com.zcyh.mr.product.basic.frtb.MeasureValuation`；不再保留 `FrtbSensitivityBuilder.MeasureValuation` 嵌套兼容别名。
- 若新增产品或新增风险类别，先补充本文件规则，再实现代码。
