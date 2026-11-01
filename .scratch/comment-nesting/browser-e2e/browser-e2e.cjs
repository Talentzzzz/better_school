// 评论区 v2 遗留浏览器端到端验证（票 03/04/05/06/07 前端点击链路）
// 运行：node browser-e2e.cjs （前置：docker 中间件 + 8080 jar + vite 5173）
const { chromium } = require('playwright')
const { execSync } = require('child_process')

const BASE = 'http://localhost:5173'
const API = BASE + '/api'
const ts = Date.now() % 100000000

async function api(method, path, body, token) {
  const res = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body == null ? undefined : JSON.stringify(body)
  })
  return res.json()
}

function readCode(phone) {
  const buf = execSync(
    `docker exec bs-redis redis-cli -a bs123456 GET bs:verify-code:${phone}`,
    { encoding: 'buffer' })
  // Redisson Kryo 编码：值非明文，数字字符直取、末字节高位清除（同 smoke06.py；先去 redis-cli 尾部换行）
  let end = buf.length
  while (end > 0 && (buf[end - 1] === 0x0a || buf[end - 1] === 0x0d)) end--
  let digits = ''
  for (let i = 0; i < end; i++) {
    const b = buf[i]
    if (b >= 48 && b <= 57) digits += String.fromCharCode(b)
  }
  if (end > 0 && buf[end - 1] & 0x80) digits += String.fromCharCode(buf[end - 1] & 0x7f)
  return digits.slice(-6)
}

async function register(phone, nickname) {
  await api('POST', '/user/send-code', { phone })
  const code = readCode(phone)
  const res = await api('POST', '/user/register', { phone, code, nickname })
  if (res.code !== '200') throw new Error('register failed: ' + JSON.stringify(res))
  return { token: res.data.token, userId: res.data.userInfo.userId, nickname }
}

let passed = 0, failed = 0, fatal = false, browser = null
function check(name, ok, detail) {
  if (ok) { passed++; console.log('PASS ' + name) }
  else { failed++; console.log('FAIL ' + name + (detail ? ' | ' + detail : '')) }
}
async function checkWait(name, fn, timeoutMs) {
  try { await waitFor(fn, timeoutMs, name); check(name, true) }
  catch (e) { check(name, false, e.message) }
}

async function waitFor(fn, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  let lastErr
  while (Date.now() < deadline) {
    try { if (await fn()) return true } catch (e) { lastErr = e }
    await new Promise(r => setTimeout(r, 800))
  }
  throw new Error('timeout waiting: ' + label + (lastErr ? ' last=' + lastErr.message : ''))
}

;(async () => {
  // ---------- API 造数 ----------
  const A = await register('139' + String(ts).padStart(8, '0'), 'e2eA' + ts)
  const B = await register('139' + String(ts + 1).padStart(8, '0'), 'e2eB' + ts)
  const post = await api('POST', '/community/posts', { title: 'e2e-comment-' + ts, content: 'browser e2e', category: 'DAILY' }, A.token)
  const pid = post.data
  const rootContents = [1, 2, 3, 4].map(i => 'e2e-root-' + i + '-' + ts)
  const rootIds = []
  for (const c of rootContents) {
    rootIds.push((await api('POST', `/community/posts/${pid}/comments`, { content: c }, B.token)).data.commentId)
  }
  // R1 楼内：回复根 + 回复楼内子回复（深层）+ 3 条根级回复，共 5 条
  const replyRoot = (await api('POST', `/community/comments/${rootIds[0]}/replies`, { content: 'e2e-reply-a-' + ts }, B.token)).data.commentId
  const replyDeep = (await api('POST', `/community/comments/${replyRoot}/replies`, { content: 'e2e-reply-deep-' + ts }, B.token)).data.commentId
  for (let i = 0; i < 3; i++) {
    await api('POST', `/community/comments/${rootIds[0]}/replies`, { content: `e2e-reply-m${i}-` + ts }, B.token)
  }
  // 异步审核：轮询直到 4 根全部可见且 R1 楼 5 条回复齐
  await waitFor(async () => {
    const d = (await api('GET', `/community/posts/${pid}/comments?page=1&pageSize=20&sort=new`, null, A.token)).data
    const r1 = d.list.find(c => c.commentId === rootIds[0])
    return d.list.length >= 4 && r1 && r1.replyCount === 5
  }, 40000, 'async review approve roots+replies')
  console.log('SETUP OK pid=' + pid)

  // ---------- 浏览器 ----------
  browser = await chromium.launch()
  const mkCtx = (u) => browser.newContext().then(async ctx => {
    await ctx.addInitScript(([token, userId, nickname]) => {
      localStorage.setItem('bs_token', token)
      localStorage.setItem('bs_user', JSON.stringify({ userId, nickname }))
    }, [u.token, u.userId, u.nickname])
    return ctx
  })
  const ctxA = await mkCtx(A), ctxB = await mkCtx(B)
  const pA = await ctxA.newPage(), pB = await ctxB.newPage()
  const url = `${BASE}/post/${pid}`
  const rootItem = (page, content) => page.locator('.comment-item', { hasText: content })

  // ===== 票 04：头部 N + 默认最热 tab =====
  await pA.goto(url, { waitUntil: 'networkidle' })
  const header = await pA.locator('.comment-header-title').innerText()
  check('04-header-N(9)', header === '全部评论(9)', header)
  const activeTab = await pA.locator('.sort-tab.active').innerText()
  check('04-default-hot-tab', activeTab === '最热', activeTab)
  const firstHot = await pA.locator('.comment-item .comment-content').first().innerText()
  check('04-degenerate-hot-id-asc', firstHot === rootContents[0], firstHot)

  // ===== 票 03：展开楼 / 平铺 / ▶ 指向 / 展开更多 / 收起 =====
  const r1Item = rootItem(pA, rootContents[0])
  await r1Item.locator('.thread-toggle').click()
  await pA.locator('.reply-item').first().waitFor()
  let replyCount = await pA.locator('.reply-item').count()
  check('03-thread-first-page-3', replyCount === 3, 'got ' + replyCount)
  check('03-has-expand-more', await pA.getByText('展开更多回复').count() === 1)
  const deepReply = pA.locator('.reply-item', { hasText: 'e2e-reply-deep-' + ts })
  const tag = await deepReply.locator('.reply-tag').innerText()
  check('03-deep-reply-point-tag', tag === '▶ ' + B.nickname, tag)
  const flatReply = pA.locator('.reply-item', { hasText: 'e2e-reply-a-' + ts })
  check('03-reply-to-root-no-tag', await flatReply.locator('.reply-tag').count() === 0)
  await pA.getByText('展开更多回复').click()
  await checkWait('03-expand-more-5', async () => (await pA.locator('.reply-item').count()) === 5, 5000)
  await pA.getByText('收起', { exact: true }).click()
  await checkWait('03-collapse-restore-toggle', async () => (await r1Item.locator('.thread-toggle').count()) === 1, 5000)

  // ===== 票 06：评论点赞按钮/数字/已赞态（A 点赞 R2）=====
  const r2Like = rootItem(pA, rootContents[1]).locator('.meta-like')
  check('06-like-initial-0', (await r2Like.innerText()).trim() === '0')
  await r2Like.click()
  await checkWait('06-like-count-1', async () => (await r2Like.innerText()).trim() === '1', 5000)
  check('06-like-liked-class', (await r2Like.getAttribute('class')).includes('liked'))

  // ===== 票 06：B 也点赞 R2 → 2 赞，供票 04 最热排序 =====
  await pB.goto(url, { waitUntil: 'networkidle' })
  const r2LikeB = rootItem(pB, rootContents[1]).locator('.meta-like')
  await r2LikeB.click()
  await checkWait('06-second-user-like', async () => (await r2LikeB.innerText()).trim() === '2', 5000)

  // ===== 票 04：最热按真实点赞数生效 / 最新 id 升序 =====
  await pA.locator('.sort-tab', { hasText: '最新' }).click()
  await checkWait('04-new-sort-id-asc', async () => (await pA.locator('.comment-item .comment-content').first().innerText()) === rootContents[0], 5000)
  await pA.locator('.sort-tab', { hasText: '最热' }).click()
  await checkWait('04-hot-sort-by-likes', async () => (await pA.locator('.comment-item .comment-content').first().innerText()) === rootContents[1], 5000)

  // ===== 票 05：乐观插顶（B 发布秒见）=====
  const optContent = 'e2e-optimistic-' + ts
  await pB.locator('.comment-input').fill(optContent)
  await pB.locator('.send-btn').click()
  await checkWait('05-optimistic-insert-top', async () => (await pB.locator('.comment-item .comment-content').first().innerText()) === optContent, 5000)
  check('05-inserted-no-review-badge', !(await rootItem(pB, optContent).innerText()).includes('审核中'))
  // 他人视角：发布后立即可见性快照（若已异步过审则跳过强断言，仅记录）
  await pA.reload({ waitUntil: 'networkidle' })
  const visibleNow = (await pA.locator('.comment-item', { hasText: optContent }).count()) > 0
  console.log('INFO 05-stranger-immediate-visible=' + visibleNow + ' (race-tolerant)')
  // 刷新后作者仍可见（待审或已过审），且他人经异步过审后最终可见
  await pB.reload({ waitUntil: 'networkidle' })
  check('05-author-visible-after-reload', (await rootItem(pB, optContent).count()) === 1)
  await checkWait('05-stranger-visible-after-async-approve', async () => {
    await pA.reload({ waitUntil: 'networkidle' })
    return (await rootItem(pA, optContent).count()) === 1
  }, 40000)

  // ===== 票 03 补充：UI 回复写链路（回复入口 → 目标栏 → 占位切换 → 发送）=====
  const replyUiContent = 'e2e-reply-ui-' + ts
  const r3Item = rootItem(pA, rootContents[2])
  await r3Item.locator('.meta-btn', { hasText: '回复' }).click()
  check('03-reply-target-bar', (await pA.locator('.reply-target-text').innerText()) === '回复 @' + B.nickname)
  check('03-reply-input-placeholder', (await pA.locator('.comment-input').getAttribute('placeholder')) === `回复 @${B.nickname}：`)
  await pA.locator('.reply-target-bar .btn-text', { hasText: '取消' }).click()
  check('03-reply-cancel', (await pA.locator('.reply-target-bar').count()) === 0)
  await r3Item.locator('.meta-btn', { hasText: '回复' }).click()
  await pA.locator('.comment-input').fill(replyUiContent)
  await pA.locator('.send-btn').click()
  // 回复异步过审后才进入楼内（listReplies 无作者视角，以轮询为准）
  await checkWait('03-ui-reply-approved', async () => {
    const d = (await api('GET', `/community/comments/${rootIds[2]}/replies?page=1`, null, A.token)).data
    return d.list.some(r => r.content === replyUiContent)
  }, 40000)
  await pA.reload({ waitUntil: 'networkidle' })
  await rootItem(pA, rootContents[2]).locator('.thread-toggle').click()
  await checkWait('03-ui-reply-in-thread', async () => (await pA.locator('.reply-item', { hasText: replyUiContent }).count()) === 1, 5000)

  // ===== 票 06 补充：回复条目的点赞按钮 =====
  await pB.reload({ waitUntil: 'networkidle' })
  await rootItem(pB, rootContents[2]).locator('.thread-toggle').click()
  const uiReplyLike = pB.locator('.reply-item', { hasText: replyUiContent }).locator('.meta-like')
  await uiReplyLike.click()
  await checkWait('06-reply-like-count-1', async () => (await uiReplyLike.innerText()).trim() === '1', 5000)
  check('06-reply-like-liked-class', (await uiReplyLike.getAttribute('class')).includes('liked'))

  // ===== 票 04 补充："加载更多"接真实 total（根评论超过单页 20 条）=====
  for (let i = 0; i < 18; i++) {
    await api('POST', `/community/posts/${pid}/comments`, { content: `e2e-extra-${i}-` + ts }, B.token)
  }
  await checkWait('04-extra-roots-approved', async () => {
    const d = (await api('GET', `/community/posts/${pid}/comments?page=1&pageSize=50&sort=new`, null, A.token)).data
    return d.total >= 23 && d.list.length >= 20
  }, 60000)
  await pA.reload({ waitUntil: 'networkidle' })
  check('04-first-page-20', (await pA.locator('.comment-item').count()) === 20)
  await pA.getByText('加载更多评论').click()
  await checkWait('04-load-more-rest', async () => (await pA.locator('.comment-item').count()) >= 23, 8000)

  // ===== 票 07：删除交互（B 视角，pB 刷新取服务端权威态）=====
  await pB.reload({ waitUntil: 'networkidle' })
  const readN = (page) => page.locator('.comment-header-title').innerText().then(t => Number(t.replace(/\D/g, '')))

  // 删楼内子回复 rep-a：确认弹窗文案（单条）、条目消失、楼计数-1、▶ 指向已删评论昵称保留
  await rootItem(pB, rootContents[0]).locator('.thread-toggle').click()
  await pB.locator('.reply-item').first().waitFor()
  const nBefore = await readN(pB)
  const repA = pB.locator('.reply-item', { hasText: 'e2e-reply-a-' + ts })
  await repA.locator('button', { hasText: '删除' }).last().click()
  let msg = await pB.locator('.confirm-title').innerText()
  check('07-reply-confirm-text', msg === '删除这条回复？', msg)
  await pB.locator('.confirm-btn.ok').click()
  await checkWait('07-reply-removed', async () => (await pB.locator('.reply-item', { hasText: 'e2e-reply-a-' + ts }).count()) === 0, 5000)
  const deepStill = pB.locator('.reply-item', { hasText: 'e2e-reply-deep-' + ts })
  const tagAfter = await deepStill.locator('.reply-tag').innerText()
  check('07-point-tag-keeps-deleted-nickname', tagAfter === '▶ ' + B.nickname, tagAfter)
  await pB.getByText('收起', { exact: true }).click() // 展开态无 .thread-toggle，先收起再断言标签
  const toggleText = await rootItem(pB, rootContents[0]).locator('.thread-toggle').innerText()
  check('07-thread-toggle-decremented', toggleText === '展开 4 条回复', toggleText)
  await checkWait('07-header-N-after-reply-delete', async () => (await readN(pB)) === nBefore - 1, 8000)

  // 删根评论（有回复）：整楼消失 + 弹窗文案（整楼）+ 计数扣 1+楼内可见数
  const nBeforeRoot = await readN(pB)
  await rootItem(pB, rootContents[0]).locator('button', { hasText: '删除' }).last().click()
  msg = await pB.locator('.confirm-title').innerText()
  check('07-root-confirm-text-thread', msg.includes('该楼所有回复将一并隐藏'), msg)
  await pB.locator('.confirm-btn.ok').click()
  await checkWait('07-root-removed-with-thread', async () => (await rootItem(pB, rootContents[0]).count()) === 0 && (await pB.locator('.reply-item', { hasText: 'e2e-reply-' }).count()) === 0, 5000)
  await checkWait('07-root-delete-count-1+4', async () => (await readN(pB)) === nBeforeRoot - 5, 8000)

  // 删根评论（无回复）：普通文案
  await rootItem(pB, rootContents[1]).locator('button', { hasText: '删除' }).last().click()
  msg = await pB.locator('.confirm-title').innerText()
  check('07-root-confirm-text-plain', msg === '删除这条评论？', msg)
  await pB.locator('.confirm-btn.ok').click()
  await checkWait('07-root-no-reply-removed', async () => (await rootItem(pB, rootContents[1]).count()) === 0, 5000)

  // 删审核中根评论：计数不动
  const nStable = await readN(pB)
  const pendContent = 'e2e-pending-del-' + ts
  await pB.locator('.comment-input').fill(pendContent)
  await pB.locator('.send-btn').click()
  await waitFor(async () => (await pB.locator('.comment-item .comment-content').first().innerText()) === pendContent, 5000, 'pending inserted')
  await rootItem(pB, pendContent).locator('button', { hasText: '删除' }).last().click()
  await pB.locator('.confirm-btn.ok').click()
  await waitFor(async () => (await rootItem(pB, pendContent).count()) === 0, 5000, 'pending removed')
  await new Promise(r => setTimeout(r, 3000)) // 留给异步过审窗口：若删前已被通过，计数会变动并在此暴露
  check('07-pending-delete-count-unchanged', (await readN(pB)) === nStable, `before=${nStable} after=${await readN(pB)}`)

  // 列表缺席检查（DB 行保留由集成测试与联调环境手工查库覆盖）
  await pB.reload({ waitUntil: 'networkidle' })
  check('07-deleted-not-in-any-list', (await rootItem(pB, rootContents[0]).count()) === 0 && (await pB.locator('.reply-item', { hasText: 'e2e-reply-a-' + ts }).count()) === 0)

  console.log(`RESULT: ${passed} passed, ${failed} failed`)
})().catch(e => { fatal = true; console.error('FATAL ' + e.message) }).finally(() => {
  if (browser) browser.close().catch(() => {})
  process.exit(fatal ? 2 : failed ? 1 : 0)
})
