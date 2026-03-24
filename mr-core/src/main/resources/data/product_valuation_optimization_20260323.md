# 产品估值优化清单

## 文档目的

本文件用于记录产品估值实现层面的已识别缺口、优化建议与后续实施方向。

本文件关注的是产品估值、现金流生成、节假日处理、交割规则等实现问题，不等同于 FRTB 敏感性规则文档。

## 当前已识别问题

### 0. Bond 浮息债计息目标口径统一为单利

文件：
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\ir\Bond.java

当前处理：
- 债券浮息端在构造 `StructuredCashflow.ScfInfo` 时，统一将目标计息口径固定为单利。
- 不再依赖债券输入中的：
  - `floatingIndexFreq`
  - `fltDayCountBasis`
  - `adjustFlag`
- 债券利息支付时点 `paymentTiming` 不再作为外部输入字段，统一在内部固定为 `arrear`。
- 债券本金处理标识 `NOTIONAL_FLAG` 不再作为外部输入字段，统一在内部固定为空字符串。

实现方式：
- 浮息债统一设置目标频率为 `smp`
- 目标日计数规则统一使用债券自身 `dayCountBasis`
- 债券统一设置 `paymentTiming = "arrear"`
- 债券统一设置 `nationalFlag = "END"`，内部固定到期归还本金
- `StructuredCashflow` 浮动端起点调整统一使用 `fixingCalendar`，不再回退 `settleCalendar`
- `Swaption`、`IRSCCS` 显式传入 `fixingCalendar`；`Bond/CDS` 当前内部映射为 `settleCalendar`

边界说明：
- 本次仅收口 `Bond` 产品。
- `StructuredCashflow.ScfInfo` 中同名字段暂时保留，避免影响其他利率产品。

### 1. CapFloor 缺少现金流交割 payment 规则

文件：
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\ir\CapFloor.java

当前情况：
- `CapFloor` 在现金流生成和估值过程中，尚未显式建模现金流交割 `payment` 规则。
- 当前实现主要围绕起息、到期、折现和期权价值计算展开，但没有独立的支付规则口径。

影响：
- 当产品约定存在明确的支付规则时，当前实现可能无法准确反映真实支付日。
- 该问题首先影响现金流生成准确性，进一步可能影响估值结果与现金流明细输出。

优化建议：
- 增加与交割相关的输入字段定义。
- 在现金流生成阶段显式处理 payment 规则。
- 明确 payment 规则与现有 `settleRule`、`fixingRule` 的边界，避免字段语义重叠。

### 2. CapFloor 缺少浮动利率节假日调整规则

文件：
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\ir\CapFloor.java

当前情况：
- `CapFloor` 已有 `fixingRule`、`fixingDayoff` 等字段参与部分日期处理。
- 但当前实现未形成完整的“浮动利率节假日调整规则”口径，尤其是针对浮动端重置、观察和相关现金流日期的节假日调整。

影响：
- 在节假日敏感场景下，浮动利率现金流的重置日期、起止日期和估值结果可能与业务预期不一致。
- 该问题属于产品估值实现完整性问题，不直接等同于 FRTB 敏感性规则问题。

优化建议：
- 明确浮动端日期规则的字段定义与优先级。
- 在现金流构建阶段补齐节假日调整逻辑。
- 增加覆盖节假日边界的样例测试，验证现金流日期与估值结果。

## 后续维护建议

- 后续如果继续发现 `Bond`、`Swaption`、`IrsCcs` 等产品的估值实现缺口，统一追加到本文件。
- 每条问题建议至少记录：
  - 问题描述
  - 影响范围
  - 当前实现位置
  - 优化建议
  - 是否已修复
