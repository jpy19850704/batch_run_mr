# Product 代码审查报告（2026-03-02）

## 范围
- 目录：`src/main/java/com/zcyh/mr/product/**`
- 相关调度：`src/main/java/com/zcyh/mr/calc/**`
- 审查重点：代码合理性、健壮性、估值计量一致性（`valuation` 主路径）

## 结论摘要
- 当前存在 **2 个编译阻断问题**。
- 存在 **2 个场景估值口径错误（FX 情景未真正生效）**。
- 存在 **多处输入校验不足/潜在 NPE**。
- 部分期权 Greek 的持仓口径不一致，存在结果解释风险。

## 发现（按严重级别）

### P0 - 编译阻断
1. `CommOptCalc` 使用了未定义变量 `CommOptMeasure`
- 文件：`src/main/java/com/zcyh/mr/calc/CommOptCalc.java:64`
- 现象：`"SUCCESS".equals(CommOptMeasure.status)` 中 `CommOptMeasure` 未定义。
- 影响：该类无法正常编译。
- 建议：改为 `measure.status`。

2. `CommVanillaOpt` 调用了未实现方法 `getSafeFxRate`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommVanillaOpt.java:46`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommVanillaOpt.java:123`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommVanillaOpt.java:551`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommVanillaOpt.java:552`
- 对照：文件内仅有 `getFxRate`，无 `getSafeFxRate`（`.../CommVanillaOpt.java:704`）
- 影响：类编译失败。
- 建议：补齐 `getSafeFxRate`（重载：`(String)` 与 `(FxSpot,String)`）或统一替换为已存在方法。

### P1 - 估值口径错误（场景/敏感度）
3. `CapFloor` 场景估值仍使用基准 FX 汇率
- 文件：`src/main/java/com/zcyh/mr/product/ir/CapFloor.java:45`
- 文件：`src/main/java/com/zcyh/mr/product/ir/CapFloor.java:108`
- 现象：`fxSpot` 在构造函数按基准市场数据初始化，`calc(MarketData)` 中未按传入场景重建。
- 影响：FX shock 下 `valuationCny` 可能不变，FX Delta/跨币比较失真。
- 建议：在 `calc(MarketData)` 内用传入 `marketData.fxSpot` 新建 `FxSpot`。

4. `Swaption` 场景估值仍使用基准 FX 汇率
- 文件：`src/main/java/com/zcyh/mr/product/ir/Swaption.java:50`
- 文件：`src/main/java/com/zcyh/mr/product/ir/Swaption.java:91`
- 文件：`src/main/java/com/zcyh/mr/product/ir/Swaption.java:167`
- 现象与影响同上。
- 建议：`swaptionValue()` 改为使用当前 `this.marketData` 生成临时 `FxSpot`，或在 `calc(MarketData)` 里同步刷新 `fxSpot`。

### P2 - 健壮性/防御性不足
5. `CapFloor` GIRR Vega 路径缺少空保护
- 文件：`src/main/java/com/zcyh/mr/product/ir/CapFloor.java:296`
- 文件：`src/main/java/com/zcyh/mr/product/ir/CapFloor.java:297`
- 现象：直接使用 `girrVegaMarketData.marketData`，未判空。
- 风险：当 `MarketData.getFrtbMarketDataListGIRRVega(...)` 返回空对象时可能 NPE。
- 建议：参照 `Swaption` 的写法先判断 `girrVegaMarketData != null && girrVegaMarketData.marketData != null`。

6. `CommFwd` 输入校验不足，且方向判断大小写敏感
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommFwd.java:49`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommFwd.java:55`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommFwd.java:56`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommFwd.java:64`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommFwd.java:233`
- 现象：仅校验 `UNDERLYING_CODE`，但后续直接解引用曲线与关键数值；`buyOrSell.equals("B")` 对 `b` 不兼容。
- 风险：NPE 或方向误判。
- 建议：补齐 `discountCurve/priceCurve/contractSize/strikePrice/buyOrSell` 校验，并使用 `equalsIgnoreCase`。

7. `CommSwap` 同类问题
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommSwap.java:45`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommSwap.java:48`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommSwap.java:49`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommSwap.java:55`
- 文件：`src/main/java/com/zcyh/mr/product/comm/CommSwap.java:280`
- 现象与建议同 `CommFwd`。

8. `AutoCallBase` 公共校验存在潜在空指针
- 文件：`src/main/java/com/zcyh/mr/product/basic/structure/AutoCallBase.java:241`
- 现象：`validateCommon()` 中直接访问 `marketData.irSpot`，未先判断 `marketData` 或 `marketData.irSpot` 非空。
- 风险：输入异常时抛 NPE，不易定位。
- 建议：在校验前增加 `marketData`/`marketData.irSpot` 非空判断，输出明确错误信息。

### P3 - 口径一致性风险
9. 期权 Greek 是否乘持仓口径不一致
- 文件：`src/main/java/com/zcyh/mr/product/basic/option/DigOptBase.java:89`
- 文件：`src/main/java/com/zcyh/mr/product/basic/option/DigOptBase.java:94`
- 文件：`src/main/java/com/zcyh/mr/product/basic/option/BarOptBase.java:140`
- 文件：`src/main/java/com/zcyh/mr/product/basic/option/BarOptBase.java:145`
- 文件：`src/main/java/com/zcyh/mr/product/fx/FxVanillaOpt.java:75`
- 现象：`valuation` 乘 `pos`，但部分 Greek 未乘 `pos`；另一些产品（如 `SpreadOptBase`）又会乘 `pos`。
- 风险：跨产品比较时易误读。
- 建议：统一口径（推荐全部输出“持仓后 Greek”），并在字段文档明确说明。

## 审查过程说明
- 已通读主要产品类（FX/IR/COMM/EQ/Credit）及公共基类。
- 运行过 `mvn -q -DskipTests compile`，当前仓库存在历史备份类重复（`*_bak`/`*_codex`）导致全量编译噪音；以上问题为基于源码审读的确定性结论。
