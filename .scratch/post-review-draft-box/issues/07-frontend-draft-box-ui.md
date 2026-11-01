# 07: 前端——草稿箱 UI + 退出弹窗

**What to build:** "我的-发帖"左上角第一个位置放草稿箱入口；草稿箱列表展示编辑中、被拒转入、待审撤回的帖子（被拒带拒绝理由与"转草稿"按钮，待审带撤回按钮）；草稿编辑页支持完整编辑（含封面）并提交进审核。发布页与草稿编辑页在用户中途退出时弹窗询问"是否存入草稿箱"。状态文案补草稿态。

**Blocked by:** 02: 帖子草稿箱四态流转（后端）；05: 封面独立列 + feed 摘要（后端）

**Status:** ready-for-human

- [x] "我的-发帖"左上角第一个为草稿箱入口，可进入草稿箱列表
- [x] 草稿箱列表：草稿可编辑/删除；被拒帖显示拒绝理由并可一键转草稿；待审帖可撤回
- [x] 草稿编辑页可编辑全部字段（含封面）并提交，缺必填项时提示
- [x] 发布页与草稿编辑页中途退出均弹窗询问是否存草稿；确认后草稿落库
- [x] 状态文案覆盖草稿/待审/被拒各态（清理商品侧已废弃的下架文案残留）
- [x] 浏览器实测全流程：写帖退出存草稿 → 草稿箱编辑提交 → 被拒后转草稿重提

## Comments

- 2026-09-08 实现完成：后端 PostSummaryResponseDTO 补 `rejectReason`（仅 mine 接口 REJECTED 行填充，草稿箱列表免二次取详情，PostDraftBoxIntegrationTest 补断言 7 用例绿）。前端：`/me/post-drafts` 草稿箱页（DRAFT 编辑/删除、REJECTED 理由+转草稿、PENDING_REVIEW 撤回）；发布页支持 `?draft=id` 草稿编辑模式（存草稿/提交审核双按钮）；发布页与草稿编辑页 onBeforeRouteLeave 脏态弹窗询问存草稿（保存失败留在本页防丢内容）；"我的-发帖"笔记 tab 左上角草稿箱入口（带在存数量角标）；状态文案 补 DRAFT 草稿、PENDING_REVIEW 改待审（对齐领域词汇），清理 OFF_SHELF/已下架残留（ProfileView、ProductDetailView 改映射 DRAFT 草稿）。vue-tsc 绿；浏览器实测全流程通过（写帖退出存草稿→编辑提交→撤回→初筛命中停留→管理员拒绝→理由展示→转草稿→重提→删除清理）。联调库补跑迁移 009（content_review.review_round 缺失导致裁决 999）。
- 2026-09-08 双轴评审修复：①mine 列表 rejectReason 改批量查询（IModerationRepository.findRejectReasons IN + MAX(id) 最新轮，对齐 likedIdsOf 免 N+1 惯例）②PostResponseDTO 加 `independentCover` 原始独立封面，草稿编辑回填不再用展示口径（避免"相册首图兜底"被固化为独立封面，浏览器复验通过）③发布页 formBody() 去重三处字面量 ④草稿箱状态集/文案抽 utils/postStatus.ts 共享。已知取舍：草稿编辑用 getPost 回填会使作者本人 viewCount +1（postDetail 既有口径，未动）；草稿箱列表沿用 pageSize=50 与"我的-发帖"一致。
