# Pricing 测试方案

## 1. 目的

本文档用于整理当前 `pricing / FRTB` 回归测试的标准执行方式，覆盖以下内容：

- 测试数据如何获取
- 临时回归脚本如何编译和执行
- 结果文件输出到哪里
- 如何核对回归结果是否正确

本文档只描述当前项目已经实际使用过的测试方法，不描述未来规划方案。

## 2. 适用范围

当前文档适用于以下两类测试：

- 基于 H2 输入表的产品定价与 FRTB 敏感性回归
- 基于 `engine/test/TmpRun*.java` 临时回归程序的批量验证

不适用于：

- JUnit 单元测试
- 外部文件样例驱动测试
- 生产环境联调

## 3. 数据来源

### 3.1 默认测试输入

默认优先使用 H2 数据库中的输入数据：

- 数据库文件：
  - `E:\zcyh_mr\H2db\mr_input_store`
- JDBC URL：
  - `jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE`

### 3.2 交易数据来源

临时回归程序统一从表 `MR_TRADE_INPUT` 读取交易 JSON：

- 字段：
  - `trade_content_text`
- 常用过滤条件：
  - `data_date='2025-12-31'`
  - `product_type in (...)`

典型 SQL 形态：

```sql
select trade_content_text
from MR_TRADE_INPUT
where data_date='2025-12-31'
  and product_type in ('BOND','CAPFLOOR')
order by id
```

### 3.3 市场数据来源

临时回归程序统一从表 `MR_MARKET_CURVE_INPUT` 读取市场数据 JSON：

- 字段：
  - `curve_content_text`
- 常用过滤条件：
  - `data_date='2025-12-31'`

典型 SQL 形态：

```sql
select curve_content_text
from MR_MARKET_CURVE_INPUT
where data_date='2025-12-31'
order by market_data_type, curve_id
```

### 3.4 输入装载方式

各个 `TmpRun*.java` 都遵循同一模式：

1. 从 H2 读取 `trade_data`
2. 从 H2 读取 `market_data`
3. 组装输入 JSON：
   - `oper_code = FRTB`
   - `data_date = 20251231`
4. 调用：
   - `new Calc(input.toJSONString()).run()`

## 4. 脚本位置

临时回归程序统一放在：

- `E:\zcyh_mr\engine\test`

当前已实际使用的回归程序包括：

- `E:\zcyh_mr\engine\test\TmpRunPricingFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunFxVanillaFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunFxBarDigFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunFxRangeStepFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunFxSharkSpreadWeddingFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunEqRangeStepFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunEqCommBarDigFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunEqCommRemainFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunCommCoreFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunAutoCallFromH2.java`
- `E:\zcyh_mr\engine\test\TmpRunCdsFromH2.java`

补充：

- `E:\zcyh_mr\engine\test\TmpRunPricingFromH2AutoServer.java`
  - 主要用于直接读取 H2 数据，不作为当前主回归入口

## 5. 编译方法

### 5.1 Maven 编译

先做主工程编译：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
mvn -f E:\zcyh_mr\engine\pom.xml -pl mr-app -am -DskipTests compile
```

预期结果：

- `BUILD SUCCESS`

### 5.2 临时回归程序编译

临时程序统一使用 `mr_cp.txt` 中的 classpath：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$cp = (Get-Content -Path 'E:\zcyh_mr\engine\test\mr_cp.txt' -Raw).Trim()
$fullCp = 'E:\zcyh_mr\engine\test;E:\zcyh_mr\engine\mr-core\target\classes;E:\zcyh_mr\engine\mr-app\target\classes;' + $cp
javac -encoding UTF-8 -cp $fullCp -d E:\zcyh_mr\engine\test E:\zcyh_mr\engine\test\TmpRunPricingFromH2.java
```

如果一次要编译多个 runner，可以把多个 `TmpRun*.java` 放到同一条 `javac` 命令后面。

## 6. 执行方法

### 6.1 利率主回归

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$cp = (Get-Content -Path 'E:\zcyh_mr\engine\test\mr_cp.txt' -Raw).Trim()
$fullCp = 'E:\zcyh_mr\engine\test;E:\zcyh_mr\engine\mr-core\target\classes;E:\zcyh_mr\engine\mr-app\target\classes;' + $cp
java -cp $fullCp TmpRunPricingFromH2
```

覆盖产品：

- `BOND`
- `BOND_FUTURE`
- `CAPFLOOR`
- `IRSCCS`
- `STD_IRS`
- `SWAPTION`
- `IR_RANGE_ACCURE`
- `IR_STEP_UP`

### 6.2 外汇产品回归

香草：

```powershell
java -cp $fullCp TmpRunFxVanillaFromH2
```

Barrier / Digital：

```powershell
java -cp $fullCp TmpRunFxBarDigFromH2
```

Range / Step：

```powershell
java -cp $fullCp TmpRunFxRangeStepFromH2
```

Shark / Spread / Wedding：

```powershell
java -cp $fullCp TmpRunFxSharkSpreadWeddingFromH2
```

### 6.3 指数与商品回归

指数 Range / Step：

```powershell
java -cp $fullCp TmpRunEqRangeStepFromH2
```

指数/商品 Barrier / Digital：

```powershell
java -cp $fullCp TmpRunEqCommBarDigFromH2
```

指数/商品其余结构产品：

```powershell
java -cp $fullCp TmpRunEqCommRemainFromH2
```

商品核心产品：

```powershell
java -cp $fullCp TmpRunCommCoreFromH2
```

### 6.4 AutoCall 与 CDS

AutoCall：

```powershell
java -cp $fullCp TmpRunAutoCallFromH2
```

CDS：

```powershell
java -cp $fullCp TmpRunCdsFromH2
```

## 7. 结果输出

各个 runner 会把结果写入 `E:\zcyh_mr\engine\test`，命名规则统一为：

- `*_result_current.json`

当前主要结果文件包括：

- `E:\zcyh_mr\engine\test\ir_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\fx_vanilla_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\fx_bar_dig_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\fx_range_step_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\fx_shark_spread_wedding_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\eq_range_step_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\eq_comm_bar_dig_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\eq_comm_remaining_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\comm_core_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\autocall_frtb_result_current.json`
- `E:\zcyh_mr\engine\test\cds_frtb_result_current.json`

覆盖写入前，runner 会先备份旧结果：

- 输出格式：
  - `BACKUP_FILE|<绝对路径>`

## 8. 控制台输出格式

runner 控制台输出统一包括以下几类行：

- 单笔结果：
  - `TRADE_RESULT|<instrumentId>|<product>|STATUS=<status>|FRTB_COUNT=<n>`
- 单笔错误：
  - `ERROR_TRADE|<instrumentId>|<errors>`
- 总数：
  - `INPUT_TRADE_COUNT|<n>`
  - `OUTPUT_TRADE_COUNT|<n>`
  - `SUCCESS|<n>`
  - `ERROR|<n>`
- 产品汇总：
  - `SUMMARY|<product>|INPUT=<n>|OUTPUT=<n>|FRTB=<n>`
- 结果文件：
  - `RESULT_FILE|<绝对路径>`

## 9. 结果核对方法

### 9.1 第一层核对：执行是否成功

先看：

- `SUCCESS`
- `ERROR`

最低要求：

- `ERROR=0`

如果有错误交易，先看控制台中的：

- `ERROR_TRADE|...`

### 9.2 第二层核对：输入输出条数是否一致

重点看：

- `INPUT_TRADE_COUNT`
- `OUTPUT_TRADE_COUNT`

最低要求：

- 两者应一致

如果不一致，通常说明：

- 有交易未返回
- 有交易在 `Calc` 中被过滤
- 输入 SQL 范围与回归结果不一致

### 9.3 第三层核对：产品汇总是否符合预期

看每个产品的：

- `SUMMARY|PRODUCT|INPUT=...|OUTPUT=...|FRTB=...`

核对重点：

1. `INPUT` 与本次脚本应覆盖的产品数一致
2. `OUTPUT` 不应少于 `INPUT`
3. `FRTB` 不应无故从非零变成零

### 9.4 第四层核对：抽查单笔 FRTB 条数

先看：

- `TRADE_RESULT|...|FRTB_COUNT=<n>`

重点抽查：

- 本轮刚修改过的产品
- 历史上容易因到期日或字段缺失变成 `0` 的样例

### 9.5 第五层核对：抽查结果 JSON

需要看具体敏感性内容时，可直接读取结果文件：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$json = Get-Content 'E:\zcyh_mr\engine\test\ir_frtb_result_current.json' -Raw -Encoding UTF8 | ConvertFrom-Json
```

典型抽查内容：

- `FRTB_SENSITIVITY`
- `RISK_FACTOR_CLASS`
- `RISK_FACTOR_ID`
- `RISK_FACTOR_VERTEX_1`
- `RISK_FACTOR_VERTEX_2`
- `BUCKET`
- `SENSITIVITY_VALUE`

### 9.6 对比历史结果

runner 会先生成：

- `*_result_backup_yyyyMMdd_HHmmss.json`

可以用 PowerShell 做简单对比：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Get-Item E:\zcyh_mr\engine\test\ir_frtb_result_backup_*.json | Sort-Object LastWriteTime -Descending | Select-Object -First 3 FullName,LastWriteTime
```

如果要比较两次结果差异，建议重点看：

- `SUCCESS/ERROR`
- `SUMMARY`
- 指定交易的 `FRTB_SENSITIVITY` 条数

## 10. 已知注意事项

### 10.1 H2 数据是当前默认事实来源

当前多数回归并不依赖 `src/main/resources/data` 中的 JSON 样例，而是直接读取：

- `MR_TRADE_INPUT`
- `MR_MARKET_CURVE_INPUT`

### 10.2 H2 本地样例可能已经被补充或修正

历史回归过程中，曾对本地 H2 做过以下类型的数据修正：

- 顺延到期日
- 补齐缺失字段
- 新增 AutoCall 样例
- 补齐 USD fixing / vol 样例

这些调整保留在本地 H2 中，不在 Git 中。

### 10.3 runner 是临时程序，不是生产入口

`TmpRun*.java` 的用途是：

- 快速批量回归
- 验证某一轮修改是否引入回归

它们不是长期生产接口，不应替代正式应用入口。

### 10.4 文档和测试命令应跟随当前 runner 实际实现更新

如果后续：

- 新增 runner
- 变更 H2 URL
- 变更 `product_type` 过滤范围
- 变更结果文件路径

则本文档也应同步更新。

## 11. 推荐执行顺序

日常回归建议按以下顺序执行：

1. 先编译主工程
2. 再编译本轮需要的 `TmpRun*.java`
3. 优先跑本轮改动产品对应的 runner
4. 若改动涉及公共 builder / MarketData / VolUtil，再补跑相关产品族全量 runner
5. 最后抽查 `*_result_current.json`

## 12. 当前建议的最小回归组合

如果改动只在利率主线：

- `TmpRunPricingFromH2`

如果改动在 FX 公共 builder：

- `TmpRunFxVanillaFromH2`
- `TmpRunFxBarDigFromH2`
- `TmpRunFxRangeStepFromH2`
- `TmpRunFxSharkSpreadWeddingFromH2`

如果改动在 EQ / CMTY 公共 builder：

- `TmpRunEqRangeStepFromH2`
- `TmpRunEqCommBarDigFromH2`
- `TmpRunEqCommRemainFromH2`
- `TmpRunCommCoreFromH2`
- `TmpRunAutoCallFromH2`

如果改动在 CSR / CDS：

- `TmpRunCdsFromH2`
