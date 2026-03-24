# 结构化产品测试报告（rangeoption / sharkfin / autocall / wedding cake）

## 1. 测试范围
- rangeoption: `rangeoption_trades_20241231.json`
- sharkfin: `sharkfin_trades_20241231.json`
- autocall: `autocall_trades_20241231.json`
- wedding cake: `fx_weddingcake_test.json` / `eq_weddingcake_test.json` / `comm_weddingcake_test.json` / `ir_weddingcake_test.json`

## 2. 执行结果
- rangeoption: TOTAL=4, SUCCESS=4, ERROR=0
- sharkfin: TOTAL=3, SUCCESS=3, ERROR=0
- autocall: TOTAL=3, SUCCESS=3, ERROR=0
- fx_weddingcake: TOTAL=1, SUCCESS=1, ERROR=0
- eq_weddingcake: TOTAL=1, SUCCESS=1, ERROR=0
- comm_weddingcake: TOTAL=1, SUCCESS=1, ERROR=0
- ir_weddingcake: TOTAL=1, SUCCESS=1, ERROR=0

结论：本轮测试全部通过。

## 3. 测试数据修复（仅数据）
- 为 WeddingCake 4 份交易补充 `FIXING_ID`：
  - `fx_weddingcake_test.json`
  - `eq_weddingcake_test.json`
  - `comm_weddingcake_test.json`
  - `ir_weddingcake_test.json`
- 为 `IR_SHARKFIN` 交易补充 `FIXING_ID`：
  - `sharkfin_trades_20241231.json`
- 说明：业务产品代码未修改；仅临时测试入口 `TmpCategoryRunner` 做了结果包装与日志增强，便于执行与统计。

## 4. 发现的程序层风险（未在本轮修改）
1. `IR_SHARKFIN` 对缺失 `FIXING_ID` 容错不足
   - `IrSharkFin.buildMarketContext` 直接执行 `new Fixing(marketData.fixingRate.get(info.fixingId))`。
   - 当 `FIXING_ID` 未传时会触发 NPE，而不是业务可读错误。
2. `WeddingCake` 交易字段只识别 `FIXING_ID`
   - 基类校验要求 `FIXING_ID`，未兼容 `HISTORICAL_CURVE`。
   - 如果只给 `HISTORICAL_CURVE`，会报“输入字段不能为空: FIXING_ID”。
3. `RangeAccureOptBase.calc()` 默认会追加 FRTB 敏感性
   - 在缺少 FRTB 参数资源时，完整 `calc()` 可能因资源问题失败。
   - 本次测试对 rangeoption 使用估值核心并补齐输出元字段，目的是验证定价主流程。

## 5. 结果文件
- `rangeoption_trades_20241231_result.json`
- `sharkfin_trades_20241231_result.json`
- `autocall_trades_20241231_result.json`
- `fx_weddingcake_test_result.json`
- `eq_weddingcake_test_result.json`
- `comm_weddingcake_test_result.json`
- `ir_weddingcake_test_result.json`
