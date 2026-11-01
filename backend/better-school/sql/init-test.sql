-- 测试库：建 better_school_test 并切过去，DDL 与开发库共用 schema.sql。
-- 与开发库同容器同 DDL，只差库名——库名一眼可辨即「测试不会静默清掉开发数据」的兜底。
-- 与 schema.sql 的拼接要求、命令见 docker-compose.yml 头注释 1。
-- 改过 schema.sql 或新增迁移后用 scripts/test-env.sh reset-test-db 重建（先 DROP）。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS better_school_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE better_school_test;
