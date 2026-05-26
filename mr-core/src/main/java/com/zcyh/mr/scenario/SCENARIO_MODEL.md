# Scenario 计量模型文档

## 一、文档目的

本文档用于说明 `scenario` 模块当前采用的标准计量模型，重点描述：

- 标准输入输出对象
- 各类情景的计量方法
- 历史类情景的日期区间语义
- 历史市场数据查询计划的组织方式

本文档不展开 Spring 装配、Adapter、Mapper 细节，重点放在情景计量本身。

## 二、标准模型

### 2.1 `ScenarioDefinition`

情景控制定义，描述单条情景规则。当前核心字段包括：

- `scenarioId`
- `scenarioCode`
- `scenarioName`
- `scenarioType`
- `curveType`
- `curveCode`
- `riskGroupId`
- `termCode`
- `termDays`
- `shockValue`
- `shockType`
- `shockRule`
- `scenarioNo`
- `holdingPeriod`
- `jumpDayNo`
- `increaseDays`
- `holidayCalendarCode`
- `startDate`
- `endDate`

补充说明：

- `CUSTOM / KEY_RATE` 在内部控制点模型上统一只使用 `termDays`
- 外部字段若不是标准 `termDays`
  - 由 `Mapper / Assembler` 负责转换
- 这两类情景内部不再依赖 definition 上的 `termCode`

### 2.2 `ScenarioMarketSeries`

市场数据标准对象，描述单条标准化市场点。当前核心字段包括：

- `curveType`
- `curveCode`
- `dataDate`
- `termCode`
- `termDays`
- `dimension2`
- `value`

### 2.3 `ScenarioGeneratedRecord`

情景生成结果对象，描述单条情景输出记录。当前真实输出字段包括：

- `scenarioId`
- `subScenarioId`
- `scenarioName`
- `scenarioType`
- `riskGroupId`
- `curveType`
- `curveCode`
- `dataDate`
- `termCode`
- `termDays`
- `dimension2`
- `originalValue`
- `changedValue`
- `shiftValue`
- `shiftRule`
- `modifier`

说明：

- `historyDate` 已从输出模型中删除，不再作为结果字段输出
- `dataDate` 统一表示当前市场数据日期，通常等于估值日

## 三、情景类型总览

当前支持的情景类型包括：

- `CUSTOM`
- `KEY_RATE`
- `HISTORY`
- `VAR`
- `SVAR`
- `BACKTEST`
- `MC`

其中可分为两大类：

1. 非历史类情景
- `CUSTOM`
- `KEY_RATE`

2. 历史类情景
- `HISTORY`
- `VAR`
- `SVAR`
- `BACKTEST`
- `MC`

## 四、非历史类情景计量方法

### 4.1 `CUSTOM`

`CUSTOM` 代表控制点直接施加冲击。

控制点期限规则：

- 内部统一只按 `termDays` 组织控制点
- definition 上的 `termCode` 不参与 `CUSTOM` 计量
- 市场点输出仍保留 market data 自身的 `termCode`
- 若控制点 `termDays<0`
  - 记录异常日志并跳过该控制点
- `termDays=0` 视为合法期限点

计量方法：

1. 根据 `ScenarioDefinition` 选择对应风险因子类型与曲线。
2. 按 `curveType` 选择 `RiskFactorProcessor`。
3. 若该风险因子需要期限插值，则通过 `LinearInterpolator` 在控制点之间插值。
4. 对当前市场数据点应用冲击。

冲击语义：

- `shockType=ABSOLUTE`
  - 新值 = 原值 + 冲击值
- `shockType=RELATIVE`
  - 新值 = 原值 × (1 + 冲击值)

字段赋值规则：

- `scenarioId`
  - 取 `scenarioCode`，为空时回退到 `scenarioId`
- `subScenarioId`
  - 固定等于输出 `scenarioId`
- `scenarioName`
  - 取定义上的 `scenarioName`
- `scenarioType`
  - 固定为 `CUSTOM`
- `riskGroupId`
  - 取定义上的 `riskGroupId`
- `curveType / curveCode / termCode / termDays / dimension2`
  - 取当前市场点
- `dataDate`
  - 取当前市场日期
- `originalValue`
  - 当前市场值
- `shiftValue`
  - 取定义上的 `shockValue`
- `shiftRule`
  - 优先取定义上的 `shockRule`，为空时按 `ABSOLUTE`
- `changedValue`
  - 按 `shiftRule` 作用于 `originalValue`
- `modifier`
  - 取请求用户

### 4.2 `KEY_RATE`

`KEY_RATE` 用于关键利率期限点情景。

控制点期限规则：

- 内部统一只按 `termDays` 组织关键期限点
- definition 上的 `termCode` 不参与 `KEY_RATE` 计量
- 市场点输出仍保留 market data 自身的 `termCode`
- 若控制点 `termDays<0`
  - 记录异常日志并跳过该控制点
- `termDays=0` 视为合法期限点

计量方法：

1. 控制点按关键期限组织。
2. 仅对关键期限点直接施加冲击。
3. 其余期限通过线性插值推导冲击值。
4. 对整条利率曲线生成完整情景输出。

字段赋值规则：

- `scenarioId`
  - 取控制点的 `scenarioCode`，为空时回退到 `scenarioId`
- `subScenarioId`
  - 规则为 `scenarioId-keyTermDays`
- `scenarioName / scenarioType / riskGroupId`
  - 取控制点定义
- `curveType / curveCode / termCode / termDays / dimension2`
  - 取当前市场点
- `dataDate`
  - 取当前市场日期
- `originalValue`
  - 当前市场值
- `shiftValue`
  - 关键期限点取控制点 shock，其余期限点当前实现保持为 0
- `shiftRule`
  - 关键期限点沿用控制点 `shockRule`，非关键期限点固定为 `ABSOLUTE`
- `changedValue`
  - 关键期限点按 `shiftRule` 施加，非关键期限点当前保持原值
- `modifier`
  - 取请求用户

## 五、历史类情景统一模型

历史类情景统一使用两个区间概念：

### 5.1 `calculationRange`

计量区间，表示该情景真正用于计算历史变化的日期范围。

### 5.2 `dataSearchRange`

查询区间，表示为了完成历史补点、跳期、差分、插值而需要从数据库获取的历史市场数据范围。

原则：

- `dataSearchRange` 可以大于 `calculationRange`
- `calculationRange` 表示计量语义
- `dataSearchRange` 表示取数语义

## 六、历史类情景的具体计量方法

历史类结果的统一输出规则：

- `scenarioId`
  - 取匹配定义上的 `scenarioCode`，为空时回退到 `scenarioId`
- `subScenarioId`
  - 规则为 `comparisonDate_sampleDate_index`
- `scenarioName / scenarioType / riskGroupId`
  - 取匹配定义
- `curveType / curveCode / termCode / termDays / dimension2`
  - 取当前市场点
- `dataDate`
  - 取当前市场日期
- `originalValue`
  - 当前市场值
- `shiftValue`
  - 取 `sampleDate` 与 `comparisonDate` 两点历史变化
- `shiftRule`
  - 取匹配定义自己的 `shockType`
- `changedValue`
  - `ABSOLUTE` 时为 `originalValue + shiftValue`
  - `RELATIVE` 时为 `originalValue * (1 + shiftValue)`
- `modifier`
  - 取请求用户

说明：

- 同一 task 内 `shockType` 不作为公共字段校验
- 历史类执行时按匹配 definition 自己的 `shockType` 生效
- 如果估值日当前市场缺少某个 `curveCode`
  - 该曲线按不生效曲线处理
  - 不参与情景生成
  - 同时输出异常日志

### 6.1 `HISTORY`

`HISTORY` 属于显式起止日 + 派生样本型。

计量方法：

1. 必须提供：
   - `startDate`
   - `endDate`
2. 从 `startDate` 出发，按工作日样本向后推进。
3. 样本推进步长由 `increaseDays` 控制。
4. 单个样本变化跨度由 `jumpDayNo` 控制。
5. 当样本日期触碰 `endDate` 时停止。

当前规则：

- 缺少 `startDate` 或 `endDate` 时，直接跳过并记录日志
- 不再退化成 `VAR`

### 6.2 `VAR`

`VAR` 属于派生区间型。

锚点：

- `valuationDate`

方向：

- 向前回溯

计量方法：

1. 从 `valuationDate` 出发，按工作日向前选择样本日期。
2. 第一个样本日期以 `valuationDate` 为锚点。
3. 相邻样本日期之间的推进间隔由 `increaseDays` 控制。
4. 单个样本变化表示：
   - `sampleDate`
   - 与 `sampleDate` 向前 `jumpDayNo` 个工作日之间的变化
5. `scenarioNo` 表示样本数量。

### 6.3 `SVAR`

`SVAR` 属于派生区间型。

锚点：

- `startDate`

方向：

- 向后推进

计量方法：

1. `startDate` 作为样本锚点。
2. 从 `startDate` 开始，向后选取 `scenarioNo` 个样本日期。
3. 样本日期之间的推进间隔由 `increaseDays` 控制。
4. 单个样本变化表示：
   - `sampleDate`
   - 与 `sampleDate` 向前 `jumpDayNo` 个工作日之间的变化
5. `scenarioNo` 由外部输入决定，表示 `SVAR` 的样本数量。

当前规则：

- 缺少 `startDate` 时直接跳过并记录日志
- `scenarioNo` 缺失或小于等于 0 时直接跳过并记录日志
- 不再退化成 `VAR`

### 6.4 `BACKTEST`

`BACKTEST` 属于派生区间型。

锚点：

- `valuationDate`

方向：

- 向后推进

计量方法：

1. `BACKTEST` 固定：
   - `jumpDayNo = 1`
   - `increaseDays = 1`
2. 以 `valuationDate` 为锚点向后选择样本日期。
3. 单个样本变化表示：
   - `sampleDate`
   - 与 `sampleDate` 向前 1 个工作日之间的变化

说明：

- 当前 `BACKTEST` 不是固定 `+30 天`
- 而是严格按工作日推进 1 天

### 6.5 `MC`

`MC` 属于派生区间型。

锚点：

- `valuationDate`

计量方法：

1. 基于历史样本窗口统计变化序列。
2. 计算波动或标准差。
3. 用统计结果生成随机冲击。

参数语义与 `VAR` 类似：

- `jumpDayNo`
  - 单个样本变化跨度
- `increaseDays`
  - 样本点推进间隔
- `scenarioNo`
  - 样本数量

当前实现中，`MC` 也按工作日样本序列构造历史变化。

`MC` 输出字段规则：

- `scenarioId`
  - 取定义上的 `scenarioCode`，为空时回退到 `scenarioId`
- `subScenarioId`
  - 规则为 `scenarioCode_index`
  - 使用简单序号规则，不再编码日期信息
- `scenarioName / scenarioType / riskGroupId`
  - 取定义
- `curveType / curveCode / termCode / termDays / dimension2`
  - 取当前市场点
- `dataDate`
  - 取当前市场日期
- `originalValue`
  - 当前市场值
- `shiftValue`
  - 由历史样本标准差与随机矩阵共同生成
- `shiftRule`
  - 当前固定输出为 `ABSOLUTE`
- `changedValue`
  - `originalValue + shiftValue`
- `modifier`
  - 取请求用户

补充规则：

- 如果估值日当前市场缺少某个 `curveCode`
  - 该曲线按不生效曲线处理
  - 不参与 `MC` 情景生成
  - 同时输出异常日志

## 七、关键参数语义

### 7.1 `jumpDayNo`

表示单个情景变化的跨度。

在历史类情景中，它优先应理解为：

- 工作日步长

默认规则：

- 缺失时默认取 `1`
- 对全部情景类型统一生效
- 对未直接使用该字段的情景类型，不产生额外影响

而不是简单自然日偏移。

### 7.2 `increaseDays`

表示相邻样本日期之间的推进间隔。

它用于控制：

- 第 1 个样本点
- 第 2 个样本点
- 第 3 个样本点

之间如何在时间轴上展开。

默认规则：

- 缺失时默认取 `1`
- 对全部情景类型统一生效
- 对未直接使用该字段的情景类型，不产生额外影响

### 7.3 `scenarioNo`

表示当前定义对应的情景样本编号。

### 7.4 `startDate / endDate`

在当前规则中：

- `HISTORY`
  - `startDate/endDate` 用于界定向后推进的样本边界
- `SVAR`
  - `startDate` 为派生锚点
- `VAR / MC / BACKTEST`
  - 不把 `startDate/endDate` 作为主输入

### 7.5 `holidayCalendarCode`

表示所使用的节假日日历代码。

规则：

- 该字段仍保留在 `ScenarioDefinition`
- 但其值由系统默认配置回填，而不是由单条情景定义自由传入
- `calendarCode` 为空时，默认全工作日
- 节假日定义本质上记录的是休息日

## 八、历史市场查询计划

### 8.1 查询主键

当前历史市场数据查询计划按以下维度组织：

- `curveType`
- `curveCode`

即：

- 同一类风险因子、同一条曲线的数据放在一个查询计划中

### 8.2 查询计划对象

`ScenarioMarketQueryPlanner` 会根据全部 `ScenarioDefinition` 生成 `QueryPlan`。

每个 `QueryPlan` 当前包含：

- `QueryKey(curveType, curveCode)`
- `riskGroupIds`
- `ranges`

### 8.3 区间合并规则

同一个 `curveType + curveCode` 下：

- 重叠区间合并
- 相邻 10 天内的区间合并
- 超过 10 天间隔的区间保留分段

这意味着查询计划可以保留多段日期区间，而不是强制压成一个大区间。

### 8.4 查询执行

`ScenarioHistoricalMarketLoader` 会遍历 `QueryPlan.ranges`，逐段查询历史市场数据。

这样可以减少：

- 不必要的大区间扫描
- 离散历史窗口之间的大量无效数据读取

## 九、历史数据匹配与补全规则

### 9.1 匹配基准

历史数据匹配基于 `marketData`，而不是基于 `ScenarioDefinition` 本身。

匹配顺序为：

1. 先按市场点主键精确匹配：
   - `curveCode`
   - `dimension2`（若有）
   - `termCode`
2. 如果 `termCode` 精确匹配不到
   - 再根据 `termCode` 反推 `termDays`
   - 按 `termDays` 在同一曲线维度内做插值

### 9.2 `dimension2` 规则

- `dimension2` 只作为分组键和匹配键
- 不允许跨 `dimension2` 做插值
- 如果存在 `dimension2`
  - 仅在同一个 `dimension2` 维度下按 `termDays` 做补全

### 9.3 整天缺失补全规则

如果某个查询日期整天缺失：

1. 优先使用最近的前一个有效日整天补齐
2. 如果前一个有效日不存在
   - 再使用最近的后一个有效日整天补齐

这里“整天缺失”指该日期下整列市场点都不存在有效历史值。

### 9.4 局部缺失补全规则

对非整天缺失的局部缺口：

1. 先在同一曲线分组内按期限维度补全
2. 期限维度补全时仅在同一 `curveCode` / 同一 `dimension2` 下进行
3. 边界外点不做外推，直接使用最近有效点拉平
4. 若仍有时间维度上的局部空洞
   - 再做时间维度插值

补充说明：

- 在期限维度上：
  - 当目标点落在已有有效期限范围之外时
  - 直接使用最近的有效期限点拉平
- 在时间维度上：
  - 当目标点落在已有有效日期范围之外时
  - 直接使用最近的有效日期点拉平
- 只有位于有效范围内部的缺口，才进入插值逻辑

### 9.5 当前值兜底

当前规则下，不再默认使用估值日当前市场值对历史空洞做最终兜底补值。

## 十、当前实现映射

### 10.1 区间规则

- `E:\zcyh_mr\engine\mr-core\src\main\java\com\zcyh\mr\scenario\ScenarioRangeResolver.java`

职责：

- 统一定义历史类情景的区间语义

### 10.2 查询计划

- `E:\zcyh_mr\engine\mr-app\src\main\java\com\zcyh\mr\springboot\scenario\ScenarioMarketQueryPlanner.java`

职责：

- 按 `curveType + curveCode` 组织历史查询计划
- 管理区间合并与分段

### 10.3 历史市场查询

- `E:\zcyh_mr\engine\mr-app\src\main\java\com\zcyh\mr\springboot\scenario\ScenarioHistoricalMarketLoader.java`

职责：

- 执行历史市场数据查询计划
- 将查询结果装配为 `ScenarioMarketSeries`

### 10.4 历史类情景计量

- `E:\zcyh_mr\engine\mr-core\src\main\java\com\zcyh\mr\scenario\strategy\HistoricalScenarioStrategy.java`
- `E:\zcyh_mr\engine\mr-core\src\main\java\com\zcyh\mr\scenario\strategy\McScenarioStrategy.java`

职责：

- 使用统一的区间规则
- 基于历史市场数据完成具体情景生成
