# 11: 前端·营销活动页 / 抽奖交互 / 我的奖品与中奖记录

**Spec:** `.scratch/marketing/spec.md` §「营销活动页与抽奖交互」+ §「我的奖品 / 中奖记录」+ §「营销域」→「接口口径与失败语义」

**What to build:** `/marketing` 活动页（七区块）+ 单抽三结果弹窗 + 我的奖品 / 中奖记录页，全部接三个营销服务的真实接口。

**Blocked by:** 05、06、07、08（活动页 7 区块并发调三家；抽奖与兑换/领奖全是这三家的写接口）

**Status:** resolved

### 一 · 活动页

- [x] 路由 `/marketing`，**不加 `requiresAuth`**（与 `/market` 一致）
- [x] 灰底单列竖排白卡，区块自上而下：
  1. **活动 banner**：渐变红整卡；活动名（21px 粗）+ 口号 + 底部左右两端「剩 N 天」「每日最多抽 N 次 · 今日已抽 N」
  2. **抽奖主卡**：居中的「我的抽奖次数」大数字（44px 主色）+ **整行**主按钮「抽一次」；下方小字「100 积分 = 1 次」+「用积分兑换次数 ›」
  3. **邀请码卡**：专属邀请码大字（20px、字距 4）+ 复制 / 分享两个次按钮；下方「已成功拉新 N 人」+ 头像并排（24px、间距 4）+「查看名单 ›」
  4. **填码卡**：输入框 + 提交；**只有这里能填码**；下方可展开「失败时会怎么提示」列出五种失败文案
  5. **积分卡**：余额大字 +「兑换奖品 ›」
  6. **奖品目录**：2 列网格；每格 = 方形奖品图 + 名称 +「剩 x/y」或「不限量」；**已兑完**档在图上右下角压黑底白字角标；可兑档显示「N 分可兑」
  7. **底部**：「我的奖品 / 中奖记录 ›」
- [x] **聚合读 = 前端并发调三家**（活动页 7 区块逐块归属干净：banner / 抽奖主卡 / 奖品目录 / 底部 → 抽奖，邀请码卡 / 填码卡 → 拉新，积分卡 → 积分），**不做 BFF**；各区块**各自的加载与失败态**
- [x] `front/src/api/marketing.ts` 调 `httpGet('/points/…')` 等；前端**单实例**（`http.ts` 的 `baseURL: '/api'` 不变）

### 二 · 抽奖交互

- [x] 点「抽一次」→ **居中弹窗**，三种结果：中奖（奖品色块 96px 圆角 + 档位小字 + 奖品名 + 主按钮「开心收下」）/ 谢谢参与（灰底色块 + 副文案「再攒几次，好运在路上」+「知道了」）/ 次数不足（灰底色块 + 引导文案 + 主按钮「去邀请好友」+ 次按钮「用积分兑换次数」）
- [x] 前端生成 uuid 随 `draw` 请求带上（`client_token`），**重放不重复抽**
- [x] 次数为 0 时抽奖主按钮的状态要收口（置灰 / 文案改「用积分兑换次数」）
- [x] **删掉原型里的调试控件**（真实结果由抽奖服务返回，不再切三种）
- [x] **补票 02 原型没画的两个态**：**访客态**（个人区块显示「登录后参与」，点抽奖 / 填码 / 兑换跳 `/login?redirect=/marketing`）与**兑换失败态**
- [x] **失败语义按业务 code 分支**：`api/marketing.ts` 按 code 分支（`NO_CHANCE` → 上面的「次数不足」专用弹窗、填码五码 → 对应文案），未识别 code 落回默认 toast `info`
- [x] **`front/src/api/http.ts` 的 `unwrap` 加一个「调用方自己处理失败」的开关**，否则业务 code 已被专用交互处理后默认 toast 还会再弹一次
- [x] 活动 `status` 为 `ENDED` 时页面切「已结束」，但**奖品目录与我的奖品仍可查**

### 三 · 我的奖品 / 中奖记录

- [x] 顶部三段等分 tab「待领取 / 已领取 / 积分入账」（映射 `PENDING` / `RECEIVED` / `CREDITED`）
- [x] 列表行 = 奖品图（56px 色块）+ 档位小字（主色）+ 名称 + 「中奖于 …」+ 右侧状态字与「去领取」按钮（**仅待领取**有按钮）
- [x] 领取表单：底部上滑面板 —— 面板标题 + 奖品摘要行 + 三个字段（收件人 / 手机号 11 位 / 收货地址 textarea）+ 提示「提交后奖品将进入待发放，实物不可退换」+ 提交 / 取消
- [x] 中奖记录：**过滤谢谢参与**（后端已过滤），`created_at DESC`
- [x] 「积分入账」tab **不回填收件信息**
- [x] `npm run build`（`vue-tsc`）通过；双视口目视验收（含访客态、兑换失败态、已结束态），截图存 `verify-11/`
- [x] 视觉照 `prototype/marketing` 分支的 `MarketingVariantA.vue` / `PrizesA.vue`（变体 A：叙事流、抽奖主卡居中；我的奖品三段 tab + 底部上滑领取表单）

## 实现要点（票 11）

**落地面**（全部 `front/`，无后端改动）：新增 `api/marketing.ts`（三服务接口封装 + code 常量 + 逐条固定文案表 + TIER_TEXT/PRIZE_TINTS）、`views/marketing/MarketingView.vue`（七区块 + 三结果弹窗 + 兑换失败弹窗）、`views/marketing/MyPrizesView.vue`（三段 tab + 底部上滑领取表单）；`api/http.ts` 的 `unwrap` 加 `silent` 开关（`httpGet/httpPost` 第三参改为 `AxiosRequestConfig & { silent }` 交叉类型，`trade.ts` 传 `{ params }` 的旧调用不受影响）；`router` 加 `/marketing`（无 requiresAuth）与 `/marketing/prizes`；`utils/format.ts` 加 `formatDateTime`（精确到分的绝对时间，`formatTime` 是相对时间不适合「中奖于」）。

**三个裁量（票面/spec 没定死的）**：
1. **「中奖记录」与「我的奖品」是同一页**：三个原型变体都只有三段 tab，且每次非 THANKS 抽奖都会落 `lottery_user_prize`（中奖与兑换同表），`/api/lottery/records` 对本前端**无独立消费面**（THANKS 过滤与 `created_at DESC` 由 my-prizes 接口天然承接）；records 留作审计/后续入口。
2. **「查看名单 ›」= 卡片内联展开**（前 20 条：昵称 + 加入日期）——原型是死链，票面未定去向，不值得单开一页。
3. **兑换入口 = 点奖品目录里可兑档色块 → ConfirmDialog → 兑换**；「用积分兑换次数」同样走确认弹窗。**兑换失败态**（票 02 原型没画）补成灰底居中弹窗（与抽奖弹窗同壳），文案 `EXCHANGE_FAIL_TEXT` 按 code 取，未收录 code 落回默认 toast。
4. **分享** = `navigator.share`，不支持的环境回退复制。

**验收**：`npm run build`（vue-tsc）通过；Playwright 拦 `/api` 回伪造数据，双视口（390×844 / 1280×800）目视 16 张截图存 `verify-11/`——访客态（四个人区块「登录后参与」+ 点抽奖跳 `/login?redirect=/marketing`）、登录态七区块全渲染、中奖/谢谢参与/次数不足三弹窗（含次数 0 后主按钮收口成「用积分兑换次数」）、兑换次数成功 toast 与兑奖品「积分不足」失败弹窗、填码成功 + 4205 固定文案 toast、查看名单展开、ENDED 态（banner「已结束」+ 主按钮/提交禁用 + 目录仍可查）、我的奖品三段 tab + 领取表单提交后 toast、桌面视口。**坑**：Playwright `page.route` 的 mock 正则必须锚定 `^https?://host/api/`（否则 `/src/api/*.ts` 模块请求被拦成 JSON 全页白屏），且 `lottery/draw` 未锚定结尾时会连 `draw-chances` 一起拦（路由按注册逆序匹配）。

**code-review 采纳 4 条**：① 填码框 `maxlength` 6→**8**（后端 `Constants.INVITE_CODE_LENGTH = 8`，原型 mock 的 6 位码是错的，照抄会不可用——本票最重发现）；② MyPrizesView `load()` 补 catch（防 unhandled rejection）；③ 两页奖品取色统一按 `prizeId % PRIZE_TINTS.length`（原目录按序号、奖品页按 id，同一奖品两页可能异色）；④ 删掉中奖弹窗里 spec 没写的「+N 积分已入账」行。
**未采纳**：五个 `loadXxx` 抽共享（spec 明确要各区块独立加载/失败态，显式五份比参数化更直白）；两弹窗收壳组件（仅两处且结构差异在演化）；`showErrors` 改名（语义直白）。

**已知波及 / 不在本票范围**：真实跨服务链路（三服务 + 单体真起）的联调验证归联调；「抽奖按钮动效与音效」map 里留的待定项——本票未做（弹窗直出，票面只定「点下去弹什么」）；入口行「活动」格（票 04 已指 `/marketing`）自此生效。
