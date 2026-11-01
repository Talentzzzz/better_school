-- 开发库：建 better_school 并切过去，DDL 主体在 schema.sql（唯一 DDL 来源）。
-- 单独执行本文件只会建出一个空库——必须与 schema.sql 按序拼接喂给同一个 mysql 连接，
-- 原因与命令见 docker-compose.yml 头注释 1；平时直接跑 scripts/test-env.sh up 即可（幂等）。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS better_school DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE better_school;
