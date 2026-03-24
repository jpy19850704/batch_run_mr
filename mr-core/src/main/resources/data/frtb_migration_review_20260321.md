# FRTB 敏感度模块迁移收口说明（2026-03-21）

## 1. 本轮目标

本轮目标不是继续扩展更多产品，而是把 FRTB 敏感度计量模块收口到“长期稳定版本”：

1. 清理仍在生产路径上的旧版手工 FRTB 入口。
2. 删除已经被公共模板替代、但仍留在子类中的影子实现。
3. 删除未实际落地的空契约。
4. 对最终仍保留的兼容边界做明确标注。

## 1.1 交易自身 Greek 与 FRTB 敏感度的最终边界

在长期稳定版本中，模块内明确保留以下边界：

1. 交易自身 greek 与 FRTB 敏感度属于两套不同用途的计量结果。
2. 该边界适用于全部交易类型、全部标的类型、全部风险类别，不限于单个产品。
3. 交易自身 `delta / gamma / vega / pv01` 可继续服务于：
   - 产品定价
   - 对冲分析
   - 报表展示
   - 本地风险解释
4. FRTB 敏感度单独服务于：
   - 监管敏感度输出
   - SBA 聚合
   - 资本计量
5. 因此，交易自身 greek 与 FRTB 敏感度：
   - 不要求数值一致
   - 不要求 shock 方式一致
   - 不要求 vertex 结构一致
6. 判断 FRTB 是否正确时，以 `frtbsense_rule.md` 规定的监管字段与监管 shock 规则为准，不以交易自身 greek 是否相同作为判定标准。
7. `Swaption` 只是这一模块级原则的一个具体例子：
   - 交易本身 `vega` 可保留本地整面 IR vol shift 口径；
   - FRTB GIRR Vega 按 FRTB tenor shock 与第二维期限规则单独计量；
   - 两者故意允许分离，不视为缺陷。

## 1.2 敏感性发生规则

当前生产实现采用以下统一规则：

1. 线性产品仅产生 `Delta`，不产生 `Vega / Curvature`。
2. 含权产品仅对主标的风险类别产生 `Delta / Vega / Curvature`。
3. 含权产品的其他真实依赖风险类别仅产生 `Delta`。
4. `FX Delta` 与 `GIRR Delta` 可适用于全部产品类型；只要交易真实依赖汇率或利率曲线，即可产生对应 `Delta`。

对应示例：

1. `FXFWD / FXSWAP`
   - 仅应存在：`FX Delta`、`GIRR Delta`
   - 不应存在：`FX Vega / FX Curvature / GIRR Vega / GIRR Curvature`

2. `FX_AUTO_CALL`、`FX_BARRIER`、`FX_DIGITAL`、`FX_SHARKFIN`、`FX_SPREADOPT`、`FX_WEDDING_CAKE`
   - `FX` 为主标的风险类别，产生：`Delta / Vega / Curvature`
   - `GIRR` 如交易真实依赖利率曲线，仅产生：`Delta`

3. `IR_BARRIER`、`IR_DIGITAL`、`IR_WEDDING_CAKE`、`IR_SHARKFIN`、`IR_SPREADOPT`、`IR_RANGE_ACCURE`、`IR_STEP_UP`
   - `GIRR` 为主标的风险类别，产生：`Delta / Vega / Curvature`
   - `FX` 如交易存在汇率换算依赖，仅产生：`Delta`

4. `EQ / CMTY` 含权产品同理：
   - 主标的风险类别产生：`Delta / Vega / Curvature`
   - 其他风险类别仅产生：`Delta`

## 2. 本轮修改轮次

### 第一轮：清理活跃旧生产路径

修改文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\structure\RangeAccureOptBase.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\structure\StepUpOptBase.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\ir\StdIrs.java

修改内容：

1. `RangeAccureOptBase.getSensListFX()` 改为直接调用 `FrtbSensitivityBuilder.buildFxSensitivities(...)`。
2. `StepUpOptBase.getSensListFXCommon()` 改为直接调用 `FrtbSensitivityBuilder.buildFxSensitivities(...)`。
3. 两个结构基类中的 FX Curvature 双外币缩放逻辑，统一改为 `CNY/CNH` 视为本币。
4. `StdIrs.calcFrtbSens(...)` 改为直接调用 `FrtbSensitivityBuilder.buildGirrSensitivities(...)`，不再手工组装 GIRR Delta。

### 第二轮：删除影子旧实现

修改文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\fx\FxBarOpt.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\fx\FxDigOpt.java

修改内容：

1. 删除已经被基类公共模板替代的整段手工 FRTB 逻辑。
2. 删除不再被生产路径使用的旧辅助方法。
3. 类注释改为明确说明：FRTB 统一通过基类公共模板输出。

### 第三轮：删除未落地空契约

删除文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\FrtbDependencyProvider.java

删除原因：

1. 全库没有任何产品实现该接口。
2. 该接口只保留默认空方法，属于名义架构，不是实际架构。
3. 继续保留会误导后续维护者判断当前模块成熟度。

### 补充轮：清理 FxVanilla 活跃旧路径

修改文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\fx\FxVanillaOpt.java

修改内容：

1. `calcFrtbSens()` 中的 GIRR 路径改为通过 `FrtbSensitivityBuilder.buildGirrSensitivities(...)` 输出。
2. FX Delta dependency 的币种过滤继续下沉到 `FxSensitivityBuilder`，产品层只传币种。
3. FX Curvature 本币判断统一为 `CNY/CNH`。
4. 删除已不再使用的旧辅助方法。

### 补充轮：统一 FX/EQ/CMTY 的 Vega 顶点拆分

修改文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\AbstractSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\GirrSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\FxSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\EqSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\CmtySensitivityBuilder.java

修改内容：

1. 在 `AbstractSensitivityBuilder` 中新增统一的期限点线性拆分方法。
2. `GirrSensitivityBuilder` 的 Vega 拆分改为复用公共方法。
3. `FxSensitivityBuilder`、`EqSensitivityBuilder`、`CmtySensitivityBuilder` 的 Vega 拆分，不再调用 `FrtbSeneUtils.frtbSens2Vertex(..., "VEGA")`，统一改为复用公共方法。
4. FX/EQ/CMTY 的 Vega 期限集合保持现有监管口径：`6M/1Y/3Y/5Y/10Y`，统一的是实现方式，不是 tenor 集合本身。

### 最终轮：统一所有风险大类的 Vega tenor shock 实现

修改文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\VolUtil.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\IrVol.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\FxVol.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\EqVol.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\CommVol.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\MarketData.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\AbstractSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\GirrSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\FxSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\EqSensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\CmtySensitivityBuilder.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\frtbsa\sba\core\FrtbAggregator.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\frtbsa\sba\core\girr\GirrModule.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\frtbsa\sba\core\girr\GirrVega.java

修改内容：

1. Vega 不再使用“整张波动率曲面统一 shock 后再做事后拆分”的方式。
2. `MarketData` 新增统一的 tenor shock 入口，所有 `GIRR / FX / EQ / CMTY` 都使用同一组标准期限：`6M / 1Y / 3Y / 5Y / 10Y`。
3. 基础波动率曲面继续沿用交易原有插值方式：
   - IR 基础面继续按原有方差插值。
   - FX / EQ / CMTY 基础面继续按原有曲面取值逻辑。
4. FRTB Vega 只额外叠加一层 `shockCurveData`：
   - 标准 tenor 点上定义 `shock ratio = 0.001`
   - shock ratio 只在标准期限点之间按线性传播
   - 交易真实请求期限按 tenor 线性插值得到 `shockRatio(days)`
   - 交易最终使用的波动率为：`baseVol(original interpolation) * (1 + shockRatio(days))`
5. 四个 builder 的 Vega 都改成：
   - 按 tenor 逐点生成 shock market
   - 按 tenor 逐点重估
   - 直接输出 `riskFactorVertex1 = tenor`
   - 数值统一为 `(valuation_shocked - valuation_base) / 0.001`
6. GIRR Vega 的第一维同样统一为 `6M / 1Y / 3Y / 5Y / 10Y`，不再使用旧的 10 个 tenor。
7. GIRR Vega 保留第二维 `riskFactorVertex2`：
   - 第二维原始值由交易直接传入 dependency
   - 若与标准 tenor 前后 5 天内命中，则 100% 落该点
   - 否则在线性相邻 tenor 间拆分
   - `CapFloor/Swaption` 的第二维优先使用 `FIXING_FREQ`
   - `CapFloor` 缺失时回退 `PAY_FREQ`
   - `Swaption` 缺失时回退 `UNDERLYING_FREQ`
8. 下游 `frtbsa` 的 GIRR Vega 聚合恢复为双维：
   - `vertex1` 用于第一维 tenor shock
   - `vertex2` 用于交易标的期限映射后的 tenor 相关性
9. GIRR Delta Basis 也已收口到公共 builder：
   - `IrsCcs` 不再手工 `new FrtbSenes()`；
   - 统一通过 `currency1 / curve1 / currency2 / curve2` 生成 Basis dependency；
   - builder 内部判断输出 `CCS_BASIS_<ccy>_OVER_USD/EUR`；
   - Basis 直接对目标曲线整条平移 `1bp` 后重估，属于平移 PV01，不按期限点拆分；
   - `vertex1 / vertex2` 保持空字符串；
   - GIRR bucket 在敏感度输出层统一执行 `CNH -> CNY`，不改写 `riskFactorId`。

## 3. 本轮验证命令与结果

### 3.1 第一轮编译与回归

编译命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
mvn -f E:\zcyh_mr\engine\pom.xml -pl mr-app -am -DskipTests compile
```

结果：

- BUILD SUCCESS

回归命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$cp = (Get-Content -Path 'E:\zcyh_mr\engine\test\mr_cp.txt' -Raw).Trim()
$fullCp = 'E:\zcyh_mr\engine\test;E:\zcyh_mr\engine\mr-core\target\classes;E:\zcyh_mr\engine\mr-app\target\classes;' + $cp
javac -encoding UTF-8 -cp $fullCp -d E:\zcyh_mr\engine\test E:\zcyh_mr\engine\test\TmpRunPricingFromH2.java E:\zcyh_mr\engine\test\TmpRunFxRangeStepFromH2.java E:\zcyh_mr\engine\test\TmpRunEqRangeStepFromH2.java E:\zcyh_mr\engine\test\TmpRunCommCoreFromH2.java
java -cp $fullCp TmpRunPricingFromH2
java -cp $fullCp TmpRunFxRangeStepFromH2
java -cp $fullCp TmpRunEqRangeStepFromH2
java -cp $fullCp TmpRunCommCoreFromH2
```

结果：

- TmpRunPricingFromH2：SUCCESS=35，ERROR=0
- TmpRunFxRangeStepFromH2：SUCCESS=2，ERROR=0
- TmpRunEqRangeStepFromH2：SUCCESS=2，ERROR=0
- TmpRunCommCoreFromH2：SUCCESS=6，ERROR=0

### 3.2 第二轮与第三轮编译与回归

编译命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
mvn -f E:\zcyh_mr\engine\pom.xml -pl mr-app -am -DskipTests compile
```

结果：

- BUILD SUCCESS

回归命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$cp = (Get-Content -Path 'E:\zcyh_mr\engine\test\mr_cp.txt' -Raw).Trim()
$fullCp = 'E:\zcyh_mr\engine\test;E:\zcyh_mr\engine\mr-core\target\classes;E:\zcyh_mr\engine\mr-app\target\classes;' + $cp
javac -encoding UTF-8 -cp $fullCp -d E:\zcyh_mr\engine\test E:\zcyh_mr\engine\test\TmpRunFxBarDigFromH2.java
java -cp $fullCp TmpRunFxBarDigFromH2
```

结果：

- SUCCESS=7
- ERROR=0
- FX_BARRIER=36
- FX_DIGITAL=36

### 3.3 补充轮：FxVanilla 编译与回归

编译命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
mvn -f E:\zcyh_mr\engine\pom.xml -pl mr-app -am -DskipTests compile
```

结果：

- BUILD SUCCESS

回归命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$cp = (Get-Content -Path 'E:\zcyh_mr\engine\test\mr_cp.txt' -Raw).Trim()
$fullCp = 'E:\zcyh_mr\engine\test;E:\zcyh_mr\engine\mr-core\target\classes;E:\zcyh_mr\engine\mr-app\target\classes;' + $cp
javac -encoding UTF-8 -cp $fullCp -d E:\zcyh_mr\engine\test E:\zcyh_mr\engine\test\TmpRunFxVanillaFromH2.java
java -cp $fullCp TmpRunFxVanillaFromH2
```

结果：

- SUCCESS=3
- ERROR=0
- FXOPT=27

结果文件：

- E:\zcyh_mr\engine\test\fx_vanilla_frtb_result_current.json

### 3.4 补充轮：FX/EQ/CMTY Vega 统一拆分回归

编译命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
mvn -f E:\zcyh_mr\engine\pom.xml -pl mr-app -am -DskipTests compile
```

结果：

- BUILD SUCCESS

回归命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$cp = (Get-Content -Path 'E:\zcyh_mr\engine\test\mr_cp.txt' -Raw).Trim()
$fullCp = 'E:\zcyh_mr\engine\test;E:\zcyh_mr\engine\mr-core\target\classes;E:\zcyh_mr\engine\mr-app\target\classes;' + $cp
javac -encoding UTF-8 -cp $fullCp -d E:\zcyh_mr\engine\test E:\zcyh_mr\engine\test\TmpRunFxVanillaFromH2.java E:\zcyh_mr\engine\test\TmpRunFxBarDigFromH2.java E:\zcyh_mr\engine\test\TmpRunFxRangeStepFromH2.java E:\zcyh_mr\engine\test\TmpRunFxSharkSpreadWeddingFromH2.java E:\zcyh_mr\engine\test\TmpRunEqRangeStepFromH2.java E:\zcyh_mr\engine\test\TmpRunEqCommBarDigFromH2.java E:\zcyh_mr\engine\test\TmpRunEqCommRemainFromH2.java E:\zcyh_mr\engine\test\TmpRunCommCoreFromH2.java
java -cp $fullCp TmpRunFxVanillaFromH2
java -cp $fullCp TmpRunFxBarDigFromH2
java -cp $fullCp TmpRunFxRangeStepFromH2
java -cp $fullCp TmpRunFxSharkSpreadWeddingFromH2
java -cp $fullCp TmpRunEqRangeStepFromH2
java -cp $fullCp TmpRunEqCommBarDigFromH2
java -cp $fullCp TmpRunEqCommRemainFromH2
java -cp $fullCp TmpRunCommCoreFromH2
```

结果：

- TmpRunFxVanillaFromH2：SUCCESS=3，ERROR=0，FXOPT=27
- TmpRunFxBarDigFromH2：SUCCESS=7，ERROR=0，FX_BARRIER=36，FX_DIGITAL=36
- TmpRunFxRangeStepFromH2：SUCCESS=2，ERROR=0，FX_RANGE_ACCURE=22，FX_STEP_UP=14
- TmpRunFxSharkSpreadWeddingFromH2：SUCCESS=7，ERROR=0，FX_SHARKFIN=13，FX_SPREADOPT=38，FX_WEDDING_CAKE=3
- TmpRunEqRangeStepFromH2：SUCCESS=2，ERROR=0，EQ_RANGE_ACCURE=11，EQ_STEP_UP=7
- TmpRunEqCommBarDigFromH2：SUCCESS=10，ERROR=0，EQ_BARRIER=21，EQ_DIGITAL=14，COMM_BARRIER=21，COMM_DIGITAL=14
- TmpRunEqCommRemainFromH2：SUCCESS=10，ERROR=0，EQ_SHARKFIN=7，EQ_SPREADOPT=14，EQ_WEDDING_CAKE=9，COMM_SHARKFIN=28，COMM_SPREADOPT=18，COMM_WEDDING_CAKE=10
- TmpRunCommCoreFromH2：SUCCESS=6，ERROR=0，COMMFWD=4，COMMOPT=20，COMMSWAP=5，COMM_RANGE_ACCURE=13，COMM_STEP_UP=10

### 3.5 最终轮：统一 Vega tenor shock 回归

编译命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
mvn -f E:\zcyh_mr\engine\pom.xml -pl mr-app -am -DskipTests compile
```

结果：

- BUILD SUCCESS

补充说明：

1. 由于 `E:\zcyh_mr\engine\test` 的 classpath 在回归时排在最前，且该目录历史上缓存过同包名的旧 class，
   本轮还额外使用 `javac` 将变更后的公共类编译到 `E:\zcyh_mr\engine\test`，避免被旧版 class 覆盖。
2. 运行中曾暴露一个字段映射问题：
   - 统一 Vega tenor shock 初版直接把原始 `curveData` 传给 `VolUtil`
   - 但 `VolUtil` 处理的是公共 `VERTEX1 / VERTEX2` 字段
   - 原始 FX/EQ/CMTY/IR 曲面仍是 `OPTION_TERM / DELTA` 或 `OPTION_TERM / UNDERLYING_TERM`
   - 最终通过在 `MarketData` 中补齐统一字段映射修复

回归命令：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$cp = (Get-Content -Path 'E:\zcyh_mr\engine\test\mr_cp.txt' -Raw).Trim()
$fullCp = 'E:\zcyh_mr\engine\test;E:\zcyh_mr\engine\mr-core\target\classes;E:\zcyh_mr\engine\mr-app\target\classes;' + $cp
javac -encoding UTF-8 -cp $fullCp -d E:\zcyh_mr\engine\test E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\VolUtil.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\IrVol.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\FxVol.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\EqVol.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\CommVol.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\marketdata\MarketData.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\AbstractSensitivityBuilder.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\GirrSensitivityBuilder.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\FxSensitivityBuilder.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\EqSensitivityBuilder.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\CmtySensitivityBuilder.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\frtbsa\sba\core\FrtbAggregator.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\frtbsa\sba\core\girr\GirrModule.java E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\frtbsa\sba\core\girr\GirrVega.java
java -cp $fullCp TmpRunPricingFromH2
java -cp $fullCp TmpRunFxVanillaFromH2
java -cp $fullCp TmpRunFxBarDigFromH2
java -cp $fullCp TmpRunFxRangeStepFromH2
java -cp $fullCp TmpRunFxSharkSpreadWeddingFromH2
java -cp $fullCp TmpRunEqRangeStepFromH2
java -cp $fullCp TmpRunEqCommBarDigFromH2
java -cp $fullCp TmpRunEqCommRemainFromH2
java -cp $fullCp TmpRunCommCoreFromH2
java -cp $fullCp TmpRunAutoCallFromH2
java -cp $fullCp TmpRunCdsFromH2
```

结果：

- TmpRunPricingFromH2：SUCCESS=35，ERROR=0，BOND=125，BOND_FUTURE=20，CAPFLOOR=19，IRSCCS=117，IR_RANGE_ACCURE=14，IR_STEP_UP=6，STD_IRS=5，SWAPTION=16
- TmpRunFxVanillaFromH2：SUCCESS=3，ERROR=0，FXOPT=27
- TmpRunFxBarDigFromH2：SUCCESS=7，ERROR=0，FX_BARRIER=36，FX_DIGITAL=36
- TmpRunFxRangeStepFromH2：SUCCESS=2，ERROR=0，FX_RANGE_ACCURE=22，FX_STEP_UP=14
- TmpRunFxSharkSpreadWeddingFromH2：SUCCESS=7，ERROR=0，FX_SHARKFIN=13，FX_SPREADOPT=34，FX_WEDDING_CAKE=3
- TmpRunEqRangeStepFromH2：SUCCESS=2，ERROR=0，EQ_RANGE_ACCURE=11，EQ_STEP_UP=5
- TmpRunEqCommBarDigFromH2：SUCCESS=10，ERROR=0，EQ_BARRIER=21，EQ_DIGITAL=14，COMM_BARRIER=21，COMM_DIGITAL=14
- TmpRunEqCommRemainFromH2：SUCCESS=10，ERROR=0，EQ_SHARKFIN=8，EQ_SPREADOPT=16，EQ_WEDDING_CAKE=7，COMM_SHARKFIN=25，COMM_SPREADOPT=17，COMM_WEDDING_CAKE=8
- TmpRunCommCoreFromH2：SUCCESS=6，ERROR=0，COMMFWD=4，COMMOPT=18，COMMSWAP=5，COMM_RANGE_ACCURE=13，COMM_STEP_UP=8
- TmpRunAutoCallFromH2：SUCCESS=4，ERROR=0，EQ_AUTO_CALL=21，COMM_AUTO_CALL=21
- TmpRunCdsFromH2：SUCCESS=4，ERROR=0，CDS=38

## 4. 最终残留扫描结论

扫描口径：

1. 只看生产产品目录。
2. 排除 `product/test`。
3. 排除公共 builder 自身。
4. 排除 `FrtbSensitivity.java` 的旧对象转换实现。

扫描结果：

### 4.1 仍保留的手工 `FrtbSenes` 生产代码

当前主产品路径中未再保留手工 `new FrtbSenes()` 的敏感度生成代码。

说明：

1. `IrsCcs` 的 GIRR Basis 已收口到 `GirrSensitivityBuilder`。
2. 当前仍保留的 `FrtbSenes` 仅处于最终兼容输出边界，由公共 builder 与 `FrtbSensitivityBuilder` 统一直接生成。

### 4.2 已不存在的活跃手工 FRTB 入口

本轮结束后，以下旧模式已不再出现在生产产品主路径中：

1. `MarketData.getFrtbMarketDataListFX* + new FrtbSenes()`
2. `MarketData.getFrtbMarketDataListGIRRDelta(...) + new FrtbSenes()`
3. `MarketData.getFrtbMarketDataListGIRRCurvature(...) + new FrtbSenes()`
4. `MarketData.getFrtbMarketDataListCSR(...) + new FrtbSenes()`

## 5. 明确保留的兼容边界

以下内容仍然保留，但它们已经是“明确兼容边界”，不是遗留旧入口：

### 5.1 `MeasureValuation`

保留文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\FrtbSensitivityBuilder.java

保留原因：

1. 当前 builder 与产品层统一使用顶层 `MeasureValuation`。
2. `FrtbSensitivityBuilder.MeasureValuation` 嵌套兼容别名已删除。
3. 当前该模型仅承担公共最小估值快照，不再存在双轨命名。

### 5.2 `FrtbSeneUtils.frtbSens2Vertex(...)`

保留文件：

- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\FrtbSeneUtils.java
- E:\zcyh_mr\engine\src\main\java\com\zcyh\mr\product\basic\frtb\builder\CmtySensitivityBuilder.java

保留原因：

1. FX/EQ/CMTY 的 Vega 顶点拆分已经完全退出旧工具路径，改为统一 tenor shock + 直接输出。
2. 商品 Delta 也已切到按标准期限点 shock + 直接输出，不再依赖该工具做期限拆分。
3. 当前该工具仅保留为历史兼容工具，不再参与生产路径。

## 6. 本轮商品 Delta 优化

本轮新增的商品 Delta 收口如下：

1. 商品 Delta 已从“整条曲线统一 shock 后再按期限拆分”切换为“按标准期限点逐点 shock、逐点重估、直接输出对应期限”。
2. 商品 Delta 进一步统一为 ratio shock 口径：
   - 标准 tenor 点上定义 `shock ratio = 0.01`
   - 仅在被 shock tenor 的相邻两个区间内线性衰减
   - 若被 shock 的是全局最小 tenor，则小于最小 tenor 的期限直接使用 `0.01`
   - 若被 shock 的是全局最大 tenor，则大于最大 tenor 的期限直接使用 `0.01`
   - 交易最终使用价格为：`basePrice * (1 + shockRatio(days))`
   - 商品 Delta 数值统一按 `diff / 0.01`
3. 商品基础曲线继续使用原有线性插值；shock 部分通过 `CommSpotInfo.shockCurveData` 单独叠加，不改变原曲线取值逻辑。
4. 商品 Delta dependency 已增强为显式携带 `priceCurve`，builder 不再对全量商品曲线逐条重估，只处理交易真实依赖的商品价格曲线。
5. 商品 bucket 解析已从“取最后一个字符”改为“显式提取末尾整数”，可正确处理 `Bucket 10` 及更大编号。
6. 受影响的商品产品包括：
   - `CommFwd`
   - `CommSwap`
   - `CommVanillaOpt`
   - `CommRangeAccureOpt`
   - `CommStepUpOpt`
   - `CommBarOpt`
   - `CommDigOpt`
   - `CommSharkFin`
   - `CommSpreadOpt`
   - `CommWeddingCake`
   - `CommAutoCall`

## 7. 当前模块的稳定版本结论

截至本轮结束：

1. 生产产品的 FRTB 敏感度生成已统一收口到公共 builder 主线。
2. 已清掉的残留包括：
   - RangeAccure/StepUp 活跃旧 FX 入口
   - StdIrs 活跃旧 GIRR Delta 入口
   - FxBarOpt/FxDigOpt 影子旧实现
   - 未落地空契约 `FrtbDependencyProvider`
   - FxVanilla 活跃旧 GIRR 路径
   - 商品 Delta “整曲线 shock 后再拆分”的旧路径
3. 目前保留的仅是：
- builder 直接输出 `FrtbSenes`
   - 顶层 `MeasureValuation`
4. Vega 最终实现口径已经统一为：
   - 标准期限统一使用 `6M / 1Y / 3Y / 5Y / 10Y`
   - 基础曲线继续按交易原始插值方式
   - 标准 tenor 点上定义 `shock ratio = 0.001`
   - 交易真实请求期限按 tenor 线性插值得到 `shockRatio(days)`
   - 交易最终使用波动率为：`baseVol * (1 + shockRatio(days))`
   - 所有 Vega 数值统一使用 `diff / 0.001`
   - 其中 GIRR Vega 额外保留第二维 tenor，按交易原始期限映射到标准 tenor
5. 当前敏感性发生规则已经与生产实现保持一致：
   - 线性产品仅有 `Delta`
   - 含权产品仅主标的风险类别产生 `Vega / Curvature`
   - 其他真实依赖风险类别仅保留 `Delta`
6. 商品 Delta 当前也已与“比例 shock + 请求期限插值”实现方式保持一致：
   - 使用 `0D / 3M / 6M / 1Y / 2Y / 3Y / 5Y / 10Y / 15Y / 20Y / 30Y`
   - shock ratio 为 `0.01`

因此，当前版本可以视为：

**“FRTB 敏感度生产路径已完成收口，Vega 已切换到逐 tenor shock 的最终实现方式，剩余仅保留明确标注的兼容边界。”**
- 线性信用产品当前规则进一步收口：
  - `Bond` 仅在含权债场景下允许产生 `GIRR Curvature`；
  - `Bond` 不产生 `CSR Curvature`；
  - `Cds` 不产生任何 `Curvature`。
- 含权产品的非主标的 `GIRR` 仅保留 `Delta`，不再生成 `GIRR Curvature`。
