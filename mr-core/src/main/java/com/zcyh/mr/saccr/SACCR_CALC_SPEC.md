# SA-CCR 计量方案

## 总体口径

SA-CCR 不从 engine 估值器重新估值。计量入口只接受 `batch_id` 和 `data_date`，交易 MTM 统一读取 `engine_result_db.TB_OUT_TRADE_RESULT_DETAIL` 中对应批次、日期、交易的 `VALUATION_CNY`。

SACCR 专属输入表放在 `engine_db`，表名统一以 `SACCR_` 开头，输入表不包含 `BATCH_ID`，只按 `DATA_DATE` 管理。

## 输入表

### SACCR_TRADE_CP

交易范围与归属表，同时替代独立 trade scope 文件。

| 字段 | 含义 |
| --- | --- |
| `DATA_DATE` | 数据日期 |
| `INSTRUMENT_ID` | 交易唯一标识 |
| `COUNTERPARTY_ID` | 交易对手 |
| `NETTING_SET_ID` | 净额集合；为空表示无净额协议 |

`NETTING_SET_ID` 为空时，按单笔独立净额协议模式处理：

- `NETTING_MODE = TRADE`
- 内部计算单元 `NETTING_SET_ID = SINGLE_<INSTRUMENT_ID>`
- 不与同一交易对手下其他交易合并
- `MARGIN_TYPE = NONE`

### SACCR_NETTING_SET

净额集合保证金协议表。`NETTING_SET_ID` 和保证金协议保持唯一对应，不再维护多个 CSA。

| 字段 | 含义 |
| --- | --- |
| `DATA_DATE` | 数据日期 |
| `NETTING_SET_ID` | 净额集合 |
| `COUNTERPARTY_ID` | 交易对手 |
| `MARGIN_TYPE` | `NONE` / `BILATERAL` / `ONE_WAY_BANK` |
| `MARGIN_CCY` | `THRESHOLD` / `MTA` / `NICA` 统一币种 |
| `MARGIN_FX_RATE_TO_CNY` | 保证金币种兑人民币汇率 |
| `THRESHOLD` | 保证金币种下 TH |
| `MTA` | 保证金币种下 MTA |
| `NICA` | 保证金币种下 NICA |
| `MPOR_DAYS` | 显式 MPOR 工作日 |

`IS_CLEARED`、`CCP_TYPE`、`IS_CLIENT_CLEARING`、`MEETS_PORTABILITY_CONDITIONS` 不参与 engine EAD 逻辑。CCP、客户清算、可转移条件等监管判断由输入侧折算为显式 `MPOR_DAYS`。

### SACCR_COLLATERAL_DETAIL

押品明细表。押品可以挂在净额集合，也可以挂在单笔交易。

| 字段 | 含义 |
| --- | --- |
| `DATA_DATE` | 数据日期 |
| `COLLATERAL_ID` | 押品唯一标识 |
| `COLLATERAL_SCOPE` | `NETTING_SET` / `TRADE` |
| `NETTING_SET_ID` | 净额集合押品归属 |
| `INSTRUMENT_ID` | 交易押品归属 |
| `COLLATERAL_TYPE` | `CASH` / `BOND` / `EQUITY` / `FUND` / `GOLD` / `OTHER` |
| `DIRECTION` | `RECEIVED` / `POSTED` |
| `COLLATERAL_CCY` | 押品币种 |
| `MARKET_VALUE` | 押品市值 |
| `FX_RATE_TO_CNY` | 押品币种兑人民币汇率 |
| `HAIRCUT_RATE` | 折扣率 |
| `ELIGIBLE_FLAG` | 是否合格押品 |

计量时统一计算：

```text
ADJUSTED_VALUE_CNY = MARKET_VALUE * FX_RATE_TO_CNY * (1 - HAIRCUT_RATE)
COLLATERAL_C = Σ RECEIVED 合格押品 - Σ POSTED 合格押品
```

## 支持产品

当前支持普通利率、汇率、商品和 CDS，不包括结构性衍生品。

| 产品代码 | 资产类别 | 说明 |
| --- | --- | --- |
| `IRSCCS` | `IR` | 普通利率/货币互换 |
| `STD_IRS` | `IR` | 标准利率互换 |
| `BOND_FUTURE` | `IR` | 国债期货类输入按标准字段提供 |
| `CAPFLOOR` | `IR` | 利率期权 |
| `SWAPTION` | `IR` | 掉期期权 |
| `FXFWD` | `FX` | 外汇远期 |
| `FXSWAP` | `FX` | 外汇掉期 |
| `FXOPT` | `FX` | 外汇期权 |
| `COMMFWD` | `COMMODITY` | 商品远期 |
| `COMMSWAP` | `COMMODITY` | 商品掉期 |
| `COMMOPT` | `COMMODITY` | 商品期权 |
| `CDS` | `CREDIT` | 信用违约互换 |

## 标准交易字段

转换器读取 `TB_OUT_TRADE_RESULT_DETAIL.TRADE_INPUT_JSON`，只认标准字段名。

公共字段：

| 字段 | 含义 |
| --- | --- |
| `DIRECTION` | `1` / `-1` |
| `NOTIONAL` | 名义本金 |
| `CURRENCY` | 币种 |
| `START_DATE` | 起始日期，`yyyyMMdd` |
| `END_DATE` | 到期日期，`yyyyMMdd` |

资产类别专属字段：

| 资产类别 | 字段 |
| --- | --- |
| `FX` | `CURRENCY_PAIR` |
| `CREDIT` | `REFERENCE_ENTITY`、`CREDIT_RATING`、`IS_INDEX` |
| `COMMODITY` | `COMMODITY_BUCKET`、`COMMODITY_TYPE`、`UNDERLYING_PRICE`、`QUANTITY` |

期权字段：

| 字段 | 含义 |
| --- | --- |
| `OPTION_TYPE` | `CALL` / `PUT` |
| `OPTION_EXPIRY` | 期权到期日 |
| `STRIKE_PRICE` | 行权价 |
| `UNDERLYING_PRICE` | 标的价格 |

期权方向不再使用 `OPTION_LONG`，统一由 `DIRECTION` 表示。

## 代码层级

```text
mr-app
  com.zcyh.mr.springboot.engine.SaccrEngineAdapter
    - engineCode = sa_ccr
    - 解析 batch_id/data_date/persist_result
    - 调用输入组装、core 计算、结果落库

  com.zcyh.mr.springboot.saccr.SaccrInputQueryService
    - 读取 SACCR_TRADE_CP
    - 读取 TB_OUT_TRADE_RESULT_DETAIL 获取 VALUATION_CNY 和 TRADE_INPUT_JSON
    - 读取 SACCR_NETTING_SET
    - 读取 SACCR_COLLATERAL_DETAIL 并计算 COLLATERAL_C
    - 组装 SaccrNettingSet

  com.zcyh.mr.springboot.saccr.SaccrTradeInputConvertService
  com.zcyh.mr.springboot.saccr.SaccrTradeInputConverterRegistry
  com.zcyh.mr.springboot.saccr.SaccrTradeInputConverter
  com.zcyh.mr.springboot.saccr.StandardSaccrTradeConverter
    - 按产品转换 TRADE_INPUT_JSON 到 SaccrTrade

  com.zcyh.mr.springboot.service.SaccrResultPersistService
    - 写 TB_OUT_SACCR_RESULT
    - 写 TB_OUT_SACCR_TRADE_DETAIL
    - 写 TB_OUT_SACCR_COLLATERAL_DETAIL

mr-core
  com.zcyh.mr.saccr.SaccrCalculator
    - 纯 EAD 计量
    - 不读库、不估值、不落库、不计算资本
    - MPOR 只使用输入侧显式 MPOR_DAYS
```

## 输出表

### TB_OUT_SACCR_RESULT

一行一个计算单元。`NETTING_MODE=NETTING_SET` 时代表一个净额集合；`NETTING_MODE=TRADE` 时代表一笔无净额协议交易的独立计算单元。

主要字段：

`BATCH_ID`、`DATA_DATE`、`NETTING_MODE`、`NETTING_SET_ID`、`COUNTERPARTY_ID`、`TRADE_COUNT`、`MARGIN_TYPE`、`SUM_MTM`、`COLLATERAL_C`、`THRESHOLD_CNY`、`MTA_CNY`、`NICA_CNY`、`RC`、`ADDON_IR`、`ADDON_FX`、`ADDON_CREDIT`、`ADDON_EQUITY`、`ADDON_COMMODITY`、`ADDON_AGGREGATE`、`MULTIPLIER`、`PFE`、`EAD`。

交易对手维度结果由 Web 按 `COUNTERPARTY_ID` 汇总 `EAD` 即可。

### TB_OUT_SACCR_TRADE_DETAIL

一行一笔交易。结构化字段保存公共和资产类别专属主字段，`MEASURE_FACTOR_JSON` 只保存中间计量要素：

`START_DATE`、`END_DATE`、`OPTION_EXPIRY` 为 Doris `DATE` 类型；批次维度 `DATA_DATE` 仍保持 `yyyyMMdd` 字符串。

- `delta`
- `adjustedNotional`
- `maturityFactor`
- `supervisoryDuration`
- `effectiveNotional`
- `mporDays`
- `startYears`
- `endYears`
- `optionTYears`

### TB_OUT_SACCR_COLLATERAL_DETAIL

一行一条合格押品审计结果，保存折后人民币金额 `ADJUSTED_VALUE_CNY`。
