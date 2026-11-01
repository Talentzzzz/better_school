<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useChatRoomsStore } from '@/stores/chatRooms'

const route = useRoute()
const chatRooms = useChatRoomsStore()

const items = [
  { path: '/home', label: '首页' },
  { path: '/market', label: '市集' },
  { path: '/publish', label: '', plus: true },
  { path: '/messages', label: '消息' },
  { path: '/profile', label: '我' }
]

function isActive(item: { path: string }) {
  return route.path.startsWith(item.path)
}
</script>

<template>
  <nav class="tabbar safe-bottom">
    <router-link
      v-for="item in items"
      :key="item.path"
      :to="item.path"
      class="tab-item"
      :class="{ active: isActive(item) }"
    >
      <span v-if="item.plus" class="tab-plus">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </span>
      <span v-else class="tab-label-wrap">
        <span class="tab-label">{{ item.label }}</span>
        <span v-if="item.path === '/messages' && chatRooms.unreadTotal > 0" class="tab-dot">
          {{ chatRooms.unreadTotal > 99 ? '99+' : chatRooms.unreadTotal }}
        </span>
      </span>
    </router-link>
  </nav>
</template>

<style scoped lang="scss">
.tabbar {
  position: fixed;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: min(100%, 480px);
  height: calc(#{$tabbar-h} + env(safe-area-inset-bottom));
  display: flex;
  z-index: 999;
  background: #fff;
  border-top: 0.5px solid $line;
}

.tab-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: $text-2;
  font-size: 17px;
  font-weight: 400;
  letter-spacing: 0.4px;

  &.active {
    color: $text-1;
    font-weight: 500;
  }
}

.tab-label-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  height: 100%;
}

.tab-dot {
  position: absolute;
  top: 4px;
  right: -2px;
  min-width: 15px;
  height: 15px;
  padding: 0 3px;
  border-radius: 8px;
  background: $primary;
  color: #fff;
  font-size: 10px;
  font-weight: 400;
  line-height: 15px;
  text-align: center;
}

.tab-plus {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 32px;
  border-radius: 10px;
  background: $primary;
}
</style>
