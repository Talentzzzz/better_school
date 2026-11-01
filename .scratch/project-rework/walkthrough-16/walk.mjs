/**
 * 票 16 手工走查脚本（一次性）：驱动 front 的 vite dev server（5173），
 * 用夹具按「票 03/04/05/09 收口后的后端契约」应答 /api/**，
 * 在 390 宽移动视口与桌面视口逐页截图并做 DOM 断言（无死链 / 无空区块 / 无残留字样）。
 *
 * 依赖：playwright + chromium。优先用本仓/本机已装的 playwright；找不到时扫描 npx 缓存，
 * 且只认「浏览器可执行文件真实存在」的那一份（版本与缓存不匹配的会被跳过）。
 * 准备浏览器：`npx playwright install chromium`，或 `PLAYWRIGHT_ROOT=<含 node_modules 的目录>`。
 *
 * 运行：node .scratch/project-rework/walkthrough-16/walk.mjs
 */
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/** 解析可用的 playwright：常规 require → PLAYWRIGHT_ROOT → npx 缓存（挑浏览器真存在的那份） */
function resolvePlaywright() {
  const candidates = []
  const req = createRequire(import.meta.url)
  try {
    candidates.push({ label: 'require("playwright")', load: () => req('playwright') })
  } catch {
    // 本仓未装依赖，落到下面几条
  }
  if (process.env.PLAYWRIGHT_ROOT) {
    const root = process.env.PLAYWRIGHT_ROOT
    candidates.push({
      label: `PLAYWRIGHT_ROOT=${root}`,
      load: () => createRequire(path.join(root, 'noop.js'))('playwright')
    })
  }
  const caches = [
    path.join(os.homedir(), 'AppData', 'Local', 'npm-cache', '_npx'),
    path.join(os.homedir(), '.npm', '_npx')
  ]
  for (const cache of caches) {
    if (!fs.existsSync(cache)) continue
    for (const dir of fs.readdirSync(cache)) {
      const root = path.join(cache, dir, 'node_modules')
      if (!fs.existsSync(path.join(root, 'playwright', 'package.json'))) continue
      candidates.push({
        label: `npx cache ${dir}`,
        load: () => createRequire(path.join(root, 'noop.js'))('playwright')
      })
    }
  }
  for (const c of candidates) {
    try {
      const pw = c.load()
      // 版本与已下载浏览器不匹配时 executablePath() 指向不存在的文件，跳过
      if (fs.existsSync(pw.chromium.executablePath())) return pw
    } catch {
      // 试下一份
    }
  }
  throw new Error('未找到可用的 playwright/chromium；请先 `npx playwright install chromium`')
}

const { chromium } = resolvePlaywright()

const BASE = process.env.WALK_BASE || 'http://127.0.0.1:5173'
const OUT = path.resolve('.scratch/project-rework/walkthrough-16/shots')
fs.mkdirSync(OUT, { recursive: true })

// ---------- 路由表（从 router/index.ts 现读，避免走查里再抄一份） ----------
const ROUTE_PATTERNS = [...fs.readFileSync('front/src/router/index.ts', 'utf8').matchAll(/path:\s*'([^']+)'/g)]
  .map((m) => m[1])
  .filter((p) => !p.includes('pathMatch'))
  .map((p) => p.replace(/\([^)]*\)/g, '')) // 去掉 (\d+) 之类约束

/** 段位对齐的宽松匹配：路由段 `:x` 或来源里的 `${...}`（记作 X）都算命中 */
function pathMatches(pattern, candidate) {
  const ps = pattern.split('/')
  const cs = candidate.split('/')
  if (ps.length !== cs.length) return false
  return ps.every((seg, i) => seg === cs[i] || seg.startsWith(':') || cs[i] === 'X')
}

/** 来源里出现的站内跳转目标（router.push/replace、to=、path:）是否都落在路由表内 */
function staticLinkCheck() {
  const files = []
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name)
      if (e.isDirectory()) walk(p)
      else if (/\.(ts|vue)$/.test(e.name)) files.push(p)
    }
  }
  walk('front/src')

  const refs = []
  const push = (file, link) => {
    const clean = link.replace(/\$\{[^}]*\}/g, 'X').split(/[?#]/)[0]
    if (clean.length > 1) refs.push({ file, link: clean })
  }
  for (const f of files) {
    const src = fs.readFileSync(f, 'utf8')
    for (const m of src.matchAll(/router\.(?:push|replace)\(\s*[`'"]([^`'"]*)/g)) push(f, m[1])
    for (const m of src.matchAll(/(?:path|to):\s*'(\/[^']*)'/g)) push(f, m[1])
    for (const m of src.matchAll(/\sto="([^"]*)"/g)) push(f, m[1])
  }
  const checked = refs.filter((r) => r.link.startsWith('/') && !r.link.includes('pathMatch'))
  const dead = checked.filter((r) => !ROUTE_PATTERNS.some((p) => pathMatches(p, r.link)))
  return {
    routeCount: ROUTE_PATTERNS.length,
    refsChecked: checked.length,
    dead: dead.map((d) => `${path.relative(process.cwd(), d.file).replace(/\\/g, '/')} -> ${d.link}`)
  }
}

// ---------- 夹具（对齐收口后的后端契约） ----------
const page = (list) => ({ code: '200', info: '', data: { page: 1, pageSize: 10, total: list.length, list } })
const ok = (data) => ({ code: '200', info: '', data })

const ME = { userId: 1, phone: '13800000000', nickname: '我', avatar: null, role: 'USER', status: 'NORMAL', creditScore: 90, lastLoginAt: null }

const errandPublished = {
  orderId: 1, publisherId: 2, publisherNickname: '张同学', publisherAvatar: null,
  takerId: null, takerNickname: null, takerAvatar: null,
  type: 'EXPRESS_PICKUP', description: '帮取菜鸟驿站两个快递，送到 6 号楼楼下',
  pickupLocation: '菜鸟驿站', deliveryLocation: '6 号楼楼下',
  reward: 20, currentTakers: 0, deadline: '2026-10-06T12:00:00',
  status: 'PUBLISHED', createdAt: '2026-10-05T10:00:00', images: []
}
const errandCompleted = {
  ...errandPublished, orderId: 2, status: 'COMPLETED',
  takerId: 3, takerNickname: '李同学', takerAvatar: null, currentTakers: 1
}
const product = {
  productId: 1, userId: 2, sellerNickname: '张同学', sellerAvatar: null,
  title: '考研数学全套资料', description: '有少量笔记，无划线',
  images: [], price: 45, tradeLocation: '图书馆一楼', category: 'BOOKS',
  status: 'ON_SALE', viewCount: 12, favoriteCount: 3, favorited: false,
  createdAt: '2026-10-05T10:00:00'
}
const roomErrand = {
  roomId: 11, peerUserId: 2, peerNickname: '张同学', peerAvatar: null,
  peerRole: 'TAKER', source: 'ERRAND', unread: 1,
  lastMsgType: 'TEXT', lastMsgPreview: '好的，马上到', lastMsgAt: '2026-10-05T11:00:00'
}
const roomProduct = {
  roomId: 12, peerUserId: 3, peerNickname: '李同学', peerAvatar: null,
  peerRole: 'SELLER', source: 'PRODUCT', unread: 0,
  lastMsgType: 'TEXT', lastMsgPreview: '可以便宜点吗', lastMsgAt: '2026-10-05T09:00:00'
}
const notice = {
  id: 1, type: 'ORDER_EVENT', title: '订单动态', content: '有同学接下了你的跑腿单',
  read: false, refType: 'ERRAND', refId: 1, createdAt: '2026-10-05T11:00:00'
}
const post = {
  postId: 1, title: '失物招领：图书馆捡到一张饭卡', cover: null, userId: 2, nickname: '张同学',
  avatar: null, likeCount: 2, createdAt: '2026-10-05T08:00:00', viewCount: 5,
  content: '在图书馆三楼捡到一张饭卡，请联系我'
}

/** pathname → 夹具；未命中的 GET 落空分页（避免页面因 undefined 崩） */
function fixture(url) {
  const p = url.pathname
  if (p === '/api/wallet/balance') return ok({ balance: 1000, frozenAmount: 0 })
  if (p === '/api/credit/my') return ok({ creditScore: 90, status: 'NORMAL' })
  if (p === '/api/chat/rooms') return page([roomErrand, roomProduct])
  if (p === '/api/chat/notifications/unread-count') return ok({ count: 1 })
  if (p === '/api/chat/notifications') return page([notice])
  if (p === '/api/errand/orders') return page([errandPublished, errandCompleted])
  if (p === '/api/errand/orders/mine') return page([errandPublished, errandCompleted])
  if (p === '/api/errand/orders/1') return ok(errandPublished)
  if (p === '/api/errand/orders/2') return ok(errandCompleted)
  if (p === '/api/trade/products') return page([product])
  if (p === '/api/trade/products/1') return ok(product)
  if (p === '/api/trade/orders/mine') return page([])
  if (p === '/api/community/posts') return page([post])
  if (p === '/api/community/posts/mine') return page([post])
  if (p === '/api/community/posts/liked') return page([])
  if (p === '/api/community/posts/commented') return page([])
  if (p === '/api/user/1/profile') {
    return ok({
      userId: 1, nickname: '我', avatar: null, signature: null, gender: null,
      receivedLikeCount: 0, showLiked: true, showComments: true
    })
  }
  return page([])
}

// ---------- 走查清单 ----------
const TARGETS = [
  { id: '01-home', label: '首页', path: '/home' },
  { id: '02-market-errand', label: '市集·跑腿', path: '/market' },
  {
    id: '03-market-idle', label: '市集·闲置', path: '/market',
    steps: async (p) => {
      // 只点顶部 tab 条里的「闲置」，避免撞上入口行/卡片里的同名文案
      await p.locator('.nav-tabs .nav-tab', { hasText: '闲置' }).click({ timeout: 5000 })
      await p.waitForTimeout(500)
    }
  },
  { id: '04-publish-index', label: '发布索引', path: '/publish' },
  { id: '05-messages', label: '消息', path: '/messages' },
  { id: '06-market-messages', label: '消息·市集分区', path: '/market/messages' },
  { id: '07-notifications', label: '系统通知', path: '/notifications?tab=system' },
  { id: '08-my-orders', label: '我的·订单', path: '/me/orders' },
  { id: '09-errand-publish', label: '跑腿发布', path: '/publish/errand' },
  { id: '10-errand-detail', label: '跑腿详情（待接单）', path: '/errand/1' },
  { id: '11-errand-detail-done', label: '跑腿详情（已完成）', path: '/errand/2' },
  { id: '12-product-publish', label: '商品发布', path: '/publish/product' },
  { id: '13-product-detail', label: '商品详情', path: '/product/1' },
  { id: '14-profile', label: '我的', path: '/profile' }
]

/** 已删路由：应被 catch-all 兜回 /home（无白屏/无 404 空页） */
const REMOVED_ROUTES = ['/publish/want', '/want/1', '/chat/11/members']

const FORBIDDEN = ['求购', '群', '成色', '评价', '拉新', '邀请', 'want', 'WANT_TO_BUY', 'MARKET_WANT', 'ChatMembers', 'referral']

const DOM_ASSERTIONS = {
  '01-home': (t) => [
    ['首页发现流渲染（帖卡 + 分类 chips）', t.includes('发现') && t.includes('最热') && t.includes('失物招领：图书馆捡到一张饭卡')]
  ],
  '02-market-errand': (t) => {
    const tabs = ['跑腿', '闲置'].filter((x) => t.includes(x))
    return [
      ['市集顶部只剩两个 tab', tabs.length === 2 && !t.includes('求购')],
      ['跑腿卡片渲染（悬赏 ¥20）', t.includes('¥20')]
    ]
  },
  '03-market-idle': (t) => [
    ['闲置卡片渲染（商品标题）', t.includes('考研数学全套资料')],
    ['商品卡片不再出现成色标签', !t.includes('九成新') && !t.includes('八成新')]
  ],
  '04-publish-index': (t) => [
    ['发布索引只剩 3 个入口', !t.includes('发求购') && t.includes('发帖子') && t.includes('卖闲置') && t.includes('发跑腿')]
  ],
  '05-messages': (t) => [
    ['私信行渲染（对家昵称）', t.includes('张同学') && t.includes('李同学')],
    ['系统消息行渲染', t.includes('系统消息')],
    ['私信行不再有群标签', !t.includes('群')]
  ],
  '06-market-messages': (t) => [
    ['市集消息只剩跑腿/闲置两栏', t.includes('跑腿') && t.includes('闲置') && !t.includes('求购')],
    ['跑腿系统消息行渲染', t.includes('系统消息')]
  ],
  '07-notifications': (t) => [
    ['系统通知分段渲染通知行', t.includes('系统通知') && t.includes('订单动态') && t.includes('有同学接下了你的跑腿单')]
  ],
  '08-my-orders': (t) => [
    ['订单页只剩跑腿/闲置两 tab', t.includes('跑腿') && t.includes('闲置') && !t.includes('求购')],
    ['跑腿订单行按单人悬赏渲染', t.includes('¥20') && !t.includes('每人')]
  ],
  '09-errand-publish': (t) => [
    ['跑腿发布无「需要人数」区块', !t.includes('需要人数')],
    ['悬赏输入区在位', t.includes('悬赏（校园币）')]
  ],
  '10-errand-detail': (t) => [
    ['详情显示悬赏金额', t.includes('悬赏金额') && t.includes('¥20')],
    ['详情无人数行', !t.includes('人数')],
    ['详情无评价区块', !t.includes('互评') && !t.includes('暂无评价')],
    ['详情无协作群入口', !t.includes('协作群')],
    ['待接单操作区只有抢单', t.includes('立即抢单')]
  ],
  '11-errand-detail-done': (t) => [
    ['完成态不再出现评价入口', !t.includes('评价') && !t.includes('互评')],
    ['完成态保留订单已完成提示', t.includes('订单已完成')]
  ],
  '12-product-publish': (t) => [
    ['商品发布无成色 chips', !t.includes('成色') && !t.includes('几乎全新')],
    ['分类 chips 仍在位', t.includes('分类') && t.includes('数码')]
  ],
  '13-product-detail': (t) => [
    ['商品详情标签行只剩分类/浏览', t.includes('图书') && t.includes('浏览 12')],
    ['详情不再渲染成色标签', !t.includes('九成新') && !t.includes('八成新')]
  ],
  '14-profile': (t) => [
    ['「我的」主 tab 只剩笔记/赞过/评论/闲置/跑腿', t.includes('笔记') && t.includes('闲置') && t.includes('跑腿') && !t.includes('求购')],
    ['资料区与钱包/信用分区块在位', t.includes('获赞') && t.includes('钱包') && t.includes('信用分')]
  ]
}

const viewports = [
  { key: 'mobile', width: 390, height: 844, isMobile: true, scale: 2 },
  { key: 'desktop', width: 1280, height: 800, isMobile: false, scale: 1 }
]

const results = []
const browser = await chromium.launch()

for (const vp of viewports) {
  const context = await browser.newContext({
    viewport: { width: vp.width, height: vp.height },
    deviceScaleFactor: vp.scale,
    isMobile: vp.isMobile,
    hasTouch: vp.isMobile,
    locale: 'zh-CN'
  })
  await context.addInitScript(
    ([token, user]) => {
      localStorage.setItem('bs_token', token)
      localStorage.setItem('bs_user', user)
    },
    ['walkthrough-token', JSON.stringify(ME)]
  )
  await context.route((u) => u.pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    if (req.method() !== 'GET') return route.fulfill({ json: ok(null) })
    const body = fixture(new URL(req.url()))
    return route.fulfill({ json: body })
  })

  const pageObj = await context.newPage()

  for (const target of TARGETS) {
    const consoleErrors = []
    const pageErrors = []
    const onConsole = (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) }
    const onPageError = (e) => pageErrors.push(String(e))
    pageObj.on('console', onConsole)
    pageObj.on('pageerror', onPageError)

    await pageObj.goto(`${BASE}${target.path}`, { waitUntil: 'domcontentloaded' })
    await pageObj.waitForTimeout(800)
    let stepError = null
    if (target.steps) {
      try {
        await target.steps(pageObj)
      } catch (e) {
        stepError = String(e).split('\n')[0]
      }
    }
    await pageObj.waitForTimeout(300)

    const text = await pageObj.locator('body').innerText()
    const url = new URL(pageObj.url())
    const forbiddenHits = FORBIDDEN.filter((w) => text.includes(w))
    const asserts = (DOM_ASSERTIONS[target.id] ? DOM_ASSERTIONS[target.id](text) : [])
    // 页内 <a href> 全量收集：逐个对路由表，页面自己就带死链的话这里能抓到
    const hrefs = await pageObj.$$eval('a[href]', (els) => els.map((e) => e.getAttribute('href') || ''))
    const pageLinks = [
      ...new Set(
        hrefs.filter((h) => h.startsWith('/') && !h.startsWith('//')).map((h) => h.split(/[?#]/)[0])
      )
    ]
    const pageDeadLinks = pageLinks.filter((l) => !ROUTE_PATTERNS.some((p) => pathMatches(p, l)))
    const shot = path.join(OUT, `${vp.key}-${target.id}.png`)
    await pageObj.screenshot({ path: shot, fullPage: true })

    results.push({
      viewport: vp.key, id: target.id, label: target.label, path: target.path,
      landedPath: url.pathname + url.search, shot: path.relative(process.cwd(), shot).replace(/\\/g, '/'),
      forbiddenHits, assertions: asserts.map(([name, pass]) => ({ name, pass })),
      pageLinks, pageDeadLinks,
      consoleErrors, pageErrors,
      stepError,
      textSample: text.slice(0, 200).replace(/\s+/g, ' ')
    })

    pageObj.off('console', onConsole)
    pageObj.off('pageerror', onPageError)
    console.log(`[${vp.key}] ${target.id} landed=${url.pathname}${url.search} forb=${forbiddenHits.join(',') || '-'} stepErr=${stepError || '-'} console=${consoleErrors.length} pageErr=${pageErrors.length}`)
  }

  // 已删路由：兜回 /home，页面不应白屏
  for (const removed of REMOVED_ROUTES) {
    await pageObj.goto(`${BASE}${removed}`, { waitUntil: 'domcontentloaded' })
    await pageObj.waitForTimeout(500)
    const landed = new URL(pageObj.url()).pathname
    const text = await pageObj.locator('body').innerText()
    const shot = path.join(OUT, `${vp.key}-zz-removed-${removed.replace(/\W/g, '_')}.png`)
    await pageObj.screenshot({ path: shot, fullPage: true })
    results.push({
      viewport: vp.key, id: `removed${removed}`, label: `已删路由 ${removed}`, path: removed,
      landedPath: landed, shot: path.relative(process.cwd(), shot).replace(/\\/g, '/'),
      forbiddenHits: [],
      assertions: [{ name: `已删路由兜回 /home 且非空页`, pass: landed === '/home' && text.trim().length > 0 }],
      pageLinks: [], pageDeadLinks: [],
      consoleErrors: [], pageErrors: []
    })
  }

  await context.close()
}

await browser.close()

const linkCheck = staticLinkCheck()
const report = path.resolve('.scratch/project-rework/walkthrough-16/report.json')
fs.writeFileSync(report, JSON.stringify({ staticLinkCheck: linkCheck, records: results }, null, 2))

console.log(
  `\n[静态死链检查] 路由表 ${linkCheck.routeCount} 条；来源内站内跳转 ${linkCheck.refsChecked} 处；死链 ${linkCheck.dead.length} 处`
)
for (const d of linkCheck.dead) console.log(`        - ${d}`)

let fail = 0
for (const r of results) {
  const badAsserts = r.assertions.filter((a) => !a.pass)
  const problems = [
    ...badAsserts.map((a) => `断言失败: ${a.name}`),
    ...(r.forbiddenHits.length ? [`页面出现残留字样: ${r.forbiddenHits.join(',')}`] : []),
    ...(r.deadLinks?.length ? [`页内死链: ${r.deadLinks.join(',')}`] : []),
    ...(r.pageDeadLinks?.length ? [`页内 <a> 死链: ${r.pageDeadLinks.join(',')}`] : []),
    ...(r.consoleErrors.length ? [`console error: ${r.consoleErrors.slice(0, 2).join(' | ')}`] : []),
    ...(r.pageErrors.length ? [`page error: ${r.pageErrors.slice(0, 2).join(' | ')}`] : [])
  ]
  if (problems.length) fail++
  const status = problems.length ? 'FAIL' : 'ok  '
  console.log(`[${status}] ${r.viewport} ${r.id} (${r.landedPath}) -> ${path.basename(r.shot)}`)
  for (const p of problems) console.log(`        - ${p}`)
}
const assertions = results.reduce((s, r) => s + r.assertions.length, 0)
console.log(
  `\n共 ${results.length} 条走查记录 / ${assertions} 条断言，${fail} 条有问题；页内 <a> 共 ${results.reduce((s, r) => s + r.pageLinks.length, 0)} 处已核。报告: ${path.relative(process.cwd(), report)}`
)
process.exitCode = fail || linkCheck.dead.length ? 1 : 0
