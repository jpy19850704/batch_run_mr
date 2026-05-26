# Scenario 模块架构文档

## 一、当前定位

`scenario` 模块现在是 Spring Boot 主应用中的一个业务引擎，而不是独立运行的旧子系统。

当前只保留两种运行模式：

1. `processor` 模式
   - 不依赖数据库
   - 直接接收 `data_list` 和 `control_list`
   - 适合前端/接口直接传市场数据进行冲击计算

2. `service` 模式
   - 依赖独立的 Scenario 业务数据源
   - 通过 `ScenarioMapper` 查询情景配置、历史市场数据、节假日和结果落库表
   - 适合批量情景生成和正式业务流程

旧架构中的以下内容已经退出运行链路：

- `DaoUtils`
- `mybatis-config.xml`
- 旧 outer 目录下的独立启动入口与部署方式

## 二、运行链路

### 2.1 Spring Boot 总入口

```
HTTP 请求
  → MrEngineController
  → EngineOrchestratorService
  → EngineRegistry
  → ScenarioEngineAdapter
  → processor / service 分流
```

### 2.2 processor 模式

```
请求 payload
  → ScenarioEngineAdapter.runProcessorMode()
  → RiskFactorProcessor.getProcessor(risk_factor_type)
  → processor.process(data_list, control_list, user)
  → 返回带 CHANGED_RATE 的结果集
```

特点：

- 无数据库依赖
- 无 Mapper 依赖
- 只做风险因子冲击处理
- 当前是 Scenario 最轻量、最稳定的运行路径

### 2.3 service 模式

```
请求 payload
  → ScenarioEngineAdapter.runServiceMode()
  → ScenarioService.custScenario()
  → ScenarioMapper.selectScenario()
  → 按 SCENARIO_TYPE 选择策略
  → 策略内部通过 DataExtractor / ScenarioMapper 取数
  → RiskFactorProcessor 处理冲击
  → insertFlag=0 时入库，insertFlag=1 时直接返回
```

特点：

- 依赖独立业务库
- 依赖 Spring 管理的 `ScenarioMapper`
- 依赖 Spring 管理的 `scenarioExecutor`
- 如果未启用 Scenario 业务数据源，会直接返回显式错误

## 三、Spring 装配结构

### 3.1 关键配置类

#### `EngineRegistryConfig`

职责：

- 注册 `mr`、`scenario`、`frtb_sba`、`frtb_drc` 适配器 Bean
- 统一构建 `EngineRegistry`
- 让 `EngineOrchestratorService` 通过注入方式获取引擎注册表

#### `ScenarioDataSourceConfig`

职责：

- 该类已收敛为引擎输入数据源统一配置（不再单独维护 scenario 专用数据源）
- `ScenarioMapper` 通过统一 `engineDbJdbcTemplate` 读取视图数据
- `scenarioExecutor` 和 `ScenarioService` 仍由 Spring 管理

### 3.2 Scenario 开关与配置

当前通过以下配置控制 service 模式：

- `mr.scenario.service.enabled`
- `mr.scenario.executor.core-size`
- `mr.scenario.executor.max-size`
- `mr.scenario.executor.queue-capacity`

默认情况下：

- `mr.scenario.service.enabled=true`

这意味着：

- `processor` 模式可以直接使用
- `service` 模式在统一输入库可用时直接运行

## 四、核心类职责

### 4.1 `ScenarioEngineAdapter`

职责：

- 接收统一 JSON 入参
- 识别 `mode=processor` 或 `mode=service`
- 对 `processor` 模式直接调用风险因子处理器
- 对 `service` 模式委托给 `ScenarioService`
- 在 `ScenarioService` 不可用时返回明确错误

### 4.2 `ScenarioService`

职责：

- 查询情景类型
- 根据类型分发到不同策略实现
- 使用注入的 `ExecutorService` 并发执行多个 `SCENARIO_ID`
- 汇总结果并按 `insertFlag` 决定返回还是入库

当前构造方式：

```java
new ScenarioService(scenarioMapper, scenarioExecutor)
```

这意味着它已经不再依赖任何静态 DAO 工具。

### 4.3 `ScenarioMapper`

职责：

- 查询情景配置
- 查询市场数据与历史数据
- 查询节假日
- 删除旧结果
- 批量插入新结果

`ScenarioMapper.xml` 仍然保留并复用，当前它是业务 SQL 资产，不属于旧架构残留。

## 五、策略层

### 5.1 策略实现

| 情景类型 | 实现类 | 说明 |
|---|---|---|
| `CUSTOM` | `CustomScenarioStrategy` | 自定义冲击规则 |
| `HISTORY` | `HistoricalScenarioStrategy` | 历史情景 |
| `VAR` | `HistoricalScenarioStrategy` | VaR 历史模拟 |
| `BACKTEST` | `HistoricalScenarioStrategy` | 回测历史情景 |
| `SVAR` | `HistoricalScenarioStrategy` | 压力 VaR |
| `MC` | `McScenarioStrategy` | 蒙特卡洛情景 |
| `KEY_RATE` | `CustomScenarioStrategy` | 关键利率情景 |

### 5.2 并行方式

`ScenarioService` 会按 `SCENARIO_ID` 并发提交任务：

- 每个情景 ID 是一个独立任务
- 失败时返回空结果，不中断其他情景
- 并发线程池由 Spring 统一托管

## 六、风险因子处理器层

### 6.1 风险因子处理器工厂

`RiskFactorProcessor` 同时承担：

- 处理器注册中心
- 风险因子工厂
- 公共处理模板

### 6.2 支持的风险因子类型

| 风险因子类型 | 是否需要期限插值 | 说明 |
|---|---|---|
| `IR_SPOT` | 是 | 利率曲线 |
| `FX_SPOT` | 否 | 汇率现货 |
| `COMM_SPOT` | 是 | 商品现货曲线 |
| `EQ_SPOT` | 否 | 权益现货 |
| `IR_VOL` | 是 | 利率波动率 |
| `FX_VOL` | 是 | 汇率波动率 |
| `COMM_VOL` | 是 | 商品波动率 |
| `EQ_VOL` | 是 | 权益波动率 |

### 6.3 公共处理流程

```
preProcess()
  → 筛选本类型 control 数据
  → 按是否需要期限插值选择处理路径
  → applyShiftToDatum()
  → postProcess()
```

其中：

- 需要插值的类型走 `LinearInterpolator`
- 不需要插值的类型直接应用控制点冲击

## 七、数据库职责划分

### 7.1 任务库与业务库分离

当前架构已经明确分成两套数据源：

1. 默认任务库
   - 由 `spring.datasource.*` 管理
   - 主要服务异步任务、批量任务、状态跟踪

2. Scenario 业务库
   - 由 `mr.scenario.datasource.*` 管理
   - 专门服务 `ScenarioMapper`

这样可以避免：

- 把任务状态表和业务市场数据表混在一起
- 旧项目里的数据库连接写死问题
- 不同环境迁移时配置互相污染

## 八、当前约束

1. `processor` 模式已经完全脱离数据库，可直接作为轻量引擎使用。
2. `service` 模式只有在显式启用 Scenario 业务数据源时才可用。
3. 旧 `OuterService` 独立启动方式已经删除，当前唯一入口是 Spring Boot。
4. 旧 `DaoUtils + mybatis-config.xml` 已删除，后续不再允许恢复这条链路。

## 九、后续演进建议

1. 继续补充 `service` 模式的业务库集成测试。
2. 将 `ScenarioMapper.xml` 中真正不再使用的 SQL 进一步清理。
3. 视需要补充 Scenario 结果表、配置表的数据库规范文档。
4. 如果后续 `frtb` 和 `mr pricing` 也全部收口到同一套装配方式，可复用当前 `EngineRegistryConfig` 的模式。
