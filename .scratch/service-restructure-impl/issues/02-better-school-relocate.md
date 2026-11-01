# 02: better-school 单体工程平移

**Spec:** `.scratch/service-restructure/spec.md` §1 目标结构、§2.1 目录/模块映射、§2.3 sql 映射（单体段）、§2.5 docs 映射（单体段）、§4-1

**What to build:** `backend/` 根的单体工程整体平移进 `backend/better-school/`——bs-* 七模块、聚合 pom、Dockerfile、sql、docs 各就各位。平移后全树仍可编译、全量测试绿，`backend/` 根开始呈现三工程布局的第一块。

**Blocked by:** None (can start immediately)；与票 03/04 无交集可并行

**Status:** resolved

- [x] `backend/{pom.xml, Dockerfile, bs-types, bs-api, bs-domain, bs-case, bs-infrastructure, bs-trigger, bs-app}` → `backend/better-school/` 原样平移，模块名、内部结构、包名**零改动**
- [x] 聚合 pom artifactId `better-school-backend` → `better-school`（§4-1，与 lottery/points 聚合 pom 命名先例对齐）；modules 列表不变
- [x] Dockerfile 平移：`COPY --from=build /build/bs-app/target/...` 路径不变（jar 内相对路径不变），构建 context 变为工程目录——在注释或提交信息里记一笔镜像构建命令的新 context
- [x] sql 落位 `better-school/sql/`：`schema.sql`、`init-dev.sql`、`init-test.sql`、`migrations/001–021`、`026`、`027`（025 断号保持）、`xxl-job-tasks.sql`（现内容原样，即单体段——营销 handler 原先无 SQL 落点）
- [x] docs 落位 `better-school/docs/`：`api/{user,community,trade,errand,chat,files,moderation}.md` + `db/` 同名七篇；根 `docs/api/README.md`、`db/README.md` 中的**单体段**抽出写入两工程 README（营销段留在根，等票 03/04 搬走）
- [x] 验收：`backend/better-school` 下 `mvn compile` 与全量 `mvn test` 通过；`backend/` 根不再有 bs-* 模块、旧 pom.xml、旧 Dockerfile

## 实现要点（票 02）

- 全部平移走 `git mv`（527 个文件 rename，历史保留）；`.dockerignore` 随 Dockerfile 一起平移（构建 context 级机制，票面没列但属 Dockerfile 一体）。mvn 编译期产物 `target/` 随目录整体落新址。
- **机械联改两处（票面未列但 mvn 必需）**：① 七个子模块 pom 的 `<parent>` artifactId 同步 `better-school-backend` → `better-school`（聚合 pom 改名后 parent 引用必须跟）；② 聚合 pom `<name>` 同步改。code-review Spec 轴判定为合理联改。
- **接线修正三处（票面未列，保持全树可运行的最小改动）**：① `scripts/test-env.sh` 加 `BS_SQL_DIR` 指向 `better-school/sql/`，`apply_init` 改用它（不改则单体建库断）——营销段 `apply_marketing_init`/`SQL_DIR` 未动，6→4 库收敛留给票 05；② `docker-compose.yml` 头注释单体段手工命令路径（init-dev/init-test/schema.sql、xxl-job-tasks.sql）补 `better-school/` 前缀，营销段注释未动；③ `better-school/sql/xxl-job-tasks.sql` 头注释执行命令路径同前缀。
- Dockerfile 新 context 记录在文件头注释：`docker build -t better-school backend/better-school`。
- README 拆段：两工程 README 从根 README 抽出时补了两类小指针——better-school 侧「营销文档在根、收敛后搬走」、根侧「单体文档已落 better-school」；better-school db README 的 docker-compose 相对链接为 `../../../docker-compose.yml`（深度多一层）。
- 迁移测试零改动仍正确：`MigrationTestBase.locateMigrationScript` 用 `../sql/migrations`（surefire 工作目录 = bs-app 模块根）相对解析，模块与 sql 同步平移后自动命中新位置；代码与注释一字未动。
- 验收实测：`mvn compile` 绿；全量 `mvn test` 341 用例 0 失败（bs-app 14:29 min），Reactor 名已显示新 artifactId `better-school`；backend 根 ls 确认无 bs-*/pom.xml/Dockerfile；`better-school-backend` 全仓（backend 下）grep 零残留。
- **已知波及、不在本票范围**（均 spec §2.6/§3.4 划给票 05）：根 `backend/docs/README.md:3` 与 `docs/testing.md` 仍写旧路径（`backend/bs-*`、`bs-app/src/test/...`）；`backend/CLAUDE.md` 目录示例；lottery/points/referral 聚合 pom 头注释「与单体 backend/pom.xml 是兄弟目录」的措辞随票 03/04 重写自然消解。code-review Standards 轴的「xxl-job.sql（根）与 xxl-job-tasks.sql（better-school）分居两目录」观察项系 spec §2.3 有意安排，非遗漏。
- 收口提交须显式 `git add` 两个新 README 与票面文件（rename 已 staged，untracked 新文件不会自动进）。
