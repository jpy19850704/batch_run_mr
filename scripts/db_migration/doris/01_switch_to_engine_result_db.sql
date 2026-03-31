-- =========================================================
-- Doris 结果库统一脚本
-- 目标：统一结果库为 engine_result_db
-- 说明：
-- 1) 先在 FE MySQL 协议端执行：mysql -h127.0.0.1 -P9030 -uroot
-- 2) 先执行本脚本的“建库+建表”部分
-- 3) 如需保留历史数据，再执行“数据迁移”部分（按实际旧库二选一）
-- =========================================================

-- ========================
-- A. 建库与建表
-- ========================

CREATE DATABASE IF NOT EXISTS engine_result_db;
USE engine_result_db;

-- 推荐直接执行标准 DDL 文件中的 TB_OUT_* 建表语句：
-- E:\zcyh_mr\engine\mr-app\src\main\resources\db\mr_output_schema_doris.sql
-- 如果已建过表，可跳过。


-- ========================
-- B. 从旧库迁移历史数据（可选）
-- ========================
-- 注意：
-- 1) 旧库可能是 mr 或 mr_db，请按实际选择一组执行
-- 2) 迁移前建议先暂停写入任务，避免增量缺口

-- ---------- 方案 B1：旧库为 mr ----------
-- INSERT INTO engine_result_db.TB_OUT_TRADE_RESULT_DETAIL SELECT * FROM mr.TB_OUT_TRADE_RESULT_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_SCENARIO_RESULT_DETAIL SELECT * FROM mr.TB_OUT_TRADE_SCENARIO_RESULT_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL SELECT * FROM mr.TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL SELECT * FROM mr.TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_DRC_DETAIL SELECT * FROM mr.TB_OUT_TRADE_DRC_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_MARKET_DATA_DETAIL SELECT * FROM mr.TB_OUT_MARKET_DATA_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_SCENARIO_FILE_DETAIL SELECT * FROM mr.TB_OUT_SCENARIO_FILE_DETAIL;

-- ---------- 方案 B2：旧库为 mr_db ----------
-- INSERT INTO engine_result_db.TB_OUT_TRADE_RESULT_DETAIL SELECT * FROM mr_db.TB_OUT_TRADE_RESULT_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_SCENARIO_RESULT_DETAIL SELECT * FROM mr_db.TB_OUT_TRADE_SCENARIO_RESULT_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL SELECT * FROM mr_db.TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL SELECT * FROM mr_db.TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_TRADE_DRC_DETAIL SELECT * FROM mr_db.TB_OUT_TRADE_DRC_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_MARKET_DATA_DETAIL SELECT * FROM mr_db.TB_OUT_MARKET_DATA_DETAIL;
-- INSERT INTO engine_result_db.TB_OUT_SCENARIO_FILE_DETAIL SELECT * FROM mr_db.TB_OUT_SCENARIO_FILE_DETAIL;


-- ========================
-- C. 迁移后核对
-- ========================

-- USE engine_result_db;
-- SHOW TABLES;
-- SELECT COUNT(1) AS CNT FROM TB_OUT_TRADE_RESULT_DETAIL;
-- SELECT COUNT(1) AS CNT FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL;
-- SELECT COUNT(1) AS CNT FROM TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL;
-- SELECT COUNT(1) AS CNT FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL;
-- SELECT COUNT(1) AS CNT FROM TB_OUT_TRADE_DRC_DETAIL;
-- SELECT COUNT(1) AS CNT FROM TB_OUT_MARKET_DATA_DETAIL;
-- SELECT COUNT(1) AS CNT FROM TB_OUT_SCENARIO_FILE_DETAIL;
