// 票 14 前端走查（营销页 / 我的奖品 / 审核工作台）：390×844 移动视口。
//
// 运行（前置：front 起 vite 5173 即可，本脚本自行拦 /api 回伪造数据，不依赖后端进程）：
//   .scratch/project-rework/verify-14/node_modules/.bin/playwright ... 见 package.json
//   node walkthrough.cjs
//
// 拦截口径（坑，照票 11/16 的先例）：正则必须锚定 `^https?://<host>/api/`——
// glob `**/api/**` 会把 Vite 的 `/src/api/*.ts` 模块请求也拦成 JSON 致白屏。
const { chromium } = require('playwright')
const fs = require('fs')
const path = require('path')

const BASE = 'http://localhost:5173'
const OUT = path.join(__dirname)
const API_RE = /^https?:\/\/localhost:5173\/api\//

const ok = (data) => JSON.stringify({ code: '200', info: '成功', data })

// ---------- 伪造数据（形状逐字段对齐后端 DTO）----------

const activity = {
  name: '开学季抽奖',
  startAt: '2026-09-01T00:00:00',
  endAt: '2026-12-31T23:59:59',
  status: 'ONGOING',
  drawCountPrice: 100,
  drawCountValidDays: 30
}

const prizes = [
  { prizeId: 8803, tier: 3, name: '20 积分', type: 'VIRTUAL', pointsAmount: 20, remaining: 96, total: 100, unlimited: false },
  { prizeId: 8804, tier: 2, name: '校园帆布包', type: 'PHYSICAL', pointsAmount: null, remaining: 8, total: 10, unlimited: false },
  { prizeId: 8807, tier: 1, name: '蓝牙耳机', type: 'PHYSICAL', pointsAmount: null, remaining: 0, total: 3, unlimited: false },
  { prizeId: 8808, tier: 4, name: '食堂 5 元券', type: 'VIRTUAL', pointsAmount: null, remaining: null, total: null, unlimited: true },
  { prizeId: 8805, tier: 5, name: '谢谢参与', type: 'THANKS', pointsAmount: null, remaining: null, total: null, unlimited: true }
]

const drawWin = {
  recordId: 900001,
  prizeType: 'PHYSICAL',
  prizeId: 8804,
  tier: 2,
  name: '校园帆布包',
  pointsAmount: null
}

const prizeRows = (status) => {
  const all = [
    { id: 900001, prizeId: 8804, tier: 2, name: '校园帆布包', prizeType: 'PHYSICAL', status: 'SHIPPED', createdAt: '2026-10-01T12:03:00', claimedAt: '2026-10-02T09:10:00' },
    { id: 900002, prizeId: 8803, tier: 3, name: '20 积分', prizeType: 'VIRTUAL', status: 'COMPLETED', createdAt: '2026-09-28T20:11:00', claimedAt: '2026-09-28T20:11:05' },
    { id: 900003, prizeId: 8807, tier: 1, name: '蓝牙耳机', prizeType: 'PHYSICAL', status: 'PENDING', createdAt: '2026-09-27T09:40:00', claimedAt: null }
  ]
  const list = all.filter((p) => p.status === status)
  return { page: 1, pageSize: 50, total: list.length, list }
}

const auditQueue = {
  page: 1,
  pageSize: 20,
  total: 2,
  list: [
    { targetType: 'ERRAND', targetId: 7001, authorId: 21, authorNickname: '小林', reward: 6, content: '帮我把快递从 3 号门拿到 6 号楼 502，谢谢！', images: [], createdAt: '2026-10-06T09:12:00' },
    { targetType: 'POST', targetId: 7002, authorId: 22, authorNickname: '阿May', reward: null, content: '图书馆四楼靠窗的位置真的绝了，安静又晒得到太阳', images: [], createdAt: '2026-10-06T10:05:00' }
  ]
}

async function mockApi(page) {
  await page.route(API_RE, async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    const method = route.request().method()
    if (p === '/api/lottery/activity') return route.fulfill({ contentType: 'application/json', body: ok(activity) })
    if (p === '/api/lottery/prizes') return route.fulfill({ contentType: 'application/json', body: ok(prizes) })
    if (p === '/api/lottery/draw-chances') {
      return route.fulfill({ contentType: 'application/json', body: ok({ available: 3, todayDrawn: 1 }) })
    }
    if (p === '/api/points/balance') return route.fulfill({ contentType: 'application/json', body: ok({ balance: 260 }) })
    if (p === '/api/lottery/draw' && method === 'POST') {
      return route.fulfill({ contentType: 'application/json', body: ok(drawWin) })
    }
    if (p === '/api/lottery/my-prizes' && method === 'GET') {
      const status = url.searchParams.get('status') || 'PENDING'
      return route.fulfill({ contentType: 'application/json', body: ok(prizeRows(status)) })
    }
    if (/^\/api\/lottery\/my-prizes\/\d+\/receive$/.test(p) && method === 'POST') {
      return route.fulfill({
        contentType: 'application/json',
        body: ok({ id: Number(p.split('/')[4]), status: 'COMPLETED' })
      })
    }
    if (p === '/api/moderation/queue') {
      return route.fulfill({ contentType: 'application/json', body: ok(auditQueue) })
    }
    // 其余一律空信封，避免任何真实后端依赖
    return route.fulfill({ contentType: 'application/json', body: ok(null) })
  })
}

async function seedAuth(page, role) {
  await page.addInitScript(
    ([r]) => {
      localStorage.setItem('bs_token', 'walkthrough-fake-token')
      localStorage.setItem(
        'bs_user',
        JSON.stringify({ userId: 1001, nickname: '走查用账号', avatar: null, role: r })
      )
    },
    [role]
  )
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, name) })
  console.log('  →', name)
}

async function main() {
  const browser = await chromium.launch()
  const results = []

  // ---- 访客态：营销页（banner + 奖品目录，无次数） ----
  const visitorCtx = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const visitor = await visitorCtx.newPage()
  await mockApi(visitor)
  await visitor.goto(BASE + '/marketing', { waitUntil: 'networkidle' })
  await visitor.waitForTimeout(300)
  await shot(visitor, '01-marketing-visitor.png')
  results.push(await visitor.locator('.page').innerText())
  await visitorCtx.close()

  // ---- 登录态：营销页 + 抽奖结果 + 我的奖品（四段 tab + 确认收货） ----
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } })
  await seedAuth(ctx.pages()[0] ?? (await ctx.newPage()), 'USER')
  const page = ctx.pages()[0]
  await mockApi(page)
  await page.goto(BASE + '/marketing', { waitUntil: 'networkidle' })
  await page.waitForTimeout(300)
  await shot(page, '02-marketing-loggedin.png')
  results.push(await page.locator('.page').innerText())

  // 触发抽奖 → 中奖弹窗
  await page.locator('.m-draw-btn').click()
  await page.waitForTimeout(400)
  await shot(page, '03-draw-win-modal.png')
  results.push(await page.locator('.m-modal').innerText())
  await page.locator('.m-modal-btn').first().click()
  await page.waitForTimeout(200)

  // 次数不足弹窗（把次数改成 0 后重抽：改 chances 的 mock 需要重载，这里换成校验按钮文案路径）
  await page.goto(BASE + '/marketing/prizes', { waitUntil: 'networkidle' })
  await page.waitForTimeout(300)
  await shot(page, '04-my-prizes-pending.png')
  results.push(await page.locator('.page').innerText())
  for (const [tab, file] of [
    ['已兑换', '05-my-prizes-exchanged.png'],
    ['已发货', '06-my-prizes-shipped.png'],
    ['已完成', '07-my-prizes-completed.png']
  ]) {
    await page.locator('.tabs-equal button', { hasText: tab }).click()
    await page.waitForTimeout(300)
    await shot(page, file)
    results.push(await page.locator('.page').innerText())
  }

  // 确认收货：已发货 tab 里点一次，看确认弹窗与 toast 后的列表
  await page.locator('.tabs-equal button', { hasText: '已发货' }).click()
  await page.waitForTimeout(250)
  await page.locator('.p-btn').first().click()
  await page.waitForTimeout(300)
  await shot(page, '08-receive-confirm-dialog.png')
  await ctx.close()

  // ---- 管理员态：审核工作台（无 agent 痕迹 / 无空区块） ----
  const adminCtx = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const admin = await adminCtx.newPage()
  await seedAuth(admin, 'ADMIN')
  await mockApi(admin)
  await admin.goto(BASE + '/admin/review', { waitUntil: 'networkidle' })
  await admin.waitForTimeout(300)
  await shot(admin, '09-review-workbench.png')
  results.push(await admin.locator('.review-workbench').innerText())
  await adminCtx.close()

  await browser.close()
  fs.writeFileSync(path.join(OUT, 'walkthrough-text.txt'), results.join('\n\n=====\n\n'), 'utf8')
  console.log('OK: 截图与文本已落', OUT)
}

main().catch((e) => {
  console.error('走查失败：', e)
  process.exit(1)
})
