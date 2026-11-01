<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'
import { roomDisplayName } from '@/utils/chat'
import NavBar from '@/components/NavBar.vue'
import AppAvatar from '@/components/AppAvatar.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const keyword = ref('')
const loading = ref(true)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return chatRooms.rooms
  return chatRooms.rooms.filter((r) => roomDisplayName(r).toLowerCase().includes(kw))
})

onMounted(async () => {
  try {
    if (auth.isLoggedIn) await chatRooms.loadRooms()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="contact-search-page">
    <NavBar title="联系人" />

    <div class="search-box">
      <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <circle cx="11" cy="11" r="7" />
        <path d="M20 20l-4-4" />
      </svg>
      <input v-model="keyword" class="search-input" placeholder="搜索联系人" />
    </div>

    <template v-if="filtered.length">
      <button
        v-for="r in filtered"
        :key="r.roomId"
        class="contact-row"
        @click="router.push(`/chat/${r.roomId}`)"
      >
        <AppAvatar :src="r.peerAvatar" :name="roomDisplayName(r)" :size="42" />
        <div class="contact-main">
          <span class="contact-name ellipsis">{{ roomDisplayName(r) }}</span>
          <span class="contact-last ellipsis">{{ r.lastMsgPreview || '（暂无消息）' }}</span>
        </div>
        <span class="contact-time">{{ formatTime(r.lastMsgAt) }}</span>
      </button>
    </template>
    <EmptyState v-else-if="!loading" text="没有找到该联系人" />
    <EmptyState v-else text="加载中…" />
  </div>
</template>

<style scoped lang="scss">
.contact-search-page {
  min-height: 100vh;
  background: $bg-page;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 12px;
  padding: 0 12px;
  height: 36px;
  border-radius: $radius-full;
  background: #fff;
  color: $text-3;

  &:focus-within {
    outline: 1px solid $primary-light;
  }
}

.search-input {
  flex: 1;
  font-size: 14px;
  color: $text-1;
}

.contact-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border-bottom: 0.5px solid $line;
  background: #fff;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.contact-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.contact-name {
  font-size: 15px;
  font-weight: 600;
}

.contact-last {
  font-size: 12px;
  color: $text-3;
}

.contact-time {
  font-size: 11px;
  color: $text-4;
  flex-shrink: 0;
}
</style>
