<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { getNotificationsUnreadCount, listNotifications } from '@/api/chat'
import type { Notification } from '@/types/api'
import { formatTime } from '@/utils/format'
import { roomDisplayName } from '@/utils/chat'
import { vLongpress } from '@/utils/longpress'
import AppAvatar from '@/components/AppAvatar.vue'
import ActionSheet from '@/components/ActionSheet.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const loading = computed(() => !chatRooms.loaded)

const rooms = computed(() => chatRooms.rooms)

const menuRoomId = ref<number | null>(null)
const menuRoomName = computed(() => {
  const room = chatRooms.roomById(menuRoomId.value ?? 0)
  return (room && roomDisplayName(room)) || ''
})

function openMenu(roomId: number) {
  menuRoomId.value = roomId
}

async function onDelete() {
  const roomId = menuRoomId.value
  menuRoomId.value = null
  if (roomId != null) await chatRooms.hideRoom(roomId)
}

/** 系统消息行：只取最新一条的时间与未读数（范围 = BOTTOM_SYSTEM，与通知页 system tab 同源，票 07 定） */
const latestSystem = ref<Notification | null>(null)
const systemUnread = ref(0)

onMounted(() => {
  if (!auth.isLoggedIn) return
  chatRooms.loadRooms()
  Promise.all([listNotifications(1, 1, 'BOTTOM_SYSTEM'), getNotificationsUnreadCount('BOTTOM_SYSTEM')])
    .then(([pageRes, unreadRes]) => {
      latestSystem.value = pageRes.list[0] ?? null
      systemUnread.value = unreadRes.count
    })
    .catch(() => {}) // 失败态由 http 拦截统一提示，行内保留空态（与 MarketMessagesView 同口径）
})
</script>

<template>
  <div class="page messages-page">
    <header class="msg-nav">
      <div class="msg-title">消息</div>
      <div class="msg-right">
        <button class="msg-icon-btn" aria-label="搜索联系人" @click="router.push('/messages/contacts')">
          <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-4-4" />
          </svg>
        </button>
      </div>
    </header>

    <div class="entry-row">
      <button class="entry-item" @click="router.push('/notifications?tab=like')">
        <span class="entry-icon entry-icon--like">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="#FF5470">
            <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
          </svg>
        </span>
        <span class="entry-label">赞</span>
      </button>
      <button class="entry-item" @click="router.push('/notifications?tab=comment')">
        <span class="entry-icon entry-icon--comment">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="#37B868">
            <path d="M21 12a8 8 0 0 1-11.7 7.1L4 21l1.9-5.3A8 8 0 1 1 21 12z" />
          </svg>
        </span>
        <span class="entry-label">评论</span>
      </button>
    </div>

    <button class="notice-row" @click="router.push('/notifications?tab=system')">
      <span class="notice-icon">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="#fff">
          <path d="M12 22a2.5 2.5 0 0 0 2.5-2.5h-5A2.5 2.5 0 0 0 12 22zm7-6v-5a7 7 0 0 0-5.3-6.8V3.5a1.7 1.7 0 1 0-3.4 0v.7A7 7 0 0 0 5 11v5l-1.6 2.2V19h17.2v-.8z" />
        </svg>
      </span>
      <span class="notice-main">
        <span class="notice-title">系统消息</span>
        <span class="notice-sub ellipsis">审核通知、平台公告、信用分变动</span>
      </span>
      <span v-if="latestSystem" class="notice-time">{{ formatTime(latestSystem.createdAt) }}</span>
      <span v-if="systemUnread > 0" class="notice-dot" />
    </button>

    <div class="sec-head">私信</div>

    <template v-if="rooms.length">
      <button
        v-for="r in rooms"
        :key="r.roomId"
        v-longpress="() => openMenu(r.roomId)"
        class="thread-row"
        @click="router.push(`/chat/${r.roomId}`)"
      >
        <AppAvatar :src="r.peerAvatar" :name="roomDisplayName(r)" :size="46" />
        <div class="thread-main">
          <div class="thread-name-row">
            <span class="thread-name ellipsis">{{ roomDisplayName(r) }}</span>
            <span class="thread-time">{{ formatTime(r.lastMsgAt) }}</span>
          </div>
          <div class="thread-last ellipsis">{{ r.lastMsgPreview || '（暂无消息）' }}</div>
        </div>
        <span v-if="r.unread > 0" class="thread-dot">
          {{ r.unread > 99 ? '99+' : r.unread }}
        </span>
      </button>
    </template>
    <EmptyState v-else-if="!loading" text="暂无私信，去逛逛并联系感兴趣的伙伴吧" />

    <div class="unread-tip" v-if="chatRooms.unreadTotal > 0">
      共 {{ chatRooms.unreadTotal }} 条未读消息
    </div>

    <ActionSheet
      v-if="menuRoomId !== null"
      :danger="`删除会话${menuRoomName ? `「${menuRoomName}」` : ''}`"
      @danger="onDelete"
      @cancel="menuRoomId = null"
    />
  </div>
</template>

<style scoped lang="scss">
.messages-page {
  padding-bottom: calc(#{$tabbar-h} + env(safe-area-inset-bottom));
}

.msg-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(8px);
}

.msg-title {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  font-weight: 600;
}

.msg-right {
  position: absolute;
  top: 0;
  right: 4px;
  height: 48px;
  display: flex;
  align-items: center;
}

.msg-icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  color: $text-1;

  &:active {
    background: $bg-secondary;
  }
}

.entry-row {
  display: flex;
  gap: 26px;
  padding: 4px 12px 14px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.entry-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 4px 0 0;
  border-radius: $radius;

  &:active {
    opacity: 0.8;
  }
}

.entry-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 16px;

  &--like {
    background: #ffdce4;
  }

  &--comment {
    background: #d8f3e2;
  }
}

.entry-label {
  font-size: 14px;
  color: $text-2;
}

.notice-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  background: #fff;
  border-bottom: 0.5px solid $line;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.notice-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #3e7bfa;
  flex-shrink: 0;
}

.notice-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.notice-title {
  font-size: 16px;
  font-weight: 500;
}

.notice-sub {
  font-size: 12px;
  color: $text-3;
}

.notice-time {
  font-size: 11px;
  color: $text-4;
  align-self: flex-start;
  margin-top: 4px;
}

.notice-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: $primary;
  align-self: flex-start;
  margin-top: 8px;
}

.sec-head {
  padding: 12px 16px 6px;
  font-size: 13px;
  color: $text-3;
}

.thread-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 0.5px solid $line;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.thread-main {
  flex: 1;
  min-width: 0;
}

.thread-name-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.thread-name {
  max-width: 70%;
  font-size: 16px;
  font-weight: 500;
}

.thread-time {
  margin-left: auto;
  font-size: 11px;
  color: $text-4;
}

.thread-last {
  margin-top: 3px;
  font-size: 13px;
  color: $text-3;
}

.thread-dot {
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

.unread-tip {
  padding: 12px;
  text-align: center;
  color: $text-3;
  font-size: 12px;
}
</style>
