# Git 提交流程规则

## 1. 仓库结构

- 主仓：E:\zcyh_mr\engine
- 子仓（submodule）：E:\zcyh_mr\engine\src\main\java\com\zcyh\mr

## 2. 提交流程

1. 修改 `mr` 目录内代码时：
   - 先在子仓 `mr` 提交。
   - 再回主仓提交 submodule 指针更新。
2. 修改 `mr` 目录外代码时：
   - 直接在主仓提交。
3. 禁止在提交中包含构建产物和本地数据库文件（依赖 `.gitignore` 约束）。

## 3. 分支策略（mr 子仓）

- 仅允许以下两个分支：
  - `main`
  - `frtb_price_oper_code`（或 `frtb_price***` 命名）
- 不新建其他分支。
- 日常开发提交在 `frtb_price***` 分支进行；稳定后再合并到 `main`。

## 4. 提交基线

- 每次提交必须保证可编译。
- 涉及接口或任务流程改动时，必须包含对应测试或回归验证结果。

