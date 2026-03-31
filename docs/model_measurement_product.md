# 模型计量文档（产品计量）

## 外汇产品（FX）

### FXFWD（外汇远期）
- 金融介绍：未来按约定汇率交换两种货币本金，用于锁定未来购汇/结汇成本。
- 主要输入要素：买卖方向、双币名义、结算日、标的/基础折现曲线。
- 主要市场数据：FX_SPOT、IR_SPOT（两币种）。
- 估值计量方法：  
  \[
  V=(N_u\cdot DF_u\cdot FX_{b/u}-N_b\cdot DF_b)\cdot sign
  \]
- PV外计量方法：PV01 采用折现曲线 +1bp 重估差分。
- 计量输出：VALUATION、VALUATION_CNY、PV01、CASH_FLOW、DETAIL。

### FXSWAP（外汇掉期）
- 金融介绍：近端换汇 + 远端反向换汇，常用于外币融资与流动性管理。
- 主要输入要素：近远端名义、近远端结算日、买卖方向、双币折现曲线。
- 主要市场数据：FX_SPOT、IR_SPOT。
- 估值计量方法：近端腿与远端腿分别贴现后求和。
- PV外计量方法：近远端双腿 PV01 合并。
- 计量输出：VALUATION、PV01、近远端现金流。

### FXOPT（外汇香草期权）
- 金融介绍：支付权利金获得未来按行权价换汇的权利，适合“保底+保留上行”。
- 主要输入要素：Call/Put、行权价、到期日、交割日、名义、波动率曲面。
- 主要市场数据：FX_SPOT、IR_SPOT、FX_VOL。
- 估值计量方法：Black/Bachelier 欧式定价。
  \[
  V_{Black}=w\left(S e^{-r_fT}N(wd_1)-K e^{-r_dT}N(wd_2)\right)
  \]
- PV外计量方法：Delta/Gamma/Vega/Theta 使用有限差分重估。
- 计量输出：OptionMeasure（含 IMPLIED_VOL、DELTA、GAMMA、VEGA、THETA、FWD_PRICE、SPOT_PRICE）。

## 利率产品（IR）

### STD_IRS（标准利率互换）
- 金融介绍：固定腿与浮动腿现金流交换，用于重构利率暴露。
- 主要输入要素：固定利率、浮动基准、重置与支付频率、日历、曲线。
- 主要市场数据：IR_SPOT、FIXING。
- 估值计量方法：
  \[
  V=\sum CF_{fixed}\cdot DF-\sum CF_{float}\cdot DF
  \]
- PV外计量方法：PV01（+1bp 差分），并输出 forward_rate、dcf、trade_price。
- 计量输出：StdIrsMeasure。

### IRSCCS（跨币种/复合利率互换）
- 金融介绍：同时管理利率风险与汇率风险的互换结构。
- 主要输入要素：双腿条款、币种、名义、结算规则、曲线标识。
- 主要市场数据：IR_SPOT、FX_SPOT、FIXING。
- 估值计量方法：双腿现金流贴现后按估值币种汇总。
- PV外计量方法：PV01（曲线扰动重估）。
- 计量输出：IrsCcsMeasure。

### BOND（债券）
- 金融介绍：票息+本金现金流资产，价格受利率与信用利差共同驱动。
- 主要输入要素：票息、频率、到期、摊销、折现曲线、信用点差曲线。
- 主要市场数据：IR_SPOT、FX_SPOT、FIXING。
- 估值计量方法：结构化现金流贴现，含 SOY 校准。
  \[
  PV=\sum_i CF_i\cdot DF_i
  \]
- PV外计量方法：
  - PV01：+1bp 重估差分。
  - 有效久期：
    \[
    D_{eff}=\frac{V_- - V_+}{2\Delta y\cdot V_0}
    \]
  - 有效凸性：
    \[
    C_{eff}=\frac{V_+ + V_- -2V_0}{\Delta y^2\cdot V_0}
    \]
- 计量输出：BondMeasure（SPREAD_OVER_YIELD、EFFECTIVE_DURATION、EFFECTIVE_CONVEXITY、ACCRUED_INTEREST）。

### BOND_FUTURE（债券期货）
- 金融介绍：以可交割债券篮子为标的，服务快速利率对冲。
- 主要输入要素：可交割券集合、转换因子、期货价格、到期日。
- 主要市场数据：IR_SPOT、FX_SPOT。
- 估值计量方法：CTD 选择 + 模型远期价格，净基差校准后得到期货估值。
- PV外计量方法：PV01（相关曲线 +1bp 差分）。
- 计量输出：BondFutureMeasure（UNDERLYING_BOND_ID、UNDERLYING_BOND_VALUE、NET_BASIS）。

### CAPFLOOR（利率上限/下限）
- 金融介绍：给浮动利率设置成本上限或收益下限。
- 主要输入要素：Cap/Floor 类型、名义、行权利率、起止日、重置与支付规则。
- 主要市场数据：IR_SPOT、IR_VOL、FIXING。
- 估值计量方法：逐期 caplet/floorlet 定价并贴现求和（Black/Bachelier）。
- PV外计量方法：Gamma、Vega 与 PV01 差分重估。
- 计量输出：CapFloorMeasure。

### SWAPTION（互换期权）
- 金融介绍：未来是否进入 IRS 的权利，用于未来利率对冲锁定。
- 主要输入要素：期权到期、标的互换期限、固定利率、名义、波动率曲面。
- 主要市场数据：IR_SPOT、IR_VOL、FIXING。
- 估值计量方法：Black/Bachelier 互换期权定价。
- PV外计量方法：Gamma、Vega 与 PV01 差分重估。
- 计量输出：SwaptionMeasure。

## 商品产品（COMM）

### COMMFWD（商品远期）
- 金融介绍：锁定未来商品买入/卖出价格。
- 主要输入要素：合约规模、行权价、结算日、参考曲线、折现曲线。
- 主要市场数据：COMM_SPOT、IR_SPOT、FX_SPOT。
- 估值计量方法：
  \[
  V=(F-K)\cdot DF\cdot Position
  \]
- PV外计量方法：PV01（折现曲线 +1bp）。
- 计量输出：CommFwdMeasure。

### COMMSWAP（商品掉期）
- 金融介绍：固定价与浮动商品价交换，常用于能源和大宗商品套保。
- 主要输入要素：近端/远端行权价、近远端结算日、合约规模。
- 主要市场数据：COMM_SPOT、IR_SPOT、FX_SPOT。
- 估值计量方法：近远端两腿估值求和。
- PV外计量方法：双腿 PV01 合并。
- 计量输出：CommSwapMeasure。

### COMMOPT（商品香草期权）
- 金融介绍：在保护商品价格不利变动的同时保留有利行情收益。
- 主要输入要素：Call/Put、行权价、到期、名义、波动率曲面。
- 主要市场数据：COMM_SPOT、IR_SPOT、COMM_VOL。
- 估值计量方法：Black/Bachelier 欧式期权。
- PV外计量方法：Greeks 有限差分。
- 计量输出：OptionMeasure。

## 信用产品（CREDIT）

### CDS（信用违约互换）
- 金融介绍：支付保费换取参考主体违约损失补偿，是信用风险转移核心工具。
- 主要输入要素：名义、恢复率、买卖方向、到期、保费规则、标的债。
- 主要市场数据：折现 IR_SPOT、信用点差曲线 IR_SPOT、FX_SPOT。
- 估值计量方法：
  \[
  V=PV_{default\ leg}-PV_{premium\ leg}
  \]
- PV外计量方法：PV01（折现曲线 +1bp 重估差分）。
- 计量输出：CdsMeasure（含现金流分解）。

## 结构性产品（按结构描述）

### 障碍结构（FX_BARRIER / IR_BARRIER / EQ_BARRIER / COMM_BARRIER）
- 金融介绍：收益与是否触及障碍绑定，用于降低成本和路径化表达观点。
- 主要输入要素：障碍方向与位置、敲入敲出、rebate、历史触碰状态。
- 主要市场数据：标的曲线、折现曲线、波动率曲面。
- 估值计量方法：单障碍/双障碍解析定价 + no-touch 概率；可叠加 VV 调整。
- PV外计量方法：含 VV 的价格函数做差分 Greeks。
- 计量输出：OptionMeasure + barrier detail。

### 数字结构（FX_DIGITAL / IR_DIGITAL / EQ_DIGITAL / COMM_DIGITAL）
- 金融介绍：满足条件支付固定金额，适合事件驱动与离散收益表达。
- 主要输入要素：行权价、rebate、到期与交割。
- 主要市场数据：标的曲线、折现曲线、波动率曲面。
- 估值计量方法：Call Spread 复制：
  \[
  Digital(K)\approx\frac{C(K-\epsilon)-C(K+\epsilon)}{2\epsilon}
  \]
- PV外计量方法：复制价格函数的差分 Greeks。
- 计量输出：OptionMeasure。

### 价差期权结构（FX_SPREADOPT / IR_SPREADOPT / EQ_SPREADOPT / COMM_SPREADOPT）
- 金融介绍：对“相对价格/相对利率”而非单点价格建仓。
- 主要输入要素：双执行价 \(K_1,K_2\)、方向、名义。
- 主要市场数据：标的曲线、折现曲线、波动率曲面。
- 估值计量方法：两腿欧式价格净额，可叠加 VV。
- PV外计量方法：组合净值差分 Greeks。
- 计量输出：OptionMeasure。

### 自动赎回结构（FX_AUTO_CALL / IR_AUTO_CALL / EQ_AUTO_CALL / COMM_AUTO_CALL）
- 金融介绍：定期观察是否提前赎回，常用于收益增强票据。
- 主要输入要素：观察日序列、敲出条件、票息参数、名义。
- 主要市场数据：标的曲线、波动率、折现曲线。
- 估值计量方法：Monte Carlo 路径估值。
- PV外计量方法：固定随机矩阵下的重估差分（降噪）。
- 计量输出：OptionMeasure + path/detail。

### 鲨鱼鳍结构（FX_SHARKFIN / FX_MC_SHARKFIN / IR_SHARKFIN / EQ_SHARKFIN / COMM_SHARKFIN）
- 金融介绍：分段收益与尾部不对称结构，兼顾收益增强与尾部控制。
- 主要输入要素：区间边界、收益参数、观察规则。
- 主要市场数据：标的曲线、波动率、折现曲线、必要 fixing。
- 估值计量方法：解析拆腿或 MC 路径。
- PV外计量方法：差分 Greeks（MC 版本复用路径）。
- 计量输出：OptionMeasure。

### 婚礼蛋糕结构（FX_WEDDING_CAKE / IR_WEDDING_CAKE / EQ_WEDDING_CAKE / COMM_WEDDING_CAKE）
- 金融介绍：outer/mid/inner 三层收益分层结构，适合区间化收益设计。
- 主要输入要素：内外层区间、三层收益率、观察日、结算条款。
- 主要市场数据：标的曲线、波动率、折现曲线、历史 fixing。
- 估值计量方法：固定腿 + 两个双障碍 KO 腿组合，支持 VV。
- PV外计量方法：整体价值差分 Greeks。
- 计量输出：OptionMeasure + 分层触碰状态 detail。

### 区间累计结构（FX_RA / IR_RA / EQ_RA / COMM_RA）
- 金融介绍：标的在观察区间内时累计收益，表达“震荡行情”观点。
- 主要输入要素：上下界、观察日、累计收益率、名义。
- 主要市场数据：标的曲线、波动率曲面、折现曲线、fixing。
- 估值计量方法：区间累计修正公式，校准 \(\sigma\) 与 smile 斜率 \(\sigma_1\)。
- PV外计量方法：按资产类别口径进行 Delta/Gamma/Theta/Vega/Rho 差分。
- 计量输出：OptionMeasure 扩展字段（DOMESTIC_RATE、FOREIGN_RATE、SIGMABYGOALSEEK）。

### 阶梯结构（FX_STEP_UP / IR_STEP_UP / EQ_STEP_UP / COMM_STEP_UP）
- 金融介绍：收益按触发区间分级（低/中/高）提升，用于分情景收益优化。
- 主要输入要素：区间边界、三档收益率、观察与交割规则。
- 主要市场数据：标的曲线、波动率、折现曲线、fixing。
- 估值计量方法：分段收益函数拆解并合成总价值。
- PV外计量方法：差分 Greeks。
- 计量输出：OptionMeasure 扩展字段。

