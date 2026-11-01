-- marketing 服务测试库：建 bs_marketing_test 并切过去，与开发库同容器、同 DDL，只差库名——
-- 库名一眼可辨即「测试不会静默清掉开发数据」的兜底。
-- 建库与建表的拼接方式见 init-dev.sql 注释与 scripts/test-env.sh（apply_service_init）。
-- 改过 schema.sql 后，用 scripts/test-env.sh reset-test-db 重建（先 DROP；幂等可重复执行）。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS bs_marketing_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE bs_marketing_test;
