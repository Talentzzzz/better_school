<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getErrandOrder } from '@/api/errand'
import { listMembers, listMessages, recallMessage, sendMessage } from '@/api/chat'
import { getProduct } from '@/api/trade'
import { getPost } from '@/api/community'
import { uploadImage } from '@/api/files'
import type { ChatMessage, RoomMember } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { chatWs } from '@/utils/chatWs'
import { formatTime } from '@/utils/format'
import { parseImageContent, roomDisplayName } from '@/utils/chat'
import { vLongpress } from '@/utils/longpress'
import NavBar from '@/components/NavBar.vue'
import AppImage from '@/components/AppImage.vue'
import ActionSheet from '@/components/ActionSheet.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const roomId = Number(route.params.roomId)
const PAGE_LIMIT = 50
const RECALL_WINDOW_MS = 2 * 60 * 1000

interface Row {
  msg: ChatMessage
  state: 'sent' | 'pending' | 'failed'
  file?: File // IMAGE 待上传原图（失败重发复用）
  localUrl?: string // 乐观上屏临时地址
}

const rows = ref<Row[]>([])
const input = ref('')
const scroller = ref<HTMLDivElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const noMoreHistory = ref(false)
const loadingOlder = ref(false)
const members = ref<RoomMember[]>([])
const menuRow = ref<Row | null>(null)

// CARD 实时查对象：key=`${refType}:${refId}`，查不到即「内容已失效」
interface CardInfo {
  title: string
  invalid?: boolean
}
const cardInfos = ref<Record<string, CardInfo>>({})

let maxSeq = 0
let unbindMessage: (() => void) | null = null
let unbindRecall: (() => void) | null = null

const roomName = computed(() => {
  const room = chatRooms.roomById(roomId)
  return (room && roomDisplayName(room)) || '私信'
})
const peer = computed(() => members.value.find((m) => m.userId !== auth.userId) ?? null)

function scrollToBottom() {
  nextTick(() => {
    if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
  })
}

// ---------- 接收（推送+补拉统一出口，seq 判重） ----------

function releaseLocalUrl(row: Row) {
  if (row.localUrl) {
    URL.revokeObjectURL(row.localUrl)
    row.localUrl = undefined
  }
}

function applyIncoming(msg: ChatMessage, scroll = true) {
  if (msg.roomId !== roomId || msg.seq <= maxSeq) return
  // 乐观上屏的消息被 200/补拉/推送带回：按 client_msg_id 撞行转正
  if (msg.clientMsgId) {
    const row = rows.value.find((r) => r.msg.clientMsgId === msg.clientMsgId)
    if (row) {
      row.msg = msg
      row.state = 'sent'
      row.file = undefined
      releaseLocalUrl(row)
      maxSeq = msg.seq
      if (scroll) scrollToBottom()
      markReadIfUnread()
      return
    }
  }
  maxSeq = msg.seq
  rows.value.push({ msg, state: 'sent' })
  if (msg.msgType === 'CARD') void ensureCard(msg)
  if (scroll) scrollToBottom()
  markReadIfUnread()
}

function markReadIfUnread() {
  // 停留在会话期间保持已读（打开即推水位线，幂等无噪音）
  if (chatRooms.roomById(roomId)?.unread) void chatRooms.markRead(roomId)
}

// ---------- 历史（beforeSeq 倒序分页，上滑加载更早） ----------

async function loadInitial() {
  try {
    const list = await listMessages(roomId, { limit: PAGE_LIMIT })
    if (list.length < PAGE_LIMIT) noMoreHistory.value = true
    for (const msg of list) applyIncoming(msg, false)
    scrollToBottom()
  } catch {
    // 会话不存在/无权限，toast 已弹
  }
}

async function loadOlder() {
  const oldest = rows.value[0]
  if (loadingOlder.value || noMoreHistory.value || !oldest || oldest.state !== 'sent') return
  loadingOlder.value = true
  try {
    const list = await listMessages(roomId, { beforeSeq: oldest.msg.seq, limit: PAGE_LIMIT })
    if (list.length < PAGE_LIMIT) noMoreHistory.value = true
    if (list.length) {
      const el = scroller.value
      const prevHeight = el?.scrollHeight ?? 0
      // 旧页 seq 全部 ≤ maxSeq，不能走 applyIncoming（会被判重丢弃），必须直接 prepend
      rows.value = [...list.map((msg) => ({ msg, state: 'sent' as const })), ...rows.value]
      for (const msg of list) {
        if (msg.msgType === 'CARD') void ensureCard(msg)
      }
      // prepend 后保持视口停在原消息上
      nextTick(() => {
        if (el) el.scrollTop += el.scrollHeight - prevHeight
      })
    }
  } catch {
    // toast 已弹
  } finally {
    loadingOlder.value = false
  }
}

function onScroll() {
  const el = scroller.value
  if (el && el.scrollTop < 40) void loadOlder()
}

// ---------- 发送（乐观上屏 pending → 200 转正 → 失败红叹号手动重发复用 client_msg_id） ----------

function newClientMsgId() {
  return crypto.randomUUID()
}

function optimisticRow(partial: Partial<ChatMessage>, extra?: Partial<Row>): Row {
  const msg: ChatMessage = {
    roomId,
    messageId: 0,
    seq: Number.MAX_SAFE_INTEGER,
    fromUserId: auth.userId,
    fromNickname: auth.userInfo?.nickname ?? null,
    fromAvatar: auth.userInfo?.avatar ?? null,
    msgType: 'TEXT',
    status: 'NORMAL',
    content: '',
    refType: null,
    refId: null,
    clientMsgId: newClientMsgId(),
    deliverySeq: null,
    createdAt: new Date().toISOString(),
    ...partial
  }
  return { msg, state: 'pending', ...extra }
}

async function doSendText(row: Row) {
  row.state = 'pending'
  try {
    const saved = await sendMessage(roomId, {
      clientMsgId: row.msg.clientMsgId!,
      msgType: 'TEXT',
      content: row.msg.content
    })
    row.msg = saved
    row.state = 'sent'
    maxSeq = Math.max(maxSeq, saved.seq)
  } catch {
    row.state = 'failed'
  }
}

function imageDims(url: string) {
  return new Promise<{ width?: number; height?: number }>((resolve) => {
    const img = new Image()
    img.onload = () => resolve({ width: img.naturalWidth, height: img.naturalHeight })
    img.onerror = () => resolve({})
    img.src = url
  })
}

async function doSendImage(row: Row) {
  row.state = 'pending'
  try {
    // 首发传 row.file；重发时 file 仍在，重新上传（幂等由 client_msg_id 收敛）
    const url = row.file ? await uploadImage(row.file) : parseImageContent(row.msg.content)?.url
    if (!url) throw new Error('no url')
    const dims = await imageDims(url)
    const saved = await sendMessage(roomId, {
      clientMsgId: row.msg.clientMsgId!,
      msgType: 'IMAGE',
      content: JSON.stringify({ url, ...dims })
    })
    row.msg = saved
    row.state = 'sent'
    row.file = undefined
    releaseLocalUrl(row)
    maxSeq = Math.max(maxSeq, saved.seq)
  } catch {
    row.state = 'failed'
  }
}

async function onSend() {
  const content = input.value.trim()
  if (!content) return
  input.value = ''
  rows.value.push(optimisticRow({ msgType: 'TEXT', content }))
  scrollToBottom()
  await doSendText(rows.value[rows.value.length - 1])
}

function onPickImages(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  target.value = ''
  if (!files.length) return
  for (const file of files) {
    const localUrl = URL.createObjectURL(file)
    // 多图拆条：每张独立一条消息（spec §7.4）
    rows.value.push(
      optimisticRow(
        { msgType: 'IMAGE', content: JSON.stringify({ url: localUrl }) },
        { file, localUrl }
      )
    )
    void doSendImage(rows.value[rows.value.length - 1])
  }
  scrollToBottom()
}

function retry(row: Row) {
  if (row.state !== 'failed') return
  if (row.msg.msgType === 'IMAGE') void doSendImage(row)
  else if (row.msg.msgType === 'CARD') void doSendCard(row)
  else void doSendText(row)
}

// ---------- 撤回（发送者本人 2 分钟窗口，就地灰字） ----------

function canRecall(row: Row): boolean {
  return (
    row.state === 'sent' &&
    row.msg.fromUserId === auth.userId &&
    row.msg.status === 'NORMAL' &&
    Date.now() - new Date(row.msg.createdAt).getTime() < RECALL_WINDOW_MS
  )
}

function openMsgMenu(row: Row) {
  if (canRecall(row)) menuRow.value = row
}

async function doRecall() {
  const row = menuRow.value
  menuRow.value = null
  if (!row || row.state !== 'sent') return
  try {
    await recallMessage(roomId, row.msg.seq)
    row.msg.status = 'RECALLED'
  } catch {
    // toast 已弹
  }
}

// ---------- CARD 实时查对象 + 失效态 ----------

const CARD_LABELS: Record<string, string> = { PRODUCT: '商品', POST: '帖子', ERRAND: '跑腿' }
const CARD_FETCHERS: Record<string, (id: number) => Promise<string>> = {
  PRODUCT: (id) => getProduct(id).then((p) => p.title),
  POST: (id) => getPost(id).then((p) => p.title),
  ERRAND: (id) => getErrandOrder(id).then((o) => o.description)
}
const CARD_ROUTES: Record<string, (id: number) => string> = {
  PRODUCT: (id) => `/product/${id}`,
  POST: (id) => `/post/${id}`,
  ERRAND: (id) => `/errand/${id}`
}

async function ensureCard(msg: ChatMessage) {
  if (!msg.refType || !msg.refId) return
  const fetcher = CARD_FETCHERS[msg.refType]
  if (!fetcher) return
  const key = `${msg.refType}:${msg.refId}`
  if (cardInfos.value[key]) return
  try {
    cardInfos.value[key] = { title: await fetcher(msg.refId) }
  } catch {
    cardInfos.value[key] = { title: '', invalid: true }
  }
}

function cardInfoOf(msg: ChatMessage): CardInfo | null {
  if (!msg.refType || !msg.refId) return null
  return cardInfos.value[`${msg.refType}:${msg.refId}`] ?? null
}

function onCardClick(msg: ChatMessage) {
  if (!msg.refType || !msg.refId) return
  if (cardInfoOf(msg)?.invalid) return
  const toPath = CARD_ROUTES[msg.refType]
  if (toPath) router.push(toPath(msg.refId))
}

// ---------- 卡片发送入口（商品/帖子详情页「联系 TA」带入，spec §7.7） ----------

interface PendingCard {
  refType: 'PRODUCT' | 'POST'
  refId: number
}
const pendingCard = ref<PendingCard | null>(null)
// 标题/失效态复用 cardInfos 缓存（与气泡渲染同源，查一次）
const pendingCardInfo = computed(() => {
  const pc = pendingCard.value
  if (!pc) return null
  return cardInfos.value[`${pc.refType}:${pc.refId}`] ?? null
})

function initPendingCard() {
  const raw = typeof route.query.card === 'string' ? route.query.card : ''
  const [refType, idStr] = raw.split(':')
  const refId = Number(idStr)
  if ((refType !== 'PRODUCT' && refType !== 'POST') || !Number.isInteger(refId) || refId <= 0) return
  pendingCard.value = { refType, refId }
  void ensureCard({ refType, refId } as ChatMessage) // 对象已删/不可见 → 失效态条仍展示但禁发（后端同样拒绝）
}

function dropPendingCardQuery() {
  pendingCard.value = null
  router.replace({ query: { ...route.query, card: undefined } })
}

async function doSendCard(row: Row) {
  row.state = 'pending'
  try {
    const saved = await sendMessage(roomId, {
      clientMsgId: row.msg.clientMsgId!,
      msgType: 'CARD',
      refType: row.msg.refType!,
      refId: row.msg.refId!
    })
    row.msg = saved
    row.state = 'sent'
    maxSeq = Math.max(maxSeq, saved.seq)
  } catch {
    row.state = 'failed'
  }
}

async function onSendCard() {
  const pc = pendingCard.value
  const info = pendingCardInfo.value
  if (!pc || !info || info.invalid) return
  dropPendingCardQuery()
  rows.value.push(optimisticRow({ msgType: 'CARD', refType: pc.refType, refId: pc.refId, content: '' }))
  scrollToBottom()
  await doSendCard(rows.value[rows.value.length - 1])
}

// ---------- 已读回执（members 水位 map + READ 推送，纯前端推导，spec §7.5） ----------

function watermark(userId: number): number {
  return chatRooms.readSeqs[roomId]?.[userId] ?? 0
}

/** 1v1 自己消息旁「已读/未读」小字 */
function directReadLabel(row: Row): string | null {
  // members 拉取失败（403/网络）时水位未知，宁缺勿错
  if (!members.value.length || row.state !== 'sent' || row.msg.status !== 'NORMAL') return null
  if (row.msg.fromUserId !== auth.userId || !peer.value) return null
  return watermark(peer.value.userId) >= row.msg.seq ? '已读' : '未读'
}

// ---------- 渲染辅助 ----------

function imageOf(row: Row) {
  return parseImageContent(row.msg.content)
}

onMounted(() => {
  // 打开会话立即推水位线，不等补拉（spec §2）
  void chatRooms.markRead(roomId)
  unbindMessage = chatRooms.onMessage((msg) => applyIncoming(msg))
  unbindRecall = chatWs.on('RECALL', (env) => {
    if (env.roomId !== roomId || !env.seq) return
    const row = rows.value.find((r) => r.msg.seq === env.seq)
    if (row) row.msg.status = 'RECALLED'
  })
  void loadInitial()
  void initPendingCard()
  // 拉一次成员：对家身份走 room summary 的 peerUserId，这里只为拿 last_read_seq 建水位 map 初值
  // （READ 推送只在新读发生时来，初值缺了会把历史消息的「已读」误判成「未读」）
  listMembers(roomId)
    .then((res) => {
      members.value = res.members
      for (const m of res.members) chatRooms.mergeReadSeq(roomId, m.userId, m.lastReadSeq)
    })
    .catch(() => {
      // 非成员 403 等场景无回执，toast 已弹
    })
})

onUnmounted(() => {
  unbindMessage?.()
  unbindRecall?.()
})
</script>

<template>
  <div class="chat-page">
    <NavBar :title="roomName" />

    <div ref="scroller" class="chat-scroll" @scroll="onScroll">
      <div v-if="loadingOlder || !noMoreHistory" class="chat-load-tip">
        {{ loadingOlder ? '加载中…' : '上滑加载更早消息' }}
      </div>
      <div v-if="!rows.length" class="chat-empty">打个招呼开始聊天吧～</div>
      <div
        v-for="row in rows"
        :key="row.msg.clientMsgId || row.msg.seq"
        class="msg-row"
        :class="row.msg.fromUserId === auth.userId ? 'mine' : 'theirs'"
      >
        <div class="msg-col">
          <div v-if="row.msg.status === 'RECALLED'" class="bubble-recalled">已撤回</div>

          <div
            v-else-if="row.msg.msgType === 'TEXT'"
            v-longpress="() => openMsgMenu(row)"
            class="bubble"
          >{{ row.msg.content }}</div>

          <div
            v-else-if="row.msg.msgType === 'IMAGE'"
            v-longpress="() => openMsgMenu(row)"
            class="img-bubble"
          >
            <AppImage
              v-if="imageOf(row)?.url"
              :src="imageOf(row)!.url"
              :ratio="imageOf(row)?.width ? `${imageOf(row)!.width}:${imageOf(row)!.height}` : '3:4'"
              dynamic
            />
            <span v-else class="bubble">[图片]</span>
          </div>

          <button
            v-else-if="row.msg.msgType === 'CARD'"
            v-longpress="() => openMsgMenu(row)"
            class="card-bubble"
            :class="{ 'card-bubble--invalid': cardInfoOf(row.msg)?.invalid }"
            @click="onCardClick(row.msg)"
          >
            <span class="card-tag">{{ CARD_LABELS[row.msg.refType ?? ''] || '卡片' }}</span>
            <span class="card-title ellipsis">
              {{ cardInfoOf(row.msg)?.invalid ? '内容已失效' : cardInfoOf(row.msg)?.title || '加载中…' }}
            </span>
            <span v-if="!cardInfoOf(row.msg)?.invalid" class="card-arrow">›</span>
          </button>

          <div class="msg-meta">
            <span class="msg-time">{{ formatTime(row.msg.createdAt) }}</span>
            <span
              v-if="row.msg.fromUserId === auth.userId && row.state !== 'sent' && row.msg.status !== 'RECALLED'"
              class="msg-state"
              :class="row.state"
              @click="retry(row)"
            >
              {{ row.state === 'pending' ? '发送中…' : '! 点击重发' }}
            </span>
            <span v-if="directReadLabel(row)" class="msg-read">{{ directReadLabel(row) }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="pendingCard" class="card-entry">
      <span class="card-tag">{{ CARD_LABELS[pendingCard.refType] }}</span>
      <span class="card-title ellipsis">
        {{ pendingCardInfo?.invalid ? '内容已失效' : pendingCardInfo?.title || '加载中…' }}
      </span>
      <button
        v-if="!pendingCardInfo?.invalid"
        class="card-entry-send"
        @click="onSendCard"
      >
        发送
      </button>
      <button class="card-entry-close" aria-label="移除卡片" @click="dropPendingCardQuery">×</button>
    </div>

    <footer class="chat-input-bar safe-bottom">
      <input ref="fileInput" type="file" accept="image/*" multiple class="chat-file" @change="onPickImages" />
      <button class="chat-plus" @click="fileInput?.click()">＋</button>
      <input
        v-model="input"
        class="chat-input"
        maxlength="500"
        placeholder="输入消息…"
        @keyup.enter="onSend"
      />
      <button class="btn-primary chat-send" :disabled="!input.trim()" @click="onSend">
        发送
      </button>
    </footer>

    <ActionSheet v-if="menuRow" danger="撤回" @danger="doRecall" @cancel="menuRow = null" />
  </div>
</template>

<style scoped lang="scss">
.chat-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 14px 12px;
  background: $bg-secondary;
}

.chat-load-tip {
  text-align: center;
  padding: 4px 0 10px;
  color: $text-4;
  font-size: 11px;
}

.chat-empty {
  text-align: center;
  padding: 40px 0;
  color: $text-3;
  font-size: 13px;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;

  &.mine {
    justify-content: flex-end;

    .msg-col {
      align-items: flex-end;
    }
  }
}

.msg-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  max-width: 76%;
  min-width: 0;
}

.bubble {
  padding: 9px 13px;
  border-radius: 10px;
  background: #fff;
  font-size: 14px;
  line-height: 1.55;
  word-break: break-word;
  white-space: pre-wrap;

  .mine & {
    background: $primary;
    color: #fff;
    border-bottom-right-radius: 3px;
  }

  .theirs & {
    border-bottom-left-radius: 3px;
  }
}

.bubble-recalled {
  padding: 6px 12px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.05);
  color: $text-3;
  font-size: 12px;
}

.img-bubble {
  width: 160px;
  border-radius: 10px;
  overflow: hidden;
}

.card-bubble {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  text-align: left;

  .mine & {
    background: mix(#fff, $primary, 85%);
    border-bottom-right-radius: 3px;
  }

  .theirs & {
    border-bottom-left-radius: 3px;
  }

  &:active {
    opacity: 0.85;
  }

  &--invalid {
    opacity: 0.6;
  }
}

.card-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: $bg-secondary;
  color: $text-3;
  font-size: 10px;
  line-height: 16px;
}

.card-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: $text-1;
}

.card-arrow {
  flex-shrink: 0;
  color: $text-4;
  font-size: 16px;
}

.msg-meta {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.msg-time {
  font-size: 10px;
  color: $text-4;
}

.msg-state {
  font-size: 10px;
  cursor: pointer;

  &.pending {
    color: $text-4;
  }

  &.failed {
    color: #e34d59;
  }
}

.msg-read {
  font-size: 10px;
  color: $text-4;
}

.card-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fff;
  border-top: 0.5px solid $line;
}

.card-entry-send {
  flex-shrink: 0;
  height: 28px;
  padding: 0 12px;
  border-radius: $radius-full;
  background: $primary;
  color: #fff;
  font-size: 12px;

  &:disabled {
    opacity: 0.4;
  }
}

.card-entry-close {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: $bg-search;
  color: $text-3;
  font-size: 16px;
  line-height: 1;
}

.chat-input-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fff;
  border-top: 0.5px solid $line;
}

.chat-file {
  display: none;
}

.chat-plus {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: $bg-search;
  color: $text-2;
  font-size: 20px;

  &:disabled {
    opacity: 0.4;
  }
}

.chat-input {
  flex: 1;
  height: 38px;
  padding: 0 14px;
  border-radius: $radius-full;
  background: $bg-search;
  font-size: 14px;
}

.chat-send {
  height: 36px;
  padding: 0 16px;
  font-size: 13px;
  flex-shrink: 0;
}
</style>
