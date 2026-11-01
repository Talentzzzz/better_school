<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { getNotificationsUnreadCount, listNotifications, markNotificationsRead } from '@/api/chat'
import type { ChatRoomSummary, Notification, RoomPeerRole, RoomSource } from '@/types/api'
import { formatTime } from '@/utils/format'
import { roomDisplayName } from '@/utils/chat'
import AppAvatar from '@/components/AppAvatar.vue'
import EmptyState from '@/components/EmptyState.vue'
import NavBar from '@/components/NavBar.vue'

const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

/**
 * 外层两 tab 与市集页同序；每 tab 一行的全部口径（标题 / 系统消息范围 / 分区标题）收在一处。
 * scope 只有跑腿有生产方：发给本人的交易动态只此一域有生产方，闲置无生产方故不出系统消息行（票 07 定）。
 * section 只在无 chips 的 tab 用（闲置走「我买的 / 我卖的」chips，不摆分区标题）。
 */
const TAB_META: Record<RoomSource, { label: string; scope?: string; section?: string }> = {
  ERRAND: { label: '跑腿', scope: 'MARKET_ERRAND', section: '跑手消息' },
  PRODUCT: { label: '闲置' }
}

const TABS = (Object.keys(TAB_META) as RoomSource[]).map((value) => ({
  value,
  label: TAB_META[value].label
}))

const ROLE_TEXT: Record<RoomPeerRole, string> = {
  TAKER: '跑手',
  SELLER: '卖家',
  BUYER: '买家'
}

/** 系统消息气泡点进去的订单详情路由（票 07 §2：按通知的 refType 跳既有路由） */
const NOTICE_ROUTE: Record<string, (refId: number) => string> = {
  ERRAND: (refId) => `/errand/${refId}`
}

const kind = ref<RoomSource>('ERRAND')
/** 闲置的两个可切换分类：我买的（对家=卖家）/ 我卖的（对家=买家） */
const side = ref<'buy' | 'sell'>('buy')
const threadOpen = ref(false)

const noticeCache = reactive<Record<string, Notification[]>>({})
const unreadCache = reactive<Record<string, number>>({})
/** 系统消息加载中：空态要等取数落地再判，否则首屏先闪一下空态 */
const noticeLoading = ref(false)

const scope = computed(() => TAB_META[kind.value].scope)
const notices = computed(() => (scope.value ? noticeCache[scope.value] ?? [] : []))
const noticeUnread = computed(() => (scope.value ? unreadCache[scope.value] ?? 0 : 0))
const latestNotice = computed(() => notices.value[0])

// 会话分栏判据 = 会话来源列（与市集入口行未读角标同源）
const partyRooms = computed(() => chatRooms.rooms.filter((r) => r.source === kind.value))

const buyingRooms = computed(() => partyRooms.value.filter((r) => r.peerRole === 'SELLER'))
const sellingRooms = computed(() => partyRooms.value.filter((r) => r.peerRole === 'BUYER'))
const sideRooms = computed(() => (side.value === 'buy' ? buyingRooms.value : sellingRooms.value))

const unreadOf = (rooms: ChatRoomSummary[]) => rooms.reduce((sum, r) => sum + r.unread, 0)

const hasSystem = computed(() => !!scope.value)
/** 空态判据（票 07）：本 tab 既无系统消息又无对家会话；取数未落地前不判，免得首屏闪空态 */
const isEmpty = computed(
  () =>
    chatRooms.loaded &&
    !noticeLoading.value &&
    notices.value.length === 0 &&
    partyRooms.value.length === 0
)
const shownRooms = computed(() =>
  kind.value === 'PRODUCT' ? sideRooms.value : partyRooms.value
)

async function loadNotices(currentScope: string) {
  noticeLoading.value = true
  try {
    const all: Notification[] = []
    for (let page = 1; ; page++) {
      const res = await listNotifications(page, 50, currentScope)
      all.push(...res.list)
      if (all.length >= res.total) break
    }
    noticeCache[currentScope] = all
    const { count } = await getNotificationsUnreadCount(currentScope)
    unreadCache[currentScope] = count
  } catch {
    // 失败态由 http 拦截统一提示，页面保留上一次数据
  } finally {
    noticeLoading.value = false
  }
}

/** 进系统消息会话：一次推齐该范围的水位（各 tab 各推各的水位，互不误清） */
async function openThread() {
  threadOpen.value = true
  const currentScope = scope.value
  if (!currentScope) return
  unreadCache[currentScope] = 0
  await markNotificationsRead(currentScope)
}

function openRoom(roomId: number) {
  router.push(`/chat/${roomId}`)
}

/** 系统消息气泡 → 对应订单详情；无 refType / refId 的通知（公告类）不跳 */
function openNotice(n: Notification) {
  const refId = n.refId
  const buildRoute = n.refType ? NOTICE_ROUTE[n.refType] : undefined
  if (buildRoute && refId != null) router.push(buildRoute(refId))
}

function roleOf(room: ChatRoomSummary): string {
  return room.peerRole ? ROLE_TEXT[room.peerRole] : ''
}

function previewOf(room: ChatRoomSummary): string {
  return room.lastMsgPreview || '（暂无消息）'
}

watch(
  kind,
  () => {
    threadOpen.value = false
    side.value = 'buy'
    loadCurrentNotices()
  }
)

function loadCurrentNotices() {
  const currentScope = scope.value
  if (currentScope && !noticeCache[currentScope]) loadNotices(currentScope)
}

onMounted(() => {
  if (auth.isLoggedIn) chatRooms.loadRooms()
  loadCurrentNotices()
})
</script>

<template>
  <div class="page--plain market-msg">
    <NavBar title="市集消息" />

    <div class="tabs-equal">
      <button
        v-for="t in TABS"
        :key="t.value"
        :class="{ on: kind === t.value }"
        @click="kind = t.value"
      >
        {{ t.label }}
      </button>
    </div>

    <EmptyState v-if="isEmpty" class="mm-empty" text="暂无消息" />

    <template v-else>
      <!-- 系统消息：一条会话行（形态同私信行），点进去是该域多条系统消息的聊天框 -->
      <button v-if="hasSystem" class="mm-notice" @click="openThread">
        <span class="mm-notice-icon">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="#fff">
            <path d="M12 22a2.5 2.5 0 0 0 2.5-2.5h-5A2.5 2.5 0 0 0 12 22zm7-6v-5a7 7 0 0 0-5.3-6.8V3.5a1.7 1.7 0 1 0-3.4 0v.7A7 7 0 0 0 5 11v5l-1.6 2.2V19h17.2v-.8z" />
          </svg>
        </span>
        <span class="mm-notice-main">
          <span class="mm-notice-name">系统消息</span>
          <span class="mm-notice-sub ellipsis">{{ latestNotice?.content || '暂无系统消息' }}</span>
        </span>
        <span class="mm-notice-right">
          <span class="mm-time">{{ formatTime(latestNotice?.createdAt) }}</span>
          <span v-if="noticeUnread" class="mm-unread">{{ noticeUnread > 99 ? '99+' : noticeUnread }}</span>
        </span>
      </button>

      <!-- 闲置：系统消息之下两个可切换分类（chips，各带未读角标） -->
      <div v-if="kind === 'PRODUCT'" class="mm-chips">
        <button class="mm-chip" :class="{ on: side === 'buy' }" @click="side = 'buy'">
          我买的
          <span v-if="unreadOf(buyingRooms)" class="mm-chip-badge">{{ unreadOf(buyingRooms) }}</span>
        </button>
        <button class="mm-chip" :class="{ on: side === 'sell' }" @click="side = 'sell'">
          我卖的
          <span v-if="unreadOf(sellingRooms)" class="mm-chip-badge">{{ unreadOf(sellingRooms) }}</span>
        </button>
      </div>
      <div v-else class="mm-sec">{{ TAB_META[kind].section }}</div>

      <button
        v-for="r in shownRooms"
        :key="r.roomId"
        class="mm-row"
        @click="openRoom(r.roomId)"
      >
        <AppAvatar :src="r.peerAvatar" :name="roomDisplayName(r)" :size="44" />
        <div class="mm-main">
          <div class="mm-title-row">
            <span v-if="roleOf(r)" class="mm-role">{{ roleOf(r) }}</span>
            <span class="mm-title ellipsis">{{ roomDisplayName(r) }}</span>
            <span class="mm-time">{{ formatTime(r.lastMsgAt) }}</span>
          </div>
          <div class="mm-text ellipsis">{{ previewOf(r) }}</div>
        </div>
        <span v-if="r.unread" class="mm-unread">{{ r.unread > 99 ? '99+' : r.unread }}</span>
      </button>
    </template>

    <!-- 系统消息聊天框：该域的系统消息就是框里的多条气泡（不落 chat 的 room/message 表） -->
    <div v-if="threadOpen" class="mm-thread">
      <header class="mm-thread-nav">
        <button class="mm-thread-back" aria-label="返回" @click="threadOpen = false">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M15 5l-7 7 7 7" />
          </svg>
        </button>
        <div class="mm-thread-title">系统消息</div>
        <span class="mm-thread-pad" />
      </header>
      <div class="mm-thread-body">
        <div class="mm-thread-date">近 7 天</div>
        <EmptyState v-if="!notices.length" text="暂无系统消息" />
        <div v-for="n in notices" :key="n.id" class="mm-bubble-row">
          <span class="mm-bubble-avatar">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="#fff">
              <path d="M12 22a2.5 2.5 0 0 0 2.5-2.5h-5A2.5 2.5 0 0 0 12 22zm7-6v-5a7 7 0 0 0-5.3-6.8V3.5a1.7 1.7 0 1 0-3.4 0v.7A7 7 0 0 0 5 11v5l-1.6 2.2V19h17.2v-.8z" />
            </svg>
          </span>
          <button class="mm-bubble" @click="openNotice(n)">
            <div class="mm-bubble-title">{{ n.title }}</div>
            <div class="mm-bubble-body">{{ n.content }}</div>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.market-msg {
  min-height: 100vh;
  background: $bg-page-alt;
}

.mm-empty {
  padding-top: 90px;
}

// 系统消息：一条会话入口（形态同私信行）
.mm-notice {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 13px 16px;
  background: #fff;
  border-bottom: 0.5px solid $line;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.mm-notice-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: #3e7bfa;
  flex-shrink: 0;
}

.mm-notice-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mm-notice-name {
  font-size: 16px;
  font-weight: 500;
}

.mm-notice-sub {
  font-size: 13px;
  color: $text-3;
}

.mm-notice-right {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
}

// 分区标题（跑手消息 / 卖家消息）
.mm-sec {
  padding: 9px 16px;
  background: $bg-page-alt;
  color: $text-3;
  font-size: 12px;
}

// 闲置的两个可切换分类
.mm-chips {
  display: flex;
  gap: 8px;
  padding: 9px 12px;
  background: $bg-page-alt;
}

.mm-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 16px;
  border-radius: 999px;
  background: #fff;
  color: $text-2;
  font-size: 13px;

  &.on {
    background: $primary;
    color: #fff;
    font-weight: 600;
  }
}

.mm-chip-badge {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: $primary;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}

.mm-chip.on .mm-chip-badge {
  background: #fff;
  color: $primary;
}

.mm-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 13px 16px;
  background: #fff;
  border-bottom: 0.5px solid $line;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.mm-main {
  flex: 1;
  min-width: 0;
}

.mm-title-row {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.mm-title {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
}

.mm-role {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: $bg-secondary;
  color: $text-3;
  font-size: 10px;
}

.mm-time {
  flex-shrink: 0;
  font-size: 11px;
  color: $text-4;
}

.mm-text {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.5;
  color: $text-3;
}

.mm-unread {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: $primary;
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

// ── 系统消息聊天框 ──
.mm-thread {
  position: fixed;
  inset: 0;
  z-index: 300;
  display: flex;
  flex-direction: column;
  max-width: 480px;
  margin: 0 auto;
  background: $bg-page-alt;
}

.mm-thread-nav {
  display: grid;
  grid-template-columns: 56px 1fr 56px;
  align-items: center;
  height: 48px;
  padding: 0 6px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.mm-thread-back {
  display: flex;
  align-items: center;
  height: 48px;
  padding-left: 8px;
  color: $text-1;
}

.mm-thread-title {
  text-align: center;
  font-size: 17px;
  font-weight: 600;
}

.mm-thread-pad {
  display: block;
}

.mm-thread-body {
  flex: 1;
  padding: 14px 14px calc(20px + env(safe-area-inset-bottom));
  overflow-y: auto;
}

.mm-thread-date {
  margin-bottom: 14px;
  text-align: center;
  font-size: 11px;
  color: $text-4;
}

.mm-bubble-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

.mm-bubble-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #3e7bfa;
  flex-shrink: 0;
}

.mm-bubble {
  max-width: 268px;
  padding: 10px 12px;
  border-radius: 4px 14px 14px 14px;
  background: #fff;
  box-shadow: $shadow-card;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.mm-bubble-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-1;
}

.mm-bubble-body {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.55;
  color: $text-2;
}
</style>
