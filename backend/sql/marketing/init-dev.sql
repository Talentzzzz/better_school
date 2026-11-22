-- marketing 服务开发库：建 bs_marketing 并切过去，DDL 主体在 schema.sql（唯一 DDL 来源）。
-- 单独执行本文件只会建出一个空库——必须与 schema.sql 按序拼接喂给同一个 mysql 连接，
-- 原因与命令见 scripts/test-env.sh（apply_service_init）；平时直接跑 scripts/test-env.sh up 即可（幂等）。
-- outbox_message 只在新形状 schema.sql 里（票 08 已形状切换，旧形状不并存）。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS bs_marketing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE bs_marketing;
