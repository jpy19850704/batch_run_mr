# 数据库 SQL 方言备注（MySQL / Oracle）

## 1. 运行时 SQL 策略

- 目标：优先兼容 MySQL 与 Oracle。
- 策略：运行时按数据库方言分支 SQL。
  - MySQL：使用 `LIMIT`。
  - Oracle：使用 `FETCH FIRST ... ROWS ONLY`。

当前已落地：

- 文件：`E:\zcyh_mr\engine\mr-app\src\main\java\com\zcyh\mr\springboot\service\AsyncJobService.java`
- 场景：待分发任务分页查询（`dispatchPendingJobs`）。
- 机制：启动后通过 JDBC 元数据识别数据库类型，生成对应分页 SQL。

## 2. 无法统一的 SQL 处理规则

- 若无法用同一条 SQL 兼容两种数据库，则采用：
  1. 运行时 SQL：优先做方言分支；
  2. DDL/初始化脚本：默认采用 MySQL 写法，并在本文件记录 Oracle 对应写法。

当前记录：

- 文件：`E:\zcyh_mr\engine\mr-app\src\main\resources\db\mr_input_schema.sql`
- 已使用 MySQL 语法：`INSERT ... ON DUPLICATE KEY UPDATE`
- 对应 Oracle 写法建议：`MERGE INTO ... WHEN MATCHED THEN UPDATE WHEN NOT MATCHED THEN INSERT`

## 3. 执行原则

- 新增 SQL 时，优先选择两库通用语法。
- 涉及分页、UPSERT 等方言差异明显的语句，必须在提交中附带本备注更新。
