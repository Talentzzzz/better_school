# 03 前置提交票 B/C 存量改动

Type: task
Status: open
Blocked by: —

## Question

把工作区中票 B/C 的全部未提交改动（`backend/*` 代码、`backend/CLAUDE.md`、`.scratch/tx-boundary-refactor/*` 文档与测试注释同步）提交为一个 commit，保证后续重组 diff 纯净可回滚。git 身份用 repo 级 TalentXD666 <a13683639985@163.com>。

## Answer

已完成（2026-11-23）。

- Commit hash：`72236ee8e54d34a725a893568e5403f35f53f583`（分支 `chat-ws-layering-rollout`，作者 TalentXD666 <a13683639985@163.com>）
- 提交内容概要：70 files changed, +1010 / -808，单 commit 收口票 B/C 全部存量改动
  - 票 B：`IProductPayCompositeTxPort` / `ProductPayCompositeTxPort` / `CompositeTxExecutor`(+Action/Test) 删除；商品支付 prepare→freeze→confirm 两段化、取消翻转先行+case 补解冻；`listOrphanFrozen` 参数化 type 对；新增 `ProductFundOrphanScanCase` / `ProductFundOrphanScanJob`（xxl `productFundOrphanScan` 已注册）
  - 票 C：`IPointsDeductPort` / `PointsDeductPort` 删除；购买链路三段化，编排落 `marketing-case` `DrawCountPurchaseCase`；`DEDUCTED(0)`→`CREATED(0)`（code 不变零迁移）；扫描并入 `drawCountPurchaseScan`（收敛=三段全量重放，零新表）；积分不足单即置 DEAD
  - credit 域表所有权配套：`user_credit` 独立表（`UserCreditAccountMapper.xml` / `IUserCreditAccountDao` / `UserCredit` PO），`bs_user` 列迁出说明
  - 文档/注释同步：`backend/CLAUDE.md`、`docs/adr/0016`、`sql.md`、trade.md / user.md / marketing-services.md §4 / lottery.md / schema / xxl-job-tasks.sql、tx-boundary-refactor 票与 map/spec、各集成测试注释两段式口径
  - 范围外保留未跟踪：`.scratch/monolith-restructure/`（重组立票材料，不属于票 B/C）
- 提交前健全性检查：better-school 与 marketing 两 reactor `mvn -o test-compile` 均绿。
