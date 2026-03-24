# BASH 转换脚本使用说明

## 1. 脚本路径
- `d:\后端代码\engine\bin\market_data_csv_to_json.sh`
- `d:\后端代码\engine\bin\curvegeneration_csv_to_json.sh`

## 2. 运行环境
- 已安装 `bash`（建议 Git Bash）
- 已安装 `gawk`

在 PowerShell 中检查：
```powershell
bash --version
gawk --version
```

## 3. market_data 转换
脚本：
- `market_data_csv_to_json.sh`

用途：
- 将 `market_data` CSV 转为 JSON（默认根节点为 `market_data`）

参数：
- 必填：`--in <csv>` `--out <json>`
- 可选：`--output-mode market_data|array`（默认 `market_data`）
- 可选：`--pretty true|false`（默认 `true`）
- 可选：`--fill-policy NONE`（bash 版仅支持 `NONE`）

示例：
```powershell
& "C:\Program Files\Git\bin\bash.exe" ./bin/market_data_csv_to_json.sh `
  --in ./src/main/resources/data/market_data_ir_spot_template.csv `
  --out ./target/market_data_ir_spot.json `
  --fill-policy NONE
```

## 4. curvegeneration 转换
脚本：
- `curvegeneration_csv_to_json.sh`

用途：
- 将 `CurveGeneration` 输入 CSV 转为 JSON 数组（`CurveInput` 列表）

参数：
- 必填：`--in <csv>` `--out <json>`
- 可选：`--pretty true|false`（默认 `true`）

示例：
```powershell
& "C:\Program Files\Git\bin\bash.exe" ./bin/curvegeneration_csv_to_json.sh `
  --in ./src/main/resources/data/curvegeneration_zero_curve_bootstrap_template.csv `
  --out ./target/curvegeneration_zero_curve_bootstrap.json `
  --pretty true
```

## 5. 模板文件位置（每类型一个 CSV）
`market_data`：
- `src/main/resources/data/market_data_ir_spot_template.csv`
- `src/main/resources/data/market_data_comm_spot_template.csv`
- `src/main/resources/data/market_data_eq_spot_template.csv`
- `src/main/resources/data/market_data_fx_spot_template.csv`
- `src/main/resources/data/market_data_fixing_template.csv`
- `src/main/resources/data/market_data_ir_fixing_template.csv`
- `src/main/resources/data/market_data_ir_vol_template.csv`
- `src/main/resources/data/market_data_fx_vol_template.csv`
- `src/main/resources/data/market_data_eq_vol_template.csv`
- `src/main/resources/data/market_data_comm_vol_template.csv`

`curvegeneration`：
- `src/main/resources/data/curvegeneration_zero_curve_bootstrap_template.csv`
- `src/main/resources/data/curvegeneration_fx_implied_curve_construct_template.csv`
- `src/main/resources/data/curvegeneration_zero_curve_subtract_template.csv`
- `src/main/resources/data/curvegeneration_vol_rrbf_2_delta_template.csv`

## 6. 返回码说明
- `0`：成功
- `2`：参数错误/不支持参数
- `3`：输入文件不存在或空文件
- `4`：数据校验失败（字段缺失、格式不合法等）

## 7. 常见问题
- 报错 `gawk is required but not found`：
  - 安装 `gawk`，并确保在 `PATH` 中可执行。
- PowerShell 找不到 `bash`：
  - 用绝对路径执行：`C:\Program Files\Git\bin\bash.exe`
- 报错 `only supports --fill-policy NONE`：
  - `market_data` bash 版不支持 `LINEAR`，请改为 `NONE`。
