<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import type { ChatRoomSummary, RoomPeerRole } from '@/types/api'
import { formatTime } from '@/utils/format'
import { roomDisplayName } from '@/utils/chat'
import AppAvatar from '@/components/AppAvatar.vue'
import EmptyState from '@/components/EmptyState.vue'
import NavBar from '@/components/NavBar.vue'

const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const ROLE_TEXT: Record<RoomPeerRole, string> = {
  SELLER: '卖家',
  BUYER: '买家'
}

/** 闲置的两个可切换分类：我买的（对家=卖家）/ 我卖的（对家=买家） */
const side = ref<'buy' | 'sell'>('buy')

// 会话分栏判据 = 会话来源列（与市集入口行未读角标同源）
const partyRooms = computed(() => chatRooms.rooms.filter((r) => r.source === 'PRODUCT'))

const buyingRooms = computed(() => partyRooms.value.filter((r) => r.peerRole === 'SELLER'))
const sellingRooms = computed(() => partyRooms.value.filter((r) => r.peerRole === 'BUYER'))
const sideRooms = computed(() => (side.value === 'buy' ? buyingRooms.value : sellingRooms.value))

const unreadOf = (rooms: ChatRoomSummary[]) => rooms.reduce((sum, r) => sum + r.unread, 0)

/** 空态判据：既无对家会话即空；取数未落地前不判，免得首屏闪空态 */
const isEmpty = computed(() => chatRooms.loaded && partyRooms.value.length === 0)

function openRoom(roomId: string) {
  router.push(`/chat/${roomId}`)
}

function roleOf(room: ChatRoomSummary): string {
  return room.peerRole ? ROLE_TEXT[room.peerRole] : ''
}

function previewOf(room: ChatRoomSummary): string {
  return room.lastMsgPreview || '（暂无消息）'
}

onMounted(() => {
  if (auth.isLoggedIn) chatRooms.loadRooms()
})
</script>

<template>
  <div class="page--plain market-msg">
    <NavBar title="市集消息" />

    <div class="tabs-equal">
      <button class="on">闲置</button>
    </div>

    <EmptyState v-if="isEmpty" class="mm-empty" text="暂无消息" />

    <template v-else>
      <!-- 闲置：系统消息之下两个可切换分类（chips，各带未读角标） -->
      <div class="mm-chips">
        <button class="mm-chip" :class="{ on: side === 'buy' }" @click="side = 'buy'">
          我买的
          <span v-if="unreadOf(buyingRooms)" class="mm-chip-badge">{{ unreadOf(buyingRooms) }}</span>
        </button>
        <button class="mm-chip" :class="{ on: side === 'sell' }" @click="side = 'sell'">
          我卖的
          <span v-if="unreadOf(sellingRooms)" class="mm-chip-badge">{{ unreadOf(sellingRooms) }}</span>
        </button>
      </div>

      <button
        v-for="r in sideRooms"
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

</style>
