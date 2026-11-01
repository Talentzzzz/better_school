# 02: 帖子草稿箱四态流转（后端）

**What to build:** 帖子草稿箱：草稿状态进入帖子状态机（直接创建轮次为 0，对齐商品语义）；作者可保存草稿、编辑草稿、提交草稿进待审（用例层完整性校验：正文与分类必填，标题/配图/封面可空）；被拒帖可转草稿（作者可见拒绝理由），改完重提轮次 +1 且两轮审核记录并存；待审帖可撤回回草稿箱。"我的-发帖"列表纳入草稿状态，草稿与被拒详情仅作者可见。

**Blocked by:** 01: 帖子审核新语义——先审后发 + 同步初筛报错 + 审核轮次

**Status:** ready-for-human

- [x] 保存草稿（可留空）→ 编辑 → 提交进待审；缺必填项时提交被校验拦截
- [x] 被拒帖转草稿后可见拒绝理由；重提轮次 +1，两轮审核记录与理由并存可回溯
- [x] 待审帖撤回回草稿箱，撤回后可再编辑重提
- [x] "我的-发帖"列表含草稿/待审/被拒各态；非作者访问草稿与被拒详情不可见
- [x] 作者可软删任意自己状态下的帖子（维持现状口径）
- [x] 集成测试对齐 TradeDraftBox / TradeRejectDraftAndEdit 先例（四态流转、轮次 +1、幂等）

## Comments

- 2026-09-08 实现完成（commit d30edc6）：DRAFT 入 PostStatusVO；迁移 012 放开 post.category/content 可空 + init.sql 原地同步；submitDraft 条件 UPDATE（round+1、锚定 review_entered_at、刷新 expire_at，对齐商品）；提交走 PostPublishCase（信用分 + 完整性正文/分类必填 + 初筛命中停留草稿）；详情 rejectReason 对作者在 REJECTED 与 DRAFT 态均回显（转草稿自救仍可见理由，review 双子代理确认对齐票面字面要求）；"我的-发帖"纳入 DRAFT。新增 PostDraftBoxIntegrationTest 7 用例，全量 156 集成测试绿。联调库注意手工跑 migrations/012。
