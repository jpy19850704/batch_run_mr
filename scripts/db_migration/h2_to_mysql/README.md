# H2 到 MySQL 迁移方案（排除 calendar）

## 1. 目标与边界

- 目标：将当前 H2 中 engine 运行必需表迁移到 MySQL，便于 engine 与 web 分离运维。
- 不迁移：`calendar`（已由缓存统一处理）。
- 不迁移：`TB_OUT_*` 结果表（当前已规划由 Doris 承接）。

本方案迁移范围：

- `MR_TRADE_INPUT`
- `MR_MARKET_CURVE_INPUT`
- `MR_RISKFACTOR_DATA`
- `MR_SCENARIO_RULE`
- `MR_AGG_RULE`
- `MR_ASYNC_JOB`
- `MR_ASYNC_BATCH_JOB`
- `MR_ASYNC_BATCH_ITEM`
- `MR_AUDIT_LOG`

## 2. 账号与权限建议（当前联调口径）

- 使用“独立 schema”，账号可按联调需要互通授权。
- 当前脚本口径：
  - engine schema：`mr_engine`
  - web schema：`ry-vue`（保持现状）
  - engine 用户：`engine_app`
  - web 账号：复用现有 `ry-vue` 业务账号（不强制新建 `web_app`）
- 权限策略（本地联调）：
  - engine 账号可读写 `mr_engine` 与 `ry-vue`
  - web 账号可读写 `ry-vue` 与 `mr_engine`

对应脚本：

- `01_mysql_schema_and_user.sql`

## 3. 执行步骤

1. 初始化 MySQL schema 与用户
   - 执行 `01_mysql_schema_and_user.sql`
2. 建表
   - 执行 `02_mysql_engine_tables.sql`
3. 初始化默认规则
   - 执行 `03_seed_mr_agg_rule.sql`
4. 迁移数据（建议停写窗口执行）
   - 从 H2 导出上述 9 张表数据
   - 导入 MySQL `mr_engine`
5. 行数校验
   - H2 执行 `00_count_check_h2.sql`
   - MySQL 执行 `04_count_check_mysql.sql`
6. 切换 engine 数据源
   - `MR_SCENARIO_DB_URL` 指向 MySQL `mr_engine`
7. 冒烟验证
   - 启动 engine，执行一次估值任务与批量任务

## 4. 迁移前基线（当前环境）

以下为当前 H2 实测行数（2026-03-28）：

- `MR_TRADE_INPUT` = 106
- `MR_MARKET_CURVE_INPUT` = 20
- `MR_RISKFACTOR_DATA` = 62304
- `MR_SCENARIO_RULE` = 206
- `MR_AGG_RULE` = 1
- `MR_ASYNC_JOB` = 963
- `MR_ASYNC_BATCH_JOB` = 29
- `MR_ASYNC_BATCH_ITEM` = 958
- `MR_AUDIT_LOG` = 80

## 5. H2 校验命令示例

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$sql = Get-Content -Raw -Encoding UTF8 E:\zcyh_mr\engine\scripts\db_migration\h2_to_mysql\00_count_check_h2.sql
java -cp E:\zcyh_mr\engine\lib\h2-2.1.214.jar org.h2.tools.Shell `
  -url "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1" `
  -user sa `
  -sql $sql
```

## 6. MySQL 校验命令示例

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
mysql --host=127.0.0.1 --port=3306 --user=root --database=mr_engine `
  < E:/zcyh_mr/engine/scripts/db_migration/h2_to_mysql/04_count_check_mysql.sql
```

## 7. 风险提示

- 大小写风险：跨 Linux 环境请统一表名大小写策略，避免 `MR_*` 与 `mr_*` 混用导致查询失败。
- 迁移窗口：建议迁移期间暂停写入，防止增量丢失。
- 账号最小权限：联调完成后建议收敛为最小权限（按服务职责拆分授权）。
