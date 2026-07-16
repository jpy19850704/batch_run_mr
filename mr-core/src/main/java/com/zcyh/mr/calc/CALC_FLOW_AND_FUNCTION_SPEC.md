# Calc 流程与功能说明（重构基线）

## 1. 文档目标

本文档描述 `com.zcyh.mr.calc.Calc` 当前完整能力与执行流程，作为后续“全面整合改造”的基线。

目标是先固化现状，再做结构性重构，避免持续增量补丁式修改。

## 2. 覆盖范围

本说明覆盖：

- `src/main/java/com/zcyh/mr/calc/Calc.java`
- `src/main/java/com/zcyh/mr/calc/OperModeControl.java`
- `src/main/java/com/zcyh/mr/calc/result/CalcResultProcessService.java`（结果处理工具类）
- `src/main/java/com/zcyh/mr/calc/scenario/CalcScenarioInputResolver.java`（计量情景输入解析与分发）
- `src/main/java/com/zcyh/mr/calc/scenario/ScenarioProcessConstants.java`（场景处理口径常量）
- `src/main/java/com/zcyh/mr/loader/Loader.java`
- `mr-app/.../com/zcyh/mr/springboot/engine/MrCalcEngineAdapter.java`（输入适配与批量封装）

不展开：

- `product/**` 下各产品具体定价公式
- `frtbsa/**` 下 SBA/DRC 内部计算细节

## 3. 运行入口

### 3.1 直接入口

- `new Calc(json).run()`

### 3.2 外层服务入口

- `MrCalcEngineAdapter.calculate(inputJson)`
- 适配器负责：
  - 加载四类情景引用列表对应的情景文件，写入 `CalcScenarioInputCache` 后再调用 `Calc`
  - `batch_tasks` 已不再作为 calc 入口支持

## 4. 输入契约（当前）

`Calc/Loader` 消费的主字段：

- `calc_mode`
- `data_date`
- `trade_data`
- `market_data`
- `other_data`（可选）
- `scenario_data`（可选，按普通情景处理）

适配器扩展字段：

- `regular_scenario_ref_list`
- `var_scenario_ref_list`
- `ima_modellable_scenario_ref_list`
- `ima_nmrf_scenario_ref_list`

## 5. calc_mode 语义与控制

模式由 `OperModeControl`（ThreadLocal）统一控制：

- `PRICING`：估值；存在 `scenario_data` 或情景引用列表时生成 `scenario_result`
- `CURVE_GENERATION`：曲线生成

关键点：

- `Calc` 下发到产品计算器的执行码会被统一归一成 `PRICING`。
- 是否输出 FRTB 字段由 `frtb_disable` 控制；是否走情景流程由是否存在情景数据决定。

## 6. 主流程

## 6.1 构造阶段（`Calc(String jsonData)`）

由 `Loader` 解析并注入：

- trades
- marketData
- dataDate
- rawCalcMode
- 归一后的 calcMode（给下游执行）
- calendar
- otherData
- scenarioDataList
- validationErrors

## 6.2 运行阶段（`Calc.run()`）

1. `OperModeControl.init(rawCalcMode)` + `FrtbCalcControl.init(frtbDisabled)` 初始化模式上下文。
2. 执行 `applyCurveGeneration()` 处理曲线生成。
3. 若 `OperModeControl.isCurveGenerationOnly()`：
   - 由 `CalcResultProcessService.buildCurveGenerationOnlyResult()` 构造曲线生成结果直接返回。
4. 调用 `runWithMarketData(...)` 执行基准估值，同时收集计算器实例和可场景复用的产品类型。
5. 由 `CalcResultProcessService.appendCurveGenerationOutput()` 将曲线生成结果附加到基准结果中。
6. 若无场景数据：直接返回基准结果。
7. 若场景模式：
   - 从缓存的计算器中筛选实现 `ScenarioCapable` 的实例
   - 由 `CalcResultProcessService.buildEffectiveBaseTrades()` 构建有效基准交易列表（包含错误占位）
   - 由 `CalcResultProcessService.collectUnsupportedScenarioProducts()` 识别不支持场景复算的产品并记日志
   - 构建 `RiskFactorMatcher` 索引，用于逐笔判断交易是否受场景影响
   - 遍历每个场景：
     - 解析受影响交易集合（`affectedIds`）
     - 全部不受影响时由 `CalcResultProcessService.buildZeroPnlResults()` 返回零损益
     - 否则构造场景市场并调用 `ScenarioCapable.calcScenario(scenarioMd, affectedIds)` 仅重估受影响交易
     - 由 `CalcResultProcessService.buildPnlResults()` 按 `INSTRUMENT_ID` 对齐并计算 PnL
     - 由 `CalcResultProcessService.buildScenarioItem()` 组装场景条目，写入 `SCENARIO_PROCESS_TYPE`、`SCENARIO_TAG` 和 `RESULT_KIND`
   - 将 `scenarioResults` 写入 `data.scenario_result`
8. `finally` 中 `OperModeControl.clear()` + `FrtbCalcControl.clear()` 清理上下文。

## 6.3 基准调度（`runWithMarketData`）

1. 按 `PRODUCT_CODE` 分组（`LinkedHashMap`，保持首次出现顺序）。
2. 对每个分组：
   - 从 `REGISTRY` 找工厂
   - 创建产品计算器实例
   - 缓存实例（供场景复算）
   - 调用 `calc()`（当前通过反射）
   - 合并结果到 `mergedData`
3. 将 `Loader` 产生的 `validationErrors` 合并进 `log_data`。
4. 返回 `{ "data": mergedData }`。

## 6.4 合并规则（`mergeData`）

- 对各产品结果 `data` 下的数组字段做追加合并。
- `trade_data` 特殊处理：
  - 若缺失 `PRODUCT_CODE`，补默认分组值
  - 当 FRTB 关闭时统一清理：
    - `FRTB_SENSITIVITY = []`
    - `DRC = null`

## 7. 场景流程细节

## 7.1 场景数据来源

主来源：输入 JSON 的 `scenario_data`，由 `Loader` 解析为 `scenarioDataList`。

每条场景条目包含：

- `scenarioId`
- `subScenarioId`
- `scenarioName`
- 场景市场数据（解析后 `MarketData`）
- `impactKeys`（可选）

补充来源：批次输入加载任务根据四类情景引用列表加载情景文件写入 `CalcScenarioInputCache`，`CalcScenarioInputResolver.resolveScenarioData()` 在 Calc 构造阶段从缓存中读取并按列表类型（`REGULAR` / `VAR` / `IMA_MODELLABLE` / `IMA_NMRF`）生成对应的 `ScenarioEntry`。

每条 `ScenarioEntry` 携带 `ScenarioProcessMetadata`，包含：

- `processType`：场景处理口径（取值见 `ScenarioProcessConstants`）
- `tag`：附加标签（如 `riskClass`、`lh`、`imaRiskClass`）
- `entryKey`：场景唯一标识
- `nmrfRiskFactorId`（仅 IMA_NMRF）
- `nmrfType`（仅 IMA_NMRF）

## 7.2 场景复算接口

`Calc` 内部接口：

- `String calcScenario(MarketData scenarioMd)`

当前实现该接口的计算器：

- `BondCalc`
- `BondFutureCalc`
- `CdsCalc`
- `StdIrsCalc`

未实现产品在场景阶段被跳过并记日志，不阻断全任务。

## 7.3 fast-skip 判定

- 先从基准市场建立 canonical impact key 索引
- 将场景 `impact_keys` 映射为 canonical key
- 满足以下条件时跳过场景重算：
  - 交易使用键非空
  - 场景键可完整解析
  - 两者集合不相交
- 跳过时输出零损益（`scenarioValuationCny = baseValuationCny`）

## 7.4 PnL 对齐规则

- 按 `INSTRUMENT_ID` 对齐基准与场景交易结果
- 输出字段：
  - `BASE_VALUATION_CNY`
  - `SCENARIO_VALUATION_CNY`
  - `PNL = scenario - base`

场景条目结构：

- `SCENARIO_ID`（可选）
- `SUBSCENARIO_ID`（可选）
- `SCENARIO_NAME`
- `trade_data`（PnL 列表）

## 7.5 场景输出元数据

每条场景条目由 `CalcResultProcessService.buildScenarioItem()` 构建，输出字段包括：

- `SCENARIO_ID`、`SUBSCENARIO_ID`、`SCENARIO_NAME`
- `SCENARIO_PROCESS_TYPE`：场景处理口径（`REGULAR` / `VAR` / `IMA_MODELLABLE` / `IMA_NMRF`），由 `ScenarioProcessMetadata.processType` 携带
- `SCENARIO_TAG`：附加分组标签（如 `{"riskClass": "IR", "lh": 10}`），用于下游按风险类别和流动性期限聚合
- `RESULT_KIND`：当前固定为 `"SCENARIO"`
- `trade_data`：PnL 列表

## 8. 错误与日志口径

## 8.1 Loader 校验层

- 交易与市场数据校验错误汇总到 `validationErrors`
- 非法交易可在进入产品计算前即被跳过
- 最终并入输出 `log_data`

## 8.2 调度层

每个产品分组独立容错：

- 不支持 `PRODUCT_CODE`：记录日志并继续
- 计算器初始化失败：按交易记录日志并继续
- `calc()` 失败：按交易记录日志并继续

## 8.3 场景层

- 不支持 `ScenarioCapable` 的产品：仅记录跳过日志，场景流程继续

## 9. 当前注册产品清单

`Calc.REGISTRY` 当前注册产品数：48。

- COMMFWD, COMMSWAP, BOND, WILLOW_BOND, FXFWD, FXSWAP, COMMOPT, IRSCCS, CAPFLOOR, FXOPT, FX_ASIAN, EQ_ASIAN, COMM_ASIAN, AUTO_CALL, COMPOSITE, FX_SPREADOPT, EQ_SPREADOPT, COMM_SPREADOPT, IR_SPREADOPT, IR_BARRIER, EQ_BARRIER, FX_BARRIER, COMM_BARRIER, IR_DIGITAL, EQ_DIGITAL, FX_DIGITAL, COMM_DIGITAL, FX_WEDDING_CAKE, EQ_WEDDING_CAKE, COMM_WEDDING_CAKE, IR_WEDDING_CAKE, EQ_SHARKFIN, COMM_SHARKFIN, IR_SHARKFIN, SWAPTION, BOND_FUTURE, CDS, TRS, IR_RANGE_ACCURE, IR_STEP_UP, EQ_RANGE_ACCURE, EQ_STEP_UP, COMM_RANGE_ACCURE, COMM_STEP_UP, FX_RANGE_ACCURE, FX_STEP_UP, STD_IRS

## 10. 当前结构性问题（重构驱动）

1. `*Calc` 包装层重复模板代码较多：
   - `calc/run/calcTrade` 结构重复
   - 结果封装与日志处理重复
2. 分发层仍使用反射调用 `calc()`（已通过 `AbstractCalc` 部分缓解，但未完全消除）。
3. 各产品异常输出格式不完全统一。
4. 场景能力仅部分产品实现，行为存在分层差异。
5. 个别产品预处理逻辑（如 `UNDERLYING_DATA` 重建）与通用流程耦合。

## 11. 全面整合改造建议（非补丁式）

目标原则：

1. 保留一个总调度器（`Calc`）
2. 保留多产品处理器（按产品差异实现）
3. 抽离公共流程，收敛重复模板
4. 移除反射，改为强类型接口调用

建议分阶段推进：

### 阶段 A：执行接口收敛

- 引入统一接口（如 `ProductRunner`）：
  - `runBase()`
  - 可选 `runScenario(MarketData)`
- 替代当前反射调用路径

### 阶段 B：公共基类抽象

- 引入 `AbstractProductCalc` 承载共性逻辑：
  - 遍历交易
  - 异常捕获
  - `trade_data/log_data` 封装
- 各产品仅保留差异钩子

### 阶段 C：场景能力统一

- 明确统一策略：
  - 要么所有注册产品必须支持场景
  - 要么不支持产品统一输出确定性占位结果

### 阶段 D：预处理解耦

- 将 `UNDERLYING_DATA` 等特殊预处理下沉到独立 preprocessor

### 阶段 E：回归基线

- 建立 PRICING/FRTB/SCENARIO 金标样例集
- 分批迁移产品并做严格回归

## 12. 重构验收标准

- 分发路径不再使用反射
- `*Calc` 包装层重复代码显著减少
- 全产品日志与错误口径统一
- 场景行为可预期、可文档化
- 兼容现有任务 JSON（输入协议不破坏）
